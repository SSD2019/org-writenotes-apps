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
    m_pageWidth  = 1200;
    m_pageHeight = 928;
    m_canvasRgba.assign(static_cast<size_t>(canvasW) * canvasH * 4, 0xFF);

    if (m_sessionHandle > 0) {
        wn_session_destroy(m_sessionHandle);
    }
    m_sessionHandle = wn_session_create(m_pageWidth, m_pageHeight, 0, 48);
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

    renderFullScreen(/*fullFlash=*/true);
    return true;
}

float KindleScribeApp::computeViewScale() const {
    const int canvasW = std::max(64, m_fb.width());
    const int pageW   = std::max(64, m_pageWidth);
    return static_cast<float>(canvasW) / static_cast<float>(pageW);
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

void KindleScribeApp::renderTopToolbar() {
    const int w = m_fb.width();
    m_fb.fillRect(0, 0, w, kToolbarHeight, 0xF0);
    m_fb.drawRectBorder(0, 0, w, kToolbarHeight, 2, 0x00);

    // 7 evenly spaced E-Ink action buttons across the top bar (y = 2..48)
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
    const int btnH     = 46;
    for (int i = 0; i < btnCount; ++i) {
        int bx = i * btnW;
        bool active = (i == 0 && m_sidebarOpen) || (i == 1 && m_eraserMode);
        uint8_t bg  = active ? 0x20 : 0xF0;
        uint8_t fg  = active ? 0xFF : 0x00;
        m_fb.fillRect(bx + 2, 2, btnW - 4, btnH, bg);
        m_fb.drawRectBorder(bx + 2, 2, btnW - 4, btnH, 2, 0x00);
        m_fb.drawText(bx + 12, 2 + (btnH - 16) / 2, labels[static_cast<size_t>(i)], fg, bg, 2);
    }

    // Status bar strip (y = 50..74): displays active Org heading title & sketch filename
    m_fb.fillRect(2, 50, w - 4, kToolbarHeight - 52, 0xE4);
    std::string statusLine = "Node: " + m_activeTitle;
    if (!m_activeFileName.empty()) {
        statusLine += "  [" + m_activeFileName + " " +
                      std::to_string(m_pageWidth) + "x" + std::to_string(m_pageHeight) + "]";
    } else {
        statusLine += "  [New Sketch]";
    }
    const size_t maxChars = static_cast<size_t>(std::max(16, (w - 24) / 16));
    if (statusLine.size() > maxChars) {
        statusLine.resize(maxChars);
    }
    m_fb.drawText(12, 54, statusLine, 0x00, 0xE4, 2);
}

void KindleScribeApp::renderFullScreen(bool fullFlash) {
    if (m_sessionHandle <= 0) return;

    syncPageInfoFromEngine();
    const int canvasW = m_fb.width();
    const int canvasH = std::max(64, m_fb.height() - kToolbarHeight);
    const float scale = computeViewScale();
    const int renderW = canvasW;
    const int renderH = std::max(64, static_cast<int>(std::lround(static_cast<float>(m_pageHeight) * scale)));

    const size_t neededBytes = static_cast<size_t>(renderW) * std::max(canvasH, renderH) * 4;
    if (m_canvasRgba.size() < neededBytes) {
        m_canvasRgba.assign(neededBytes, 0xFF);
    }

    renderTopToolbar();

    // Fill full canvas area with clean paper white first
    m_fb.fillRect(0, kToolbarHeight, canvasW, canvasH, 0xFF);

    if (wn_session_render_rgba(m_sessionHandle, m_canvasRgba.data(), renderW, renderH, 0.0f, 0.0f)) {
        const int blitH = std::min(canvasH, renderH);
        m_fb.blitRgbaRegion(
            m_canvasRgba.data(),
            renderW,
            renderH,
            0,
            0,
            0,
            kToolbarHeight,
            renderW,
            blitH,
            /*dither1Bit=*/false
        );
        if (renderH < canvasH) {
            m_fb.drawHLine(0, kToolbarHeight + renderH, canvasW, 0xA0);
        }
    }

    if (m_sidebarOpen) {
        const int sbW = std::min(kSidebarWidth, canvasW / 2);
        m_fb.fillRect(0, kToolbarHeight, sbW, canvasH, 0xFA);
        m_fb.drawRectBorder(0, kToolbarHeight, sbW, canvasH, 3, 0x00);
        m_fb.drawText(16, kToolbarHeight + 16, "ORG-WRITENOTES OUTLINE", 0x00, 0xFA, 2);
        m_fb.drawHLine(12, kToolbarHeight + 44, sbW - 24, 0x00);

        int itemY = kToolbarHeight + 60;
        const size_t maxVisible = static_cast<size_t>(std::max(1, (canvasH - 70) / 64));
        for (size_t i = 0; i < m_outlineNodes.size() && i < maxVisible; ++i) {
            const auto& node = m_outlineNodes[i];
            bool selected = (node.nodeId == m_activeNodeId);
            uint8_t bg = selected ? 0x30 : 0xFA;
            uint8_t fg = selected ? 0xFF : 0x00;
            m_fb.fillRect(8, itemY, sbW - 16, 56, bg);
            m_fb.drawRectBorder(8, itemY, sbW - 16, 56, 1, 0x40);
            std::string indent(static_cast<size_t>(std::max(0, node.level - 1)) * 2, ' ');
            std::string line = indent + (node.todoKeyword.empty() ? "* " : (node.todoKeyword + " ")) + node.title;
            if (line.size() > 28) line.resize(28);
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
    const float scale = computeViewScale();
    const int renderW = canvasW;
    const int renderH = std::max(64, static_cast<int>(std::lround(static_cast<float>(m_pageHeight) * scale)));

    const size_t neededBytes = static_cast<size_t>(renderW) * std::max(canvasH, renderH) * 4;
    if (m_canvasRgba.size() < neededBytes) {
        m_canvasRgba.assign(neededBytes, 0xFF);
    }

    const int maxScreenY = std::min(canvasH, renderH);
    int x0 = std::clamp(static_cast<int>(std::floor(left)) - 12, 0, canvasW);
    int y0 = std::clamp(static_cast<int>(std::floor(top)) - 12, 0, maxScreenY);
    int x1 = std::clamp(static_cast<int>(std::ceil(right)) + 12, 0, canvasW);
    int y1 = std::clamp(static_cast<int>(std::ceil(bottom)) + 12, 0, maxScreenY);
    if (x1 <= x0 || y1 <= y0) return;

    const float pageLeft   = static_cast<float>(x0) / scale;
    const float pageTop    = static_cast<float>(y0) / scale;
    const float pageRight  = static_cast<float>(x1) / scale;
    const float pageBottom = static_cast<float>(y1) / scale;

    if (wn_session_render_dirty_rgba(
            m_sessionHandle,
            m_canvasRgba.data(),
            renderW,
            renderH,
            pageLeft,
            pageTop,
            pageRight,
            pageBottom
        )) {
        const bool dither1Bit = (wf == EinkWaveform::DU || wf == EinkWaveform::A2);
        m_fb.blitRgbaRegion(
            m_canvasRgba.data(),
            renderW,
            renderH,
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
        case 0: // Toggle Outline Drawer (refresh outline list from server when opening)
            m_sidebarOpen = !m_sidebarOpen;
            if (m_sidebarOpen && m_sync.isConnected()) {
                std::vector<OrgNodeSummary> remoteNodes;
                if (m_sync.fetchNodeList("buffer_outline", &remoteNodes) && !remoteNodes.empty()) {
                    m_outlineNodes = std::move(remoteNodes);
                } else if (m_sync.fetchNodeList("pushed_history", &remoteNodes) && !remoteNodes.empty()) {
                    m_outlineNodes = std::move(remoteNodes);
                }
            }
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
                m_pageWidth,
                m_pageHeight,
                48,
                48,
                0,
                static_cast<int>(0xFFFFFFFF),
                static_cast<int>(0xFF888888),
                m_rulingStyle,
                dims4
            );
            m_commitPending = true;
            renderFullScreen(/*fullFlash=*/false);
            break;
        case 3: // Undo
            if (wn_session_undo(m_sessionHandle, dims2)) {
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 4: // Redo
            if (wn_session_redo(m_sessionHandle, dims2)) {
                m_commitPending = true;
                renderFullScreen(/*fullFlash=*/false);
            }
            break;
        case 5: // Sync / Commit now + Refresh active node from Emacs + Full E-Ink Refresh
            flushPendingCommit();
            syncWithServer(/*forceReconnect=*/!m_sync.isConnected());
            renderFullScreen(/*fullFlash=*/true);
            break;
        case 6: // Exit to Kindle UI
            flushPendingCommit();
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
            const auto selectedNode = m_outlineNodes[idx];
            flushPendingCommit();
            m_sidebarOpen = false;
            if (!fetchAndApplyRemoteNode(selectedNode.nodeId)) {
                m_activeNodeId = selectedNode.nodeId;
                m_activeTitle  = selectedNode.title;
            }
            renderFullScreen(/*fullFlash=*/false);
        }
    }
    return true;
}

void KindleScribeApp::flushPendingDuRefresh(double timestampMs) {
    if (!m_hasPendingDu) return;
    m_hasPendingDu = false;
    m_lastDuRefreshMs = timestampMs;

    const int canvasW = m_fb.width();
    const int canvasH = std::max(64, m_fb.height() - kToolbarHeight);
    int x0 = std::clamp(static_cast<int>(std::floor(m_pendingDuMinX)) - 6, 0, canvasW);
    int y0 = std::clamp(static_cast<int>(std::floor(m_pendingDuMinY)) - 6, 0, canvasH);
    int x1 = std::clamp(static_cast<int>(std::ceil(m_pendingDuMaxX)) + 6, 0, canvasW);
    int y1 = std::clamp(static_cast<int>(std::ceil(m_pendingDuMaxY)) + 6, 0, canvasH);
    if (x1 > x0 && y1 > y0) {
        m_fb.refreshRect(
            EinkRect{ x0, y0 + kToolbarHeight, x1 - x0, y1 - y0 },
            EinkWaveform::DU,
            /*fullFlash=*/false
        );
    }
}

void KindleScribeApp::handleInputEvent(const ScribeInputEvent& ev) {
    if (m_sessionHandle <= 0 || ev.action == ScribeEventAction::HOVER) return;

    // Top bar & Sidebar interactions respond to both Pen and Finger taps
    if (ev.action == ScribeEventAction::DOWN && !m_strokeActive) {
        if (handleToolbarTap(ev.x, ev.y)) return;
        if (handleSidebarTap(ev.x, ev.y)) return;
    }

    // If stylus-only canvas mode is enabled, ignore finger touches on the drawing surface
    if (ev.tool == ScribeToolType::FINGER && m_input.stylusOnlyCanvas()) {
        return;
    }

    const float canvasX = ev.x;
    const float canvasY = std::max(0.0f, ev.y - static_cast<float>(kToolbarHeight));
    if (ev.y < static_cast<float>(kToolbarHeight) && !m_strokeActive) return;

    const float scale = computeViewScale();
    const float pageX = canvasX / scale;
    const float pageY = canvasY / scale;

    const bool useEraser = m_eraserMode || (ev.tool == ScribeToolType::ERASER) || ev.barrelButton;

    if (useEraser) {
        float dirtyPage4[4] = {0, 0, 0, 0};
        const float eraseRadiusPage = 18.0f / scale;
        if (ev.action == ScribeEventAction::DOWN) {
            m_prevX = canvasX;
            m_prevY = canvasY;
            if (wn_session_erase_at(m_sessionHandle, pageX, pageY, eraseRadiusPage, dirtyPage4)) {
                refreshCanvasDirtyRect(
                    dirtyPage4[0] * scale,
                    dirtyPage4[1] * scale,
                    dirtyPage4[2] * scale,
                    dirtyPage4[3] * scale,
                    EinkWaveform::DU
                );
            }
        } else if (ev.action == ScribeEventAction::MOVE) {
            if (wn_session_erase_segment(
                    m_sessionHandle,
                    m_prevX / scale,
                    m_prevY / scale,
                    pageX,
                    pageY,
                    eraseRadiusPage,
                    dirtyPage4
                )) {
                refreshCanvasDirtyRect(
                    dirtyPage4[0] * scale,
                    dirtyPage4[1] * scale,
                    dirtyPage4[2] * scale,
                    dirtyPage4[3] * scale,
                    EinkWaveform::DU
                );
            }
            m_prevX = canvasX;
            m_prevY = canvasY;
        } else if (ev.action == ScribeEventAction::UP) {
            renderFullScreen(/*fullFlash=*/false);
            m_commitPending = true;
            if (!m_deferAutoCommit) {
                flushPendingCommit();
            }
        }
        return;
    }

    // Active Pen Stroke with Fast Direct DU Trace + Pen-Up GC16_FAST Grayscale Settle
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
            m_sessionHandle,
            pageX,
            pageY,
            press,
            ev.tiltX,
            ev.tiltY,
            ev.timestampMs
        );
        float screenRadius = std::max(1.0f, (m_penWidth * scale) * (0.35f + 0.65f * press) * 0.5f);
        m_fb.drawThickLine(
            canvasX,
            canvasY + static_cast<float>(kToolbarHeight),
            canvasX,
            canvasY + static_cast<float>(kToolbarHeight),
            screenRadius,
            0x00
        );
    } else if (ev.action == ScribeEventAction::MOVE && m_strokeActive) {
        float press = ev.pressure > 0.0f ? ev.pressure : 0.5f;
        wn_session_move_stroke(
            m_sessionHandle,
            pageX,
            pageY,
            press,
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

        float screenRadius = std::max(1.0f, (m_penWidth * scale) * (0.35f + 0.65f * press) * 0.5f);
        m_fb.drawThickLine(
            m_prevX,
            m_prevY + static_cast<float>(kToolbarHeight),
            canvasX,
            canvasY + static_cast<float>(kToolbarHeight),
            screenRadius,
            0x00
        );

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
        int dimsAndDirty6[6] = {0};
        wn_session_end_stroke(m_sessionHandle, dimsAndDirty6);

        bool pageExpanded = (dimsAndDirty6[0] > 0 && dimsAndDirty6[1] > 0) &&
                            (dimsAndDirty6[0] != m_pageWidth || dimsAndDirty6[1] != m_pageHeight);
        if (dimsAndDirty6[0] > 0) m_pageWidth  = dimsAndDirty6[0];
        if (dimsAndDirty6[1] > 0) m_pageHeight = dimsAndDirty6[1];

        if (pageExpanded) {
            renderFullScreen(/*fullFlash=*/false);
        } else {
            // Settle the completed stroke with 16-level grayscale anti-aliasing (GC16_FAST)
            refreshCanvasDirtyRect(
                m_strokeMinX,
                m_strokeMinY,
                m_strokeMaxX,
                m_strokeMaxY,
                EinkWaveform::GC16_FAST
            );
        }
        m_commitPending = true;
        if (!m_deferAutoCommit) {
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

bool KindleScribeApp::applyNodeSnapshot(
    const OrgNodeSnapshotData& snap,
    const std::string& preferredFileName
) {
    if (snap.nodeId.empty()) return false;

    m_activeNodeId = snap.nodeId;
    m_activeTitle  = snap.title.empty() ? "Untitled" : snap.title;

    // Prepend or update this node in m_outlineNodes
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
    if (!preferredFileName.empty()) {
        for (const auto& b : snap.inkBlocks) {
            if (b.fileName == preferredFileName) {
                chosen = &b;
                break;
            }
        }
    }
    if (!chosen && !m_activeFileName.empty()) {
        for (const auto& b : snap.inkBlocks) {
            if (b.fileName == m_activeFileName) {
                chosen = &b;
                break;
            }
        }
    }
    if (!chosen && !snap.inkBlocks.empty()) {
        chosen = &snap.inkBlocks.front();
    }

    if (chosen) {
        m_activeFileName = chosen->fileName;
        if (!chosen->etag.empty()) {
            m_sync.setKnownEtagForFile(chosen->fileName, chosen->etag);
        }
        m_activeEtag     = m_sync.knownEtagForFile(chosen->fileName);
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
                m_sessionHandle,
                m_pageWidth,
                m_pageHeight,
                chosen->xRuling,
                chosen->yRuling,
                0,
                static_cast<int>(0xFFFFFFFF),
                static_cast<int>(0xFF888888),
                m_rulingStyle,
                dims4
            );
        }
    } else {
        // Node has no ink_canvas block yet; prepare a fresh 1200x928 page
        m_activeFileName.clear();
        m_activeEtag.clear();
        m_pageWidth  = 1200;
        m_pageHeight = 928;
        if (m_sessionHandle > 0) {
            wn_session_destroy(m_sessionHandle);
        }
        m_sessionHandle = wn_session_create(m_pageWidth, m_pageHeight, 0, 48);
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
    }

    m_commitPending = false;
    return true;
}

bool KindleScribeApp::fetchAndApplyRemoteNode(
    const std::string& nodeId,
    const std::string& preferredFileName
) {
    if (!m_sync.isConnected()) return false;
    OrgNodeSnapshotData snap;
    if (!m_sync.fetchNodeSnapshot(nodeId, &snap)) {
        return false;
    }
    return applyNodeSnapshot(snap, preferredFileName);
}

bool KindleScribeApp::pollServerNotifications() {
    if (!m_sync.isConnected() || m_strokeActive) return false;
    bool updated = false;
    std::string method;
    std::string paramsJson;
    while (m_sync.pollServerNotification(&method, &paramsJson, 0)) {
        if (method == "emacs.focusNode") {
            flushPendingCommit();
            OrgNodeSnapshotData snap;
            if (parseNodeSnapshotJson(paramsJson, &snap)) {
                if (applyNodeSnapshot(snap)) {
                    updated = true;
                }
            }
        } else if (method == "emacs.zoomInkBlock") {
            flushPendingCommit();
            std::string fileName = extractJsonStringField(paramsJson, "fileName");
            std::string snapObj  = extractJsonObjectField(paramsJson, "snapshot");
            OrgNodeSnapshotData snap;
            if (!snapObj.empty() && parseNodeSnapshotJson(snapObj, &snap)) {
                if (applyNodeSnapshot(snap, fileName)) {
                    updated = true;
                }
            } else {
                std::string nid = extractJsonStringField(paramsJson, "nodeId");
                if (fetchAndApplyRemoteNode(nid, fileName)) {
                    updated = true;
                }
            }
        } else if (method == "emacs.nodeModified") {
            if (!m_commitPending) {
                OrgNodeSnapshotData snap;
                if (parseNodeSnapshotJson(paramsJson, &snap) && snap.nodeId == m_activeNodeId) {
                    if (applyNodeSnapshot(snap, m_activeFileName)) {
                        updated = true;
                    }
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
    if (forceReconnect || !m_sync.isConnected()) {
        if (!m_sync.connectAndHandshake()) {
            return false;
        }
    }

    // Drain any immediate server notifications (such as emacs.focusNode sent on handshake)
    std::string method;
    std::string paramsJson;
    bool loadedFromPush = false;
    while (m_sync.pollServerNotification(&method, &paramsJson, 80)) {
        if (method == "emacs.focusNode") {
            OrgNodeSnapshotData snap;
            if (parseNodeSnapshotJson(paramsJson, &snap)) {
                if (applyNodeSnapshot(snap)) {
                    loadedFromPush = true;
                }
            }
        } else if (method == "emacs.zoomInkBlock") {
            std::string fileName = extractJsonStringField(paramsJson, "fileName");
            std::string snapObj  = extractJsonObjectField(paramsJson, "snapshot");
            OrgNodeSnapshotData snap;
            if (!snapObj.empty() && parseNodeSnapshotJson(snapObj, &snap)) {
                if (applyNodeSnapshot(snap, fileName)) {
                    loadedFromPush = true;
                }
            }
        }
    }

    // Fetch outline nodes from server
    std::vector<OrgNodeSummary> remoteNodes;
    if (m_sync.fetchNodeList("pushed_history", &remoteNodes) && !remoteNodes.empty()) {
        m_outlineNodes = std::move(remoteNodes);
    }
    std::vector<OrgNodeSummary> bufferNodes;
    if (m_sync.fetchNodeList("buffer_outline", &bufferNodes) && !bufferNodes.empty()) {
        for (const auto& bn : bufferNodes) {
            bool exists = false;
            for (const auto& en : m_outlineNodes) {
                if (en.nodeId == bn.nodeId) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                m_outlineNodes.push_back(bn);
            }
        }
    }

    if (!loadedFromPush) {
        std::string targetId = m_sync.serverActiveNodeId();
        if (targetId.empty() &&
            !m_activeNodeId.empty() &&
            m_activeNodeId != "file:inbox.org::#scribe-quick-note") {
            targetId = m_activeNodeId;
        }
        if (targetId.empty() && !m_outlineNodes.empty()) {
            targetId = m_outlineNodes.front().nodeId;
        }
        if (!targetId.empty()) {
            fetchAndApplyRemoteNode(targetId);
        }
    }

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

    // If connected to Emacs and the current Org node does not have an ink_canvas block yet,
    // create and link one via node.insertInkBlock before committing the .svgz bytes.
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

    // Do not push fake placeholder node commits to a live Emacs server
    if (m_sync.isConnected() && m_activeNodeId == "file:inbox.org::#scribe-quick-note") {
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
    return ok;
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

