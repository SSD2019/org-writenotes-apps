#include "linux_stylus_input.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>
#include <unordered_map>

#if defined(__linux__) && !defined(__ANDROID__)
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <X11/Xlib.h>
#include <X11/extensions/XInput2.h>
#endif

namespace orgwritenotes {

namespace {

static double nowMonotonicMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

static std::string toLowerAscii(const char* s) {
    if (!s) return "";
    std::string out(s);
    for (char& c : out) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return out;
}

static bool containsAny(const std::string& haystack, std::initializer_list<const char*> needles) {
    for (const char* n : needles) {
        if (haystack.find(n) != std::string::npos) return true;
    }
    return false;
}

struct SharedStylusState {
    mutable std::mutex mutex;
    LinuxStylusSnapshot current;
    std::vector<LinuxStylusSample> historyRing;
    std::vector<std::string> deviceNames;
    std::atomic<bool> running{false};
    std::thread x11Thread;
    std::thread evdevThread;

    ~SharedStylusState() {
        running.store(false);
        if (x11Thread.joinable() && x11Thread.get_id() != std::this_thread::get_id()) x11Thread.join();
        if (evdevThread.joinable() && evdevThread.get_id() != std::this_thread::get_id()) evdevThread.join();
    }
};

static SharedStylusState& state() {
    static SharedStylusState s;
    return s;
}

static void recordStylusEvent(
    int toolType,
    float x,
    float y,
    float pressure,
    float tiltX,
    float tiltY,
    int buttonMask,
    double timestampMs,
    bool hasCoords
) {
    auto& st = state();
    std::lock_guard<std::mutex> lock(st.mutex);
    const double ts = timestampMs > 0.0 ? timestampMs : nowMonotonicMs();

    st.current.toolType = toolType;
    st.current.pressure = std::clamp(pressure, 0.0f, 1.0f);
    st.current.tiltX = tiltX;
    st.current.tiltY = tiltY;
    st.current.tiltRad = std::hypot(tiltX, tiltY);
    st.current.buttonMask = buttonMask;
    if (toolType == LINUX_TOOL_TYPE_STYLUS || toolType == LINUX_TOOL_TYPE_ERASER) {
        st.current.lastStylusTimestampMs = ts;
    }

    if (hasCoords && (toolType == LINUX_TOOL_TYPE_STYLUS || toolType == LINUX_TOOL_TYPE_ERASER)) {
        if (st.historyRing.size() >= 128) {
            st.historyRing.erase(st.historyRing.begin());
        }
        LinuxStylusSample sample;
        sample.x = x;
        sample.y = y;
        sample.pressure = st.current.pressure;
        sample.tiltX = tiltX;
        sample.tiltY = tiltY;
        sample.timestampMs = ts;
        st.historyRing.push_back(sample);
    }
}

#if defined(__linux__) && !defined(__ANDROID__)

// ============================================================================
// Layer 1: XInput2 Listener (Works on both native X11 and Wayland via XWayland)
// ============================================================================

struct X11DynApi {
    void* libX11 = nullptr;
    void* libXi = nullptr;

    Display* (*pXOpenDisplay)(const char*) = nullptr;
    int (*pXCloseDisplay)(Display*) = nullptr;
    int (*pXPending)(Display*) = nullptr;
    int (*pXNextEvent)(Display*, XEvent*) = nullptr;
    Bool (*pXGetEventData)(Display*, XGenericEventCookie*) = nullptr;
    void (*pXFreeEventData)(Display*, XGenericEventCookie*) = nullptr;
    char* (*pXGetAtomName)(Display*, Atom) = nullptr;
    int (*pXFree)(void*) = nullptr;
    int (*pXFlush)(Display*) = nullptr;
    int (*pXSetErrorHandler)(int (*)(Display*, XErrorEvent*)) = nullptr;
    Bool (*pXQueryExtension)(Display*, const char*, int*, int*, int*) = nullptr;

    Status (*pXIQueryVersion)(Display*, int*, int*) = nullptr;
    XIDeviceInfo* (*pXIQueryDevice)(Display*, int, int*) = nullptr;
    void (*pXIFreeDeviceInfo)(XIDeviceInfo*) = nullptr;
    int (*pXISelectEvents)(Display*, Window, XIEventMask*, int) = nullptr;

