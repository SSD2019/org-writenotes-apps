#include "write_bridge_c_api.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>

int main() {
    printf("[1/5] Creating iPadOS C ABI session (wn_session_create)...\n");
    int64_t handle = wn_session_create(1080, 540, 0, 40);
    assert(handle != 0);

    int info8[8] = {0};
    assert(wn_session_get_page_info(handle, info8));
    assert(info8[0] == 1080);
    assert(info8[1] == 540);
    assert(info8[3] == 40);
    assert(wn_session_stroke_count(handle) == 0);

    printf("[2/5] Testing 240Hz Apple Pencil batch stroke + Pencil Pro chisel roll angle...\n");
    wn_session_set_pen(handle, static_cast<int>(0xFF1D4ED8), 3.4f, true, 2 /* chisel */, false, false, 62.5f);
    wn_session_begin_stroke(handle, 100.0f, 120.0f, 0.45f, 12.0f, -8.0f, 0.0);

    const float coalescedBatch[3 * 6] = {
        140.0f, 135.0f, 0.65f, 14.0f, -9.0f, 4.0f,
        180.0f, 150.0f, 0.80f, 15.0f, -10.0f, 8.0f,
        220.0f, 165.0f, 0.60f, 13.0f, -8.0f, 12.0f
    };
    wn_session_move_stroke_batch(handle, coalescedBatch, 3);

    int outDimsAndDirty6[6] = {0};
    wn_session_end_stroke(handle, outDimsAndDirty6);
    assert(outDimsAndDirty6[0] == 1080);
    assert(outDimsAndDirty6[4] > outDimsAndDirty6[2]);
    assert(wn_session_stroke_count(handle) == 1);
    assert(wn_session_can_undo(handle));

    printf("[3/5] Testing selection, transform, and whitespace insertion C ABI...\n");
    float bounds4[4] = {0};
    int selCount = wn_session_select_rect(handle, 80.0f, 100.0f, 250.0f, 200.0f, bounds4);
    assert(selCount == 1);
    assert(wn_session_has_selection(handle));

    int newDims2[2] = {0};
    assert(wn_session_translate_selection(handle, 20.0f, 15.0f, bounds4, newDims2));
    wn_session_clear_selection(handle);
    assert(!wn_session_has_selection(handle));

    assert(wn_session_insert_vertical_space(handle, 50.0f, 80.0f, true, newDims2));

    printf("[4/5] Testing .svgz export & reload roundtrip + RGBA render...\n");
    int svgzSize = wn_session_export_svgz(handle, nullptr, 0);
    assert(svgzSize > 32);
    std::vector<uint8_t> svgzBuf(static_cast<size_t>(svgzSize));
    int written = wn_session_export_svgz(handle, svgzBuf.data(), svgzSize);
    assert(written == svgzSize);
    assert(svgzBuf[0] == 0x1F && svgzBuf[1] == 0x8B);

    int64_t handle2 = wn_session_create(400, 300, 0, 40);
    assert(wn_session_load_svgz(handle2, svgzBuf.data(), svgzSize));
    assert(wn_session_stroke_count(handle2) == 1);

    std::vector<uint8_t> rgba(400 * 300 * 4, 0);
    assert(wn_session_render_rgba(handle2, rgba.data(), 400, 300, 0.0f, 0.0f));

    printf("[5/5] Testing undo/redo & cleanup...\n");
    assert(wn_session_undo(handle, newDims2));
    assert(wn_session_can_redo(handle));
    assert(wn_session_redo(handle, newDims2));

    wn_session_destroy(handle2);
    wn_session_destroy(handle);
    printf("All iPadOS C ABI (write_bridge_c_api.h) tests PASSED!\n");
    return 0;
}

