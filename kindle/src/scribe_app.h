#ifndef KINDLE_SCRIBE_APP_H
#define KINDLE_SCRIBE_APP_H

#include "eink_fb.h"
#include "scribe_input.h"
#include "ws_protocol.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace orgwritenotes::kindle {

enum class ActiveTool : int {
    PEN    = 0,
    ERASER = 1,
    SELECT = 2,
    SPACE  = 3
};

enum class EraserMode : int {
    STROKE  = 0,
    SEGMENT = 1,
    RULED   = 2
};

enum class SelectMode : int {
    RECT  = 0,
    LASSO = 1,
    RULED = 2
};

enum class SpaceMode : int {
    VERTICAL   = 0,
    HORIZONTAL = 1,
    RULED      = 2
};

enum class PopoverKind : int {
    NONE  = 0,
    TOOLS = 1,
    PAGE  = 2,
    ORG   = 3
};

enum class SidebarTab : int {
    HISTORY = 0,
    OUTLINE = 1,
    AGENDA  = 2,
    GALLERY = 3
};

class KindleScribeApp {
public:
    static constexpr int kPillX          = 12;
    static constexpr int kPillY          = 10;
    static constexpr int kPillWidth      = 420;
    static constexpr int kPillHeight     = 44;
    static constexpr int kStripY         = 60;
    static constexpr int kStripHeight    = 46;
    static constexpr int kPopoverY       = 112;
    static constexpr int kSidebarWidth   = 560;

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

    // Full or partial E-Ink rendering using cached m_canvasRgba when possible
    void renderFullScreen(bool fullFlash = true);
    void renderOverlayOnly(EinkWaveform wf = EinkWaveform::A2);
    // Test hook: override the cached connection/conflict flags used by the status pill
    void setCachedStatusForTest(bool connected, bool hasConflicts) {
        m_cachedConnected.store(connected);
        m_cachedHasConflicts.store(hasConflicts);
    }
    void flushPendingStrokeSettle(bool force = false, double nowMs = 0.0);

    // Handles a single Wacom or Touch input event
    void handleInputEvent(const ScribeInputEvent& ev);

    // Loads or exports the active sketch (.svgz)
    bool loadSketchFile(const std::string& svgzPath);
    bool loadSvgzBytes(const std::vector<uint8_t>& bytes);
    bool saveSketchFile(const std::string& svgzPath) const;
    std::vector<uint8_t> exportSvgzBytes() const;
    bool commitCurrentSketch();
    bool flushPendingCommit();

    // Synchronous sync helpers (also used by unit tests)
    bool syncWithServer(bool forceReconnect = false);
    bool fetchAndApplyRemoteNode(const std::string& nodeId, const std::string& preferredFileName = "");
    bool applyNodeSnapshot(const OrgNodeSnapshotData& snap, const std::string& preferredFileName = "");
    bool pollServerNotifications();

    // Background async sync worker (keeps main evdev pen loop non-blocking)
    void startAsyncSyncWorker();
    void stopAsyncSyncWorker();
    void queueAsyncCommitCurrent();
    void queueAsyncSync(bool forceReconnect = false);
    void queueAsyncFetchNodeList(SidebarTab tab);
    void queueAsyncFetchNode(const std::string& nodeId, const std::string& preferredFileName = "");
    void queueAsyncInsertInkBlock();
    void queueAsyncCycleTodo();
    void queueAsyncFlagFlashcard();
    void queueAsyncPollNotifications();
    bool processAsyncSyncResults();

    void setDeferAutoCommit(bool defer) { m_deferAutoCommit = defer; }
    bool deferAutoCommit() const { return m_deferAutoCommit; }
    bool hasPendingCommit() const { return m_commitPending; }
    bool hasPendingStrokeSettle() const { return m_hasPendingSettle; }
    bool isStrokeActive() const { return m_strokeActive; }

    void setActiveNode(const std::string& nodeId, const std::string& title, const std::string& sketchFileName);
    void setOutlineNodes(const std::vector<OrgNodeSummary>& nodes);
    bool switchSketchIndex(int delta);

