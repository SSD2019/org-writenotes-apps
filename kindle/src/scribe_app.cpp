#include "scribe_app.h"
#include "write_bridge_c_api.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>

namespace orgwritenotes::kindle {

namespace {

double steadyNowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

EinkRect unionRect(const EinkRect& a, const EinkRect& b) {
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;
    int left   = std::min(a.left, b.left);
    int top    = std::min(a.top, b.top);
    int right  = std::max(a.left + a.width, b.left + b.width);
    int bottom = std::max(a.top + a.height, b.top + b.height);
    return EinkRect{ left, top, right - left, bottom - top };
}

bool pointInRect(float x, float y, int rx, int ry, int rw, int rh) {
    return x >= static_cast<float>(rx) &&
           x <= static_cast<float>(rx + rw) &&
           y >= static_cast<float>(ry) &&
           y <= static_cast<float>(ry + rh);
}

constexpr int kExpandedStripW = 780;
constexpr int kToolsPopW      = 660;
constexpr int kToolsPopH      = 196;
constexpr int kPagePopW       = 640;
constexpr int kPagePopH       = 196;
constexpr int kOrgPopW        = 560;
constexpr int kOrgPopH        = 136;
constexpr int kSelBarW        = 440;
constexpr int kSelBarH        = 46;
constexpr double kSettleDelayMs = 400.0;

} // namespace

KindleScribeApp::KindleScribeApp(
    const char* fbPath,
    const std::string& queueDir,
    int fallbackWidth,
    int fallbackHeight
)
    : m_fbPath(fbPath ? fbPath : ""),
      m_fallbackWidth(fallbackWidth),
      m_fallbackHeight(fallbackHeight),
      m_input(fallbackWidth, fallbackHeight),
      m_sync(queueDir) {}

KindleScribeApp::~KindleScribeApp() {
    stopAsyncSyncWorker();
    if (m_sessionHandle > 0) {
        wn_session_destroy(m_sessionHandle);
        m_sessionHandle = 0;
    }
}

bool KindleScribeApp::initialize() {
    if (!m_fb.openDevice(m_fbPath.c_str(), m_fallbackWidth, m_fallbackHeight)) {
        return false;
    }
    m_input.setScreenSize(m_fb.width(), m_fb.height());

    m_pageWidth   = 1200;
    m_pageHeight  = 928;
    m_renderWidth = m_fb.width();
    m_renderHeight = std::max(64, m_fb.height());
    m_canvasRgba.assign(static_cast<size_t>(m_renderWidth) * m_renderHeight * 4, 0xFF);
    m_canvasDirty = true;

    if (m_sessionHandle > 0) {
        wn_session_destroy(m_sessionHandle);
    }
    m_sessionHandle = wn_session_create(m_pageWidth, m_pageHeight, 0, 48);
    if (m_sessionHandle <= 0) return false;

    applyPenSettings();
    refreshCachedSyncStatus();
    renderFullScreen(/*fullFlash=*/true);
    return true;
}

void KindleScribeApp::applyPenSettings() {
    if (m_sessionHandle <= 0) return;
    static const float kWidths[] = { 1.6f, 2.6f, 4.5f };
    static const int kColors[]   = {
        static_cast<int>(0xFF000000),
        static_cast<int>(0xFF404040),
        static_cast<int>(0xFF888888),
        static_cast<int>(0x55888888)
    };
    m_penWidth     = kWidths[std::clamp(m_penWidthIdx, 0, 2)];
    m_penColorArgb = kColors[std::clamp(m_penColorIdx, 0, 3)];
    const int tipType = (m_penColorIdx == 3) ? 1 : 0; // highlighter vs normal
    wn_session_set_pen(
        m_sessionHandle,
        m_penColorArgb,
        m_penWidth,
        /*pressureSensitive=*/(m_penColorIdx != 3),
        tipType,
        m_snapToGrid,
        m_lineDrawing,
        /*chiselAngle=*/0.0f
    );
}

float KindleScribeApp::computeViewScale() const {
    const int canvasW = std::max(64, m_fb.width());
    const int pageW   = std::max(64, m_pageWidth);
    return (static_cast<float>(canvasW) / static_cast<float>(pageW)) * m_viewZoom;
}

void KindleScribeApp::syncPageInfoFromEngine() {
    if (m_sessionHandle <= 0) return;
    int info8[8] = {0};
    if (wn_session_get_page_info(m_sessionHandle, info8)) {
        if (info8[0] > 0) m_pageWidth  = info8[0];
        if (info8[1] > 0) m_pageHeight = info8[1];
        m_rulingStyle = std::clamp(info8[7], 0, 4);
    }
}

void KindleScribeApp::ensureCanvasRendered() {
    if (m_sessionHandle <= 0 || !m_canvasDirty) return;

    syncPageInfoFromEngine();
    const float scale = computeViewScale();
    m_renderWidth  = std::max(64, static_cast<int>(std::lround(static_cast<float>(m_pageWidth) * scale)));
    m_renderHeight = std::max(64, static_cast<int>(std::lround(static_cast<float>(m_pageHeight) * scale)));

    const size_t neededBytes = static_cast<size_t>(m_renderWidth) * m_renderHeight * 4;
    if (m_canvasRgba.size() < neededBytes) {
        m_canvasRgba.assign(neededBytes, 0xFF);
    }

    if (wn_session_render_rgba(m_sessionHandle, m_canvasRgba.data(), m_renderWidth, m_renderHeight, 0.0f, 0.0f)) {
        m_canvasDirty = false;
    }
}

void KindleScribeApp::blitCanvasRectToFb(int x, int y, int w, int h) {
    const int fbW = m_fb.width();
    const int fbH = m_fb.height();
    int x0 = std::clamp(x, 0, fbW);
    int y0 = std::clamp(y, 0, fbH);
    int x1 = std::clamp(x + w, 0, fbW);
    int y1 = std::clamp(y + h, 0, fbH);
    if (x1 <= x0 || y1 <= y0) return;

    m_fb.fillRect(x0, y0, x1 - x0, y1 - y0, 0xFF);

    const int panX = static_cast<int>(std::lround(m_panX));
    const int panY = static_cast<int>(std::lround(m_panY));
    const int srcX = x0 + panX;
    const int srcY = y0 + panY;

    const int copyLeft   = std::max(0, srcX);
    const int copyTop    = std::max(0, srcY);
    const int copyRight  = std::min(m_renderWidth, srcX + (x1 - x0));
    const int copyBottom = std::min(m_renderHeight, srcY + (y1 - y0));

    if (copyRight > copyLeft && copyBottom > copyTop) {
        const int dstX = x0 + (copyLeft - srcX);
        const int dstY = y0 + (copyTop - srcY);
        m_fb.blitRgbaRegion(
            m_canvasRgba.data(),
            m_renderWidth,
            m_renderHeight,
            copyLeft,
            copyTop,
            dstX,
            dstY,
            copyRight - copyLeft,
            copyBottom - copyTop,
            /*dither1Bit=*/false
        );
    }

    const int pageBottomScreen = m_renderHeight - panY;
    if (pageBottomScreen >= y0 && pageBottomScreen < y1) {
        m_fb.drawHLine(x0, pageBottomScreen, x1 - x0, 0xA0);
    }
}

EinkRect KindleScribeApp::currentOverlayBounds() const {
    EinkRect r{ kPillX - 2, kPillY - 2, kPillWidth + 4, kPillHeight + 4 };
    if (m_toolbarExpanded) {
        r = unionRect(r, EinkRect{ kPillX - 2, kStripY - 2, kExpandedStripW + 4, kStripHeight + 4 });
    } else if (m_sessionHandle > 0 && wn_session_has_selection(m_sessionHandle)) {
        r = unionRect(r, EinkRect{ kPillX - 2, kStripY - 2, kSelBarW + 4, kSelBarH + 4 });
    }
    if (m_popover == PopoverKind::TOOLS) {
        r = unionRect(r, EinkRect{ kPillX - 2, kPopoverY - 2, kToolsPopW + 4, kToolsPopH + 4 });
    } else if (m_popover == PopoverKind::PAGE) {
        r = unionRect(r, EinkRect{ kPillX - 2, kPopoverY - 2, kPagePopW + 4, kPagePopH + 4 });
    } else if (m_popover == PopoverKind::ORG) {
        r = unionRect(r, EinkRect{ kPillX - 2, kPopoverY - 2, kOrgPopW + 4, kOrgPopH + 4 });
    }
    if (m_sidebarOpen) {
        const int sbW = std::min(kSidebarWidth, m_fb.width() / 2);
        const int sbY = kStripY;
        r = unionRect(r, EinkRect{ 0, sbY, sbW + 4, m_fb.height() - sbY });
    }
    return r;
}

void KindleScribeApp::drawPillAndOverlays() {
    // Compact floating pill (420x44 px at top-left)
    m_fb.fillRect(kPillX, kPillY, kPillWidth, kPillHeight, 0xF8);
    m_fb.drawRectBorder(kPillX, kPillY, kPillWidth, kPillHeight, 2, 0x00);

    static const char* kToolNames[] = { "Pen", "Erase", "Sel", "Space" };
    std::string syncTxt = m_cachedHasConflicts.load()
        ? "!Conf"
        : (m_cachedConnected.load() ? "LIVE" : ("Q:" + std::to_string(m_cachedQueueCount.load())));

    const int widths[5] = { 50, 96, 78, 78, kPillWidth - 50 - 96 - 78 - 78 };
    const char* labels[5] = {
        m_toolbarExpanded ? "[-]" : "[+]",
        kToolNames[static_cast<int>(m_activeTool)],
        "Undo",
        "Redo",
        syncTxt.c_str()
    };

    int cx = kPillX;
    for (int i = 0; i < 5; ++i) {
        bool active = (i == 0 && m_toolbarExpanded) ||
                      (i == 1 && m_popover == PopoverKind::TOOLS) ||
                      (i == 4 && m_sidebarOpen);
        uint8_t bg = active ? 0x20 : 0xF8;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(cx + 2, kPillY + 2, widths[i] - 4, kPillHeight - 4, bg);
        if (i > 0) {
            m_fb.drawRectBorder(cx, kPillY, 1, kPillHeight, 1, 0x80);
        }
        if (i == 4) {
            // B/W E-Ink connection status icon (shape-coded):
            //   solid dot = live connected, ring + center dot = connected with conflicts, hollow ring = offline
            const bool connected = m_cachedConnected.load();
            const bool conflicts = m_cachedHasConflicts.load();
            const int icx = cx + 14;
            const int icy = kPillY + kPillHeight / 2;
            m_fb.drawCircle(icx, icy, 6, fg, /*filled=*/connected && !conflicts, /*thickness=*/2);
            if (connected && conflicts) m_fb.drawCircle(icx, icy, 2, fg, /*filled=*/true);
            // Clamp label so it never overruns the segment (5 glyphs at scale 2 = 80 px)
            const int maxChars = std::max(0, (widths[i] - 26 - 4) / 16);
            std::string lbl(labels[i]);
            if (static_cast<int>(lbl.size()) > maxChars) lbl.resize(static_cast<size_t>(maxChars));
            m_fb.drawText(cx + 26, kPillY + 14, lbl, fg, bg, 2);
        } else {
            m_fb.drawText(cx + 8, kPillY + 14, labels[i], fg, bg, 2);
        }
        cx += widths[i];
    }

    if (m_toolbarExpanded) {
        drawExpandedStrip();
    } else if (m_sessionHandle > 0 && wn_session_has_selection(m_sessionHandle)) {
        drawSelectionActionBar();
    }

    if (m_popover == PopoverKind::TOOLS) {
        drawToolsPopover();
    } else if (m_popover == PopoverKind::PAGE) {
        drawPagePopover();
    } else if (m_popover == PopoverKind::ORG) {
        drawOrgPopover();
    }

    if (m_sidebarOpen) {
        drawSidebarOverlay();
    }
}

void KindleScribeApp::drawExpandedStrip() {
    m_fb.fillRect(kPillX, kStripY, kExpandedStripW, kStripHeight, 0xF4);
    m_fb.drawRectBorder(kPillX, kStripY, kExpandedStripW, kStripHeight, 2, 0x00);

    const char* items[6] = { "Tools", "Page", "Org", "Nodes", "GC16", "Exit" };
    const int btnW = kExpandedStripW / 6;
    for (int i = 0; i < 6; ++i) {
        int bx = kPillX + i * btnW;
        bool active = (i == 0 && m_popover == PopoverKind::TOOLS) ||
                      (i == 1 && m_popover == PopoverKind::PAGE) ||
                      (i == 2 && m_popover == PopoverKind::ORG) ||
                      (i == 3 && m_sidebarOpen);
        uint8_t bg = active ? 0x20 : 0xF4;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, kStripY + 2, btnW - 4, kStripHeight - 4, bg);
        m_fb.drawRectBorder(bx + 2, kStripY + 2, btnW - 4, kStripHeight - 4, 1, 0x40);
        m_fb.drawText(bx + 12, kStripY + 15, items[i], fg, bg, 2);
    }
}

