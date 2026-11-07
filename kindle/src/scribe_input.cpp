#include "scribe_input.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__linux__)
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#endif

namespace orgwritenotes::kindle {

namespace {

constexpr float kDegToRad = 3.14159265358979323846f / 180.0f;

#if defined(__linux__)
static bool testBit(const unsigned long* bits, int bit) {
    const int bitsPerLong = sizeof(unsigned long) * 8;
    return ((bits[bit / bitsPerLong] >> (bit % bitsPerLong)) & 1UL) != 0;
}
#endif

static float normalizeAxis(int32_t val, const AxisRange& range) {
    const int32_t span = range.maximum - range.minimum;
    if (span <= 0) return 0.0f;
    return std::clamp(static_cast<float>(val - range.minimum) / static_cast<float>(span), 0.0f, 1.0f);
}

} // namespace

ScribeInputManager::ScribeInputManager(int screenWidth, int screenHeight)
    : m_screenWidth(std::max(1, screenWidth)),
      m_screenHeight(std::max(1, screenHeight)) {}

ScribeInputManager::~ScribeInputManager() {
    closeDevices();
}

void ScribeInputManager::setScreenSize(int width, int height) {
    m_screenWidth  = std::max(1, width);
    m_screenHeight = std::max(1, height);
}

void ScribeInputManager::closeDevices() {
#if defined(__linux__)
    for (auto& node : m_nodes) {
        if (node.fd >= 0) {
            if (node.grabbed) {
                (void)ioctl(node.fd, EVIOCGRAB, 0);
                node.grabbed = false;
            }
            close(node.fd);
            node.fd = -1;
        }
    }
#endif
    m_nodes.clear();
    m_deviceNames.clear();
}

int ScribeInputManager::discoverDevices(const char* inputDir) {
    closeDevices();

#if defined(__linux__)
    if (!inputDir || !inputDir[0]) return 0;
    DIR* dir = opendir(inputDir);
    if (!dir) return 0;

    bool haveGrabbedHardwareWacom = false;
    int stylusCustomCandidateFd = -1;
    EvdevNode stylusCustomCandidate{};

    while (struct dirent* ent = readdir(dir)) {
        if (std::strncmp(ent->d_name, "event", 5) != 0) continue;
        std::string path = std::string(inputDir) + "/" + ent->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;

        unsigned long evBits[1 + EV_MAX / (sizeof(unsigned long) * 8)] = {0};
        unsigned long keyBits[1 + KEY_MAX / (sizeof(unsigned long) * 8)] = {0};
        unsigned long absBits[1 + ABS_MAX / (sizeof(unsigned long) * 8)] = {0};

        if (ioctl(fd, EVIOCGBIT(0, sizeof(evBits)), evBits) < 0 ||
            !testBit(evBits, EV_ABS) ||
            ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) {
            close(fd);
            continue;
        }
        if (testBit(evBits, EV_KEY)) {
            (void)ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBits)), keyBits);
        }

        char devName[256] = "Kindle Input";
        ioctl(fd, EVIOCGNAME(sizeof(devName) - 1), devName);

        const bool isStylusCustom = (std::strstr(devName, "stylus-custom") != nullptr);
        const bool isWacom = testBit(keyBits, BTN_TOOL_PEN) ||
                             testBit(keyBits, BTN_TOOL_RUBBER) ||
                             testBit(keyBits, BTN_STYLUS) ||
                             (std::strstr(devName, "Wacom") != nullptr) ||
                             isStylusCustom;
        const bool isTouch = !isWacom &&
                             (testBit(absBits, ABS_MT_POSITION_X) ||
                              (testBit(absBits, ABS_X) && testBit(keyBits, BTN_TOUCH)));

        if (!isWacom && !isTouch) {
            close(fd);
            continue;
        }

        EvdevNode node;
        node.fd      = fd;
        node.isWacom = isWacom;
        node.isTouch = isTouch;
        node.name    = std::string(devName) + (isWacom ? " [Wacom EMR]" : " [Touch]");

        struct input_absinfo absInfo{};
        int xCode = (isTouch && testBit(absBits, ABS_MT_POSITION_X)) ? ABS_MT_POSITION_X : ABS_X;
        int yCode = (isTouch && testBit(absBits, ABS_MT_POSITION_Y)) ? ABS_MT_POSITION_Y : ABS_Y;

        if (ioctl(fd, EVIOCGABS(xCode), &absInfo) == 0 && absInfo.maximum > absInfo.minimum) {
            node.absX = { absInfo.minimum, absInfo.maximum };
        }
        if (ioctl(fd, EVIOCGABS(yCode), &absInfo) == 0 && absInfo.maximum > absInfo.minimum) {
            node.absY = { absInfo.minimum, absInfo.maximum };
        }
        if (isWacom && testBit(absBits, ABS_PRESSURE) && ioctl(fd, EVIOCGABS(ABS_PRESSURE), &absInfo) == 0) {
            node.pressure = { absInfo.minimum, absInfo.maximum > absInfo.minimum ? absInfo.maximum : 4095 };
        }
        if (isWacom && testBit(absBits, ABS_TILT_X) && ioctl(fd, EVIOCGABS(ABS_TILT_X), &absInfo) == 0) {
            node.tiltX = { absInfo.minimum, absInfo.maximum > absInfo.minimum ? absInfo.maximum : 60 };
        }
        if (isWacom && testBit(absBits, ABS_TILT_Y) && ioctl(fd, EVIOCGABS(ABS_TILT_Y), &absInfo) == 0) {
            node.tiltY = { absInfo.minimum, absInfo.maximum > absInfo.minimum ? absInfo.maximum : 60 };
        }

        if (isStylusCustom) {
            if (stylusCustomCandidateFd >= 0) {
                close(stylusCustomCandidateFd);
            }
            stylusCustomCandidateFd = fd;
            stylusCustomCandidate   = node;
            continue;
        }

        node.grabbed = (ioctl(fd, EVIOCGRAB, 1) == 0);
        if (isWacom && node.grabbed) {
            haveGrabbedHardwareWacom = true;
        }

        char descBuf[384];
        std::snprintf(
            descBuf,
            sizeof(descBuf),
            "%s (X:%d..%d, Y:%d..%d, P:%d..%d%s)",
            node.name.c_str(),
            node.absX.minimum,
            node.absX.maximum,
            node.absY.minimum,
            node.absY.maximum,
            node.pressure.minimum,
            node.pressure.maximum,
            node.grabbed ? ", grabbed" : ""
        );
        node.name = descBuf;

        m_nodes.push_back(node);
        m_deviceNames.push_back(node.name);
    }

    if (stylusCustomCandidateFd >= 0) {
        if (!haveGrabbedHardwareWacom) {
            stylusCustomCandidate.grabbed = (ioctl(stylusCustomCandidateFd, EVIOCGRAB, 1) == 0);
            char descBuf[384];
            std::snprintf(
                descBuf,
                sizeof(descBuf),
                "%s (X:%d..%d, Y:%d..%d, P:%d..%d%s)",
                stylusCustomCandidate.name.c_str(),
                stylusCustomCandidate.absX.minimum,
                stylusCustomCandidate.absX.maximum,
                stylusCustomCandidate.absY.minimum,
                stylusCustomCandidate.absY.maximum,
                stylusCustomCandidate.pressure.minimum,
                stylusCustomCandidate.pressure.maximum,
                stylusCustomCandidate.grabbed ? ", grabbed" : ""
            );
            stylusCustomCandidate.name = descBuf;
            m_nodes.push_back(stylusCustomCandidate);
            m_deviceNames.push_back(stylusCustomCandidate.name);
        } else {
            close(stylusCustomCandidateFd);
        }
    }

    closedir(dir);
