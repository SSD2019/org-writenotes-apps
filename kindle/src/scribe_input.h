#ifndef KINDLE_SCRIBE_INPUT_H
#define KINDLE_SCRIBE_INPUT_H

#include <cstdint>
#include <string>
#include <vector>

namespace orgwritenotes::kindle {

enum class ScribeToolType : int {
    NONE   = 0,
    PEN    = 1,
    ERASER = 2,
    FINGER = 3
};

enum class ScribeEventAction : int {
    HOVER = 0,
    DOWN  = 1,
    MOVE  = 2,
    UP    = 3
};

// Screen rotation relative to the raw Wacom EMR sensor axes on Kindle Scribe
enum class ScreenRotation : int {
    ROT_0   = 0,
    ROT_90  = 90,
    ROT_180 = 180,
    ROT_270 = 270
};

struct ScribeInputEvent {
    ScribeToolType tool     = ScribeToolType::PEN;
    ScribeEventAction action = ScribeEventAction::MOVE;
    float x                 = 0.0f;
    float y                 = 0.0f;
    float pressure          = 0.0f;
    float tiltX             = 0.0f; // radians
    float tiltY             = 0.0f; // radians
    bool barrelButton       = false;
    double timestampMs      = 0.0;
};

struct AxisRange {
    int32_t minimum = 0;
    int32_t maximum = 20966;
};

class ScribeInputManager {
public:
    ScribeInputManager(int screenWidth = 1860, int screenHeight = 2480);
    ~ScribeInputManager();

    // Scans /dev/input/event* for Wacom EMR stylus and capacitive touch nodes
    int discoverDevices(const char* inputDir = "/dev/input");
    void closeDevices();

    void setScreenSize(int width, int height);
    void setRotation(ScreenRotation rotation) { m_rotation = rotation; }
    ScreenRotation rotation() const { return m_rotation; }

    void setStylusOnlyCanvas(bool stylusOnly) { m_stylusOnlyCanvas = stylusOnly; }
    bool stylusOnlyCanvas() const { return m_stylusOnlyCanvas; }

    void setPalmRejectionWindowMs(double windowMs) { m_palmRejectionWindowMs = windowMs; }
    double palmRejectionWindowMs() const { return m_palmRejectionWindowMs; }

    bool isStylusInProximity() const { return m_stylusInProximity; }

    // Maps raw sensor (rawX, rawY) within [xRange, yRange] to screen (outX, outY)
    void mapSensorToScreen(
        int32_t rawX,
        int32_t rawY,
        const AxisRange& xRange,
        const AxisRange& yRange,
        float* outX,
        float* outY
    ) const;

    // Polls open evdev descriptors (timeoutMs >= 0) and appends filtered events
    int pollEvents(std::vector<ScribeInputEvent>& outEvents, int timeoutMs = 8);

    // Inject raw stylus or touch packet (used by unit tests & simulator)
    bool processRawSample(
        ScribeToolType tool,
        ScribeEventAction action,
        bool inProximity,
        int32_t rawX,
        int32_t rawY,
        const AxisRange& xRange,
        const AxisRange& yRange,
        float normalizedPressure,
        float tiltXDeg,
        float tiltYDeg,
        bool barrelButton,
        double timestampMs,
        std::vector<ScribeInputEvent>& outEvents
    );

    const std::vector<std::string>& detectedDevices() const { return m_deviceNames; }

private:
    struct EvdevNode {
        int fd             = -1;
        bool isWacom       = false;
        bool isTouch       = false;
        std::string name;
        AxisRange absX{0, 20966};
        AxisRange absY{0, 15725};
        AxisRange pressure{0, 4095};
        AxisRange tiltX{-60, 60};
        AxisRange tiltY{-60, 60};

        // Accumulator state between SYN_REPORT frames
        int32_t curRawX    = 0;
        int32_t curRawY    = 0;
        int32_t curPress   = 0;
        int32_t curTiltX   = 0;
        int32_t curTiltY   = 0;
        bool penHover      = false;
        bool rubberHover   = false;
        bool touching      = false;
        bool prevTouching  = false;
        bool barrelBtn     = false;
        bool dirty         = false;
    };

    int m_screenWidth              = 1860;
    int m_screenHeight             = 2480;
    ScreenRotation m_rotation      = ScreenRotation::ROT_0;
    bool m_stylusOnlyCanvas        = true;
    bool m_stylusInProximity       = false;
    double m_lastStylusTimestampMs = -1e9;
    double m_palmRejectionWindowMs = 700.0;

    std::vector<EvdevNode> m_nodes;
    std::vector<std::string> m_deviceNames;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_SCRIBE_INPUT_H

