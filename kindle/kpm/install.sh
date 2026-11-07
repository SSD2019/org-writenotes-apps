#!/bin/sh
# KPM install.sh hook for Org-WriteNotes on Kindle Scribe
set -e

PKG_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
APP_DIR="/mnt/us/org-writenotes"
DOCS_DIR="/mnt/us/documents"

mkdir -p "$APP_DIR/bin" "$APP_DIR/queue" "$APP_DIR/sketches" "$DOCS_DIR"

cp "$PKG_DIR/bin/org-writenotes-kindle" "$APP_DIR/bin/org-writenotes-kindle"
chmod +x "$APP_DIR/bin/org-writenotes-kindle"

# Preserve existing user config.json across package upgrades
if [ ! -f "$APP_DIR/config.json" ]; then
    cp "$PKG_DIR/config.json" "$APP_DIR/config.json"
fi

# Install sh_integration Scriptlets into /mnt/us/documents so they appear as books in the Kindle Library
cp "$PKG_DIR/Org-WriteNotes.sh" "$DOCS_DIR/Org-WriteNotes.sh"
cp "$PKG_DIR/Org-WriteNotes-Sync.sh" "$DOCS_DIR/Org-WriteNotes-Sync.sh"
chmod +x "$DOCS_DIR/Org-WriteNotes.sh" "$DOCS_DIR/Org-WriteNotes-Sync.sh"

echo "[KPM] Installed Org-WriteNotes to $APP_DIR and Scriptlets to $DOCS_DIR/"