    bool shouldExit() const { return m_shouldExit; }
    bool isSidebarOpen() const { return m_sidebarOpen; }
    bool isToolbarExpanded() const { return m_toolbarExpanded; }
    PopoverKind activePopover() const { return m_popover; }
    SidebarTab activeSidebarTab() const { return m_sidebarTab; }
    ActiveTool activeTool() const { return m_activeTool; }
    bool isEraserMode() const { return m_activeTool == ActiveTool::ERASER; }
    int rulingStyle() const { return m_rulingStyle; }
    float viewZoom() const { return m_viewZoom; }
    bool isCanvasDirty() const { return m_canvasDirty; }
    int strokeCount() const;
    int pageWidth() const { return m_pageWidth; }
    int pageHeight() const { return m_pageHeight; }

    EinkFramebuffer& framebuffer() { return m_fb; }
    ScribeInputManager& input() { return m_input; }
    WriteNotesSyncClient& syncClient() { return m_sync; }
    int64_t engineHandle() const { return m_sessionHandle; }

private:
    enum class AsyncOpType {
        COMMIT_PAYLOAD,
        SYNC_FULL,
        FETCH_NODE_LIST,
        FETCH_NODE,
        INSERT_INK_BLOCK,
        UPDATE_HEADER,
        FLAG_FLASHCARD,
        POLL_NOTIFICATIONS
    };

    struct AsyncTask {
        AsyncOpType type;
        bool boolParam = false;
        SidebarTab tab = SidebarTab::OUTLINE;
        std::string str1;
        std::string str2;
        std::string str3;
        int int1 = 0;
        int int2 = 0;
        InkCommitPayload commitPayload;
    };

    struct AsyncResult {
        AsyncOpType type;
        bool ok = false;
        SidebarTab tab = SidebarTab::OUTLINE;
        std::string str1;
        std::string str2;
        std::string etag;
        std::vector<OrgNodeSummary> nodes;
        OrgNodeSnapshotData snapshot;
        bool hasSnapshot = false;
    };

    void enqueueAsyncTask(AsyncTask task);
    void asyncWorkerLoop();
    void refreshCachedSyncStatus();

    // UI & Hit-testing
    EinkRect currentOverlayBounds() const;
    bool hitTestOverlay(float x, float y) const;
    bool handlePillTap(float x, float y);
    bool handleExpandedStripTap(float x, float y);
    bool handlePopoverTap(float x, float y);
    bool handleSelectionBarTap(float x, float y);
    bool handleSidebarTap(float x, float y);
    void collapseOverlaysForCanvas();

    void drawPillAndOverlays();
    void drawExpandedStrip();
    void drawToolsPopover();
    void drawPagePopover();
    void drawOrgPopover();
    void drawSelectionActionBar();
    void drawSidebarOverlay();

    // Canvas rendering & coordinates
    float computeViewScale() const;
    void syncPageInfoFromEngine();
    void applyPenSettings();
    void ensureCanvasRendered();
    void blitCanvasRectToFb(int x, int y, int w, int h);
    void restoreCanvasRegionAndRefresh(const EinkRect& rect, EinkWaveform wf);
    void refreshCanvasDirtyRect(float left, float top, float right, float bottom, EinkWaveform wf);
    void flushPendingDuRefresh(double timestampMs);

    void handleEraserEvent(const ScribeInputEvent& ev, float canvasX, float canvasY, float pageX, float pageY, float scale);
    void handleSelectEvent(const ScribeInputEvent& ev, float canvasX, float canvasY, float pageX, float pageY, float scale);
    void handleSpaceEvent(const ScribeInputEvent& ev, float canvasX, float canvasY, float pageX, float pageY);
    void handlePenEvent(const ScribeInputEvent& ev, float canvasX, float canvasY, float pageX, float pageY, float scale);

    std::string m_fbPath;
    int m_fallbackWidth   = 1860;
    int m_fallbackHeight  = 2480;

    EinkFramebuffer m_fb;
    ScribeInputManager m_input;
    WriteNotesSyncClient m_sync;

    int64_t m_sessionHandle = 0;
    int m_pageWidth         = 1200;
    int m_pageHeight        = 928;
    int m_renderWidth       = 1860;
    int m_renderHeight      = 1438;
    std::vector<uint8_t> m_canvasRgba;
    bool m_canvasDirty      = true;

