#ifndef LINUX_STYLUS_INPUT_H
#define LINUX_STYLUS_INPUT_H

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>

namespace orgwritenotes {

/**
 * Tool type constants matching android.view.MotionEvent.TOOL_TYPE_*
 */
enum LinuxToolType : int {
    LINUX_TOOL_TYPE_UNKNOWN = 0,
    LINUX_TOOL_TYPE_FINGER  = 1,
    LINUX_TOOL_TYPE_STYLUS  = 2,
    LINUX_TOOL_TYPE_MOUSE   = 3,
    LINUX_TOOL_TYPE_ERASER  = 4
};

/**
 * Button bitmask constants matching android.view.MotionEvent.BUTTON_*
 */
enum LinuxButtonMask : int {
    LINUX_BUTTON_PRIMARY          = 1 << 0, // 1
    LINUX_BUTTON_SECONDARY        = 1 << 1, // 2
    LINUX_BUTTON_TERTIARY         = 1 << 2, // 4
    LINUX_BUTTON_STYLUS_PRIMARY   = 1 << 5, // 32
    LINUX_BUTTON_STYLUS_SECONDARY = 1 << 6  // 64
};

struct LinuxStylusSample {
    float x = 0.0f;
    float y = 0.0f;
    float pressure = 0.0f;
    float tiltX = 0.0f;
    float tiltY = 0.0f;
    double timestampMs = 0.0;
};

struct LinuxStylusSnapshot {
    int toolType = LINUX_TOOL_TYPE_MOUSE;
    float pressure = 0.0f;
    float tiltRad = 0.0f;
    float tiltX = 0.0f;
    float tiltY = 0.0f;
    int buttonMask = 0;
    double lastStylusTimestampMs = 0.0;
    int detectedDeviceCount = 0;
};

class LinuxStylusInputManager {
public:
    static LinuxStylusInputManager& instance();

    bool start();
    void stop();

    int pollState(LinuxStylusSnapshot* outSnapshot, LinuxStylusSample* outHistory, int maxHistory);
    std::vector<std::string> getDetectedDevices() const;

    void injectSample(
        int toolType,
        float x,
        float y,
        float pressure,
        float tiltX,
        float tiltY,
        int buttonMask,
        double timestampMs
    );

private:
    LinuxStylusInputManager() = default;
    ~LinuxStylusInputManager();
};

} // namespace orgwritenotes

#endif // LINUX_STYLUS_INPUT_H