#endif
    return static_cast<int>(m_nodes.size());
}

void ScribeInputManager::mapSensorToScreen(
    int32_t rawX,
    int32_t rawY,
    const AxisRange& xRange,
    const AxisRange& yRange,
    float* outX,
    float* outY
) const {
    float nx = normalizeAxis(rawX, xRange);
    float ny = normalizeAxis(rawY, yRange);

    float sx = nx;
    float sy = ny;
    switch (m_rotation) {
        case ScreenRotation::ROT_0:
            sx = nx;
            sy = ny;
            break;
        case ScreenRotation::ROT_90:
            sx = ny;
            sy = 1.0f - nx;
            break;
        case ScreenRotation::ROT_180:
            sx = 1.0f - nx;
            sy = 1.0f - ny;
            break;
        case ScreenRotation::ROT_270:
            sx = 1.0f - ny;
            sy = nx;
            break;
    }

    if (outX) *outX = sx * static_cast<float>(m_screenWidth);
    if (outY) *outY = sy * static_cast<float>(m_screenHeight);
}

bool ScribeInputManager::processRawSample(
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
) {
    if (tool == ScribeToolType::PEN || tool == ScribeToolType::ERASER) {
        m_stylusInProximity = inProximity;
        if (inProximity || action != ScribeEventAction::HOVER) {
            m_lastStylusTimestampMs = timestampMs;
        }
    } else if (tool == ScribeToolType::FINGER) {
        // Hardware proximity palm rejection + temporal palm guard window (default 700ms)
        if (m_stylusInProximity) {
            return false;
        }
        if ((timestampMs - m_lastStylusTimestampMs) < m_palmRejectionWindowMs) {
            return false;
        }
    }

    float screenX = 0.0f;
    float screenY = 0.0f;
    mapSensorToScreen(rawX, rawY, xRange, yRange, &screenX, &screenY);

    ScribeInputEvent ev;
    ev.tool         = tool;
    ev.action       = action;
    ev.x            = screenX;
    ev.y            = screenY;
    ev.pressure     = (action == ScribeEventAction::UP || action == ScribeEventAction::HOVER)
        ? 0.0f
        : std::clamp(normalizedPressure, 0.0f, 1.0f);
    ev.tiltX        = tiltXDeg * kDegToRad;
    ev.tiltY        = tiltYDeg * kDegToRad;
    ev.barrelButton = barrelButton;
    ev.timestampMs  = timestampMs;

    outEvents.push_back(ev);
    return true;
}

