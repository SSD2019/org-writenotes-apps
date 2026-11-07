# Org-WriteNotes Companion Apps

This repository contains the multi-platform client applications for **Org-WriteNotes**, connecting to the GNU Emacs server plugin to edit Org-mode documents with embedded, dynamically expanding handwritten ink canvases.

Core vector ink rendering and manipulation across all clients is powered by the high-performance **[Stylus Labs Write](https://github.com/styluslabs/Write)** C++ engine.

---

## Client Applications

### 1. Android Tablet & E-Ink App (`android/`)
- **Technology:** Kotlin, Jetpack Compose, Android NDK, C++ JNI (`liborgwritenotes_jni.so`).
- **Features:**
  - Ultra-low latency S-Pen / USI stylus pressure & tilt vector drawing.
  - Native support for Onyx Boox / E-Ink refresh and pen stroke bypass.
  - 5-tab navigation rail (History, Outline, Agenda, Search, Presence).
  - Offline sync queue with automatic reconnection and replay.
  - Camera QR code scanning for zero-config pairing (`org-writenotes-show-qr`).
- **Building:**
  ```bash
  cd android
  ./gradlew :app:assembleDebug
  # Or run build & install helper:
  ./build_and_install.sh
  ```

### 2. Linux Desktop Companion (`linux/` & `android/desktop/`)
- **Technology:** Kotlin Compose Desktop + C++ JNI (`liborgwritenotes_jni.so`) with raw XInput2 / evdev stylus event fusion.
- **Features:**
  - Full companion mode with Outline & Agenda tree (`./linux/org-writenotes-linux`).
  - Single-sketch popup mode for quick Org note diagram editing (`--sketch <path>`).
  - Pressure & tilt support for Wacom EMR / XP-Pen / Huion graphic tablets under Linux (X11 & Wayland).
- **Running:**
  ```bash
  ./linux/org-writenotes-linux
  ```

### 3. iOS & iPadOS App (`ios/`)
- **Technology:** Swift 5.9, SwiftUI, Swift Package Manager, XCFramework (`WriteEngineC`).
- **Features:**
  - Apple Pencil (hover, pressure, tilt) and PencilKit integration.
  - Custom C API bridge to Stylus Labs Write vector engine.
  - Deep-link pairing via `orgwritenotes://connect?host=...&port=...&token=...`.
- **Building & Packaging:**
  ```bash
  cd ios
  # To run unit tests:
  swift test

  # To build and package iPadOS .ipa (ready for AltStore / TrollStore / Sideloadly):
  ./build_ipa.sh
  ```

---

## CI/CD & Automated Releases

The multi-platform GitHub Actions workflow (`.github/workflows/ci.yml`) compiles, tests, and packages all companion clients across macOS and Linux:

| Platform | Format / Artifact | Build Runner |
| :--- | :--- | :--- |
| **Android Tablet / E-Ink** | `org-writenotes-android.apk`, `app-debug.apk` | `ubuntu-latest` |
| **Linux Desktop (X11/Wayland)** | `org-writenotes_*.deb`, `org-writenotes-linux-x86_64.tar.gz` | `ubuntu-latest` |
| **iPadOS** | `OrgWriteNotes-iPadOS.ipa` | `macos-15` |

- **CI Artifacts:** Available on every push and PR to `main` under GitHub Actions run artifacts.
- **GitHub Releases:** Tagging a release (e.g. `git tag v0.1.0 && git push --tags`) or publishing a GitHub Release automatically packages and attaches all three platform binaries (`.ipa`, `.apk`, `.deb`, `.tar.gz`) to the release.


---

## Shared Protocol & Specs
- Protocol Specification: [docs/protocol-spec.md](docs/protocol-spec.md)
- Shared Protocol Test Fixture: [test-fixtures/protocol-golden.json](test-fixtures/protocol-golden.json)
- Extended Roadmap: [docs/roadmap-extended.md](docs/roadmap-extended.md)

---

## Emacs Server Plugin
The Emacs server-side package (`org-writenotes.el`, `org-writenotes-extras.el`, TrOCR handwriting recognition) is maintained in its dedicated repository:
- Emacs Plugin Repository: [**SSD2019/org-writenotes**](https://github.com/SSD2019/org-writenotes)
