#include "scribe_app.h"

#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

#ifdef __ANDROID__
// Force ELF PT_TLS segment alignment >= 32 bytes for static ARMv7 Bionic executables
__asm__(
    ".section .tdata,\"awT\",%progbits\n"
    ".balign 64\n"
    ".globl kindle_bionic_tls_align_guard\n"
    "kindle_bionic_tls_align_guard:\n"
    ".word 1\n"
    ".previous\n"
);
#endif

volatile std::sig_atomic_t g_stopRequested = 0;

void handleSignal(int) {
    g_stopRequested = 1;
}

void printUsage(const char* prog) {
    std::printf(
        "Org-WriteNotes Companion Client for Jailbroken Kindle Scribe\n"
        "Usage: %s [options]\n\n"
        "Options:\n"
        "  --config <path>      Path to config.json (default: /mnt/us/extensions/org-writenotes/config.json)\n"
        "  --fb <dev>           Framebuffer device (default: /dev/fb0)\n"
        "  --input <dir>        Linux evdev directory (default: /dev/input)\n"
        "  --queue <dir>        Offline commit queue directory (default: /mnt/us/org-writenotes/queue)\n"
        "  --sketch <file.svgz> Open and auto-save a local .svgz sketch file\n"
        "  --rotate <deg>       Wacom rotation: 0, 90, 180, or 270 (default: 0)\n"
        "  --sync-now           Connect to Emacs, flush offline queue, and exit\n"
        "  --refresh            Trigger full-screen GC16 E-Ink ghosting clear and exit\n"
        "  --devices            Probe and list Kindle framebuffer & evdev input devices\n"
        "  --demo-pgm <out.pgm> Render a synthetic Scribe stroke & UI snapshot to a PGM image\n"
        "  --help               Show this help message\n",
        prog
    );
}

} // namespace

