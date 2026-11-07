#!/bin/sh
# KPM uninstall.sh hook for Org-WriteNotes on Kindle Scribe

APP_DIR="/mnt/us/org-writenotes"
DOCS_DIR="/mnt/us/documents"

rm -f "$DOCS_DIR/Org-WriteNotes.sh" "$DOCS_DIR/Org-WriteNotes-Sync.sh"
rm -rf "$APP_DIR/bin"

# Only remove user config if not running as part of a KPM upgrade
if [ "${1:-}" != "upgrade" ]; then
    echo "[KPM] Removed Org-WriteNotes binary and Home Library Scriptlets (sketches in $APP_DIR/sketches preserved)."
fi
