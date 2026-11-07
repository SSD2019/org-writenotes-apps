#ifndef WRITE_BRIDGE_C_API_H
#define WRITE_BRIDGE_C_API_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================================
// Portable C ABI for Org-WriteNotes C++ Write Engine
// Used by:
//   - iPadOS Swift / Objective-C (WriteEngineC clang module)
//   - Kotlin/Native cinterop (iosArm64 / iosSimulatorArm64)
//   - Linux & Desktop C/C++ tests
// ============================================================================

// Lifecycle
int64_t wn_session_create(int width, int height, int xRuling, int yRuling);
void wn_session_retain(int64_t handle);
void wn_session_destroy(int64_t handle);

// SVG / SVGZ Serialization
bool wn_session_load_svgz(int64_t handle, const uint8_t* bytes, int length);
int wn_session_export_svgz(int64_t handle, uint8_t* outBuf, int maxCapacity);

// RGBA Pixel Rendering
bool wn_session_render_rgba(
    int64_t handle,
    uint8_t* dstRgba,
    int width,
    int height,
    float originX,
    float originY
);
bool wn_session_render_dirty_rgba(
    int64_t handle,
    uint8_t* dstRgba,
    int width,
    int height,
    float left,
    float top,
    float right,
    float bottom
);

// Pen & Active Stroke (supports Apple Pencil 240Hz coalesced batches, tilt, and Pencil Pro roll/chiselAngle)
void wn_session_set_pen(
    int64_t handle,
    int colorArgb,
    float strokeWidth,
    bool pressureSensitive,
    int tipType,
    bool snapToGrid,
    bool lineDrawing,
    float chiselAngle
);
void wn_session_begin_stroke(
    int64_t handle,
    float x,
    float y,
    float pressure,
    float tiltX,
    float tiltY,
    double timestampMs
);
void wn_session_move_stroke(
    int64_t handle,
    float x,
    float y,
    float pressure,
    float tiltX,
    float tiltY,
    double timestampMs
);
void wn_session_move_stroke_batch(
    int64_t handle,
    const float* pointsData6,
    int pointCount
);
int wn_session_snap_active_stroke_to_shape(
    int64_t handle,
    float* outSnappedPoints,
    int maxFloats,
    int* outNumPoints
);
void wn_session_end_stroke(int64_t handle, int* outDimsAndDirty6);

// Erasers (Stroke, Segment, Ruled Line, Free/Image Segment)
bool wn_session_erase_at(int64_t handle, float x, float y, float radius, float* outDirtyBounds4);
bool wn_session_erase_segment(
    int64_t handle,
    float prevX,
    float prevY,
    float x,
    float y,
    float radius,
    float* outDirtyBounds4
);
bool wn_session_erase_ruled_line_at(int64_t handle, float y, float* outDirtyBounds4);
bool wn_session_erase_free_segment(
    int64_t handle,
    float prevX,
    float prevY,
    float x,
    float y,
    float radius,
    float* outDirtyBounds4
);

// Selection (Rect, Lasso, Ruled, All) & Transforms
int wn_session_select_rect(int64_t handle, float left, float top, float right, float bottom, float* outBounds4);
int wn_session_select_lasso(int64_t handle, const float* xyPoints, int numFloats, float* outBounds4);
int wn_session_select_ruled(int64_t handle, float startY, float endY, float* outBounds4);
int wn_session_select_all(int64_t handle, float* outBounds4);
void wn_session_clear_selection(int64_t handle);
bool wn_session_has_selection(int64_t handle);
int wn_session_selection_count(int64_t handle);
bool wn_session_get_selection_bounds(int64_t handle, float* outBounds4);

bool wn_session_translate_selection(
    int64_t handle,
    float dx,
    float dy,
    float* outBounds4,
    int* outNewDims2
);
bool wn_session_scale_selection(
    int64_t handle,
    float sx,
    float sy,
    float pivotX,
    float pivotY,
    bool scaleWidths,
    float* outBounds4,
    int* outNewDims2
);
bool wn_session_rotate_selection(
    int64_t handle,
    float angleRad,
    float pivotX,
    float pivotY,
    float* outBounds4,
    int* outNewDims2
);
bool wn_session_delete_selection(int64_t handle);
int wn_session_duplicate_selection(
    int64_t handle,
    float offsetX,
    float offsetY,
    float* outBounds4,
    int* outNewDims2
);
bool wn_session_restyle_selection(int64_t handle, int colorArgb, float strokeWidth);
int wn_session_copy_selection_svg(int64_t handle, char* outBuf, int maxCapacity);
int wn_session_paste_selection_svg(
    int64_t handle,
    const char* svgFragment,
    float offsetX,
    float offsetY,
    float* outBounds4,
    int* outNewDims2
);

// Whitespace Insertion & Collapse (Vertical, Horizontal, Ruled with Word Reflow)
bool wn_session_insert_vertical_space(
    int64_t handle,
    float yThreshold,
    float deltaY,
    bool snapToRuling,
    int* outNewDims2
);
bool wn_session_insert_horizontal_space(
    int64_t handle,
    float xThreshold,
    float deltaX,
    bool snapToRuling,
    int* outNewDims2
);
bool wn_session_insert_ruled_space(
    int64_t handle,
    float startX,
    float startY,
    float endX,
    float endY,
    bool enableReflow,
    int* outNewDims2
);

// Page Setup, Ruling, Template Designer & Images
bool wn_session_resize_canvas(int64_t handle, int newWidth, int newHeight);
bool wn_session_trim_bottom_whitespace(int64_t handle, int paddingPx, int* outNewDims2);
bool wn_session_set_page_setup(
    int64_t handle,
    int width,
    int height,
    int xRuling,
    int yRuling,
    int marginLeft,
    int paperColorArgb,
    int ruleColorArgb,
    int rulingStyle,
    int* outNewDims4
);
// Fills outInfo8 with: [width, height, xRuling, yRuling, marginLeft, paperColorArgb, ruleColorArgb, rulingStyle]
bool wn_session_get_page_info(int64_t handle, int* outInfo8);
bool wn_session_lock_content_as_template(int64_t handle);
bool wn_session_unlock_template_to_content(int64_t handle);
bool wn_session_clear_custom_template(int64_t handle);
bool wn_session_has_custom_template_nodes(int64_t handle);
bool wn_session_insert_image_bytes(
    int64_t handle,
    const uint8_t* bytes,
    int length,
    bool asBackground,
    float* outBounds4
);
bool wn_session_set_template_image_opacity(int64_t handle, float opacity);
void wn_session_set_render_theme(int64_t handle, bool isDark, uint32_t darkBgArgb, uint32_t darkFgArgb);

// History & Metadata
bool wn_session_undo(int64_t handle, int* outNewDims2);
bool wn_session_redo(int64_t handle, int* outNewDims2);
bool wn_session_can_undo(int64_t handle);
bool wn_session_can_redo(int64_t handle);
int wn_session_stroke_count(int64_t handle);

#ifdef __cplusplus
}
#endif

#endif // WRITE_BRIDGE_C_API_H