int main(int argc, char** argv) {
    using namespace orgwritenotes::kindle;

    std::string configPath = "/mnt/us/org-writenotes/config.json";
    std::string fbPath     = "/dev/fb0";
    std::string inputDir   = "/dev/input";
    std::string queueDir   = "/mnt/us/org-writenotes/queue";
    std::string sketchPath;
    std::string demoPgmPath;
    int rotateDeg          = 0;
    bool syncNowOnly       = false;
    bool refreshOnly       = false;
    bool listDevicesOnly   = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--help" || arg == "-h") {
            printUsage(argv[0]);
            return 0;
        } else if (arg == "--config" && i + 1 < argc) {
            configPath = argv[++i];
        } else if (arg == "--fb" && i + 1 < argc) {
            fbPath = argv[++i];
        } else if (arg == "--input" && i + 1 < argc) {
            inputDir = argv[++i];
        } else if (arg == "--queue" && i + 1 < argc) {
            queueDir = argv[++i];
        } else if (arg == "--sketch" && i + 1 < argc) {
            sketchPath = argv[++i];
        } else if (arg == "--rotate" && i + 1 < argc) {
            rotateDeg = std::atoi(argv[++i]);
        } else if (arg == "--sync-now") {
            syncNowOnly = true;
        } else if (arg == "--refresh") {
            refreshOnly = true;
        } else if (arg == "--devices") {
            listDevicesOnly = true;
        } else if (arg == "--demo-pgm" && i + 1 < argc) {
            demoPgmPath = argv[++i];
        }
    }

    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);

    if (syncNowOnly) {
        WriteNotesSyncClient sync(queueDir);
        sync.loadConfigFile(configPath);
        std::printf("[Kindle-Scribe] Connecting to %s:%d...\n", sync.host().c_str(), sync.port());
        if (!sync.connectAndHandshake()) {
            std::fprintf(
                stderr,
                "[Kindle-Scribe] Offline or unreachable (%zu queued commits preserved).\n",
                sync.pendingQueueCount()
            );
            return 1;
        }
        int flushed = sync.replayOfflineQueue();
        std::printf("[Kindle-Scribe] Synced! Flushed %d queued commit(s).\n", flushed);
        return 0;
    }

    if (refreshOnly) {
        EinkFramebuffer fb;
        fb.openDevice(fbPath.c_str(), 1860, 2480);
        fb.refreshFullScreen(EinkWaveform::GC16, /*fullFlash=*/true);
        std::printf("[Kindle-Scribe] Triggered full-screen GC16 refresh (%dx%d).\n", fb.width(), fb.height());
        return 0;
    }

    if (!demoPgmPath.empty()) {
        KindleScribeApp app("", queueDir, 1860, 2480);
        if (!app.initialize()) return 1;

        // Draw a synthetic pressure-sensitive curve across the canvas
        ScribeInputEvent down{ ScribeToolType::PEN, ScribeEventAction::DOWN, 300.0f, 400.0f, 0.35f, 0.0f, 0.0f, false, 10.0 };
        app.handleInputEvent(down);
        for (int step = 1; step <= 24; ++step) {
            float t = static_cast<float>(step) / 24.0f;
            ScribeInputEvent mv{
                ScribeToolType::PEN,
                ScribeEventAction::MOVE,
                300.0f + t * 900.0f,
                400.0f + t * 450.0f,
                0.35f + 0.55f * t,
                0.1f,
                0.1f,
                false,
                10.0 + step * 4.0
            };
            app.handleInputEvent(mv);
        }
        ScribeInputEvent up{ ScribeToolType::PEN, ScribeEventAction::UP, 1200.0f, 850.0f, 0.0f, 0.0f, 0.0f, false, 120.0 };
        app.handleInputEvent(up);

        if (!app.framebuffer().savePgm(demoPgmPath)) {
            std::fprintf(stderr, "Failed to write PGM to %s\n", demoPgmPath.c_str());
            return 1;
        }
        std::printf(
            "[Kindle-Scribe] Saved %dx%d E-Ink snapshot (%d strokes) to %s\n",
            app.framebuffer().width(),
            app.framebuffer().height(),
            app.strokeCount(),
            demoPgmPath.c_str()
        );
        return 0;
    }

    if (listDevicesOnly) {
        EinkFramebuffer fb;
        fb.openDevice(fbPath.c_str(), 1860, 2480);
        ScribeInputManager inp(fb.width(), fb.height());
        int devCount = inp.discoverDevices(inputDir.c_str());
        std::printf(
            "Framebuffer: %dx%d (%d bpp, hardware=%s)\n",
            fb.width(),
            fb.height(),
            fb.bitsPerPixel(),
            fb.isHardwareFb() ? "yes" : "simulated"
        );
        std::printf("Detected Input Devices (%d):\n", devCount);
        for (const auto& name : inp.detectedDevices()) {
            std::printf("  - %s\n", name.c_str());
        }
        return 0;
    }

    KindleScribeApp app(fbPath.c_str(), queueDir, 1860, 2480);
    if (!app.initialize()) {
        std::fprintf(stderr, "[Kindle-Scribe] Failed to initialize framebuffer & WriteEngineC.\n");
        return 1;
    }

    if (rotateDeg == 90) app.input().setRotation(ScreenRotation::ROT_90);
    else if (rotateDeg == 180) app.input().setRotation(ScreenRotation::ROT_180);
    else if (rotateDeg == 270) app.input().setRotation(ScreenRotation::ROT_270);
    else app.input().setRotation(ScreenRotation::ROT_0);

    int devCount = app.input().discoverDevices(inputDir.c_str());

    app.syncClient().loadConfigFile(configPath);
    app.syncClient().connectAndHandshake();

    if (!sketchPath.empty()) {
        app.loadSketchFile(sketchPath);
    }

    std::printf(
        "[Kindle-Scribe] Running on %dx%d E-Ink display (%d input devices). Tap [Exit] or Ctrl+C to quit.\n",
        app.framebuffer().width(),
        app.framebuffer().height(),
        devCount
    );

    std::vector<ScribeInputEvent> events;
    events.reserve(32);
    while (!g_stopRequested && !app.shouldExit()) {
        events.clear();
        int n = app.input().pollEvents(events, 16);
        for (int i = 0; i < n; ++i) {
            app.handleInputEvent(events[static_cast<size_t>(i)]);
        }
    }

    std::printf("[Kindle-Scribe] Exiting cleanly.\n");
    return 0;
}
