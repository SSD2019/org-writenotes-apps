#include "eink_fb.h"
#include "scribe_app.h"
#include "scribe_input.h"
#include "ws_protocol.h"
#include "write_bridge_c_api.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace orgwritenotes::kindle;

namespace {

#define ASSERT_TRUE(cond, msg)                                                        \
    do {                                                                              \
        if (!(cond)) {                                                                \
            std::fprintf(stderr, "FAIL [%s:%d]: %s\n", __FILE__, __LINE__, (msg));    \
            std::exit(1);                                                             \
        }                                                                             \
    } while (0)

void testGoldenProtocolFixture(const std::string& fixturePath) {
    GoldenFixtureData golden{};
    ASSERT_TRUE(parseGoldenFixtureFile(fixturePath, &golden), "Failed to parse protocol-golden.json");
    ASSERT_TRUE(golden.protocolVersion == 2, "Expected protocolVersion == 2");
    ASSERT_TRUE(golden.handshakeMethod == "session.hello", "Expected session.hello method");
    ASSERT_TRUE(golden.token == "tok-12345", "Expected tok-12345");
    ASSERT_TRUE(golden.inkCommit.nodeId == "file:test.org::id:abc-123", "Expected golden nodeId");
    ASSERT_TRUE(golden.inkCommit.fileName == "diagram.svgz", "Expected diagram.svgz");
    ASSERT_TRUE(golden.inkCommit.width == 1200 && golden.inkCommit.height == 600, "Expected 1200x600");
    ASSERT_TRUE(golden.inkCommit.baseEtag == "sha1-prev-hash", "Expected sha1-prev-hash");

    std::string helloJson = buildSessionHelloJson(
        1,
        golden.clientName,
        golden.version,
        golden.protocolVersion,
        golden.token
    );
    ASSERT_TRUE(extractJsonStringField(helloJson, "method") == "session.hello", "Handshake method mismatch");
    ASSERT_TRUE(extractJsonStringField(helloJson, "token") == "tok-12345", "Handshake token mismatch");
    ASSERT_TRUE(extractJsonIntField(helloJson, "protocolVersion", 0) == 2, "Handshake protocolVersion mismatch");

    std::string commitJson = buildInkCommitJson(2, golden.inkCommit);
    ASSERT_TRUE(extractJsonStringField(commitJson, "method") == "ink.commit", "ink.commit method mismatch");
    ASSERT_TRUE(extractJsonStringField(commitJson, "nodeId") == golden.inkCommit.nodeId, "ink.commit nodeId mismatch");
    ASSERT_TRUE(extractJsonStringField(commitJson, "baseEtag") == "sha1-prev-hash", "ink.commit baseEtag mismatch");
    std::printf("  [PASS] testGoldenProtocolFixture\n");
}

void testBase64AndSvgzEngineRoundTrip() {
    int64_t s1 = wn_session_create(800, 600, 0, 40);
    ASSERT_TRUE(s1 > 0, "wn_session_create failed");

    wn_session_set_pen(s1, static_cast<int>(0xFF000000), 3.0f, true, 0, false, false, 0.0f);
    wn_session_begin_stroke(s1, 100.0f, 120.0f, 0.5f, 0.0f, 0.0f, 1.0);
    wn_session_move_stroke(s1, 250.0f, 220.0f, 0.8f, 0.1f, 0.1f, 10.0);
    wn_session_move_stroke(s1, 400.0f, 300.0f, 0.6f, 0.1f, 0.1f, 20.0);
    int dims6[6] = {0};
    wn_session_end_stroke(s1, dims6);
    ASSERT_TRUE(wn_session_stroke_count(s1) == 1, "Expected 1 stroke in session 1");

    std::vector<uint8_t> svgz(256 * 1024);
    int len = wn_session_export_svgz(s1, svgz.data(), static_cast<int>(svgz.size()));
    ASSERT_TRUE(len > 32, "Expected non-empty .svgz export");
    svgz.resize(static_cast<size_t>(len));

    std::string b64 = base64Encode(svgz.data(), svgz.size());
    ASSERT_TRUE(!b64.empty(), "Expected non-empty base64 string");
    std::vector<uint8_t> decoded = base64Decode(b64);
    ASSERT_TRUE(decoded == svgz, "Base64 round-trip bytes mismatch");

    int64_t s2 = wn_session_create(800, 600, 0, 40);
    ASSERT_TRUE(wn_session_load_svgz(s2, decoded.data(), static_cast<int>(decoded.size())), "Failed to reload .svgz");
    ASSERT_TRUE(wn_session_stroke_count(s2) == 1, "Expected 1 stroke after reload in session 2");

    wn_session_destroy(s1);
    wn_session_destroy(s2);
    std::printf("  [PASS] testBase64AndSvgzEngineRoundTrip\n");
}

void testWacomRotationAndPalmRejection() {
    ScribeInputManager input(1860, 2480);
    AxisRange xRange{0, 1000};
    AxisRange yRange{0, 2000};

    float sx = 0.0f, sy = 0.0f;
    input.setRotation(ScreenRotation::ROT_0);
    input.mapSensorToScreen(500, 1000, xRange, yRange, &sx, &sy);
    ASSERT_TRUE(std::abs(sx - 930.0f) < 1.0f && std::abs(sy - 1240.0f) < 1.0f, "ROT_0 mapping failed");

    input.setRotation(ScreenRotation::ROT_90);
    input.mapSensorToScreen(0, 2000, xRange, yRange, &sx, &sy);
    ASSERT_TRUE(std::abs(sx - 1860.0f) < 1.0f && std::abs(sy - 2480.0f) < 1.0f, "ROT_90 mapping failed");

    input.setRotation(ScreenRotation::ROT_0);
    std::vector<ScribeInputEvent> events;

    // 1. Stylus enters hover proximity at t = 1000ms
    bool accepted = input.processRawSample(
        ScribeToolType::PEN,
        ScribeEventAction::HOVER,
        /*inProximity=*/true,
        200,
        400,
        xRange,
        yRange,
        0.0f,
        0.0f,
        0.0f,
        false,
        1000.0,
        events
    );
    ASSERT_TRUE(accepted, "Stylus hover should be accepted");

    // 2. Palm touches screen while stylus is in proximity -> MUST BE REJECTED
    bool palmWhileHover = input.processRawSample(
        ScribeToolType::FINGER,
        ScribeEventAction::DOWN,
        false,
        800,
        1500,
        xRange,
        yRange,
        0.5f,
        0.0f,
        0.0f,
        false,
        1050.0,
        events
    );
    ASSERT_TRUE(!palmWhileHover, "Finger touch while stylus in proximity must be rejected");

    // 3. Stylus leaves proximity at t = 1200ms, palm touches at t = 1500ms (within 700ms window) -> MUST BE REJECTED
    input.processRawSample(
        ScribeToolType::PEN,
        ScribeEventAction::UP,
        /*inProximity=*/false,
        200,
        400,
        xRange,
        yRange,
        0.0f,
        0.0f,
        0.0f,
        false,
        1200.0,
        events
    );
    bool palmWithinGuard = input.processRawSample(
        ScribeToolType::FINGER,
        ScribeEventAction::DOWN,
        false,
        800,
        1500,
        xRange,
        yRange,
        0.5f,
        0.0f,
        0.0f,
        false,
        1500.0,
        events
    );
    ASSERT_TRUE(!palmWithinGuard, "Finger touch within 700ms palm guard window must be rejected");

    // 4. Finger taps toolbar at t = 2100ms (> 700ms after stylus left) -> ACCEPTED
    bool fingerAfterGuard = input.processRawSample(
        ScribeToolType::FINGER,
        ScribeEventAction::DOWN,
        false,
        100,
        20,
        xRange,
        yRange,
        0.5f,
        0.0f,
        0.0f,
        false,
        2100.0,
        events
    );
    ASSERT_TRUE(fingerAfterGuard, "Finger tap after palm guard window should be accepted");
    std::printf("  [PASS] testWacomRotationAndPalmRejection\n");
}

void testEinkWaveformsAndAppWorkflow() {
    std::string testQueueDir = "/tmp/org-writenotes-kindle-test-queue";
    KindleScribeApp app("", testQueueDir, 930, 1240);
    app.syncClient().clearOfflineQueue();
    ASSERT_TRUE(app.initialize(), "Failed to initialize KindleScribeApp");

    app.framebuffer().clearUpdateHistory();

    // Draw a stroke on the canvas
    app.handleInputEvent({ ScribeToolType::PEN, ScribeEventAction::DOWN, 200.0f, 300.0f, 0.6f, 0.0f, 0.0f, false, 100.0 });
    app.handleInputEvent({ ScribeToolType::PEN, ScribeEventAction::MOVE, 350.0f, 420.0f, 0.8f, 0.0f, 0.0f, false, 116.0 });
    app.handleInputEvent({ ScribeToolType::PEN, ScribeEventAction::UP,   350.0f, 420.0f, 0.0f, 0.0f, 0.0f, false, 132.0 });

    ASSERT_TRUE(app.strokeCount() == 1, "Expected 1 stroke on canvas");

    const auto& history = app.framebuffer().updateHistory();
    ASSERT_TRUE(history.size() >= 2, "Expected DU move update + GC16_FAST pen-up settle update");
    ASSERT_TRUE(history[0].waveform == EinkWaveform::DU, "Active stroke move must use fast DU waveform");
    ASSERT_TRUE(history.back().waveform == EinkWaveform::GC16_FAST, "Stroke end must settle with GC16_FAST waveform");

    // Offline commit queue should have recorded the stroke automatically
    ASSERT_TRUE(app.syncClient().pendingQueueCount() == 1, "Expected 1 queued offline commit after stroke");

    // Tap Undo button on top toolbar (button index 3 of 7)
    float undoX = (3.5f / 7.0f) * static_cast<float>(app.framebuffer().width());
    app.handleInputEvent({ ScribeToolType::FINGER, ScribeEventAction::DOWN, undoX, 30.0f, 0.5f, 0.0f, 0.0f, false, 2000.0 });
    ASSERT_TRUE(app.strokeCount() == 0, "Expected 0 strokes after Undo toolbar tap");

    // Tap Redo button on top toolbar (button index 4 of 7)
    float redoX = (4.5f / 7.0f) * static_cast<float>(app.framebuffer().width());
    app.handleInputEvent({ ScribeToolType::FINGER, ScribeEventAction::DOWN, redoX, 30.0f, 0.5f, 0.0f, 0.0f, false, 2100.0 });
    ASSERT_TRUE(app.strokeCount() == 1, "Expected 1 stroke after Redo toolbar tap");

    app.syncClient().clearOfflineQueue();
    std::printf("  [PASS] testEinkWaveformsAndAppWorkflow\n");
}

} // namespace

int main(int argc, char** argv) {
    std::string fixturePath = "../../test-fixtures/protocol-golden.json";
    if (argc > 1 && argv[1][0] != '\0') {
        fixturePath = argv[1];
    }

    std::printf("Running Kindle Scribe Companion Unit & Integration Tests...\n");
    testGoldenProtocolFixture(fixturePath);
    testBase64AndSvgzEngineRoundTrip();
    testWacomRotationAndPalmRejection();
    testEinkWaveformsAndAppWorkflow();
    std::printf("All Kindle Scribe tests passed!\n");
    return 0;
}

