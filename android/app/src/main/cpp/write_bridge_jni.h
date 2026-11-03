#ifndef WRITE_BRIDGE_JNI_H
#define WRITE_BRIDGE_JNI_H

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>

// Forward declarations from Stylus Labs Write (syncscribble / usvg / ulib)
class Document;
class Page;
class Element;
class StrokeBuilder;
class Painter;

namespace orgwritenotes {

enum TemplateRulingStyle : int {
    RULING_STANDARD        = 0, // Blank, Ruled, Grid, or Simple Margin
    RULING_DOTTED          = 1, // Dot Grid (dots at xRuling * yRuling intersections)
    RULING_ENGINEER_GRID   = 2, // Engineering Grid (minor 1x lines + bold major 5x lines)
    RULING_ISOMETRIC       = 3, // Isometric 30/60 deg triangle grid
    RULING_MUSIC_STAFF     = 4, // 5-line musical staves
    RULING_CORNELL_SUMMARY = 5, // Cornell Notes (Header bar + Left cue column + Bottom summary box)
    RULING_CUSTOM_DESIGNER = 6, // User-designed locked template or custom background image
    RULING_WEEKLY_PLANNER  = 7  // Weekly planner: header bar + 7 day rows with a label column
};

/**
 * NativeCanvasSession wraps a single Stylus Labs Write `Document` + `Page` + `Painter`
 * instance for an embedded or zoomed Org-WriteNotes ink block.
 */
class NativeCanvasSession {
public:
    NativeCanvasSession(int width, int height, int xRuling, int yRuling);
    ~NativeCanvasSession();

    void retain();
    void release();

    bool loadSvgzBytes(const uint8_t* bytes, size_t length);
    std::vector<uint8_t> exportSvgzBytes();

    // Renders the page into dstPixels. (originX, originY) is the page coordinate shown at the bitmap's
    // top-left corner, which lets callers render only a tile of a very large page (D4).
    bool renderToPixels(uint8_t* dstPixels, int bmpWidth, int bmpHeight, float originX = 0.0f, float originY = 0.0f);
    bool renderDirtyRectToPixels(
        uint8_t* dstPixels,
        int bmpWidth,
        int bmpHeight,
        float left,
        float top,
        float right,
        float bottom
    );

    void setPen(
        int colorArgb,
        float strokeWidth,
        bool pressureSensitive,
        int tipType = 1,
        bool snapToGrid = false,
        bool lineDrawing = false,
        float chiselAngle = 45.0f
    );
    void beginStroke(float x, float y, float pressure, float tiltX, float tiltY, double timestampMs);
    void moveStroke(float x, float y, float pressure, float tiltX, float tiltY, double timestampMs);
    void moveStrokeBatch(const float* pointsData6, int pointCount);
    int snapActiveStrokeToShape(float* outSnappedPoints, int maxFloats, int* outNumPoints);
    std::string endStroke(bool* outHeightExpanded, int* outNewHeight, float* outDirtyBounds4 = nullptr);

    // Erasers: Stroke Eraser, Ruled Line Eraser, and Free (Segment/Image) Eraser
    bool eraseAt(float x, float y, float radius, float* outDirtyBounds4 = nullptr);
    bool eraseSegment(float prevX, float prevY, float x, float y, float radius, float* outDirtyBounds4 = nullptr);
    bool eraseRuledLineAt(float y, float* outDirtyBounds4 = nullptr);
    bool eraseFreeSegment(float prevX, float prevY, float x, float y, float radius, float* outDirtyBounds4 = nullptr);

    bool resizeCanvas(int newWidth, int newHeight);
    bool trimBottomWhitespace(int paddingPx, int* outNewHeight);
    bool setTemplateImageOpacity(float opacity);