void KindleScribeApp::drawSelectionActionBar() {
    m_fb.fillRect(kPillX, kStripY, kSelBarW, kSelBarH, 0xF0);
    m_fb.drawRectBorder(kPillX, kStripY, kSelBarW, kSelBarH, 2, 0x00);
    const char* items[4] = { "Move", "Dup", "Del", "Done" };
    const int btnW = kSelBarW / 4;
    for (int i = 0; i < 4; ++i) {
        int bx = kPillX + i * btnW;
        bool active = (i == 0 && m_selectMoving);
        uint8_t bg = active ? 0x20 : 0xF0;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, kStripY + 2, btnW - 4, kSelBarH - 4, bg);
        m_fb.drawRectBorder(bx + 2, kStripY + 2, btnW - 4, kSelBarH - 4, 1, 0x40);
        m_fb.drawText(bx + 12, kStripY + 15, items[i], fg, bg, 2);
    }
}

void KindleScribeApp::drawToolsPopover() {
    m_fb.fillRect(kPillX, kPopoverY, kToolsPopW, kToolsPopH, 0xFA);
    m_fb.drawRectBorder(kPillX, kPopoverY, kToolsPopW, kToolsPopH, 2, 0x00);

    auto drawBtn = [&](int col, int row, int cols, const char* label, bool active) {
        int bw = (kToolsPopW - 16) / cols;
        int bh = 54;
        int bx = kPillX + 8 + col * bw;
        int by = kPopoverY + 10 + row * 60;
        uint8_t bg = active ? 0x20 : 0xF0;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, by, bw - 4, bh, bg);
        m_fb.drawRectBorder(bx + 2, by, bw - 4, bh, 1, 0x20);
        m_fb.drawText(bx + 8, by + 19, label, fg, bg, 2);
    };

    // Row 0: Pen widths + shades (7 cols)
    drawBtn(0, 0, 7, "Fine", m_penWidthIdx == 0);
    drawBtn(1, 0, 7, "Med",  m_penWidthIdx == 1);
    drawBtn(2, 0, 7, "Bold", m_penWidthIdx == 2);
    drawBtn(3, 0, 7, "Blk",  m_penColorIdx == 0);
    drawBtn(4, 0, 7, "DkGy", m_penColorIdx == 1);
    drawBtn(5, 0, 7, "Gray", m_penColorIdx == 2);
    drawBtn(6, 0, 7, "High", m_penColorIdx == 3);

    // Row 1: Pen modes + Eraser modes (6 cols)
    drawBtn(0, 1, 6, "Line",   m_lineDrawing);
    drawBtn(1, 1, 6, "Grid",   m_snapToGrid);
    drawBtn(2, 1, 6, "Shape",  m_shapeSnap);
    drawBtn(3, 1, 6, "E:Strk", m_activeTool == ActiveTool::ERASER && m_eraserMode == EraserMode::STROKE);
    drawBtn(4, 1, 6, "E:Seg",  m_activeTool == ActiveTool::ERASER && m_eraserMode == EraserMode::SEGMENT);
    drawBtn(5, 1, 6, "E:Rule", m_activeTool == ActiveTool::ERASER && m_eraserMode == EraserMode::RULED);

    // Row 2: Select modes + Space modes (6 cols)
    drawBtn(0, 2, 6, "S:Rect",  m_activeTool == ActiveTool::SELECT && m_selectMode == SelectMode::RECT);
    drawBtn(1, 2, 6, "S:Laso",  m_activeTool == ActiveTool::SELECT && m_selectMode == SelectMode::LASSO);
    drawBtn(2, 2, 6, "S:Rule",  m_activeTool == ActiveTool::SELECT && m_selectMode == SelectMode::RULED);
    drawBtn(3, 2, 6, "V-Spc",   m_activeTool == ActiveTool::SPACE  && m_spaceMode  == SpaceMode::VERTICAL);
    drawBtn(4, 2, 6, "H-Spc",   m_activeTool == ActiveTool::SPACE  && m_spaceMode  == SpaceMode::HORIZONTAL);
    drawBtn(5, 2, 6, "R-Spc",   m_activeTool == ActiveTool::SPACE  && m_spaceMode  == SpaceMode::RULED);
}

void KindleScribeApp::drawPagePopover() {
    m_fb.fillRect(kPillX, kPopoverY, kPagePopW, kPagePopH, 0xFA);
    m_fb.drawRectBorder(kPillX, kPopoverY, kPagePopW, kPagePopH, 2, 0x00);

    auto drawBtn = [&](int col, int row, int cols, const std::string& label, bool active) {
        int bw = (kPagePopW - 16) / cols;
        int bh = 54;
        int bx = kPillX + 8 + col * bw;
        int by = kPopoverY + 10 + row * 60;
        uint8_t bg = active ? 0x20 : 0xF0;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, by, bw - 4, bh, bg);
        m_fb.drawRectBorder(bx + 2, by, bw - 4, bh, 1, 0x20);
        m_fb.drawText(bx + 8, by + 19, label, fg, bg, 2);
    };

    // Row 0: Ruling styles
    const char* rules[5] = { "Line", "Dot", "Grid", "Music", "Corn" };
    for (int i = 0; i < 5; ++i) {
        drawBtn(i, 0, 5, rules[i], m_rulingStyle == i);
    }

    // Row 1: Multi-sketch switcher + Trim
    int totalSketches = std::max(1, static_cast<int>(m_nodeBlocks.size()));
    std::string idxLbl = std::to_string(m_activeBlockIndex + 1) + "/" + std::to_string(totalSketches);
    drawBtn(0, 1, 5, "< Prev", false);
    drawBtn(1, 1, 5, idxLbl, false);
    drawBtn(2, 1, 5, "Next >", false);
    drawBtn(3, 1, 5, "+Skch", false);
    drawBtn(4, 1, 5, "Trim", false);

    // Row 2: Zoom / Pan
    int zoomPct = static_cast<int>(std::lround(m_viewZoom * 100.0f));
    drawBtn(0, 2, 4, "Zoom -", false);
    drawBtn(1, 2, 4, std::to_string(zoomPct) + "%", std::abs(m_viewZoom - 1.0f) < 0.05f);
    drawBtn(2, 2, 4, "Zoom +", false);
    drawBtn(3, 2, 4, "Reset", false);
}

void KindleScribeApp::drawOrgPopover() {
    m_fb.fillRect(kPillX, kPopoverY, kOrgPopW, kOrgPopH, 0xFA);
    m_fb.drawRectBorder(kPillX, kPopoverY, kOrgPopW, kOrgPopH, 2, 0x00);

    std::string header = (m_activeTodo.empty() ? "" : ("[" + m_activeTodo + "] ")) + m_activeTitle;
    if (header.size() > 32) header.resize(32);
    m_fb.drawText(kPillX + 12, kPopoverY + 14, header, 0x00, 0xFA, 2);

    std::string sub = m_activeFileName.empty() ? "New Sketch" : m_activeFileName;
    if (sub.size() > 32) sub.resize(32);
    m_fb.drawText(kPillX + 12, kPopoverY + 42, sub, 0x50, 0xFA, 2);

    const char* btns[3] = { "Cycle TODO", m_flashcardFlagged ? "Unflag Card" : "Flag Card", "Sync Now" };
    int bw = (kOrgPopW - 16) / 3;
    for (int i = 0; i < 3; ++i) {
        int bx = kPillX + 8 + i * bw;
        int by = kPopoverY + 72;
        bool active = (i == 1 && m_flashcardFlagged);
        uint8_t bg = active ? 0x20 : 0xF0;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, by, bw - 4, 52, bg);
        m_fb.drawRectBorder(bx + 2, by, bw - 4, 52, 1, 0x20);
        m_fb.drawText(bx + 10, by + 18, btns[i], fg, bg, 2);
    }
}