    bool load() {
        libX11 = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
        libXi = dlopen("libXi.so.6", RTLD_LAZY | RTLD_LOCAL);
        if (!libX11 || !libXi) return false;

        pXOpenDisplay = reinterpret_cast<decltype(pXOpenDisplay)>(dlsym(libX11, "XOpenDisplay"));
        pXCloseDisplay = reinterpret_cast<decltype(pXCloseDisplay)>(dlsym(libX11, "XCloseDisplay"));
        pXPending = reinterpret_cast<decltype(pXPending)>(dlsym(libX11, "XPending"));
        pXNextEvent = reinterpret_cast<decltype(pXNextEvent)>(dlsym(libX11, "XNextEvent"));
        pXGetEventData = reinterpret_cast<decltype(pXGetEventData)>(dlsym(libX11, "XGetEventData"));
        pXFreeEventData = reinterpret_cast<decltype(pXFreeEventData)>(dlsym(libX11, "XFreeEventData"));
        pXGetAtomName = reinterpret_cast<decltype(pXGetAtomName)>(dlsym(libX11, "XGetAtomName"));
        pXFree = reinterpret_cast<decltype(pXFree)>(dlsym(libX11, "XFree"));
        pXFlush = reinterpret_cast<decltype(pXFlush)>(dlsym(libX11, "XFlush"));
        pXSetErrorHandler = reinterpret_cast<decltype(pXSetErrorHandler)>(dlsym(libX11, "XSetErrorHandler"));
        pXQueryExtension = reinterpret_cast<decltype(pXQueryExtension)>(dlsym(libX11, "XQueryExtension"));

        pXIQueryVersion = reinterpret_cast<decltype(pXIQueryVersion)>(dlsym(libXi, "XIQueryVersion"));
        pXIQueryDevice = reinterpret_cast<decltype(pXIQueryDevice)>(dlsym(libXi, "XIQueryDevice"));
        pXIFreeDeviceInfo = reinterpret_cast<decltype(pXIFreeDeviceInfo)>(dlsym(libXi, "XIFreeDeviceInfo"));
        pXISelectEvents = reinterpret_cast<decltype(pXISelectEvents)>(dlsym(libXi, "XISelectEvents"));

        return pXOpenDisplay && pXCloseDisplay && pXPending && pXNextEvent &&
               pXGetEventData && pXFreeEventData && pXGetAtomName && pXFree &&
               pXFlush && pXQueryExtension && pXIQueryVersion && pXIQueryDevice &&
               pXIFreeDeviceInfo && pXISelectEvents;
    }

    ~X11DynApi() {
        if (libXi) dlclose(libXi);
        if (libX11) dlclose(libX11);
    }
};

struct XInputDeviceProfile {
    int deviceId = 0;
    std::string name;
    int toolType = LINUX_TOOL_TYPE_MOUSE;
    int pressureValuator = -1;
    double pressureMin = 0.0;
    double pressureMax = 1.0;
    int tiltXValuator = -1;
    double tiltXMin = -90.0;
    double tiltXMax = 90.0;
    int tiltYValuator = -1;
    double tiltYMin = -90.0;
    double tiltYMax = 90.0;

