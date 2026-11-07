#include "scribe_app.h"
#include "write_bridge_c_api.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>

namespace orgwritenotes::kindle {

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

    const int canvasW = m_fb.width();
    const int canvasH = std::max(64, m_fb.height() - kToolbarHeight);
    m_canvasRgba.assign(static_cast<size_t>(canvasW) * canvasH * 4, 0xFF);

    if (m_sessionHandle > 0) {
        wn_session_destroy(m_sessionHandle);
    }
    m_sessionHandle = wn_session_create(canvasW, canvasH, 0, 48);
    if (m_sessionHandle <= 0) return false;

    // Configure crisp black ink pen for E-Ink Carta 1200 contrast
    wn_session_set_pen(
        m_sessionHandle,
        static_cast<int>(0xFF000000),
        m_penWidth,
        /*pressureSensitive=*/true,
        /*tipType=*/0,
        /*snapToGrid=*/false,
        /*lineDrawing=*/false,
        /*chiselAngle=*/0.0f
    );

    if (m_outlineNodes.empty()) {
        m_outlineNodes = {
            { "file:inbox.org::#scribe-quick-note", "inbox.org", "Kindle Scribe Quick Note", "TODO", "2026-11-06", 1, 1 },
            { "file:projects.org::#architecture", "projects.org", "System Architecture Sketch", "NEXT", "2026-11-07", 2, 2 },
            { "file:journal.org::#daily-log", "journal.org", "Daily Research Notes", "", "2026-11-06", 1, 1 }
        };
    }

    renderFullScreen(/*fullFlash=*/true);
    return true;
}

void KindleScribeApp::renderTopToolbar() {
    const int w = m_fb.width();
    m_fb.fillRect(0, 0, w, kToolbarHeight, 0xF0);
    m_fb.drawRectBorder(0, 0, w, kToolbarHeight, 2, 0x00);

    // 7 evenly spaced E-Ink action buttons across the top bar
    // [0:Outline] [1:Pen/Erase] [2:Template] [3:Undo] [4:Redo] [5:Sync] [6:Exit]
    static const char* kRuleNames[] = { "Rule:Line", "Rule:Dot", "Rule:Grid", "Rule:Mus", "Rule:Corn" };
    int ruleIdx = std::clamp(m_rulingStyle, 0, 4);

    std::string syncLabel = m_sync.isConnected()
        ? "Sync:LIVE"
        : ("Queue:" + std::to_string(m_sync.pendingQueueCount()));
    if (!m_sync.conflictedFiles().empty()) {
        syncLabel = "!Conflict";
    }

    std::vector<std::string> labels = {
        m_sidebarOpen ? "[Nodes*]" : "[Nodes]",
        m_eraserMode  ? "[Eraser]" : "[Pen]",
        std::string("[") + kRuleNames[ruleIdx] + "]",
        "[Undo]",
        "[Redo]",
        "[" + syncLabel + "]",
        "[Exit]"
    };

    const int btnCount = static_cast<int>(labels.size());
    const int btnW     = w / btnCount;
    for (int i = 0; i < btnCount; ++i) {
        int bx = i * btnW;
        bool active = (i == 0 && m_sidebarOpen) || (i == 1 && m_eraserMode);
        uint8_t bg  = active ? 0x20 : 0xF0;
        uint8_t fg  = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, 4, btnW - 4, kToolbarHeight - 8, bg);
        m_fb.drawRectBorder(bx + 2, 4, btnW - 4, kToolbarHeight - 8, 2, 0x00);
        m_fb.drawText(bx + 12, (kToolbarHeight - 16) / 2, labels[static_cast<size_t>(i)], fg, bg, 2);
    }
}

