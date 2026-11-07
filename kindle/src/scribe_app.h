#ifndef KINDLE_SCRIBE_APP_H
#define KINDLE_SCRIBE_APP_H

#include "eink_fb.h"
#include "scribe_input.h"
#include "ws_protocol.h"

#include <cstdint>
#include <string>
#include <vector>

namespace orgwritenotes::kindle {

class KindleScribeApp {
public:
    static constexpr int kToolbarHeight = 76;
    static constexpr int kSidebarWidth  = 520;

    KindleScribeApp(
        const char* fbPath = "/dev/fb0",
        const std::string& queueDir = "/mnt/us/org-writenotes/queue",
        int fallbackWidth = 1860,
        int fallbackHeight = 2480
    );
    ~KindleScribeApp();

    KindleScribeApp(const KindleScribeApp&) = delete;
    KindleScribeApp& operator=(const KindleScribeApp&) = delete;

    bool initialize();

    // Renders the full E-Ink screen (top toolbar, optional Outline sidebar, and Write canvas)
    void renderFullScreen(bool fullFlash = true);
    void renderTopToolbar();

    // Handles a single Wacom or Touch input event
    void handleInputEvent(const ScribeInputEvent& ev);

    // Loads or exports the active sketch (.svgz)
    bool loadSketchFile(const std::string& svgzPath);
    bool loadSvgzBytes(const std::vector<uint8_t>& bytes);
    bool saveSketchFile(const std::string& svgzPath) const;
    std::vector<uint8_t> exportSvgzBytes() const;
    bool commitCurrentSketch();
    bool flushPendingCommit();

    // Synchronizes outline nodes and active node sketch with the Emacs server
    bool syncWithServer(bool forceReconnect = false);
    bool fetchAndApplyRemoteNode(const std::string& nodeId, const std::string& preferredFileName = "");
    bool applyNodeSnapshot(const OrgNodeSnapshotData& snap, const std::string& preferredFileName = "");
    bool pollServerNotifications();

    void setDeferAutoCommit(bool defer) { m_deferAutoCommit = defer; }
    bool deferAutoCommit() const { return m_deferAutoCommit; }
    bool hasPendingCommit() const { return m_commitPending; }
    bool isStrokeActive() const { return m_strokeActive; }

    void setActiveNode(const std::string& nodeId, const std::string& title, const std::string& sketchFileName);
    void setOutlineNodes(const std::vector<OrgNodeSummary>& nodes);

    bool shouldExit() const { return m_shouldExit; }
    bool isSidebarOpen() const { return m_sidebarOpen; }
    bool isEraserMode() const { return m_eraserMode; }
    int rulingStyle() const { return m_rulingStyle; }
    int strokeCount() const;
    int pageWidth() const { return m_pageWidth; }
    int pageHeight() const { return m_pageHeight; }

    EinkFramebuffer& framebuffer() { return m_fb; }
    ScribeInputManager& input() { return m_input; }
    WriteNotesSyncClient& syncClient() { return m_sync; }
    int64_t engineHandle() const { return m_sessionHandle; }

private:
    bool handleToolbarTap(float x, float y);
    bool handleSidebarTap(float x, float y);
    float computeViewScale() const;
    void syncPageInfoFromEngine();
    void refreshCanvasDirtyRect(float left, float top, float right, float bottom, EinkWaveform wf);
    void flushPendingDuRefresh(double timestampMs);

    std::string m_fbPath;
    int m_fallbackWidth   = 1860;
    int m_fallbackHeight  = 2480;

    EinkFramebuffer m_fb;
    ScribeInputManager m_input;
    WriteNotesSyncClient m_sync;

    int64_t m_sessionHandle = 0;
    int m_pageWidth         = 1200;
    int m_pageHeight        = 928;
    std::vector<uint8_t> m_canvasRgba;

    std::string m_activeNodeId   = "file:inbox.org::#scribe-quick-note";
    std::string m_activeTitle    = "Kindle Scribe Quick Note";
    std::string m_activeFileName = "scribe-note.svgz";
    std::string m_activeEtag;
    std::string m_localSavePath;

    std::vector<OrgNodeSummary> m_outlineNodes;

    bool m_sidebarOpen     = false;
    bool m_eraserMode      = false;
    bool m_strokeActive    = false;
    bool m_shouldExit      = false;
    bool m_deferAutoCommit = false;
    bool m_commitPending   = false;
    bool m_hasPendingDu    = false;
    int m_rulingStyle      = 0; // 0=standard, 1=dotted, 2=engineer, 5=cornell
    float m_penWidth       = 2.6f;
    float m_prevX          = 0.0f;
    float m_prevY          = 0.0f;
    float m_strokeMinX     = 0.0f;
    float m_strokeMinY     = 0.0f;
    float m_strokeMaxX     = 0.0f;
    float m_strokeMaxY     = 0.0f;
    float m_pendingDuMinX  = 0.0f;
    float m_pendingDuMinY  = 0.0f;
    float m_pendingDuMaxX  = 0.0f;
    float m_pendingDuMaxY  = 0.0f;
    double m_lastDuRefreshMs = 0.0;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_SCRIBE_APP_H