    float lastPressure = 0.0f;
    float lastTiltX = 0.0f;
    float lastTiltY = 0.0f;
    int buttonMask = 0;
};

static void refreshXInputDevices(
    X11DynApi& api,
    Display* dpy,
    std::unordered_map<int, XInputDeviceProfile>& profiles
) {
    int nDevices = 0;
    XIDeviceInfo* devs = api.pXIQueryDevice(dpy, XIAllDevices, &nDevices);
    if (!devs) return;

    std::unordered_map<int, XInputDeviceProfile> updated;
    std::vector<std::string> detectedStylusNames;

    for (int i = 0; i < nDevices; ++i) {
        const XIDeviceInfo& d = devs[i];
        if (d.use == XIMasterKeyboard || d.use == XISlaveKeyboard) continue;

        XInputDeviceProfile prof;
        auto prevIt = profiles.find(d.deviceid);
        if (prevIt != profiles.end()) {
            prof.lastPressure = prevIt->second.lastPressure;
            prof.lastTiltX = prevIt->second.lastTiltX;
            prof.lastTiltY = prevIt->second.lastTiltY;
            prof.buttonMask = prevIt->second.buttonMask;
        }
        prof.deviceId = d.deviceid;
        prof.name = d.name ? d.name : "";
        const std::string lowerName = toLowerAscii(d.name);

        bool hasDirectTouch = false;
        for (int c = 0; c < d.num_classes; ++c) {
            XIAnyClassInfo* cls = d.classes[c];
            if (!cls) continue;
            if (cls->type == XITouchClass) {
                auto* tc = reinterpret_cast<XITouchClassInfo*>(cls);
                if (tc->mode == XIDirectTouch) {
                    hasDirectTouch = true;
                }
            } else if (cls->type == XIValuatorClass) {
                auto* vc = reinterpret_cast<XIValuatorClassInfo*>(cls);
                std::string labelLower;
                if (vc->label != None) {
                    char* atomName = api.pXGetAtomName(dpy, vc->label);
                    if (atomName) {
                        labelLower = toLowerAscii(atomName);
                        api.pXFree(atomName);
                    }
                }
                if (labelLower.find("pressure") != std::string::npos) {
                    prof.pressureValuator = vc->number;
                    prof.pressureMin = vc->min;
                    prof.pressureMax = vc->max > vc->min ? vc->max : (vc->min + 1.0);
                } else if (labelLower.find("tilt x") != std::string::npos || labelLower == "abs tilt x") {
                    prof.tiltXValuator = vc->number;
                    prof.tiltXMin = vc->min;
                    prof.tiltXMax = vc->max > vc->min ? vc->max : 90.0;
                } else if (labelLower.find("tilt y") != std::string::npos || labelLower == "abs tilt y") {
                    prof.tiltYValuator = vc->number;
                    prof.tiltYMin = vc->min;
                    prof.tiltYMax = vc->max > vc->min ? vc->max : 90.0;
                }
            }
        }

        const bool isTouchpad = containsAny(lowerName, {"touchpad", "trackpad", "synaptics", "glidepoint"});
        if (containsAny(lowerName, {"eraser", "rubber"})) {
            prof.toolType = LINUX_TOOL_TYPE_ERASER;
        } else if (containsAny(lowerName, {
                       "stylus", "pen", "wacom", "xwayland-tablet", "tablet",
                       "usi", "ipts", "huion", "xp-pen", "gaomon", "uclogic", "veikk"
                   }) && !isTouchpad && !hasDirectTouch) {
            prof.toolType = LINUX_TOOL_TYPE_STYLUS;
        } else if (hasDirectTouch || (containsAny(lowerName, {"touchscreen", "finger", "xwayland-touch"}) && !isTouchpad)) {
            prof.toolType = LINUX_TOOL_TYPE_FINGER;
        } else if (prof.pressureValuator >= 0 && !isTouchpad) {
            prof.toolType = LINUX_TOOL_TYPE_STYLUS;
        } else {
            prof.toolType = LINUX_TOOL_TYPE_MOUSE;
        }

        // Standard fallback valuator indices for Wacom / XWayland tablet devices if atoms are unnamed
        if ((prof.toolType == LINUX_TOOL_TYPE_STYLUS || prof.toolType == LINUX_TOOL_TYPE_ERASER) &&
            prof.pressureValuator < 0) {
            for (int c = 0; c < d.num_classes; ++c) {
                XIAnyClassInfo* cls = d.classes[c];
                if (cls && cls->type == XIValuatorClass) {
                    auto* vc = reinterpret_cast<XIValuatorClassInfo*>(cls);
                    if (vc->number == 2 && vc->max > vc->min) {
                        prof.pressureValuator = 2;
                        prof.pressureMin = vc->min;
                        prof.pressureMax = vc->max;
                    } else if (vc->number == 3 && prof.tiltXValuator < 0 && vc->max > vc->min) {
                        prof.tiltXValuator = 3;
                        prof.tiltXMin = vc->min;
                        prof.tiltXMax = vc->max;
                    } else if (vc->number == 4 && prof.tiltYValuator < 0 && vc->max > vc->min) {
                        prof.tiltYValuator = 4;
                        prof.tiltYMin = vc->min;
                        prof.tiltYMax = vc->max;
                    }
                }
            }
        }

        if (prof.toolType == LINUX_TOOL_TYPE_STYLUS || prof.toolType == LINUX_TOOL_TYPE_ERASER) {
            detectedStylusNames.push_back(prof.name + " (XInput2)");
        }
        updated[d.deviceid] = prof;
    }

    api.pXIFreeDeviceInfo(devs);
    profiles = std::move(updated);

    auto& st = state();
    std::lock_guard<std::mutex> lock(st.mutex);
    // Keep any evdev devices already recorded and merge XInput2 devices
    std::vector<std::string> merged;
    for (const auto& existing : st.deviceNames) {
        if (existing.find("(evdev)") != std::string::npos) {
            merged.push_back(existing);
        }
    }
    for (const auto& name : detectedStylusNames) {
        merged.push_back(name);
    }
    st.deviceNames = std::move(merged);
    st.current.detectedDeviceCount = static_cast<int>(st.deviceNames.size());
}

static void parseValuatorsIntoProfile(
    const XIValuatorState& valuators,
    XInputDeviceProfile& prof,
    float* outX,
    float* outY,
    bool* outHasXY
) {
    if (!valuators.mask || valuators.mask_len <= 0 || !valuators.values) return;
    int valueIdx = 0;
    const int maxBits = valuators.mask_len * 8;
    for (int bit = 0; bit < maxBits; ++bit) {
        if (XIMaskIsSet(valuators.mask, bit)) {
            const double v = valuators.values[valueIdx++];
            if (bit == 0 && outX) {
                *outX = static_cast<float>(v);
                if (outHasXY) *outHasXY = true;
            } else if (bit == 1 && outY) {
                *outY = static_cast<float>(v);
                if (outHasXY) *outHasXY = true;
            } else if (bit == prof.pressureValuator) {
                const double span = prof.pressureMax - prof.pressureMin;
                if (span > 1e-6) {
                    prof.lastPressure = static_cast<float>(std::clamp((v - prof.pressureMin) / span, 0.0, 1.0));
                }
            } else if (bit == prof.tiltXValuator) {
                // XInput2 / Wacom / XWayland tilt is either degrees (-60..60) or normalized; convert to radians
                if (std::abs(prof.tiltXMax) > 3.2) {
                    prof.lastTiltX = static_cast<float>(v * (3.14159265358979323846 / 180.0));
                } else {
                    prof.lastTiltX = static_cast<float>(v);
                }
            } else if (bit == prof.tiltYValuator) {
                if (std::abs(prof.tiltYMax) > 3.2) {
                    prof.lastTiltY = static_cast<float>(v * (3.14159265358979323846 / 180.0));
                } else {
                    prof.lastTiltY = static_cast<float>(v);
                }
            }
        }
    }
}

static void x11StylusThreadMain() {
    X11DynApi api;
    if (!api.load()) return;

    Display* dpy = api.pXOpenDisplay(nullptr);
    if (!dpy) return;

    int xiOpcode = 0, firstEvent = 0, firstError = 0;
    if (!api.pXQueryExtension(dpy, "XInputExtension", &xiOpcode, &firstEvent, &firstError)) {
        api.pXCloseDisplay(dpy);
        return;
    }

    int major = 2, minor = 2;
    if (api.pXIQueryVersion(dpy, &major, &minor) != Success) {
        api.pXCloseDisplay(dpy);
        return;
    }

    std::unordered_map<int, XInputDeviceProfile> profiles;
    refreshXInputDevices(api, dpy, profiles);

    // Select both standard and raw XInput2 events on the DefaultRootWindow so events are
    // received even when the Java AWT window holds an implicit button grab during a stroke.
    unsigned char maskBytes[XIMaskLen(XI_LASTEVENT)] = {0};
    XIEventMask evMask{};
    evMask.deviceid = XIAllDevices;
    evMask.mask_len = sizeof(maskBytes);
    evMask.mask = maskBytes;

    XISetMask(maskBytes, XI_HierarchyChanged);
    XISetMask(maskBytes, XI_Motion);
    XISetMask(maskBytes, XI_ButtonPress);
    XISetMask(maskBytes, XI_ButtonRelease);
    XISetMask(maskBytes, XI_TouchBegin);
    XISetMask(maskBytes, XI_TouchUpdate);
    XISetMask(maskBytes, XI_TouchEnd);

    unsigned char rawMaskBytes[XIMaskLen(XI_LASTEVENT)] = {0};
    XIEventMask rawMask{};
    rawMask.deviceid = XIAllMasterDevices;
    rawMask.mask_len = sizeof(rawMaskBytes);
    rawMask.mask = rawMaskBytes;
    XISetMask(rawMaskBytes, XI_RawMotion);
    XISetMask(rawMaskBytes, XI_RawButtonPress);
    XISetMask(rawMaskBytes, XI_RawButtonRelease);
    XISetMask(rawMaskBytes, XI_RawTouchBegin);
    XISetMask(rawMaskBytes, XI_RawTouchUpdate);
    XISetMask(rawMaskBytes, XI_RawTouchEnd);

    Window root = DefaultRootWindow(dpy);
    if (api.pXSetErrorHandler) {
        api.pXSetErrorHandler([](Display*, XErrorEvent*) -> int { return 0; });
    }
    api.pXISelectEvents(dpy, root, &evMask, 1);
    api.pXISelectEvents(dpy, root, &rawMask, 1);
    api.pXFlush(dpy);

    int x11Fd = ConnectionNumber(dpy);
    auto& st = state();

    while (st.running.load(std::memory_order_relaxed)) {
        while (api.pXPending(dpy) > 0) {
            XEvent xev;
            api.pXNextEvent(dpy, &xev);
            if (xev.xcookie.type != GenericEvent || xev.xcookie.extension != xiOpcode) {
                continue;
            }
            if (!api.pXGetEventData(dpy, &xev.xcookie)) {
                continue;
            }

            const int evType = xev.xcookie.evtype;
            if (evType == XI_HierarchyChanged) {
                refreshXInputDevices(api, dpy, profiles);
            } else if (evType == XI_Motion || evType == XI_ButtonPress || evType == XI_ButtonRelease ||
                       evType == XI_TouchBegin || evType == XI_TouchUpdate || evType == XI_TouchEnd) {
                auto* devEv = static_cast<XIDeviceEvent*>(xev.xcookie.data);
                if (devEv) {
                    auto it = profiles.find(devEv->sourceid);
                    if (it == profiles.end()) {
                        refreshXInputDevices(api, dpy, profiles);
                        it = profiles.find(devEv->sourceid);
                    }
                    if (it != profiles.end()) {
                        XInputDeviceProfile& prof = it->second;
                        parseValuatorsIntoProfile(devEv->valuators, prof, nullptr, nullptr, nullptr);

                        const bool isStylus = (prof.toolType == LINUX_TOOL_TYPE_STYLUS ||
                                               prof.toolType == LINUX_TOOL_TYPE_ERASER);
                        if (evType == XI_ButtonPress) {
                            if (devEv->detail == 1) prof.buttonMask |= LINUX_BUTTON_PRIMARY;
                            else if (devEv->detail == 2) {
                                prof.buttonMask |= isStylus ? LINUX_BUTTON_STYLUS_PRIMARY : LINUX_BUTTON_TERTIARY;
                            } else if (devEv->detail == 3) {
                                prof.buttonMask |= isStylus ? LINUX_BUTTON_STYLUS_SECONDARY : LINUX_BUTTON_SECONDARY;
                            } else if (devEv->detail == 8 || devEv->detail == 9) {
                                prof.buttonMask |= LINUX_BUTTON_STYLUS_SECONDARY;
                            }
                        } else if (evType == XI_ButtonRelease) {
                            if (devEv->detail == 1) {
                                prof.buttonMask &= ~LINUX_BUTTON_PRIMARY;
                                prof.lastPressure = 0.0f;
                            } else if (devEv->detail == 2) {
                                prof.buttonMask &= ~(LINUX_BUTTON_STYLUS_PRIMARY | LINUX_BUTTON_TERTIARY);
                            } else if (devEv->detail == 3) {
                                prof.buttonMask &= ~(LINUX_BUTTON_STYLUS_SECONDARY | LINUX_BUTTON_SECONDARY);
                            } else if (devEv->detail == 8 || devEv->detail == 9) {
                                prof.buttonMask &= ~LINUX_BUTTON_STYLUS_SECONDARY;
                            }
                        }

                        int effectiveTool = prof.toolType;
                        if (evType == XI_TouchBegin || evType == XI_TouchUpdate || evType == XI_TouchEnd) {
                            effectiveTool = LINUX_TOOL_TYPE_FINGER;
                        }
                        float pressure = prof.lastPressure;
                        if (isStylus && (prof.buttonMask & LINUX_BUTTON_PRIMARY) != 0 && pressure <= 0.001f) {
                            pressure = 0.5f;
                        }
                        recordStylusEvent(
                            effectiveTool,
                            static_cast<float>(devEv->root_x),
                            static_cast<float>(devEv->root_y),
                            pressure,
                            prof.lastTiltX,
                            prof.lastTiltY,
                            prof.buttonMask,
                            static_cast<double>(devEv->time),
                            /*hasCoords=*/true
                        );
                    }
                }
            } else if (evType == XI_RawMotion || evType == XI_RawButtonPress || evType == XI_RawButtonRelease ||
                       evType == XI_RawTouchBegin || evType == XI_RawTouchUpdate || evType == XI_RawTouchEnd) {
                auto* rawEv = static_cast<XIRawEvent*>(xev.xcookie.data);
                if (rawEv) {
                    auto it = profiles.find(rawEv->sourceid);
                    if (it == profiles.end()) {
                        refreshXInputDevices(api, dpy, profiles);
                        it = profiles.find(rawEv->sourceid);
                    }
                    if (it != profiles.end()) {
                        XInputDeviceProfile& prof = it->second;
                        float rx = 0.0f, ry = 0.0f;
                        bool hasXY = false;
                        parseValuatorsIntoProfile(rawEv->valuators, prof, &rx, &ry, &hasXY);

                        const bool isStylus = (prof.toolType == LINUX_TOOL_TYPE_STYLUS ||
                                               prof.toolType == LINUX_TOOL_TYPE_ERASER);
                        if (evType == XI_RawButtonPress) {
                            if (rawEv->detail == 1) prof.buttonMask |= LINUX_BUTTON_PRIMARY;
                            else if (rawEv->detail == 2) {
                                prof.buttonMask |= isStylus ? LINUX_BUTTON_STYLUS_PRIMARY : LINUX_BUTTON_TERTIARY;
                            } else if (rawEv->detail == 3) {
                                prof.buttonMask |= isStylus ? LINUX_BUTTON_STYLUS_SECONDARY : LINUX_BUTTON_SECONDARY;
                            } else if (rawEv->detail == 8 || rawEv->detail == 9) {
                                prof.buttonMask |= LINUX_BUTTON_STYLUS_SECONDARY;
                            }
                        } else if (evType == XI_RawButtonRelease) {
                            if (rawEv->detail == 1) {
                                prof.buttonMask &= ~LINUX_BUTTON_PRIMARY;
                                prof.lastPressure = 0.0f;
                            } else if (rawEv->detail == 2) {
                                prof.buttonMask &= ~(LINUX_BUTTON_STYLUS_PRIMARY | LINUX_BUTTON_TERTIARY);
                            } else if (rawEv->detail == 3) {
                                prof.buttonMask &= ~(LINUX_BUTTON_STYLUS_SECONDARY | LINUX_BUTTON_SECONDARY);
                            } else if (rawEv->detail == 8 || rawEv->detail == 9) {
                                prof.buttonMask &= ~LINUX_BUTTON_STYLUS_SECONDARY;
                            }
                        }

                        int effectiveTool = prof.toolType;
                        if (evType == XI_RawTouchBegin || evType == XI_RawTouchUpdate || evType == XI_RawTouchEnd) {
                            effectiveTool = LINUX_TOOL_TYPE_FINGER;
                        }
                        float pressure = prof.lastPressure;
                        if (isStylus && (prof.buttonMask & LINUX_BUTTON_PRIMARY) != 0 && pressure <= 0.001f) {
                            pressure = 0.5f;
                        }
                        recordStylusEvent(
                            effectiveTool,
                            rx,
                            ry,
                            pressure,
                            prof.lastTiltX,
                            prof.lastTiltY,
                            prof.buttonMask,
                            static_cast<double>(rawEv->time),
                            /*hasCoords=*/false
                        );
                    }
                }
            }

            api.pXFreeEventData(dpy, &xev.xcookie);
        }

        struct pollfd pfd{};
        pfd.fd = x11Fd;
        pfd.events = POLLIN;
        poll(&pfd, 1, 16);
    }

    api.pXCloseDisplay(dpy);
}

// ============================================================================
// Layer 2: Direct Linux Kernel evdev (/dev/input/event*) Stylus Reader
// Provides hardware-level Wacom EMR / USI / AES pressure, tilt, BTN_TOOL_RUBBER,
// and BTN_STYLUS barrel buttons on Wayland/X11 when user has read access.
// ============================================================================

struct EvdevStylusDevice {
    int fd = -1;
    std::string name;
    int32_t pressureMin = 0;
    int32_t pressureMax = 4096;
    int32_t tiltXMin = -60;
    int32_t tiltXMax = 60;
    int32_t tiltYMin = -60;
    int32_t tiltYMax = 60;