void KindleScribeApp::renderFullScreen(bool fullFlash) {
    if (m_sessionHandle <= 0) return;

    const int canvasW = m_fb.width();
    const int canvasH = std::max(64, m_fb.height() - kToolbarHeight);

    renderTopToolbar();

    if (wn_session_render_rgba(m_sessionHandle, m_canvasRgba.data(), canvasW, canvasH, 0.0f, 0.0f)) {
        m_fb.blitRgbaRegion(
            m_canvasRgba.data(),
            canvasW,
            canvasH,
            0,
            0,
            0,
            kToolbarHeight,
            canvasW,
            canvasH,
            /*dither1Bit=*/false
        );
    }

    if (m_sidebarOpen) {
        const int sbW = std::min(kSidebarWidth, canvasW / 2);
        m_fb.fillRect(0, kToolbarHeight, sbW, canvasH, 0xFA);
        m_fb.drawRectBorder(0, kToolbarHeight, sbW, canvasH, 3, 0x00);
        m_fb.drawText(16, kToolbarHeight + 16, "ORG-WRITENOTES OUTLINE", 0x00, 0xFA, 2);
        m_fb.drawHLine(12, kToolbarHeight + 44, sbW - 24, 0x00);

        int itemY = kToolbarHeight + 60;
        for (size_t i = 0; i < m_outlineNodes.size(); ++i) {
            const auto& node = m_outlineNodes[i];
            bool selected = (node.nodeId == m_activeNodeId);
            uint8_t bg = selected ? 0x30 : 0xFA;
            uint8_t fg = selected ? 0xFF : 0x00;
            m_fb.fillRect(8, itemY, sbW - 16, 56, bg);
            m_fb.drawRectBorder(8, itemY, sbW - 16, 56, 1, 0x40);
            std::string line = (node.todoKeyword.empty() ? "* " : (node.todoKeyword + " ")) + node.title;
            if (line.size() > 26) line.resize(26);
            m_fb.drawText(18, itemY + 18, line, fg, bg, 2);
            itemY += 64;
        }
    }

    m_fb.refreshFullScreen(EinkWaveform::GC16, fullFlash);
}

void KindleScribeApp::refreshCanvasDirtyRect(
    float left,
    float top,
    float right,
    float bottom,
    EinkWaveform wf
) {
    if (m_sessionHandle <= 0) return;
    const int canvasW = m_fb.width();
    const int canvasH = std::max(64, m_fb.height() - kToolbarHeight);

    int x0 = std::clamp(static_cast<int>(std::floor(left)) - 8, 0, canvasW);
    int y0 = std::clamp(static_cast<int>(std::floor(top)) - 8, 0, canvasH);
    int x1 = std::clamp(static_cast<int>(std::ceil(right)) + 8, 0, canvasW);
    int y1 = std::clamp(static_cast<int>(std::ceil(bottom)) + 8, 0, canvasH);
    if (x1 <= x0 || y1 <= y0) return;

    if (wn_session_render_dirty_rgba(
            m_sessionHandle,
            m_canvasRgba.data(),
            canvasW,
            canvasH,
            static_cast<float>(x0),
            static_cast<float>(y0),
            static_cast<float>(x1),
            static_cast<float>(y1)
        )) {
        const bool dither1Bit = (wf == EinkWaveform::DU || wf == EinkWaveform::A2);
        m_fb.blitRgbaRegion(
            m_canvasRgba.data(),
            canvasW,
            canvasH,
            x0,
            y0,
            x0,
            y0 + kToolbarHeight,
            x1 - x0,
            y1 - y0,
            dither1Bit
        );
        m_fb.refreshRect(
            EinkRect{ x0, y0 + kToolbarHeight, x1 - x0, y1 - y0 },
            wf,
            /*fullFlash=*/false
        );
    }
}