    // Selection operations (Rect, Lasso, Ruled, Select All)
    int selectRect(float left, float top, float right, float bottom, float* outBounds4);
    int selectLasso(const float* xyPoints, int numFloats, float* outBounds4);
    int selectRuled(float startY, float endY, float* outBounds4);
    int selectAll(float* outBounds4);
    void clearSelection();
    bool hasSelection() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return !m_selectedElements.empty();
    }
    int selectionCount() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return static_cast<int>(m_selectedElements.size());
    }
    bool getSelectionBounds(float* outBounds4) const;

    // Selection transforms & clipboard
    bool translateSelection(float dx, float dy, float* outBounds4, int* outNewWidth, int* outNewHeight);
    bool scaleSelection(
        float sx,
        float sy,
        float pivotX,
        float pivotY,
        bool scaleWidths,
        float* outBounds4,
        int* outNewWidth,
        int* outNewHeight
    );
    bool rotateSelection(
        float angleRad,
        float pivotX,
        float pivotY,
        float* outBounds4,
        int* outNewWidth,
        int* outNewHeight
    );
    bool deleteSelection();
    int duplicateSelection(
        float offsetX,
        float offsetY,
        float* outBounds4,
        int* outNewWidth = nullptr,
        int* outNewHeight = nullptr
    );
    bool restyleSelection(int colorArgb, float strokeWidth);
    std::string copySelectionSvg() const;
    int pasteSelectionSvg(
        const std::string& svgFragment,
        float offsetX,
        float offsetY,
        float* outBounds4,
        int* outNewWidth,
        int* outNewHeight
    );

    // White space expansion & collapse (Vertical, Horizontal, and Ruled with intra-line word shift & reflow)
    bool insertVerticalSpace(float yThreshold, float deltaY, bool snapToRuling, int* outNewHeight);
    bool insertHorizontalSpace(float xThreshold, float deltaX, bool snapToRuling, int* outNewWidth);
    bool insertRuledSpace(
        float startX,
        float startY,
        float endX,
        float endY,
        bool enableReflow,
        int* outNewWidth,
        int* outNewHeight
    );

    // Page, Ruling & Template Designer setup
    bool setPageSetup(
        int width,
        int height,
        int xRuling,
        int yRuling,
        int marginLeft,
        int paperColorArgb,
        int ruleColorArgb,
        int rulingStyle = -1
    );
    bool lockContentAsTemplate();
    bool unlockTemplateToContent();
    bool clearCustomTemplate();
    bool insertImageBytes(
        const uint8_t* bytes,
        size_t length,
        bool asBackground,
        float* outBounds4
    );

    int paperColorArgb() const;
    int ruleColorArgb() const;
    int marginLeft() const;
    int strokeCount() const;
    int rulingStyle() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return m_rulingStyle;
    }
    bool hasCustomTemplateNodes() const;

    bool undo();
    bool redo();
    bool canUndo() const;
    bool canRedo() const;

    void setRenderTheme(bool isDark, uint32_t darkBgArgb, uint32_t darkFgArgb);

    int width() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return m_width;
    }
    int height() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return m_height;
    }
    int xRuling() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return m_xRuling;
    }
    int yRuling() const {
        std::lock_guard<std::recursive_mutex> lock(m_mutex);
        return m_yRuling;
    }

private:
    void sanitizeSelection();
    void computeSelectionBounds(float* outBounds4) const;
    void snapPointIfEnabled(float* inoutX, float* inoutY) const;
    void rebuildProceduralRuleLayer();
    void detectRulingStyleFromLoadedPage();
    void autoExpandForBounds(float maxRight, float maxBottom, int* outNewWidth, int* outNewHeight);

    int m_width;
    int m_height;
    int m_xRuling;
    int m_yRuling;
    int m_rulingStyle = RULING_STANDARD;
    bool m_renderDarkTheme = false;
    uint32_t m_darkBgArgb = 0xFF181825;
    uint32_t m_darkFgArgb = 0xFFCDD6F4;
    uint32_t m_penColor = 0xFF1E293B;
    float m_penWidth = 2.6f;
    bool m_pressureSensitive = true;
    int m_penTipType = 1; // 0 = Flat, 1 = Round, 2 = Chisel
    float m_chiselAngle = 45.0f;
    bool m_snapToGrid = false;
    bool m_lineDrawing = false;
    int m_strokeSeq = 0;

    std::unique_ptr<Document> m_doc;
    Page* m_page = nullptr; // owned by m_doc
    std::vector<Element*> m_selectedElements;
    std::unique_ptr<StrokeBuilder> m_activeStroke;
    struct InputPt { float x; float y; double ts; };
    std::vector<InputPt> m_activeInputPoints;
    bool m_activeStrokeSnapped = false;
    std::unique_ptr<Painter> m_swPainter;
    mutable std::recursive_mutex m_mutex;
    std::atomic<int> m_refCount{1};
};

} // namespace orgwritenotes

#endif // WRITE_BRIDGE_JNI_H