    int activeTool = LINUX_TOOL_TYPE_STYLUS;
    float currentPressure = 0.0f;
    float currentTiltX = 0.0f;
    float currentTiltY = 0.0f;
    int buttonMask = 0;
    bool dirty = false;
};

static bool testBit(const unsigned long* bits, int bit) {
    const int bitsPerLong = sizeof(unsigned long) * 8;
    return ((bits[bit / bitsPerLong] >> (bit % bitsPerLong)) & 1UL) != 0;
}

static void evdevStylusThreadMain() {
    std::vector<EvdevStylusDevice> devices;

    DIR* dir = opendir("/dev/input");
    if (!dir) return;

    while (struct dirent* ent = readdir(dir)) {
        if ( std::strncmp(ent->d_name, "event", 5) != 0) continue;
        std::string path = std::string("/dev/input/") + ent->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;

        unsigned long evBits[1 + EV_MAX / (sizeof(unsigned long) * 8)] = {0};
        unsigned long keyBits[1 + KEY_MAX / (sizeof(unsigned long) * 8)] = {0};
        unsigned long absBits[1 + ABS_MAX / (sizeof(unsigned long) * 8)] = {0};

        if (ioctl(fd, EVIOCGBIT(0, sizeof(evBits)), evBits) < 0 ||
            !testBit(evBits, EV_KEY) || !testBit(evBits, EV_ABS) ||
            ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBits)), keyBits) < 0 ||
            ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) {
            close(fd);
            continue;
        }

