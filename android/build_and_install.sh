#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/saisan/.vim/emacs_plugin/org-writenotes"
SDK_DIR="/home/saisan/.android-sdk"
ANDROID_DIR="$ROOT_DIR/android"

echo "sdk.dir=$SDK_DIR" > "$ANDROID_DIR/local.properties"

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export GRADLE_USER_HOME="/home/saisan/.gradle"
export JAVA_HOME="$SDK_DIR/jdk-17"
export PATH="$JAVA_HOME/bin:$SDK_DIR/gradle-8.6/bin:$SDK_DIR/platform-tools:$PATH"

cd "$ANDROID_DIR"
echo "=== [1/2] Building APK & C++ JNI Library (liborgwritenotes_jni.so) ==="
gradle assembleDebug --no-daemon --console=plain --project-cache-dir /home/saisan/.gradle/org-writenotes-project-cache

APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
ls -lh "$APK_PATH"

echo "=== [2/2] Installing APK onto connected Android tablet ==="
ADB="$SDK_DIR/platform-tools/adb"
"$ADB" devices -l
if "$ADB" get-state >/dev/null 2>&1; then
    "$ADB" install -r "$APK_PATH"
    "$ADB" reverse tcp:9876 tcp:9876
    "$ADB" shell am start -n com.orgwritenotes/.MainActivity
    echo "SUCCESS: Installed and launched Org-WriteNotes on device, with adb reverse tcp:9876 tcp:9876 active!"
else
    echo "WARNING: No online ADB device currently detected. Connect tablet via USB and re-run."
fi