bool KindleScribeApp::handleToolbarTap(float x, float y) {
    if (y < 0.0f || y >= static_cast<float>(kToolbarHeight)) return false;
    const int btnCount = 7;
    const int btnW     = std::max(1, m_fb.width() / btnCount);
    const int idx      = std::clamp(static_cast<int>(x) / btnW, 0, btnCount - 1);

    int dims2[2] = {0, 0};
    int dims4[4] = {0, 0, 0, 0};

    switch (idx) {
        case 0: // Toggle Outline Drawer
            m_sidebarOpen = !m_sidebarOpen;
            renderFullScreen(/*fullFlash=*/false);
            break;
        case 1: // Toggle Pen / Eraser
            m_eraserMode = !m_eraserMode;
            renderTopToolbar();
            m_fb.refreshRect(EinkRect{ 0, 0, m_fb.width(), kToolbarHeight }, EinkWaveform::A2, false);
            break;
        case 2: // Cycle Ruling Style (Standard -> Dotted -> Grid -> Music -> Cornell)
            m_rulingStyle = (m_rulingStyle + 1) % 5;
            wn_session_set_page_setup(
                m_sessionHandle,
                m_fb.width(),
                std::max(64, m_fb.height() - kToolbarHeight),
                48,
                48,
                0,
                static_cast<int>(0xFFFFFFFF),
                static_cast<int>(0xFF888888),
                m_rulingStyle,
                dims4
            );
            renderFullScreen(/*fullFlash=*/false);
            break;
        case 3: // Undo
            if (wn_session_undo(m_sessionHandle, dims2)) {
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 4: // Redo
            if (wn_session_redo(m_sessionHandle, dims2)) {
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 5: // Sync / Commit now + Full E-Ink Refresh
            commitCurrentSketch();
            renderFullScreen(/*fullFlash=*/true);
            break;
        case 6: // Exit to Kindle UI
            commitCurrentSketch();
            m_shouldExit = true;
            break;
    }
    return true;
}

bool KindleScribeApp::handleSidebarTap(float x, float y) {
    if (!m_sidebarOpen) return false;
    const int sbW = std::min(kSidebarWidth, m_fb.width() / 2);
    if (x < 0.0f || x > static_cast<float>(sbW) || y < static_cast<float>(kToolbarHeight)) {
        return false;
    }
    int relY = static_cast<int>(y) - (kToolbarHeight + 60);
    if (relY >= 0) {
        size_t idx = static_cast<size_t>(relY / 64);
        if (idx < m_outlineNodes.size()) {
            const auto& node = m_outlineNodes[idx];
            m_activeNodeId   = node.nodeId;
            m_activeTitle    = node.title;
            m_sidebarOpen    = false;
            renderFullScreen(/*fullFlash=*/false);
        }
    }
    return true;
}

void KindleScribeApp::handleInputEvent(const ScribeInputEvent& ev) {
    if (m_sessionHandle <= 0 || ev.action == ScribeEventAction::HOVER) return;

    // Top bar & Sidebar interactions respond to both Pen and Finger taps
    if (ev.action == ScribeEventAction::DOWN) {
        if (handleToolbarTap(ev.x, ev.y)) return;
        if (handleSidebarTap(ev.x, ev.y)) return;
    }

    // If stylus-only canvas mode is enabled, ignore finger touches on the drawing surface
    if (ev.tool == ScribeToolType::FINGER && m_input.stylusOnlyCanvas()) {
        return;
    }

    const float canvasX = ev.x;
    const float canvasY = ev.y - static_cast<float>(kToolbarHeight);
    if (canvasY < 0.0f && !m_strokeActive) return;

    const bool useEraser = m_eraserMode || (ev.tool == ScribeToolType::ERASER) || ev.barrelButton;

    if (useEraser) {
        float dirty4[4] = {0, 0, 0, 0};
        if (ev.action == ScribeEventAction::DOWN) {
            m_prevX = canvasX;
            m_prevY = canvasY;
            if (wn_session_erase_at(m_sessionHandle, canvasX, canvasY, 18.0f, dirty4)) {
                refreshCanvasDirtyRect(dirty4[0], dirty4[1], dirty4[2], dirty4[3], EinkWaveform::DU);
            }
        } else if (ev.action == ScribeEventAction::MOVE) {
            if (wn_session_erase_segment(m_sessionHandle, m_prevX, m_prevY, canvasX, canvasY, 18.0f, dirty4)) {
                refreshCanvasDirtyRect(dirty4[0], dirty4[1], dirty4[2], dirty4[3], EinkWaveform::DU);
            }
            m_prevX = canvasX;
            m_prevY = canvasY;
        } else if (ev.action == ScribeEventAction::UP) {
            renderFullScreen(/*fullFlash=*/false);
            commitCurrentSketch();
        }
        return;
    }

    // Active Pen Stroke with Fast DU Waveform Trace + Pen-Up GC16_FAST Grayscale Settle
    if (ev.action == ScribeEventAction::DOWN) {
        m_strokeActive = true;
        m_prevX        = canvasX;
        m_prevY        = canvasY;
        m_strokeMinX   = canvasX;
        m_strokeMinY   = canvasY;
        m_strokeMaxX   = canvasX;
        m_strokeMaxY   = canvasY;
        wn_session_begin_stroke(
            m_sessionHandle,
            canvasX,
            canvasY,
            ev.pressure > 0.0f ? ev.pressure : 0.5f,
            ev.tiltX,
            ev.tiltY,
            ev.timestampMs
        );
    } else if (ev.action == ScribeEventAction::MOVE && m_strokeActive) {
        wn_session_move_stroke(
            m_sessionHandle,
            canvasX,
            canvasY,
            ev.pressure > 0.0f ? ev.pressure : 0.5f,
            ev.tiltX,
            ev.tiltY,
            ev.timestampMs
        );
        float segLeft   = std::min(m_prevX, canvasX);
        float segTop    = std::min(m_prevY, canvasY);
        float segRight  = std::max(m_prevX, canvasX);
        float segBottom = std::max(m_prevY, canvasY);

        m_strokeMinX = std::min(m_strokeMinX, segLeft);
        m_strokeMinY = std::min(m_strokeMinY, segTop);
        m_strokeMaxX = std::max(m_strokeMaxX, segRight);
        m_strokeMaxY = std::max(m_strokeMaxY, segBottom);

        m_prevX = canvasX;
        m_prevY = canvasY;

        refreshCanvasDirtyRect(segLeft, segTop, segRight, segBottom, EinkWaveform::DU);
    } else if (ev.action == ScribeEventAction::UP && m_strokeActive) {
        m_strokeActive = false;
        int dimsAndDirty6[6] = {0};
        wn_session_end_stroke(m_sessionHandle, dimsAndDirty6);

        // Settle the completed stroke with 16-level grayscale anti-aliasing (GC16_FAST)
        refreshCanvasDirtyRect(
            m_strokeMinX,
            m_strokeMinY,
            m_strokeMaxX,
            m_strokeMaxY,
            EinkWaveform::GC16_FAST
        );
        commitCurrentSketch();
    }
}

std::vector<uint8_t> KindleScribeApp::exportSvgzBytes() const {
    if (m_sessionHandle <= 0) return {};
    std::vector<uint8_t> buf(2 * 1024 * 1024);
    int written = wn_session_export_svgz(m_sessionHandle, buf.data(), static_cast<int>(buf.size()));
    if (written <= 0) return {};
    buf.resize(static_cast<size_t>(written));
    return buf;
}

bool KindleScribeApp::loadSketchFile(const std::string& svgzPath) {
    if (m_sessionHandle <= 0) return false;
    std::ifstream ifs(svgzPath, std::ios::in | std::ios::binary | std::ios::ate);
    if (!ifs) return false;
    std::streamsize sz = ifs.tellg();
    if (sz <= 0) return false;
    ifs.seekg(0, std::ios::beg);
    std::vector<uint8_t> bytes(static_cast<size_t>(sz));
    if (!ifs.read(reinterpret_cast<char*>(bytes.data()), sz)) return false;

    m_localSavePath = svgzPath;
    if (!wn_session_load_svgz(m_sessionHandle, bytes.data(), static_cast<int>(bytes.size()))) {
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

bool KindleScribeApp::commitCurrentSketch() {
    auto svgz = exportSvgzBytes();
    if (svgz.empty()) return false;

    if (!m_localSavePath.empty()) {
        saveSketchFile(m_localSavePath);
    }

    InkCommitPayload payload;
    payload.nodeId     = m_activeNodeId;
    payload.fileName   = m_activeFileName;
    payload.width      = m_fb.width();
    payload.height     = std::max(64, m_fb.height() - kToolbarHeight);
    payload.baseEtag   = m_activeEtag;
    payload.svgzBase64 = base64Encode(svgz.data(), svgz.size());
    payload.ocrText    = m_activeTitle;

    return m_sync.commitOrEnqueueInk(payload);
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