void KindleScribeApp::drawSidebarOverlay() {
    const int sbW = std::min(kSidebarWidth, m_fb.width() / 2);
    const int sbY = kStripY;
    const int sbH = m_fb.height() - sbY;
    m_fb.fillRect(0, sbY, sbW, sbH, 0xFA);
    m_fb.drawRectBorder(0, sbY, sbW, sbH, 3, 0x00);

    // 4 Tab buttons: Hist | Outl | Agnd | Gall
    const char* tabNames[4] = { "Hist", "Outl", "Agnd", "Gall" };
    int tabW = (sbW - 16) / 4;
    for (int i = 0; i < 4; ++i) {
        int tx = 8 + i * tabW;
        int ty = sbY + 8;
        bool active = (static_cast<int>(m_sidebarTab) == i);
        uint8_t bg = active ? 0x20 : 0xE8;
        uint8_t fg = active ? 0xFF : 0x00;
        m_fb.fillRect(tx + 2, ty, tabW - 4, 42, bg);
        m_fb.drawRectBorder(tx + 2, ty, tabW - 4, 42, 1, 0x00);
        m_fb.drawText(tx + 12, ty + 13, tabNames[i], fg, bg, 2);
    }

    const std::vector<OrgNodeSummary>* list = &m_outlineNodes;
    if (m_sidebarTab == SidebarTab::HISTORY) list = &m_historyNodes;
    else if (m_sidebarTab == SidebarTab::AGENDA) list = &m_agendaNodes;
    else if (m_sidebarTab == SidebarTab::GALLERY) list = &m_galleryNodes;

    if (list->empty() && !m_outlineNodes.empty()) {
        list = &m_outlineNodes;
    }

    int itemY = sbY + 62;
    const size_t maxVisible = static_cast<size_t>(std::max(1, (sbH - 72) / 64));
    for (size_t i = 0; i < list->size() && i < maxVisible; ++i) {
        const auto& node = (*list)[i];
        bool selected = (node.nodeId == m_activeNodeId);
        uint8_t bg = selected ? 0x30 : 0xFA;
        uint8_t fg = selected ? 0xFF : 0x00;
        m_fb.fillRect(8, itemY, sbW - 16, 56, bg);
        m_fb.drawRectBorder(8, itemY, sbW - 16, 56, 1, 0x40);
        std::string prefix = node.agendaLabel.empty() ? "" : (node.agendaLabel + " ");
        std::string indent(static_cast<size_t>(std::max(0, node.level - 1)) * 2, ' ');
        std::string line = prefix + indent + (node.todoKeyword.empty() ? "* " : (node.todoKeyword + " ")) + node.title;
        if (line.size() > 30) line.resize(30);
        m_fb.drawText(18, itemY + 18, line, fg, bg, 2);
        itemY += 64;
    }
}

void KindleScribeApp::renderFullScreen(bool fullFlash) {
    if (m_sessionHandle <= 0) return;

    ensureCanvasRendered();
    blitCanvasRectToFb(0, 0, m_fb.width(), m_fb.height());
    drawPillAndOverlays();
    m_fb.refreshFullScreen(EinkWaveform::GC16, fullFlash);
}

void KindleScribeApp::renderOverlayOnly(EinkWaveform wf) {
    if (m_sessionHandle <= 0) return;
    ensureCanvasRendered();
    EinkRect bounds = currentOverlayBounds();
    blitCanvasRectToFb(bounds.left, bounds.top, bounds.width, bounds.height);
    drawPillAndOverlays();
    m_fb.refreshRect(bounds, wf, /*fullFlash=*/false);
}

void KindleScribeApp::restoreCanvasRegionAndRefresh(const EinkRect& rect, EinkWaveform wf) {
    if (rect.isEmpty()) return;
    ensureCanvasRendered();
    blitCanvasRectToFb(rect.left, rect.top, rect.width, rect.height);
    drawPillAndOverlays();
    m_fb.refreshRect(rect, wf, /*fullFlash=*/false);
}

void KindleScribeApp::refreshCanvasDirtyRect(
    float left,
    float top,
    float right,
    float bottom,
    EinkWaveform wf
) {
    if (m_sessionHandle <= 0) return;
    ensureCanvasRendered();

    const float scale = computeViewScale();
    const int panX = static_cast<int>(std::lround(m_panX));
    const int panY = static_cast<int>(std::lround(m_panY));

    int x0 = std::clamp(static_cast<int>(std::floor(left)) - 12, 0, m_renderWidth);
    int y0 = std::clamp(static_cast<int>(std::floor(top)) - 12, 0, m_renderHeight);
    int x1 = std::clamp(static_cast<int>(std::ceil(right)) + 12, 0, m_renderWidth);
    int y1 = std::clamp(static_cast<int>(std::ceil(bottom)) + 12, 0, m_renderHeight);
    if (x1 <= x0 || y1 <= y0) return;

    const float pageLeft   = static_cast<float>(x0) / scale;
    const float pageTop    = static_cast<float>(y0) / scale;
    const float pageRight  = static_cast<float>(x1) / scale;
    const float pageBottom = static_cast<float>(y1) / scale;

    if (wn_session_render_dirty_rgba(
            m_sessionHandle,
            m_canvasRgba.data(),
            m_renderWidth,
            m_renderHeight,
            pageLeft,
            pageTop,
            pageRight,
            pageBottom
        )) {
        const int sx0 = std::clamp(x0 - panX, 0, m_fb.width());
        const int sy0 = std::clamp(y0 - panY, 0, m_fb.height());
        const int sx1 = std::clamp(x1 - panX, 0, m_fb.width());
        const int sy1 = std::clamp(y1 - panY, 0, m_fb.height());
        if (sx1 > sx0 && sy1 > sy0) {
            blitCanvasRectToFb(sx0, sy0, sx1 - sx0, sy1 - sy0);
            drawPillAndOverlays();
            m_fb.refreshRect(EinkRect{ sx0, sy0, sx1 - sx0, sy1 - sy0 }, wf, /*fullFlash=*/false);
        }
    }
}

void KindleScribeApp::flushPendingDuRefresh(double timestampMs) {
    if (!m_hasPendingDu) return;
    m_hasPendingDu = false;
    m_lastDuRefreshMs = timestampMs;

    int x0 = std::clamp(static_cast<int>(std::floor(m_pendingDuMinX)) - 6, 0, m_fb.width());
    int y0 = std::clamp(static_cast<int>(std::floor(m_pendingDuMinY)) - 6, 0, m_fb.height());
    int x1 = std::clamp(static_cast<int>(std::ceil(m_pendingDuMaxX)) + 6, 0, m_fb.width());
    int y1 = std::clamp(static_cast<int>(std::ceil(m_pendingDuMaxY)) + 6, 0, m_fb.height());
    if (x1 > x0 && y1 > y0) {
        m_fb.refreshRect(EinkRect{ x0, y0, x1 - x0, y1 - y0 }, EinkWaveform::DU, /*fullFlash=*/false);
    }
}

void KindleScribeApp::flushPendingStrokeSettle(bool force, double nowMs) {
    if (!m_hasPendingSettle || m_strokeActive) return;
    double ts = (nowMs > 0.0) ? nowMs : steadyNowMs();
    if (!force && (ts - m_lastStrokeEndMs) < kSettleDelayMs) {
        return;
    }
    m_hasPendingSettle = false;
    if (m_canvasDirty) {
        renderFullScreen(/*fullFlash=*/false);
    } else {
        refreshCanvasDirtyRect(
            m_settleMinX,
            m_settleMinY,
            m_settleMaxX,
            m_settleMaxY,
            EinkWaveform::GC16_FAST
        );
    }
}

bool KindleScribeApp::hitTestOverlay(float x, float y) const {
    if (pointInRect(x, y, kPillX, kPillY, kPillWidth, kPillHeight)) return true;
    if (m_toolbarExpanded && pointInRect(x, y, kPillX, kStripY, kExpandedStripW, kStripHeight)) return true;
    if (!m_toolbarExpanded && m_sessionHandle > 0 && wn_session_has_selection(m_sessionHandle) &&
        pointInRect(x, y, kPillX, kStripY, kSelBarW, kSelBarH)) return true;
    if (m_popover == PopoverKind::TOOLS && pointInRect(x, y, kPillX, kPopoverY, kToolsPopW, kToolsPopH)) return true;
    if (m_popover == PopoverKind::PAGE  && pointInRect(x, y, kPillX, kPopoverY, kPagePopW, kPagePopH)) return true;
    if (m_popover == PopoverKind::ORG   && pointInRect(x, y, kPillX, kPopoverY, kOrgPopW, kOrgPopH)) return true;
    if (m_sidebarOpen) {
        const int sbW = std::min(kSidebarWidth, m_fb.width() / 2);
        if (pointInRect(x, y, 0, kStripY, sbW, m_fb.height() - kStripY)) return true;
    }
    return false;
}

void KindleScribeApp::collapseOverlaysForCanvas() {
    if (!m_toolbarExpanded && m_popover == PopoverKind::NONE && !m_sidebarOpen) return;
    EinkRect oldBounds = currentOverlayBounds();
    m_toolbarExpanded = false;
    m_popover         = PopoverKind::NONE;
    m_sidebarOpen     = false;
    restoreCanvasRegionAndRefresh(oldBounds, EinkWaveform::A2);
}