        const bool isStylusDevice = testBit(keyBits, BTN_TOOL_PEN) ||
                                    testBit(keyBits, BTN_TOOL_RUBBER) ||
                                    testBit(keyBits, BTN_STYLUS);
        if (!isStylusDevice) {
            close(fd);
            continue;
        }

        char devName[256] = "Linux Stylus";
        ioctl(fd, EVIOCGNAME(sizeof(devName) - 1), devName);

        EvdevStylusDevice dev;
        dev.fd = fd;
        dev.name = std::string(devName) + " (evdev)";

        struct input_absinfo absInfo{};
        if (testBit(absBits, ABS_PRESSURE) && ioctl(fd, EVIOCGABS(ABS_PRESSURE), &absInfo) == 0) {
            dev.pressureMin = absInfo.minimum;
            dev.pressureMax = absInfo.maximum > absInfo.minimum ? absInfo.maximum : 4096;
        }
        if (testBit(absBits, ABS_TILT_X) && ioctl(fd, EVIOCGABS(ABS_TILT_X), &absInfo) == 0) {
            dev.tiltXMin = absInfo.minimum;
            dev.tiltXMax = absInfo.maximum > absInfo.minimum ? absInfo.maximum : 60;
        }
        if (testBit(absBits, ABS_TILT_Y) && ioctl(fd, EVIOCGABS(ABS_TILT_Y), &absInfo) == 0) {
            dev.tiltYMin = absInfo.minimum;
            dev.tiltYMax = absInfo.maximum > absInfo.minimum ? absInfo.maximum : 60;
        }