    // Viewport pan/zoom
    float m_viewZoom = 1.0f;
    float m_panX     = 0.0f;
    float m_panY     = 0.0f;

    // Active Org node & multi-sketch state
    std::string m_activeNodeId   = "file:inbox.org::#scribe-quick-note";
    std::string m_activeTitle    = "Kindle Scribe Quick Note";
    std::string m_activeTodo;
    std::string m_activeFileName = "scribe-note.svgz";
    std::string m_activeEtag;
    std::string m_localSavePath;
    bool m_flashcardFlagged = false;
    std::vector<InkBlockInfo> m_nodeBlocks;
    int m_activeBlockIndex = 0;

    std::vector<OrgNodeSummary> m_outlineNodes;
    std::vector<OrgNodeSummary> m_historyNodes;
    std::vector<OrgNodeSummary> m_agendaNodes;
    std::vector<OrgNodeSummary> m_galleryNodes;

    // Collapsible UI state
    bool m_toolbarExpanded = false;
    PopoverKind m_popover  = PopoverKind::NONE;
    bool m_sidebarOpen     = false;
    SidebarTab m_sidebarTab = SidebarTab::OUTLINE;
    bool m_shouldExit      = false;

    // Tool settings
    ActiveTool m_activeTool = ActiveTool::PEN;
    EraserMode m_eraserMode = EraserMode::STROKE;
    SelectMode m_selectMode = SelectMode::LASSO;
    SpaceMode  m_spaceMode  = SpaceMode::VERTICAL;
    int m_rulingStyle       = 0; // 0=standard, 1=dotted, 2=grid, 3=music, 4=cornell
    int m_penWidthIdx       = 1; // 0=fine(1.6), 1=med(2.6), 2=bold(4.5)
    int m_penColorIdx       = 0; // 0=black, 1=dark gray, 2=mid gray, 3=highlighter
    float m_penWidth        = 2.6f;
    int m_penColorArgb      = static_cast<int>(0xFF000000);
    bool m_lineDrawing      = false;
    bool m_snapToGrid       = false;
    bool m_shapeSnap        = false;

    // Active gesture / stroke state
    bool m_strokeActive     = false;
    bool m_selectDragging   = false;
    bool m_selectMoving     = false;
    bool m_spaceDragging    = false;
    float m_prevX           = 0.0f;
    float m_prevY           = 0.0f;
    float m_gestureStartX   = 0.0f;
    float m_gestureStartY   = 0.0f;
    float m_strokeMinX      = 0.0f;
    float m_strokeMinY      = 0.0f;
    float m_strokeMaxX      = 0.0f;
    float m_strokeMaxY      = 0.0f;
    std::vector<float> m_lassoPoints;

    // Deferred DU & GC16_FAST settle state
    bool m_hasPendingDu     = false;
    float m_pendingDuMinX   = 0.0f;
    float m_pendingDuMinY   = 0.0f;
    float m_pendingDuMaxX   = 0.0f;
    float m_pendingDuMaxY   = 0.0f;
    double m_lastDuRefreshMs = 0.0;

    bool m_hasPendingSettle   = false;
    float m_settleMinX        = 0.0f;
    float m_settleMinY        = 0.0f;
    float m_settleMaxX        = 0.0f;
    float m_settleMaxY        = 0.0f;
    double m_lastStrokeEndMs  = 0.0;

    // Sync & async worker state
    bool m_deferAutoCommit = false;
    bool m_commitPending   = false;
    std::atomic<bool> m_cachedConnected{false};
    std::atomic<size_t> m_cachedQueueCount{0};
    std::atomic<bool> m_cachedHasConflicts{false};

    std::mutex m_syncIoMutex;
    std::mutex m_queueMutex;
    std::condition_variable m_queueCv;
    std::deque<AsyncTask> m_taskQueue;
    std::deque<AsyncResult> m_resultQueue;
    std::thread m_syncThread;
    bool m_workerRunning = false;
    bool m_workerStop    = false;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_SCRIBE_APP_H