int ScribeInputManager::pollEvents(std::vector<ScribeInputEvent>& outEvents, int timeoutMs) {
    int initialCount = static_cast<int>(outEvents.size());
#if defined(__linux__)
    if (m_nodes.empty()) return 0;

    std::vector<struct pollfd> pfds(m_nodes.size());
    for (size_t i = 0; i < m_nodes.size(); ++i) {
        pfds[i].fd      = m_nodes[i].fd;
        pfds[i].events  = POLLIN;
        pfds[i].revents = 0;
    }

    int ready = poll(pfds.data(), pfds.size(), timeoutMs);
    if (ready <= 0) return 0;

    for (size_t i = 0; i < m_nodes.size(); ++i) {
        if (!(pfds[i].revents & POLLIN)) continue;
        EvdevNode& node = m_nodes[i];
        struct input_event iev{};
        while (read(node.fd, &iev, sizeof(iev)) == sizeof(iev)) {
            if (iev.type == EV_KEY) {
                if (iev.code == BTN_TOOL_PEN) {
                    node.penHover = (iev.value != 0);
                    if (!node.penHover && !node.rubberHover) {
                        node.touching = false;
                        node.curPress = node.pressure.minimum;
                    }
                    node.dirty = true;
                } else if (iev.code == BTN_TOOL_RUBBER) {
                    node.rubberHover = (iev.value != 0);
                    if (!node.penHover && !node.rubberHover) {
                        node.touching = false;
                        node.curPress = node.pressure.minimum;
                    }
                    node.dirty = true;
                } else if (iev.code == BTN_TOUCH) {
                    node.touching = (iev.value != 0);
                    if (!node.touching) {
                        node.curPress = node.pressure.minimum;
                    }
                    node.dirty = true;
                } else if (iev.code == BTN_STYLUS || iev.code == BTN_STYLUS2) {
                    node.barrelBtn = (iev.value != 0);
                    node.dirty     = true;
                }
            } else if (iev.type == EV_ABS) {
                if (iev.code == ABS_X || iev.code == ABS_MT_POSITION_X) {
                    node.curRawX = iev.value;
                    node.dirty   = true;
                } else if (iev.code == ABS_Y || iev.code == ABS_MT_POSITION_Y) {
                    node.curRawY = iev.value;
                    node.dirty   = true;
                } else if (iev.code == ABS_PRESSURE) {
                    node.curPress = iev.value;
                    node.dirty    = true;
                } else if (iev.code == ABS_TILT_X) {
                    node.curTiltX = iev.value;
                    node.dirty    = true;
                } else if (iev.code == ABS_TILT_Y) {
                    node.curTiltY = iev.value;
                    node.dirty    = true;
                } else if (iev.code == ABS_MT_TRACKING_ID) {
                    node.touching = (iev.value >= 0);
                    if (!node.touching) {
                        node.curPress = node.pressure.minimum;
                    }
                    node.dirty = true;
                }
            } else if (iev.type == EV_SYN && iev.code == SYN_REPORT && node.dirty) {
                node.dirty = false;
                double tsMs = static_cast<double>(iev.time.tv_sec) * 1000.0 +
                              static_cast<double>(iev.time.tv_usec) / 1000.0;

                ScribeToolType tool = ScribeToolType::FINGER;
                if (node.isWacom) {
                    tool = (node.rubberHover || node.barrelBtn)
                        ? ScribeToolType::ERASER
                        : ScribeToolType::PEN;
                }

                bool activeContact = node.touching || (node.isWacom && node.curPress > node.pressure.minimum);
                ScribeEventAction action = ScribeEventAction::HOVER;
                if (activeContact && !node.prevTouching) {
                    action = ScribeEventAction::DOWN;
                } else if (activeContact && node.prevTouching) {
                    action = ScribeEventAction::MOVE;
                } else if (!activeContact && node.prevTouching) {
                    action = ScribeEventAction::UP;
                }
                node.prevTouching = activeContact;

                float normPress = node.isWacom
                    ? normalizeAxis(node.curPress, node.pressure)
                    : (activeContact ? 0.5f : 0.0f);
                bool inProx = node.isWacom ? (node.penHover || node.rubberHover || activeContact) : false;

                processRawSample(
                    tool,
                    action,
                    inProx,
                    node.curRawX,
                    node.curRawY,
                    node.absX,
                    node.absY,
                    normPress,
                    static_cast<float>(node.curTiltX),
                    static_cast<float>(node.curTiltY),
                    node.barrelBtn,
                    tsMs,
                    outEvents
                );
            }
        }
    }
#else
    (void)timeoutMs;
#endif
    return static_cast<int>(outEvents.size()) - initialCount;
}

} // namespace orgwritenotes::kindle