        devices.push_back(dev);
    }
    closedir(dir);

    if (devices.empty()) return;

    {
        auto& st = state();
        std::lock_guard<std::mutex> lock(st.mutex);
        for (const auto& d : devices) {
            st.deviceNames.push_back(d.name);
        }
        st.current.detectedDeviceCount = static_cast<int>(st.deviceNames.size());
    }

    std::vector<struct pollfd> pfds(devices.size());
    for (size_t i = 0; i < devices.size(); ++i) {
        pfds[i].fd = devices[i].fd;
        pfds[i].events = POLLIN;
    }

    auto& st = state();
    while (st.running.load(std::memory_order_relaxed)) {
        int ready = poll(pfds.data(), pfds.size(), 25);
        if (ready <= 0) continue;

        for (size_t i = 0; i < devices.size(); ++i) {
            if (!(pfds[i].revents & POLLIN)) continue;
            EvdevStylusDevice& dev = devices[i];
            struct input_event ev;
            while (read(dev.fd, &ev, sizeof(ev)) == sizeof(ev)) {
                if (ev.type == EV_KEY) {
                    if (ev.code == BTN_TOOL_PEN && ev.value) {
                        dev.activeTool = LINUX_TOOL_TYPE_STYLUS;
                        dev.dirty = true;
                    } else if (ev.code == BTN_TOOL_RUBBER) {
                        dev.activeTool = ev.value ? LINUX_TOOL_TYPE_ERASER : LINUX_TOOL_TYPE_STYLUS;
                        dev.dirty = true;
                    } else if (ev.code == BTN_TOUCH) {
                        if (ev.value) dev.buttonMask |= LINUX_BUTTON_PRIMARY;
                        else {
                            dev.buttonMask &= ~LINUX_BUTTON_PRIMARY;
                            dev.currentPressure = 0.0f;
                        }
                        dev.dirty = true;
                    } else if (ev.code == BTN_STYLUS) {
                        if (ev.value) dev.buttonMask |= LINUX_BUTTON_STYLUS_PRIMARY;
                        else dev.buttonMask &= ~LINUX_BUTTON_STYLUS_PRIMARY;
                        dev.dirty = true;
                    } else if (ev.code == BTN_STYLUS2) {
                        if (ev.value) dev.buttonMask |= LINUX_BUTTON_STYLUS_SECONDARY;
                        else dev.buttonMask &= ~LINUX_BUTTON_STYLUS_SECONDARY;
                        dev.dirty = true;
                    }
                } else if (ev.type == EV_ABS) {
                    if (ev.code == ABS_PRESSURE) {
                        const float span = static_cast<float>(dev.pressureMax - dev.pressureMin);
                        if (span > 0.0f) {
                            dev.currentPressure = std::clamp(
                                static_cast<float>(ev.value - dev.pressureMin) / span,
                                0.0f,
                                1.0f
                            );
                            dev.dirty = true;
                        }
                    } else if (ev.code == ABS_TILT_X) {
                        dev.currentTiltX = static_cast<float>(ev.value * (3.14159265358979323846 / 180.0));
                        dev.dirty = true;
                    } else if (ev.code == ABS_TILT_Y) {
                        dev.currentTiltY = static_cast<float>(ev.value * (3.14159265358979323846 / 180.0));
                        dev.dirty = true;
                    }
                } else if (ev.type == EV_SYN && ev.code == SYN_REPORT && dev.dirty) {
                    dev.dirty = false;
                    const double ts = static_cast<double>(ev.time.tv_sec) * 1000.0 +
                                      static_cast<double>(ev.time.tv_usec) / 1000.0;
                    recordStylusEvent(
                        dev.activeTool,
                        0.0f,
                        0.0f,
                        dev.currentPressure,
                        dev.currentTiltX,
                        dev.currentTiltY,
                        dev.buttonMask,
                        ts,
                        /*hasCoords=*/false
                    );
                }
            }
        }
    }

    for (auto& d : devices) {
        if (d.fd >= 0) close(d.fd);
    }
}

