#!/usr/bin/env bash
# build_ipa.sh — Compiles and packages Org-WriteNotes into an iPadOS .ipa archive
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================================="
echo " Org-WriteNotes iPadOS Compilation & IPA Packaging Script"
echo "=========================================================="

BUILD_DIR="${SCRIPT_DIR}/build"
DERIVED_DATA="${BUILD_DIR}/DerivedData"
IPA_STAGE="${BUILD_DIR}/ipa_stage"
PAYLOAD_DIR="${IPA_STAGE}/Payload"
OUTPUT_IPA="${SCRIPT_DIR}/OrgWriteNotes-iPadOS.ipa"

rm -rf "${BUILD_DIR}" "${OUTPUT_IPA}"
mkdir -p "${BUILD_DIR}" "${DERIVED_DATA}"

# Step 1: Ensure project generation tool (XcodeGen) is available
USE_XCODEGEN=0
if command -v xcodegen &> /dev/null; then
    USE_XCODEGEN=1
elif command -v brew &> /dev/null; then
    echo "[1/4] Installing xcodegen via Homebrew..."
    brew install xcodegen || true
    if command -v xcodegen &> /dev/null; then
        USE_XCODEGEN=1
    fi
fi

APP_BUNDLE=""

if [[ "$USE_XCODEGEN" -eq 1 ]]; then
    echo "[1/4] Generating Xcode project via XcodeGen..."
    xcodegen generate

    echo "[2/4] Building iPadOS Application with xcodebuild..."
    xcodebuild clean build \
        -project OrgWriteNotes.xcodeproj \
        -scheme OrgWriteNotes \
        -configuration Release \
        -sdk iphoneos \
        -destination "generic/platform=iOS" \
        -derivedDataPath "${DERIVED_DATA}" \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" \
        AD_HOC_CODE_SIGNING_ALLOWED=YES

    # Locate generated .app bundle
    APP_BUNDLE="$(find "${DERIVED_DATA}" -name "OrgWriteNotes.app" -type d | head -n 1 || true)"
fi

# Fallback: direct Swift PM / xcodebuild compilation if .app was not generated
if [[ -z "${APP_BUNDLE}" || ! -d "${APP_BUNDLE}" ]]; then
    echo "[2/4] (Fallback) Compiling via xcodebuild Swift package scheme..."
    xcodebuild clean build \
        -scheme OrgWriteNotesApp \
        -configuration Release \
        -sdk iphoneos \
        -destination "generic/platform=iOS" \
        -derivedDataPath "${DERIVED_DATA}" \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" \
        AD_HOC_CODE_SIGNING_ALLOWED=YES || true

    APP_BUNDLE="$(find "${DERIVED_DATA}" -name "OrgWriteNotes.app" -type d | head -n 1 || true)"

    if [[ -z "${APP_BUNDLE}" || ! -d "${APP_BUNDLE}" ]]; then
        # Find raw executable produced by SwiftPM
        EXEC_BIN="$(find "${DERIVED_DATA}" -name "OrgWriteNotesApp" -type f -perm +111 2>/dev/null | head -n 1 || true)"
        if [[ -z "${EXEC_BIN}" ]]; then
            EXEC_BIN="$(find "${DERIVED_DATA}" -name "OrgWriteNotes" -type f -perm +111 2>/dev/null | head -n 1 || true)"
        fi

        if [[ -n "${EXEC_BIN}" && -f "${EXEC_BIN}" ]]; then
            echo "Creating .app bundle wrapper for executable: ${EXEC_BIN}"
            SYNTH_APP="${BUILD_DIR}/OrgWriteNotes.app"
            mkdir -p "${SYNTH_APP}"
            cp "${EXEC_BIN}" "${SYNTH_APP}/OrgWriteNotes"
            cp "${SCRIPT_DIR}/Info.plist" "${SYNTH_APP}/Info.plist"
            echo "APPL????" > "${SYNTH_APP}/PkgInfo"
            APP_BUNDLE="${SYNTH_APP}"
        fi
    fi
fi

if [[ -z "${APP_BUNDLE}" || ! -d "${APP_BUNDLE}" ]]; then
    echo "ERROR: Failed to locate or create OrgWriteNotes.app bundle!" >&2
    exit 1
fi

echo "[3/4] Found application bundle: ${APP_BUNDLE}"

echo "[4/4] Assembling IPA Payload archive..."
mkdir -p "${PAYLOAD_DIR}"
cp -R "${APP_BUNDLE}" "${PAYLOAD_DIR}/OrgWriteNotes.app"

# Ensure Info.plist is present in the bundle
if [[ ! -f "${PAYLOAD_DIR}/OrgWriteNotes.app/Info.plist" ]]; then
    cp "${SCRIPT_DIR}/Info.plist" "${PAYLOAD_DIR}/OrgWriteNotes.app/Info.plist"
fi

# Ensure executable permissions
chmod +x "${PAYLOAD_DIR}/OrgWriteNotes.app/OrgWriteNotes"* 2>/dev/null || true

# Apply ad-hoc code signature if codesign is available
if command -v codesign &> /dev/null; then
    echo "Applying ad-hoc code signature for sideloading/AltStore/TrollStore..."
    codesign --force --deep --sign - "${PAYLOAD_DIR}/OrgWriteNotes.app" || true
fi

# Package Payload folder into .ipa zip
cd "${IPA_STAGE}"
zip -qry "${OUTPUT_IPA}" Payload
cd "${SCRIPT_DIR}"

if [[ -f "${OUTPUT_IPA}" ]]; then
    echo "=========================================================="
    echo " SUCCESS: iPadOS IPA created successfully!"
    echo " Path: ${OUTPUT_IPA}"
    echo " Size: $(ls -lh "${OUTPUT_IPA}" | awk '{print $5}')"
    echo "=========================================================="
    unzip -l "${OUTPUT_IPA}" | head -n 20
else
    echo "ERROR: Output IPA file not found at ${OUTPUT_IPA}" >&2
    exit 1
fi
