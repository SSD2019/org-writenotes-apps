#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-/home/saisan/.android-sdk}"
ANDROID_DIR="$SCRIPT_DIR"

echo "sdk.dir=$SDK_DIR" > "$ANDROID_DIR/local.properties"

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export GRADLE_USER_HOME="/home/saisan/.gradle"
export JAVA_HOME="$SDK_DIR/jdk-17"
export PATH="$JAVA_HOME/bin:$SDK_DIR/gradle-8.6/bin:$SDK_DIR/platform-tools:$PATH"

cd "$ANDROID_DIR"
echo "=== [1/2] Building APK & C++ JNI Library (liborgwritenotes_jni.so) ==="
gradle assembleRelease -Pkotlin.compiler.execution.strategy=in-process --no-daemon --console=plain --project-cache-dir /home/saisan/.gradle/org-writenotes-project-cache

APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
ls -lh "$APK_PATH"

echo "=== [2/2] Installing APK onto connected Android tablet ==="
ADB="$SDK_DIR/platform-tools/adb"
"$ADB" devices -l
if "$ADB" get-state >/dev/null 2>&1; then
    "$ADB" install -r -d "$APK_PATH"
    "$ADB" reverse tcp:9876 tcp:9876
    "$ADB" shell am start -n com.orgwritenotes/.MainActivity
    echo "SUCCESS: Installed and launched Org-WriteNotes on device, with adb reverse tcp:9876 tcp:9876 active!"
else
    echo "WARNING: No online ADB device currently detected. Connect tablet via USB and re-run."
fi

echo "=== [3/3] Removing intermediate build files (keeping outputs/apk) ==="
BUILD_DIR="$ANDROID_DIR/app/build"
find "$BUILD_DIR" -mindepth 1 -maxdepth 1 ! -name outputs -exec rm -rf {} +
find "$BUILD_DIR/outputs" -mindepth 1 -maxdepth 1 ! -name apk -exec rm -rf {} +
du -sh "$BUILD_DIR"