#endif // defined(__linux__) && !defined(__ANDROID__)

} // namespace

LinuxStylusInputManager& LinuxStylusInputManager::instance() {
    static LinuxStylusInputManager mgr;
    return mgr;
}

LinuxStylusInputManager::~LinuxStylusInputManager() {
    stop();
}

bool LinuxStylusInputManager::start() {
#if defined(__linux__) && !defined(__ANDROID__)
    auto& st = state();
    bool expected = false;
    if (!st.running.compare_exchange_strong(expected, true)) {
        return true; // already running
    }
    st.x11Thread = std::thread(x11StylusThreadMain);
    st.evdevThread = std::thread(evdevStylusThreadMain);
    return true;
#else
    return false;
#endif
}

void LinuxStylusInputManager::stop() {
#if defined(__linux__) && !defined(__ANDROID__)
    auto& st = state();
    bool expected = true;
    if (!st.running.compare_exchange_strong(expected, false)) {
        return;
    }
    if (st.x11Thread.joinable()) st.x11Thread.join();
    if (st.evdevThread.joinable()) st.evdevThread.join();
#endif
}

int LinuxStylusInputManager::pollState(
    LinuxStylusSnapshot* outSnapshot,
    LinuxStylusSample* outHistory,
    int maxHistory
) {
    auto& st = state();
    std::lock_guard<std::mutex> lock(st.mutex);
    if (outSnapshot) {
        *outSnapshot = st.current;
    }
    int count = 0;
    if (outHistory && maxHistory > 0 && !st.historyRing.empty()) {
        count = std::min(static_cast<int>(st.historyRing.size()), maxHistory);
        const size_t startIdx = st.historyRing.size() - static_cast<size_t>(count);
        for (int i = 0; i < count; ++i) {
            outHistory[i] = st.historyRing[startIdx + static_cast<size_t>(i)];
        }
        st.historyRing.clear();
    }
    return count;
}

