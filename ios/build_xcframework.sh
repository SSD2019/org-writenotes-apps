#!/usr/bin/env bash
# Builds OrgWriteNotesEngine.xcframework (iPadOS arm64 + Simulator arm64/x86_64)
# from the shared C++ Stylus Labs Write engine (`orgwritenotes_c_static`).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CPP_DIR="${REPO_ROOT}/android/app/src/main/cpp"
BUILD_ROOT="${SCRIPT_DIR}/build/xcframework"

mkdir -p "${BUILD_ROOT}"

echo "[1/3] Building C++ Write engine for iPadOS Device (arm64)..."
cmake -S "${CPP_DIR}" -B "${BUILD_ROOT}/ios-device" \
    -G "Unix Makefiles" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphoneos \
    -DCMAKE_OSX_ARCHITECTURES="arm64" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="16.0" \
    -DORGWRITENOTES_NO_JNI=ON
cmake --build "${BUILD_ROOT}/ios-device" --target orgwritenotes_c_static -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

echo "[2/3] Building C++ Write engine for iPadOS Simulator (arm64;x86_64)..."
cmake -S "${CPP_DIR}" -B "${BUILD_ROOT}/ios-sim" \
    -G "Unix Makefiles" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="16.0" \
    -DORGWRITENOTES_NO_JNI=ON
cmake --build "${BUILD_ROOT}/ios-sim" --target orgwritenotes_c_static -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

echo "[3/3] Packaging OrgWriteNotesEngine.xcframework..."
rm -rf "${BUILD_ROOT}/OrgWriteNotesEngine.xcframework"
xcodebuild -create-xcframework \
    -library "${BUILD_ROOT}/ios-device/liborgwritenotes_c_static.a" \
    -headers "${SCRIPT_DIR}/Sources/WriteEngineC/include" \
    -library "${BUILD_ROOT}/ios-sim/liborgwritenotes_c_static.a" \
    -headers "${SCRIPT_DIR}/Sources/WriteEngineC/include" \
    -output "${BUILD_ROOT}/OrgWriteNotesEngine.xcframework"

echo "Done: ${BUILD_ROOT}/OrgWriteNotesEngine.xcframework"
