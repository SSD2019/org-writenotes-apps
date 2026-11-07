#!/usr/bin/env bash
# build_kual.sh — Builds, tests, and packages the Org-WriteNotes KUAL extension
# for Jailbroken Kindle Scribe (/mnt/us/extensions/org-writenotes)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOST_BUILD_DIR="$SCRIPT_DIR/build-host"
ARM_BUILD_DIR="$SCRIPT_DIR/build-armhf"
DIST_DIR="$SCRIPT_DIR/dist"
STAGE_DIR="$DIST_DIR/org-writenotes"

if command -v cmake >/dev/null 2>&1; then
    CMAKE_BIN="$(command -v cmake)"
    CTEST_BIN="$(command -v ctest)"
elif [[ -x "/home/saisan/.android-sdk/cmake/3.22.1/bin/cmake" ]]; then
    CMAKE_BIN="/home/saisan/.android-sdk/cmake/3.22.1/bin/cmake"
    CTEST_BIN="/home/saisan/.android-sdk/cmake/3.22.1/bin/ctest"
else
    echo "Error: cmake not found in PATH or ~/.android-sdk" >&2
    exit 1
fi

echo "=== [1/3] Building & Testing Kindle Scribe Engine on Host ==="
mkdir -p "$HOST_BUILD_DIR"
"$CMAKE_BIN" -S "$SCRIPT_DIR" -B "$HOST_BUILD_DIR" -DKINDLE_BUILD_TESTS=ON
"$CMAKE_BIN" --build "$HOST_BUILD_DIR" -j"$(nproc 2>/dev/null || echo 4)"
"$CTEST_BIN" --test-dir "$HOST_BUILD_DIR" --output-on-failure

TARGET_BIN="$HOST_BUILD_DIR/org-writenotes-kindle"

echo "=== [2/3] Cross-Compiling ARM Binary for Kindle Scribe ==="
if command -v arm-linux-gnueabihf-g++ >/dev/null 2>&1; then
    echo "Found GNU arm-linux-gnueabihf toolchain"
    rm -rf "$ARM_BUILD_DIR"
    mkdir -p "$ARM_BUILD_DIR"
    "$CMAKE_BIN" -S "$SCRIPT_DIR" -B "$ARM_BUILD_DIR" \
        -DCMAKE_SYSTEM_NAME=Linux \
        -DCMAKE_SYSTEM_PROCESSOR=arm \
        -DCMAKE_C_COMPILER=arm-linux-gnueabihf-gcc \
        -DCMAKE_CXX_COMPILER=arm-linux-gnueabihf-g++ \
        -DKINDLE_STATIC_LINK=ON \
        -DKINDLE_BUILD_TESTS=OFF
    "$CMAKE_BIN" --build "$ARM_BUILD_DIR" -j"$(nproc 2>/dev/null || echo 4)"
    arm-linux-gnueabihf-strip "$ARM_BUILD_DIR/org-writenotes-kindle" || true
    TARGET_BIN="$ARM_BUILD_DIR/org-writenotes-kindle"
elif [[ -x "/home/saisan/.android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang++" ]]; then
    NDK_BIN="/home/saisan/.android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin"
    echo "Using static ARMv7 LLVM toolchain from Android NDK"
    rm -rf "$ARM_BUILD_DIR"
    mkdir -p "$ARM_BUILD_DIR"
    "$CMAKE_BIN" -S "$SCRIPT_DIR" -B "$ARM_BUILD_DIR" \
        -DCMAKE_SYSTEM_NAME=Linux \
        -DCMAKE_SYSTEM_PROCESSOR=armv7-a \
        -DCMAKE_C_COMPILER="$NDK_BIN/armv7a-linux-androideabi21-clang" \
        -DCMAKE_CXX_COMPILER="$NDK_BIN/armv7a-linux-androideabi21-clang++" \
        -DCMAKE_EXE_LINKER_FLAGS="-static" \
        -DKINDLE_BUILD_TESTS=OFF
    "$CMAKE_BIN" --build "$ARM_BUILD_DIR" -j"$(nproc 2>/dev/null || echo 4)"
    "$NDK_BIN/llvm-strip" "$ARM_BUILD_DIR/org-writenotes-kindle" || true
    TARGET_BIN="$ARM_BUILD_DIR/org-writenotes-kindle"
else
    echo "Note: No ARM cross-compiler detected; packaging host binary for testing."
fi

echo "=== [3/3] Packaging KPM (.kpkg) & SH_Integration Scriptlet Bundle ==="
rm -rf "$DIST_DIR"
KPM_STAGE="$DIST_DIR/kpm-pkg"
mkdir -p "$KPM_STAGE/bin" "$STAGE_DIR/bin" "$DIST_DIR/documents"

# 1. Stage KPM package (.kpkg) + sh_integration Home Library Scriptlets
cp "$SCRIPT_DIR/kpm/manifest.json" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kpm/install.sh" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kpm/uninstall.sh" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kpm/launch.sh" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kpm/Org-WriteNotes.sh" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kpm/Org-WriteNotes-Sync.sh" "$KPM_STAGE/"
cp "$SCRIPT_DIR/kual/config.json" "$KPM_STAGE/"
cp "$TARGET_BIN" "$KPM_STAGE/bin/org-writenotes-kindle"
chmod +x "$KPM_STAGE/install.sh" "$KPM_STAGE/uninstall.sh" "$KPM_STAGE/launch.sh" \
         "$KPM_STAGE/Org-WriteNotes.sh" "$KPM_STAGE/Org-WriteNotes-Sync.sh" \
         "$KPM_STAGE/bin/org-writenotes-kindle"

tar -czf "$DIST_DIR/org-writenotes.kpkg" -C "$KPM_STAGE" .

# 2. Stage direct /mnt/us layout (org-writenotes/ + documents/Org-WriteNotes*.sh)
cp "$SCRIPT_DIR/kpm/Org-WriteNotes.sh" "$DIST_DIR/documents/Org-WriteNotes.sh"
cp "$SCRIPT_DIR/kpm/Org-WriteNotes-Sync.sh" "$DIST_DIR/documents/Org-WriteNotes-Sync.sh"
chmod +x "$DIST_DIR/documents/Org-WriteNotes.sh" "$DIST_DIR/documents/Org-WriteNotes-Sync.sh"

cp "$SCRIPT_DIR/kual/config.xml" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kual/menu.json" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kual/config.json" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kual/run.sh" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kpm/install.sh" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kpm/uninstall.sh" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kpm/launch.sh" "$STAGE_DIR/"
cp "$SCRIPT_DIR/kpm/manifest.json" "$STAGE_DIR/"
chmod +x "$STAGE_DIR/run.sh" "$STAGE_DIR/install.sh" "$STAGE_DIR/uninstall.sh" "$STAGE_DIR/launch.sh"

cp "$TARGET_BIN" "$STAGE_DIR/bin/org-writenotes-kindle"
chmod +x "$STAGE_DIR/bin/org-writenotes-kindle"

tar -czvf "$DIST_DIR/org-writenotes-kindle-scribe.tar.gz" -C "$DIST_DIR" org-writenotes documents
rm -rf "$KPM_STAGE"
echo "=== Packaged KPM Package: $DIST_DIR/org-writenotes.kpkg ==="
echo "=== Packaged Scribe Tarball: $DIST_DIR/org-writenotes-kindle-scribe.tar.gz ==="
file "$STAGE_DIR/bin/org-writenotes-kindle" || true
ls -lh "$DIST_DIR/org-writenotes.kpkg" "$DIST_DIR/org-writenotes-kindle-scribe.tar.gz"