std::vector<std::string> LinuxStylusInputManager::getDetectedDevices() const {
    auto& st = state();
    std::lock_guard<std::mutex> lock(st.mutex);
    return st.deviceNames;
}

void LinuxStylusInputManager::injectSample(
    int toolType,
    float x,
    float y,
    float pressure,
    float tiltX,
    float tiltY,
    int buttonMask,
    double timestampMs
) {
    recordStylusEvent(toolType, x, y, pressure, tiltX, tiltY, buttonMask, timestampMs, /*hasCoords=*/true);
}

} // namespace orgwritenotes

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_LinuxStylusBridge_nativeInit(JNIEnv*, jobject) {
    return orgwritenotes::LinuxStylusInputManager::instance().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_LinuxStylusBridge_nativeShutdown(JNIEnv*, jobject) {
    orgwritenotes::LinuxStylusInputManager::instance().stop();
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_LinuxStylusBridge_nativePollStylusState(
    JNIEnv* env,
    jobject,
    jfloatArray outState8,
    jfloatArray outHistoryFlat6
) {
    orgwritenotes::LinuxStylusSnapshot snap{};
    constexpr int MAX_HIST = 64;
    orgwritenotes::LinuxStylusSample hist[MAX_HIST];
    int maxReq = 0;
    if (outHistoryFlat6) {
        maxReq = std::min<int>(MAX_HIST, env->GetArrayLength(outHistoryFlat6) / 6);
    }
    int histCount = orgwritenotes::LinuxStylusInputManager::instance().pollState(&snap, hist, maxReq);

    if (outState8 && env->GetArrayLength(outState8) >= 8) {
        jfloat s[8] = {
            static_cast<jfloat>(snap.toolType),
            snap.pressure,
            snap.tiltRad,
            snap.tiltX,
            snap.tiltY,
            static_cast<jfloat>(snap.buttonMask),
            static_cast<jfloat>(snap.lastStylusTimestampMs),
            static_cast<jfloat>(snap.detectedDeviceCount)
        };
        env->SetFloatArrayRegion(outState8, 0, 8, s);
    }

    if (histCount > 0 && outHistoryFlat6) {
        jfloat flat[MAX_HIST * 6];
        for (int i = 0; i < histCount; ++i) {
            flat[i * 6 + 0] = hist[i].x;
            flat[i * 6 + 1] = hist[i].y;
            flat[i * 6 + 2] = hist[i].pressure;
            flat[i * 6 + 3] = hist[i].tiltX;
            flat[i * 6 + 4] = hist[i].tiltY;
            flat[i * 6 + 5] = static_cast<jfloat>(hist[i].timestampMs);
        }
        env->SetFloatArrayRegion(outHistoryFlat6, 0, histCount * 6, flat);
    }
    return histCount;
}

JNIEXPORT jobjectArray JNICALL
Java_com_orgwritenotes_ink_LinuxStylusBridge_nativeGetDetectedDevices(JNIEnv* env, jobject) {
    auto names = orgwritenotes::LinuxStylusInputManager::instance().getDetectedDevices();
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strCls, env->NewStringUTF(""));
    if (!arr) return nullptr;
    for (size_t i = 0; i < names.size(); ++i) {
        jstring s = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_LinuxStylusBridge_nativeInjectTestEvent(
    JNIEnv*,
    jobject,
    jint toolType,
    jfloat x,
    jfloat y,
    jfloat pressure,
    jfloat tiltX,
    jfloat tiltY,
    jint buttonMask,
    jdouble timestampMs
) {
    orgwritenotes::LinuxStylusInputManager::instance().injectSample(
        toolType, x, y, pressure, tiltX, tiltY, buttonMask, timestampMs
    );
}

} // extern "C"