bool KindleScribeApp::handlePillTap(float x, float y) {
    if (!pointInRect(x, y, kPillX, kPillY, kPillWidth, kPillHeight)) return false;

    const int widths[5] = { 50, 96, 78, 78, kPillWidth - 50 - 96 - 78 - 78 };
    int relX = static_cast<int>(x) - kPillX;
    int idx = 0;
    int acc = 0;
    for (int i = 0; i < 5; ++i) {
        acc += widths[i];
        if (relX < acc) {
            idx = i;
            break;
        }
    }

    EinkRect oldBounds = currentOverlayBounds();
    int dims2[2] = {0, 0};

    switch (idx) {
        case 0: // Expand / Collapse
            m_toolbarExpanded = !m_toolbarExpanded;
            if (!m_toolbarExpanded) m_popover = PopoverKind::NONE;
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
        case 1: // Tool button: cycle Pen <-> Eraser when collapsed, or toggle Tools popover
            if (m_toolbarExpanded) {
                m_popover = (m_popover == PopoverKind::TOOLS) ? PopoverKind::NONE : PopoverKind::TOOLS;
                restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            } else {
                m_activeTool = (m_activeTool == ActiveTool::PEN) ? ActiveTool::ERASER : ActiveTool::PEN;
                renderOverlayOnly(EinkWaveform::A2);
            }
            break;
        case 2: // Undo
            flushPendingStrokeSettle(/*force=*/true);
            if (wn_session_undo(m_sessionHandle, dims2)) {
                m_canvasDirty = true;
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 3: // Redo
            flushPendingStrokeSettle(/*force=*/true);
            if (wn_session_redo(m_sessionHandle, dims2)) {
                m_canvasDirty = true;
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 4: // Sync / Nodes drawer toggle
            m_sidebarOpen = !m_sidebarOpen;
            if (m_sidebarOpen) {
                if (m_workerRunning) {
                    queueAsyncFetchNodeList(m_sidebarTab);
                } else if (m_sync.isConnected()) {
                    std::vector<OrgNodeSummary> remote;
                    const char* filt = (m_sidebarTab == SidebarTab::AGENDA) ? "agenda"
                        : (m_sidebarTab == SidebarTab::HISTORY ? "pushed_history"
                        : (m_sidebarTab == SidebarTab::GALLERY ? "gallery" : "buffer_outline"));
                    if (m_sync.fetchNodeList(filt, &remote)) {
                        if (m_sidebarTab == SidebarTab::HISTORY) m_historyNodes = std::move(remote);
                        else if (m_sidebarTab == SidebarTab::AGENDA) m_agendaNodes = std::move(remote);
                        else if (m_sidebarTab == SidebarTab::GALLERY) m_galleryNodes = std::move(remote);
                        else m_outlineNodes = std::move(remote);
                    }
                }
            }
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
    }
    return true;
}

bool KindleScribeApp::handleExpandedStripTap(float x, float y) {
    if (!m_toolbarExpanded || !pointInRect(x, y, kPillX, kStripY, kExpandedStripW, kStripHeight)) {
        return false;
    }
    const int btnW = kExpandedStripW / 6;
    int idx = std::clamp(static_cast<int>(x - kPillX) / btnW, 0, 5);
    EinkRect oldBounds = currentOverlayBounds();

    switch (idx) {
        case 0:
            m_popover = (m_popover == PopoverKind::TOOLS) ? PopoverKind::NONE : PopoverKind::TOOLS;
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
        case 1:
            m_popover = (m_popover == PopoverKind::PAGE) ? PopoverKind::NONE : PopoverKind::PAGE;
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
        case 2:
            m_popover = (m_popover == PopoverKind::ORG) ? PopoverKind::NONE : PopoverKind::ORG;
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
        case 3:
            m_sidebarOpen = !m_sidebarOpen;
            if (m_sidebarOpen && m_workerRunning) queueAsyncFetchNodeList(m_sidebarTab);
            restoreCanvasRegionAndRefresh(unionRect(oldBounds, currentOverlayBounds()), EinkWaveform::A2);
            break;
        case 4:
            flushPendingStrokeSettle(/*force=*/true);
            m_canvasDirty = true;
            renderFullScreen(/*fullFlash=*/true);
            break;
        case 5:
            if (m_workerRunning) queueAsyncCommitCurrent();
            else flushPendingCommit();
            m_shouldExit = true;
            break;
    }
    return true;
}

bool KindleScribeApp::handleSelectionBarTap(float x, float y) {
    if (m_toolbarExpanded || m_sessionHandle <= 0 || !wn_session_has_selection(m_sessionHandle)) {
        return false;
    }
    if (!pointInRect(x, y, kPillX, kStripY, kSelBarW, kSelBarH)) return false;

    const int btnW = kSelBarW / 4;
    int idx = std::clamp(static_cast<int>(x - kPillX) / btnW, 0, 3);
    float bounds4[4] = {0};
    int dims2[2] = {0};

    switch (idx) {
        case 0: // Toggle Move drag mode
            m_selectMoving = !m_selectMoving;
            renderOverlayOnly(EinkWaveform::A2);
            break;
        case 1: // Duplicate selection
            if (wn_session_duplicate_selection(m_sessionHandle, 28.0f, 28.0f, bounds4, dims2) >= 0) {
                m_canvasDirty = true;
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 2: // Delete selection
            if (wn_session_delete_selection(m_sessionHandle)) {
                m_selectMoving = false;
                m_canvasDirty = true;
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 3: // Done / Clear selection
            wn_session_clear_selection(m_sessionHandle);
            m_selectMoving = false;
            restoreCanvasRegionAndRefresh(EinkRect{ kPillX - 2, kStripY - 2, kSelBarW + 4, kSelBarH + 4 }, EinkWaveform::A2);
            break;
    }
    return true;
}

bool KindleScribeApp::handlePopoverTap(float x, float y) {
    if (m_popover == PopoverKind::NONE) return false;

    if (m_popover == PopoverKind::TOOLS && pointInRect(x, y, kPillX, kPopoverY, kToolsPopW, kToolsPopH)) {
        int row = std::clamp(static_cast<int>(y - (kPopoverY + 10)) / 60, 0, 2);
        int cols = (row == 0) ? 7 : 6;
        int bw = (kToolsPopW - 16) / cols;
        int col = std::clamp(static_cast<int>(x - (kPillX + 8)) / bw, 0, cols - 1);

        if (row == 0) {
            if (col <= 2) m_penWidthIdx = col;
            else m_penColorIdx = col - 3;
            m_activeTool = ActiveTool::PEN;
            applyPenSettings();
        } else if (row == 1) {
            if (col == 0) { m_lineDrawing = !m_lineDrawing; m_activeTool = ActiveTool::PEN; applyPenSettings(); }
            else if (col == 1) { m_snapToGrid = !m_snapToGrid; m_activeTool = ActiveTool::PEN; applyPenSettings(); }
            else if (col == 2) { m_shapeSnap = !m_shapeSnap; m_activeTool = ActiveTool::PEN; }
            else if (col == 3) { m_activeTool = ActiveTool::ERASER; m_eraserMode = EraserMode::STROKE; }
            else if (col == 4) { m_activeTool = ActiveTool::ERASER; m_eraserMode = EraserMode::SEGMENT; }
            else if (col == 5) { m_activeTool = ActiveTool::ERASER; m_eraserMode = EraserMode::RULED; }
        } else {
            if (col == 0) { m_activeTool = ActiveTool::SELECT; m_selectMode = SelectMode::RECT; }
            else if (col == 1) { m_activeTool = ActiveTool::SELECT; m_selectMode = SelectMode::LASSO; }
            else if (col == 2) { m_activeTool = ActiveTool::SELECT; m_selectMode = SelectMode::RULED; }
            else if (col == 3) { m_activeTool = ActiveTool::SPACE; m_spaceMode = SpaceMode::VERTICAL; }
            else if (col == 4) { m_activeTool = ActiveTool::SPACE; m_spaceMode = SpaceMode::HORIZONTAL; }
            else if (col == 5) { m_activeTool = ActiveTool::SPACE; m_spaceMode = SpaceMode::RULED; }
        }
        renderOverlayOnly(EinkWaveform::A2);
        return true;
    }

    if (m_popover == PopoverKind::PAGE && pointInRect(x, y, kPillX, kPopoverY, kPagePopW, kPagePopH)) {
        int row = std::clamp(static_cast<int>(y - (kPopoverY + 10)) / 60, 0, 2);
        int cols = (row == 2) ? 4 : 5;
        int bw = (kPagePopW - 16) / cols;
        int col = std::clamp(static_cast<int>(x - (kPillX + 8)) / bw, 0, cols - 1);
        int dims4[4] = {0};
        int dims2[2] = {0};

        if (row == 0) {
            m_rulingStyle = col;
            wn_session_set_page_setup(
                m_sessionHandle, m_pageWidth, m_pageHeight, 48, 48, 0,
                static_cast<int>(0xFFFFFFFF), static_cast<int>(0xFF888888),
                m_rulingStyle, dims4
            );
            m_canvasDirty = true;
            m_commitPending = true;
            renderFullScreen(/*fullFlash=*/false);
        } else if (row == 1) {
            if (col == 0) switchSketchIndex(-1);
            else if (col == 2) switchSketchIndex(1);
            else if (col == 3) {
                if (m_workerRunning) queueAsyncInsertInkBlock();
                else if (m_sync.isConnected() && !m_activeNodeId.empty()) {
                    OrgNodeSnapshotData snap;
                    if (m_sync.insertInkBlock(m_activeNodeId, 1200, 928, 0, 48, &snap)) {
                        applyNodeSnapshot(snap, snap.inkBlocks.empty() ? "" : snap.inkBlocks.back().fileName);
                        renderFullScreen(false);
                    }
                }
            } else if (col == 4) {
                if (wn_session_trim_bottom_whitespace(m_sessionHandle, 48, dims2)) {
                    m_canvasDirty = true;
                    m_commitPending = true;
                    renderFullScreen(/*fullFlash=*/false);
                }
            }
        } else {
            if (col == 0) { m_viewZoom = std::max(0.5f, m_viewZoom - 0.25f); m_canvasDirty = true; renderFullScreen(false); }
            else if (col == 1 || col == 3) { m_viewZoom = 1.0f; m_panX = 0.0f; m_panY = 0.0f; m_canvasDirty = true; renderFullScreen(false); }
            else if (col == 2) { m_viewZoom = std::min(2.0f, m_viewZoom + 0.25f); m_canvasDirty = true; renderFullScreen(false); }
        }
        return true;
    }

    if (m_popover == PopoverKind::ORG && pointInRect(x, y, kPillX, kPopoverY, kOrgPopW, kOrgPopH)) {
        if (y >= static_cast<float>(kPopoverY + 72)) {
            int bw = (kOrgPopW - 16) / 3;
            int col = std::clamp(static_cast<int>(x - (kPillX + 8)) / bw, 0, 2);
            if (col == 0) {
                if (m_activeTodo.empty()) m_activeTodo = "TODO";
                else if (m_activeTodo == "TODO") m_activeTodo = "NEXT";
                else if (m_activeTodo == "NEXT") m_activeTodo = "DONE";
                else m_activeTodo.clear();
                if (m_workerRunning) queueAsyncCycleTodo();
                else if (m_sync.isConnected()) m_sync.updateNodeHeader(m_activeNodeId, m_activeTodo);
                renderOverlayOnly(EinkWaveform::A2);
            } else if (col == 1) {
                m_flashcardFlagged = !m_flashcardFlagged;
                if (m_workerRunning) queueAsyncFlagFlashcard();
                else if (m_sync.isConnected()) m_sync.flagFlashcard(m_activeNodeId, m_activeFileName, m_flashcardFlagged);
                renderOverlayOnly(EinkWaveform::A2);
            } else if (col == 2) {
                if (m_workerRunning) {
                    queueAsyncCommitCurrent();
                    queueAsyncSync(!m_cachedConnected.load());
                } else {
                    flushPendingCommit();
                    syncWithServer(/*forceReconnect=*/!m_sync.isConnected());
                    renderFullScreen(/*fullFlash=*/false);
                }
            }
        }
        return true;
    }

    return false;
}

bool KindleScribeApp::handleSidebarTap(float x, float y) {
    if (!m_sidebarOpen) return false;
    const int sbW = std::min(kSidebarWidth, m_fb.width() / 2);
    if (!pointInRect(x, y, 0, kStripY, sbW, m_fb.height() - kStripY)) {
        return false;
    }

    // Tab bar at kStripY + 8 .. kStripY + 50
    if (y >= static_cast<float>(kStripY + 8) && y <= static_cast<float>(kStripY + 50)) {
        int tabW = (sbW - 16) / 4;
        int tabIdx = std::clamp(static_cast<int>(x - 8.0f) / std::max(1, tabW), 0, 3);
        m_sidebarTab = static_cast<SidebarTab>(tabIdx);
        if (m_workerRunning) {
            queueAsyncFetchNodeList(m_sidebarTab);
        } else if (m_sync.isConnected()) {
            std::vector<OrgNodeSummary> remote;
            const char* filt = (m_sidebarTab == SidebarTab::AGENDA) ? "agenda"
                : (m_sidebarTab == SidebarTab::HISTORY ? "pushed_history"
                : (m_sidebarTab == SidebarTab::GALLERY ? "gallery" : "buffer_outline"));
            if (m_sync.fetchNodeList(filt, &remote)) {
                if (m_sidebarTab == SidebarTab::HISTORY) m_historyNodes = std::move(remote);
                else if (m_sidebarTab == SidebarTab::AGENDA) m_agendaNodes = std::move(remote);
                else if (m_sidebarTab == SidebarTab::GALLERY) m_galleryNodes = std::move(remote);
                else m_outlineNodes = std::move(remote);
            }
        }
        renderOverlayOnly(EinkWaveform::A2);
        return true;
    }

    const std::vector<OrgNodeSummary>* list = &m_outlineNodes;
    if (m_sidebarTab == SidebarTab::HISTORY) list = &m_historyNodes;
    else if (m_sidebarTab == SidebarTab::AGENDA) list = &m_agendaNodes;
    else if (m_sidebarTab == SidebarTab::GALLERY) list = &m_galleryNodes;
    if (list->empty() && !m_outlineNodes.empty()) list = &m_outlineNodes;

    int relY = static_cast<int>(y) - (kStripY + 62);
    if (relY >= 0) {
        size_t idx = static_cast<size_t>(relY / 64);
        if (idx < list->size()) {
            const auto selectedNode = (*list)[idx];
            EinkRect oldBounds = currentOverlayBounds();
            m_sidebarOpen = false;
            m_toolbarExpanded = false;
            m_popover = PopoverKind::NONE;
            restoreCanvasRegionAndRefresh(oldBounds, EinkWaveform::A2);

            if (m_workerRunning) {
                queueAsyncCommitCurrent();
                queueAsyncFetchNode(selectedNode.nodeId);
            } else {
                flushPendingCommit();
                if (!fetchAndApplyRemoteNode(selectedNode.nodeId)) {
                    m_activeNodeId = selectedNode.nodeId;
                    m_activeTitle  = selectedNode.title;
                    m_activeTodo   = selectedNode.todoKeyword;
                }
                renderFullScreen(/*fullFlash=*/false);
            }
            return true;
        }
    }
    return true;
}

void KindleScribeApp::handleInputEvent(const ScribeInputEvent& ev) {
    if (m_sessionHandle <= 0 || ev.action == ScribeEventAction::HOVER) return;

    if (ev.action == ScribeEventAction::DOWN && !m_strokeActive && !m_selectDragging && !m_spaceDragging) {
        if (handlePillTap(ev.x, ev.y)) return;
        if (handleExpandedStripTap(ev.x, ev.y)) return;
        if (handleSelectionBarTap(ev.x, ev.y)) return;
        if (handlePopoverTap(ev.x, ev.y)) return;
        if (handleSidebarTap(ev.x, ev.y)) return;

        // Tapping canvas auto-collapses any open overlay
        if (m_toolbarExpanded || m_popover != PopoverKind::NONE || m_sidebarOpen) {
            collapseOverlaysForCanvas();
        }
    }

    if (ev.tool == ScribeToolType::FINGER && m_input.stylusOnlyCanvas()) {
        return;
    }

    const float canvasX = ev.x;
    const float canvasY = ev.y;
    const float scale = computeViewScale();
    const float pageX = (canvasX + m_panX) / scale;
    const float pageY = (canvasY + m_panY) / scale;

    const bool useEraser = (m_activeTool == ActiveTool::ERASER) ||
                           (ev.tool == ScribeToolType::ERASER) ||
                           ev.barrelButton;

    if (useEraser) {
        handleEraserEvent(ev, canvasX, canvasY, pageX, pageY, scale);
    } else if (m_activeTool == ActiveTool::SELECT) {
        handleSelectEvent(ev, canvasX, canvasY, pageX, pageY, scale);
    } else if (m_activeTool == ActiveTool::SPACE) {
        handleSpaceEvent(ev, canvasX, canvasY, pageX, pageY);
    } else {
        handlePenEvent(ev, canvasX, canvasY, pageX, pageY, scale);
    }
}

void KindleScribeApp::handleEraserEvent(
    const ScribeInputEvent& ev,
    float canvasX,
    float canvasY,
    float pageX,
    float pageY,
    float scale
) {
    float dirtyPage4[4] = {0, 0, 0, 0};
    const float eraseRadiusPage = 18.0f / scale;

    if (ev.action == ScribeEventAction::DOWN) {
        m_prevX = canvasX;
        m_prevY = canvasY;
        bool erased = false;
        if (m_eraserMode == EraserMode::RULED) {
            erased = wn_session_erase_ruled_line_at(m_sessionHandle, pageY, dirtyPage4);
        } else {
            erased = wn_session_erase_at(m_sessionHandle, pageX, pageY, eraseRadiusPage, dirtyPage4);
        }
        if (erased) {
            refreshCanvasDirtyRect(
                dirtyPage4[0] * scale, dirtyPage4[1] * scale,
                dirtyPage4[2] * scale, dirtyPage4[3] * scale,
                EinkWaveform::DU
            );
        }
    } else if (ev.action == ScribeEventAction::MOVE) {
        bool erased = false;
        if (m_eraserMode == EraserMode::RULED) {
            erased = wn_session_erase_ruled_line_at(m_sessionHandle, pageY, dirtyPage4);
        } else {
            erased = wn_session_erase_segment(
                m_sessionHandle,
                (m_prevX + m_panX) / scale, (m_prevY + m_panY) / scale,
                pageX, pageY,
                eraseRadiusPage, dirtyPage4
            );
        }
        if (erased) {
            refreshCanvasDirtyRect(
                dirtyPage4[0] * scale, dirtyPage4[1] * scale,
                dirtyPage4[2] * scale, dirtyPage4[3] * scale,
                EinkWaveform::DU
            );
        }
        m_prevX = canvasX;
        m_prevY = canvasY;
    } else if (ev.action == ScribeEventAction::UP) {
        m_canvasDirty = true;
        m_commitPending = true;
        renderFullScreen(/*fullFlash=*/false);
        if (!m_deferAutoCommit && !m_workerRunning) flushPendingCommit();
    }
}

void KindleScribeApp::handleSelectEvent(
    const ScribeInputEvent& ev,
    float canvasX,
    float canvasY,
    float pageX,
    float pageY,
    float scale
) {
    float bounds4[4] = {0};
    int dims2[2] = {0};

    if (ev.action == ScribeEventAction::DOWN) {
        if (m_selectMoving && wn_session_has_selection(m_sessionHandle)) {
            m_selectDragging = true;
            m_prevX = pageX;
            m_prevY = pageY;
            return;
        }
        m_selectDragging = true;
        m_gestureStartX  = pageX;
        m_gestureStartY  = pageY;
        m_prevX          = canvasX;
        m_prevY          = canvasY;
        m_lassoPoints.clear();
        m_lassoPoints.push_back(pageX);
        m_lassoPoints.push_back(pageY);
    } else if (ev.action == ScribeEventAction::MOVE && m_selectDragging) {
        if (m_selectMoving) {
            float dx = pageX - m_prevX;
            float dy = pageY - m_prevY;
            if (std::abs(dx) + std::abs(dy) >= 2.0f) {
                wn_session_translate_selection(m_sessionHandle, dx, dy, bounds4, dims2);
                m_prevX = pageX;
                m_prevY = pageY;
                m_canvasDirty = true;
            }
            return;
        }
        if (m_selectMode == SelectMode::LASSO) {
            m_lassoPoints.push_back(pageX);
            m_lassoPoints.push_back(pageY);
        }
        m_fb.drawThickLine(m_prevX, m_prevY, canvasX, canvasY, 1.5f, 0x60);
        m_prevX = canvasX;
        m_prevY = canvasY;
        m_fb.refreshRect(
            EinkRect{ std::clamp(static_cast<int>(canvasX) - 24, 0, m_fb.width()),
                      std::clamp(static_cast<int>(canvasY) - 24, 0, m_fb.height()), 48, 48 },
            EinkWaveform::DU, false
        );
    } else if (ev.action == ScribeEventAction::UP && m_selectDragging) {
        m_selectDragging = false;
        if (m_selectMoving) {
            m_commitPending = true;
            renderFullScreen(/*fullFlash=*/false);
            return;
        }
        int count = 0;
        if (m_selectMode == SelectMode::RECT) {
            count = wn_session_select_rect(
                m_sessionHandle,
                std::min(m_gestureStartX, pageX), std::min(m_gestureStartY, pageY),
                std::max(m_gestureStartX, pageX), std::max(m_gestureStartY, pageY),
                bounds4
            );
        } else if (m_selectMode == SelectMode::RULED) {
            count = wn_session_select_ruled(
                m_sessionHandle,
                std::min(m_gestureStartY, pageY),
                std::max(m_gestureStartY, pageY),
                bounds4
            );
        } else if (m_lassoPoints.size() >= 6) {
            count = wn_session_select_lasso(
                m_sessionHandle,
                m_lassoPoints.data(),
                static_cast<int>(m_lassoPoints.size()),
                bounds4
            );
        }
        m_selectMoving = (count > 0);
        renderFullScreen(/*fullFlash=*/false);
        if (count > 0) {
            m_fb.drawRectBorder(
                static_cast<int>(bounds4[0] * scale - m_panX),
                static_cast<int>(bounds4[1] * scale - m_panY),
                std::max(4, static_cast<int>((bounds4[2] - bounds4[0]) * scale)),
                std::max(4, static_cast<int>((bounds4[3] - bounds4[1]) * scale)),
                2, 0x20
            );
            m_fb.refreshFullScreen(EinkWaveform::A2, false);
        }
    }
}

void KindleScribeApp::handleSpaceEvent(
    const ScribeInputEvent& ev,
    float /*canvasX*/,
    float /*canvasY*/,
    float pageX,
    float pageY
) {
    if (ev.action == ScribeEventAction::DOWN) {
        m_spaceDragging = true;
        m_gestureStartX = pageX;
        m_gestureStartY = pageY;
    } else if (ev.action == ScribeEventAction::UP && m_spaceDragging) {
        m_spaceDragging = false;
        int dims2[2] = {0};
        bool changed = false;
        if (m_spaceMode == SpaceMode::VERTICAL) {
            float dy = pageY - m_gestureStartY;
            if (std::abs(dy) >= 6.0f) {
                changed = wn_session_insert_vertical_space(m_sessionHandle, m_gestureStartY, dy, true, dims2);
            }
        } else if (m_spaceMode == SpaceMode::HORIZONTAL) {
            float dx = pageX - m_gestureStartX;
            if (std::abs(dx) >= 6.0f) {
                changed = wn_session_insert_horizontal_space(m_sessionHandle, m_gestureStartX, dx, true, dims2);
            }
        } else {
            changed = wn_session_insert_ruled_space(
                m_sessionHandle, m_gestureStartX, m_gestureStartY, pageX, pageY, true, dims2
            );
        }
        if (changed) {
            m_canvasDirty = true;
            m_commitPending = true;
            renderFullScreen(/*fullFlash=*/false);
        }
    }
}

void KindleScribeApp::handlePenEvent(
    const ScribeInputEvent& ev,
    float canvasX,
    float canvasY,
    float pageX,
    float pageY,
    float scale
) {
    if (ev.action == ScribeEventAction::DOWN) {
        m_strokeActive   = true;
        m_prevX          = canvasX;
        m_prevY          = canvasY;
        m_strokeMinX     = canvasX;
        m_strokeMinY     = canvasY;
        m_strokeMaxX     = canvasX;
        m_strokeMaxY     = canvasY;
        m_pendingDuMinX  = canvasX;
        m_pendingDuMinY  = canvasY;
        m_pendingDuMaxX  = canvasX;
        m_pendingDuMaxY  = canvasY;
        m_hasPendingDu   = false;
        m_lastDuRefreshMs = 0.0;

        float press = ev.pressure > 0.0f ? ev.pressure : 0.5f;
        wn_session_begin_stroke(
            m_sessionHandle, pageX, pageY, press, ev.tiltX, ev.tiltY, ev.timestampMs
        );
        float screenRadius = std::max(1.0f, (m_penWidth * scale) * (0.35f + 0.65f * press) * 0.5f);
        m_fb.drawThickLine(canvasX, canvasY, canvasX, canvasY, screenRadius, 0x00);
    } else if (ev.action == ScribeEventAction::MOVE && m_strokeActive) {
        float press = ev.pressure > 0.0f ? ev.pressure : 0.5f;
        wn_session_move_stroke(
            m_sessionHandle, pageX, pageY, press, ev.tiltX, ev.tiltY, ev.timestampMs
        );
        float segLeft   = std::min(m_prevX, canvasX);
        float segTop    = std::min(m_prevY, canvasY);
        float segRight  = std::max(m_prevX, canvasX);
        float segBottom = std::max(m_prevY, canvasY);

        m_strokeMinX = std::min(m_strokeMinX, segLeft);
        m_strokeMinY = std::min(m_strokeMinY, segTop);
        m_strokeMaxX = std::max(m_strokeMaxX, segRight);
        m_strokeMaxY = std::max(m_strokeMaxY, segBottom);

        float screenRadius = std::max(1.0f, (m_penWidth * scale) * (0.35f + 0.65f * press) * 0.5f);
        m_fb.drawThickLine(m_prevX, m_prevY, canvasX, canvasY, screenRadius, 0x00);

        if (!m_hasPendingDu) {
            m_pendingDuMinX = segLeft;
            m_pendingDuMinY = segTop;
            m_pendingDuMaxX = segRight;
            m_pendingDuMaxY = segBottom;
            m_hasPendingDu  = true;
        } else {
            m_pendingDuMinX = std::min(m_pendingDuMinX, segLeft);
            m_pendingDuMinY = std::min(m_pendingDuMinY, segTop);
            m_pendingDuMaxX = std::max(m_pendingDuMaxX, segRight);
            m_pendingDuMaxY = std::max(m_pendingDuMaxY, segBottom);
        }

        m_prevX = canvasX;
        m_prevY = canvasY;

        if (m_lastDuRefreshMs == 0.0 || std::abs(ev.timestampMs - m_lastDuRefreshMs) >= 16.0) {
            flushPendingDuRefresh(ev.timestampMs);
        }
    } else if (ev.action == ScribeEventAction::UP && m_strokeActive) {
        m_strokeActive = false;
        m_hasPendingDu = false;
        flushPendingDuRefresh(ev.timestampMs);

        if (m_shapeSnap) {
            std::vector<float> snapped(256);
            int numPts = 0;
            wn_session_snap_active_stroke_to_shape(m_sessionHandle, snapped.data(), 256, &numPts);
        }

        int dimsAndDirty6[6] = {0};
        wn_session_end_stroke(m_sessionHandle, dimsAndDirty6);

        bool pageExpanded = (dimsAndDirty6[0] > 0 && dimsAndDirty6[1] > 0) &&
                            (dimsAndDirty6[0] != m_pageWidth || dimsAndDirty6[1] != m_pageHeight);
        if (dimsAndDirty6[0] > 0) m_pageWidth  = dimsAndDirty6[0];
        if (dimsAndDirty6[1] > 0) m_pageHeight = dimsAndDirty6[1];
        if (pageExpanded) {
            m_canvasDirty = true;
        }

        // Coalesce/defer GC16_FAST settle so consecutive strokes stay in fast DU mode without stalling
        const float docMinX = m_strokeMinX + m_panX;
        const float docMinY = m_strokeMinY + m_panY;
        const float docMaxX = m_strokeMaxX + m_panX;
        const float docMaxY = m_strokeMaxY + m_panY;
        if (!m_hasPendingSettle) {
            m_settleMinX = docMinX;
            m_settleMinY = docMinY;
            m_settleMaxX = docMaxX;
            m_settleMaxY = docMaxY;
            m_hasPendingSettle = true;
        } else {
            m_settleMinX = std::min(m_settleMinX, docMinX);
            m_settleMinY = std::min(m_settleMinY, docMinY);
            m_settleMaxX = std::max(m_settleMaxX, docMaxX);
            m_settleMaxY = std::max(m_settleMaxY, docMaxY);
        }
        m_lastStrokeEndMs = steadyNowMs();

        m_commitPending = true;
        if (!m_deferAutoCommit && !m_workerRunning) {
            flushPendingCommit();
        }
    }
}

std::vector<uint8_t> KindleScribeApp::exportSvgzBytes() const {
    if (m_sessionHandle <= 0) return {};
    int needed = wn_session_export_svgz(m_sessionHandle, nullptr, 0);
    if (needed <= 0) return {};
    std::vector<uint8_t> buf(static_cast<size_t>(needed));
    int written = wn_session_export_svgz(m_sessionHandle, buf.data(), needed);
    if (written <= 0) return {};
    buf.resize(static_cast<size_t>(written));
    return buf;
}

bool KindleScribeApp::loadSvgzBytes(const std::vector<uint8_t>& bytes) {
    if (m_sessionHandle <= 0 || bytes.empty()) return false;
    if (!wn_session_load_svgz(m_sessionHandle, bytes.data(), static_cast<int>(bytes.size()))) {
        return false;
    }
    syncPageInfoFromEngine();
    m_canvasDirty = true;
    return true;
}

bool KindleScribeApp::loadSketchFile(const std::string& svgzPath) {
    if (m_sessionHandle <= 0) return false;
    m_localSavePath = svgzPath;
    std::ifstream ifs(svgzPath, std::ios::in | std::ios::binary | std::ios::ate);
    if (!ifs) return false;
    std::streamsize sz = ifs.tellg();
    if (sz <= 0) return false;
    ifs.seekg(0, std::ios::beg);
    std::vector<uint8_t> bytes(static_cast<size_t>(sz));
    if (!ifs.read(reinterpret_cast<char*>(bytes.data()), sz)) return false;

    if (!loadSvgzBytes(bytes)) {
        return false;
    }
    renderFullScreen(/*fullFlash=*/false);
    return true;
}

bool KindleScribeApp::saveSketchFile(const std::string& svgzPath) const {
    auto bytes = exportSvgzBytes();
    if (bytes.empty()) return false;
    std::ofstream ofs(svgzPath, std::ios::out | std::ios::binary | std::ios::trunc);
    if (!ofs) return false;
    ofs.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    return ofs.good();
}

bool KindleScribeApp::switchSketchIndex(int delta) {
    if (m_nodeBlocks.size() <= 1) return false;
    int next = (m_activeBlockIndex + delta) % static_cast<int>(m_nodeBlocks.size());
    if (next < 0) next += static_cast<int>(m_nodeBlocks.size());
    if (next == m_activeBlockIndex) return false;

    if (m_workerRunning) queueAsyncCommitCurrent();
    else flushPendingCommit();

    m_activeBlockIndex = next;
    const auto& chosen = m_nodeBlocks[static_cast<size_t>(m_activeBlockIndex)];
    m_activeFileName = chosen.fileName;
    m_activeEtag = chosen.etag;
    if (!chosen.svgzBase64.empty()) {
        loadSvgzBytes(base64Decode(chosen.svgzBase64));
    } else {
        m_pageWidth  = chosen.width > 0 ? chosen.width : 1200;
        m_pageHeight = chosen.height > 0 ? chosen.height : 928;
        int dims4[4] = {0};
        wn_session_set_page_setup(
            m_sessionHandle, m_pageWidth, m_pageHeight,
            chosen.xRuling, chosen.yRuling, 0,
            static_cast<int>(0xFFFFFFFF), static_cast<int>(0xFF888888),
            m_rulingStyle, dims4
        );
        m_canvasDirty = true;
    }
    renderFullScreen(/*fullFlash=*/false);
    return true;
}

bool KindleScribeApp::applyNodeSnapshot(
    const OrgNodeSnapshotData& snap,
    const std::string& preferredFileName
) {
    if (snap.nodeId.empty()) return false;

    m_activeNodeId = snap.nodeId;
    m_activeTitle  = snap.title.empty() ? "Untitled" : snap.title;
    m_activeTodo   = snap.todoKeyword;
    m_nodeBlocks   = snap.inkBlocks;
    m_activeBlockIndex = 0;

    OrgNodeSummary summary;
    summary.nodeId      = snap.nodeId;
    summary.filePath    = snap.filePath;
    summary.title       = m_activeTitle;
    summary.todoKeyword = snap.todoKeyword;
    summary.level       = snap.level;
    summary.sketchCount = static_cast<int>(snap.inkBlocks.size());

    m_outlineNodes.erase(
        std::remove_if(m_outlineNodes.begin(), m_outlineNodes.end(), [&](const OrgNodeSummary& n) {
            return n.nodeId == snap.nodeId;
        }),
        m_outlineNodes.end()
    );
    m_outlineNodes.insert(m_outlineNodes.begin(), summary);

    const InkBlockInfo* chosen = nullptr;
    auto findIdx = [&](const std::string& name) -> const InkBlockInfo* {
        for (size_t i = 0; i < m_nodeBlocks.size(); ++i) {
            if (m_nodeBlocks[i].fileName == name) {
                m_activeBlockIndex = static_cast<int>(i);
                return &m_nodeBlocks[i];
            }
        }
        return nullptr;
    };
    if (!preferredFileName.empty()) chosen = findIdx(preferredFileName);
    if (!chosen && !m_activeFileName.empty()) chosen = findIdx(m_activeFileName);
    if (!chosen && !m_nodeBlocks.empty()) {
        m_activeBlockIndex = 0;
        chosen = &m_nodeBlocks.front();
    }

    if (chosen) {
        m_activeFileName = chosen->fileName;
        if (!chosen->etag.empty()) {
            std::lock_guard<std::mutex> lock(m_syncIoMutex);
            m_sync.setKnownEtagForFile(chosen->fileName, chosen->etag);
        }
        {
            std::lock_guard<std::mutex> lock(m_syncIoMutex);
            m_activeEtag = m_sync.knownEtagForFile(chosen->fileName);
        }
        if (!chosen->svgzBase64.empty()) {
            auto bytes = base64Decode(chosen->svgzBase64);
            if (loadSvgzBytes(bytes)) {
                if (!m_localSavePath.empty()) {
                    saveSketchFile(m_localSavePath);
                }
            }
        } else {
            m_pageWidth  = chosen->width > 0 ? chosen->width : 1200;
            m_pageHeight = chosen->height > 0 ? chosen->height : 600;
            int dims4[4] = {0};
            wn_session_set_page_setup(
                m_sessionHandle, m_pageWidth, m_pageHeight,
                chosen->xRuling, chosen->yRuling, 0,
                static_cast<int>(0xFFFFFFFF), static_cast<int>(0xFF888888),
                m_rulingStyle, dims4
            );
            m_canvasDirty = true;
        }
    } else {
        m_activeFileName.clear();
        m_activeEtag.clear();
        m_pageWidth  = 1200;
        m_pageHeight = 928;
        if (m_sessionHandle > 0) {
            wn_session_destroy(m_sessionHandle);
        }
        m_sessionHandle = wn_session_create(m_pageWidth, m_pageHeight, 0, 48);
        applyPenSettings();
        m_canvasDirty = true;
    }

    m_commitPending = false;
    return true;
}

bool KindleScribeApp::fetchAndApplyRemoteNode(
    const std::string& nodeId,
    const std::string& preferredFileName
) {
    OrgNodeSnapshotData snap;
    {
        std::lock_guard<std::mutex> lock(m_syncIoMutex);
        if (!m_sync.isConnected()) return false;
        if (!m_sync.fetchNodeSnapshot(nodeId, &snap)) return false;
    }
    return applyNodeSnapshot(snap, preferredFileName);
}

bool KindleScribeApp::pollServerNotifications() {
    if (m_strokeActive) return false;
    bool updated = false;
    std::string method;
    std::string paramsJson;
    while (true) {
        {
            std::lock_guard<std::mutex> lock(m_syncIoMutex);
            if (!m_sync.isConnected() || !m_sync.pollServerNotification(&method, &paramsJson, 0)) {
                break;
            }
        }
        if (method == "emacs.focusNode") {
            flushPendingCommit();
            OrgNodeSnapshotData snap;
            if (parseNodeSnapshotJson(paramsJson, &snap) && applyNodeSnapshot(snap)) {
                updated = true;
            }
        } else if (method == "emacs.zoomInkBlock") {
            flushPendingCommit();
            std::string fileName = extractJsonStringField(paramsJson, "fileName");
            std::string snapObj  = extractJsonObjectField(paramsJson, "snapshot");
            OrgNodeSnapshotData snap;
            if (!snapObj.empty() && parseNodeSnapshotJson(snapObj, &snap)) {
                if (applyNodeSnapshot(snap, fileName)) updated = true;
            } else {
                std::string nid = extractJsonStringField(paramsJson, "nodeId");
                if (fetchAndApplyRemoteNode(nid, fileName)) updated = true;
            }
        } else if (method == "emacs.nodeModified") {
            if (!m_commitPending) {
                OrgNodeSnapshotData snap;
                if (parseNodeSnapshotJson(paramsJson, &snap) && snap.nodeId == m_activeNodeId) {
                    if (applyNodeSnapshot(snap, m_activeFileName)) updated = true;
                }
            }
        }
    }
    if (updated) {
        renderFullScreen(/*fullFlash=*/false);
    }
    return updated;
}

bool KindleScribeApp::syncWithServer(bool forceReconnect) {
    {
        std::lock_guard<std::mutex> lock(m_syncIoMutex);
        if (forceReconnect || !m_sync.isConnected()) {
            if (!m_sync.connectAndHandshake()) {
                refreshCachedSyncStatus();
                return false;
            }
        }
    }

    std::string method;
    std::string paramsJson;
    bool loadedFromPush = false;
    while (true) {
        {
            std::lock_guard<std::mutex> lock(m_syncIoMutex);
            if (!m_sync.pollServerNotification(&method, &paramsJson, 80)) break;
        }
        if (method == "emacs.focusNode") {
            OrgNodeSnapshotData snap;
            if (parseNodeSnapshotJson(paramsJson, &snap) && applyNodeSnapshot(snap)) {
                loadedFromPush = true;
            }
        } else if (method == "emacs.zoomInkBlock") {
            std::string fileName = extractJsonStringField(paramsJson, "fileName");
            std::string snapObj  = extractJsonObjectField(paramsJson, "snapshot");
            OrgNodeSnapshotData snap;
            if (!snapObj.empty() && parseNodeSnapshotJson(snapObj, &snap) && applyNodeSnapshot(snap, fileName)) {
                loadedFromPush = true;
            }
        }
    }

    std::vector<OrgNodeSummary> remoteNodes;
    std::vector<OrgNodeSummary> bufferNodes;
    std::string serverActiveId;
    {
        std::lock_guard<std::mutex> lock(m_syncIoMutex);
        m_sync.fetchNodeList("pushed_history", &remoteNodes);
        m_sync.fetchNodeList("buffer_outline", &bufferNodes);
        serverActiveId = m_sync.serverActiveNodeId();
    }

    if (!remoteNodes.empty()) {
        m_historyNodes = remoteNodes;
        m_outlineNodes = std::move(remoteNodes);
    }
    if (!bufferNodes.empty()) {
        for (const auto& bn : bufferNodes) {
            bool exists = false;
            for (const auto& en : m_outlineNodes) {
                if (en.nodeId == bn.nodeId) { exists = true; break; }
            }
            if (!exists) m_outlineNodes.push_back(bn);
        }
    }

    if (!loadedFromPush) {
        std::string targetId = serverActiveId;
        if (targetId.empty() && !m_activeNodeId.empty() && m_activeNodeId != "file:inbox.org::#scribe-quick-note") {
            targetId = m_activeNodeId;
        }
        if (targetId.empty() && !m_outlineNodes.empty()) {
            targetId = m_outlineNodes.front().nodeId;
        }
        if (!targetId.empty()) {
            fetchAndApplyRemoteNode(targetId);
        }
    }

    refreshCachedSyncStatus();
    return true;
}

bool KindleScribeApp::flushPendingCommit() {
    if (!m_commitPending) return true;
    m_commitPending = false;
    return commitCurrentSketch();
}

bool KindleScribeApp::commitCurrentSketch() {
    auto svgz = exportSvgzBytes();
    if (svgz.empty()) return false;

    if (!m_localSavePath.empty()) {
        saveSketchFile(m_localSavePath);
    }

    std::lock_guard<std::mutex> lock(m_syncIoMutex);
    if (m_activeFileName.empty()) {
        if (m_sync.isConnected() &&
            !m_activeNodeId.empty() &&
            m_activeNodeId != "file:inbox.org::#scribe-quick-note") {
            OrgNodeSnapshotData insertedSnap;
            if (m_sync.insertInkBlock(m_activeNodeId, m_pageWidth, m_pageHeight, 0, 48, &insertedSnap) &&
                !insertedSnap.inkBlocks.empty()) {
                m_activeFileName = insertedSnap.inkBlocks.back().fileName;
            }
        }
        if (m_activeFileName.empty()) {
            m_activeFileName = "scribe-note.svgz";
        }
    }

    if (m_sync.isConnected() && m_activeNodeId == "file:inbox.org::#scribe-quick-note") {
        refreshCachedSyncStatus();
        return true;
    }

    InkCommitPayload payload;
    payload.nodeId     = m_activeNodeId;
    payload.fileName   = m_activeFileName;
    payload.width      = m_pageWidth;
    payload.height     = m_pageHeight;
    payload.baseEtag   = m_sync.knownEtagForFile(m_activeFileName);
    payload.svgzBase64 = base64Encode(svgz.data(), svgz.size());
    payload.ocrText    = "";

    bool ok = m_sync.commitOrEnqueueInk(payload);
    m_activeEtag = m_sync.knownEtagForFile(m_activeFileName);
    refreshCachedSyncStatus();
    return ok;
}

void KindleScribeApp::refreshCachedSyncStatus() {
    m_cachedConnected.store(m_sync.isConnected());
    m_cachedQueueCount.store(m_sync.pendingQueueCount());
    m_cachedHasConflicts.store(!m_sync.conflictedFiles().empty());
}

void KindleScribeApp::startAsyncSyncWorker() {
    if (m_workerRunning) return;
    m_workerStop = false;
    m_workerRunning = true;
    m_syncThread = std::thread(&KindleScribeApp::asyncWorkerLoop, this);
}

void KindleScribeApp::stopAsyncSyncWorker() {
    if (!m_workerRunning) return;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        m_workerStop = true;
    }
    m_queueCv.notify_all();
    if (m_syncThread.joinable()) {
        m_syncThread.join();
    }
    m_workerRunning = false;
}

void KindleScribeApp::enqueueAsyncTask(AsyncTask task) {
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        m_taskQueue.push_back(std::move(task));
    }
    m_queueCv.notify_one();
}

void KindleScribeApp::queueAsyncCommitCurrent() {
    if (!m_commitPending) return;
    auto svgz = exportSvgzBytes();
    if (svgz.empty()) return;
    m_commitPending = false;
    if (!m_localSavePath.empty()) {
        saveSketchFile(m_localSavePath);
    }

    AsyncTask task;
    task.type = AsyncOpType::COMMIT_PAYLOAD;
    task.commitPayload.nodeId     = m_activeNodeId;
    task.commitPayload.fileName   = m_activeFileName;
    task.commitPayload.width      = m_pageWidth;
    task.commitPayload.height     = m_pageHeight;
    task.commitPayload.svgzBase64 = base64Encode(svgz.data(), svgz.size());
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncSync(bool forceReconnect) {
    AsyncTask task;
    task.type = AsyncOpType::SYNC_FULL;
    task.boolParam = forceReconnect;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncFetchNodeList(SidebarTab tab) {
    AsyncTask task;
    task.type = AsyncOpType::FETCH_NODE_LIST;
    task.tab  = tab;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncFetchNode(const std::string& nodeId, const std::string& preferredFileName) {
    AsyncTask task;
    task.type = AsyncOpType::FETCH_NODE;
    task.str1 = nodeId;
    task.str2 = preferredFileName;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncInsertInkBlock() {
    AsyncTask task;
    task.type = AsyncOpType::INSERT_INK_BLOCK;
    task.str1 = m_activeNodeId;
    task.int1 = 1200;
    task.int2 = 928;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncCycleTodo() {
    AsyncTask task;
    task.type = AsyncOpType::UPDATE_HEADER;
    task.str1 = m_activeNodeId;
    task.str2 = m_activeTodo;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncFlagFlashcard() {
    AsyncTask task;
    task.type = AsyncOpType::FLAG_FLASHCARD;
    task.str1 = m_activeNodeId;
    task.str2 = m_activeFileName;
    task.boolParam = m_flashcardFlagged;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::queueAsyncPollNotifications() {
    AsyncTask task;
    task.type = AsyncOpType::POLL_NOTIFICATIONS;
    enqueueAsyncTask(std::move(task));
}

void KindleScribeApp::asyncWorkerLoop() {
    while (true) {
        AsyncTask task;
        {
            std::unique_lock<std::mutex> lock(m_queueMutex);
            m_queueCv.wait(lock, [&]() { return m_workerStop || !m_taskQueue.empty(); });
            if (m_workerStop && m_taskQueue.empty()) break;
            task = std::move(m_taskQueue.front());
            m_taskQueue.pop_front();
        }

        AsyncResult res;
        res.type = task.type;
        res.tab  = task.tab;

        std::lock_guard<std::mutex> ioLock(m_syncIoMutex);
        switch (task.type) {
            case AsyncOpType::COMMIT_PAYLOAD: {
                if (m_sync.isConnected() && task.commitPayload.nodeId == "file:inbox.org::#scribe-quick-note") {
                    res.ok = true;
                    break;
                }
                if (task.commitPayload.fileName.empty()) {
                    if (m_sync.isConnected() && !task.commitPayload.nodeId.empty()) {
                        OrgNodeSnapshotData insertedSnap;
                        if (m_sync.insertInkBlock(
                                task.commitPayload.nodeId,
                                task.commitPayload.width,
                                task.commitPayload.height,
                                0, 48, &insertedSnap) &&
                            !insertedSnap.inkBlocks.empty()) {
                            task.commitPayload.fileName = insertedSnap.inkBlocks.back().fileName;
                            res.str1 = task.commitPayload.fileName;
                        }
                    }
                    if (task.commitPayload.fileName.empty()) {
                        task.commitPayload.fileName = "scribe-note.svgz";
                    }
                }
                task.commitPayload.baseEtag = m_sync.knownEtagForFile(task.commitPayload.fileName);
                res.ok = m_sync.commitOrEnqueueInk(task.commitPayload);
                res.str2 = task.commitPayload.fileName;
                res.etag = m_sync.knownEtagForFile(task.commitPayload.fileName);
                break;
            }
            case AsyncOpType::FETCH_NODE_LIST: {
                if (!m_sync.isConnected()) break;
                const char* filt = (task.tab == SidebarTab::AGENDA) ? "agenda"
                    : (task.tab == SidebarTab::HISTORY ? "pushed_history"
                    : (task.tab == SidebarTab::GALLERY ? "gallery" : "buffer_outline"));
                res.ok = m_sync.fetchNodeList(filt, &res.nodes);
                break;
            }
            case AsyncOpType::FETCH_NODE: {
                if (!m_sync.isConnected()) break;
                res.ok = m_sync.fetchNodeSnapshot(task.str1, &res.snapshot);
                res.hasSnapshot = res.ok;
                res.str1 = task.str2;
                break;
            }
            case AsyncOpType::INSERT_INK_BLOCK: {
                if (!m_sync.isConnected()) break;
                res.ok = m_sync.insertInkBlock(task.str1, task.int1, task.int2, 0, 48, &res.snapshot);
                res.hasSnapshot = res.ok && !res.snapshot.nodeId.empty();
                break;
            }
            case AsyncOpType::UPDATE_HEADER: {
                if (m_sync.isConnected()) {
                    res.ok = m_sync.updateNodeHeader(task.str1, task.str2);
                }
                break;
            }
            case AsyncOpType::FLAG_FLASHCARD: {
                if (m_sync.isConnected()) {
                    res.ok = m_sync.flagFlashcard(task.str1, task.str2, task.boolParam);
                }
                break;
            }
            case AsyncOpType::POLL_NOTIFICATIONS: {
                if (!m_sync.isConnected()) break;
                std::string method, paramsJson;
                while (m_sync.pollServerNotification(&method, &paramsJson, 0)) {
                    // One result per notification so none are overwritten
                    AsyncResult nr;
                    nr.type = AsyncOpType::POLL_NOTIFICATIONS;
                    if (method == "emacs.focusNode" || method == "emacs.nodeModified") {
                        if (parseNodeSnapshotJson(paramsJson, &nr.snapshot)) {
                            nr.hasSnapshot = true;
                            nr.str1 = method;
                            nr.ok = true;
                        }
                    } else if (method == "emacs.zoomInkBlock") {
                        std::string fileName = extractJsonStringField(paramsJson, "fileName");
                        std::string snapObj  = extractJsonObjectField(paramsJson, "snapshot");
                        if (!snapObj.empty() && parseNodeSnapshotJson(snapObj, &nr.snapshot)) {
                            nr.hasSnapshot = true;
                            nr.str1 = fileName;
                            nr.ok = true;
                        }
                    }
                    if (nr.hasSnapshot) {
                        std::lock_guard<std::mutex> qlock(m_queueMutex);
                        m_resultQueue.push_back(std::move(nr));
                    }
                }
                break;
            }
            case AsyncOpType::SYNC_FULL: {
                if (task.boolParam || !m_sync.isConnected()) {
                    m_sync.connectAndHandshake();
                }
                if (m_sync.isConnected()) {
                    m_sync.fetchNodeList("buffer_outline", &res.nodes);
                    std::string targetId = m_sync.serverActiveNodeId();
                    if (!targetId.empty()) {
                        res.hasSnapshot = m_sync.fetchNodeSnapshot(targetId, &res.snapshot);
                    }
                    res.ok = true;
                }
                break;
            }
        }
        refreshCachedSyncStatus();

        if (task.type == AsyncOpType::POLL_NOTIFICATIONS) continue;
        {
            std::lock_guard<std::mutex> lock(m_queueMutex);
            m_resultQueue.push_back(std::move(res));
        }
    }
}

bool KindleScribeApp::processAsyncSyncResults() {
    std::deque<AsyncResult> results;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        if (m_resultQueue.empty()) return false;
        results.swap(m_resultQueue);
    }

    bool uiNeedsRedraw = false;
    bool fullCanvasRedraw = false;
    // Never let a server push clobber unsaved local ink: skip same-node pushes while
    // drawing/uncommitted; for other nodes, commit the local strokes first.
    auto safeToApply = [&](const OrgNodeSnapshotData& snap, bool explicitRequest) {
        if (m_strokeActive && !explicitRequest) return false;
        if (m_commitPending) {
            if (!explicitRequest && snap.nodeId == m_activeNodeId) return false;
            queueAsyncCommitCurrent();
        }
        return true;
    };
    for (auto& res : results) {
        switch (res.type) {
            case AsyncOpType::COMMIT_PAYLOAD:
                if (!res.str1.empty() && m_activeFileName.empty()) {
                    m_activeFileName = res.str1;
                }
                if (!res.str2.empty() && res.str2 == m_activeFileName) {
                    m_activeEtag = res.etag;
                }
                uiNeedsRedraw = true;
                break;
            case AsyncOpType::FETCH_NODE_LIST:
                if (res.ok) {
                    if (res.tab == SidebarTab::HISTORY) m_historyNodes = std::move(res.nodes);
                    else if (res.tab == SidebarTab::AGENDA) m_agendaNodes = std::move(res.nodes);
                    else if (res.tab == SidebarTab::GALLERY) m_galleryNodes = std::move(res.nodes);
                    else m_outlineNodes = std::move(res.nodes);
                    if (m_sidebarOpen) uiNeedsRedraw = true;
                }
                break;
            case AsyncOpType::FETCH_NODE:
            case AsyncOpType::INSERT_INK_BLOCK:
                if (res.hasSnapshot && safeToApply(res.snapshot, true) && applyNodeSnapshot(res.snapshot, res.str1)) {
                    fullCanvasRedraw = true;
                }
                break;
            case AsyncOpType::POLL_NOTIFICATIONS:
                if (res.hasSnapshot) {
                    if (res.str1 == "emacs.nodeModified" && res.snapshot.nodeId != m_activeNodeId) {
                        break;
                    }
                    if (!safeToApply(res.snapshot, false)) break;
                    std::string pref = (res.str1 == "emacs.focusNode" || res.str1 == "emacs.nodeModified")
                        ? m_activeFileName : res.str1;
                    if (applyNodeSnapshot(res.snapshot, pref)) {
                        fullCanvasRedraw = true;
                    }
                }
                break;
            case AsyncOpType::SYNC_FULL:
                if (!res.nodes.empty()) m_outlineNodes = std::move(res.nodes);
                if (res.hasSnapshot && safeToApply(res.snapshot, false) && applyNodeSnapshot(res.snapshot)) {
                    fullCanvasRedraw = true;
                } else {
                    uiNeedsRedraw = true;
                }
                break;
            default:
                break;
        }
    }

    if (fullCanvasRedraw) {
        renderFullScreen(/*fullFlash=*/false);
    } else if (uiNeedsRedraw) {
        renderOverlayOnly(EinkWaveform::A2);
    }
    return fullCanvasRedraw || uiNeedsRedraw;
}

void KindleScribeApp::setActiveNode(
    const std::string& nodeId,
    const std::string& title,
    const std::string& sketchFileName
) {
    m_activeNodeId   = nodeId;
    m_activeTitle    = title;
    m_activeFileName = sketchFileName;
}

void KindleScribeApp::setOutlineNodes(const std::vector<OrgNodeSummary>& nodes) {
    m_outlineNodes = nodes;
}

int KindleScribeApp::strokeCount() const {
    if (m_sessionHandle <= 0) return 0;
    return wn_session_stroke_count(m_sessionHandle);
}

} // namespace orgwritenotes::kindle
