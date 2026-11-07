#!/bin/sh
# Name: Org-WriteNotes Sync Queue
# Author: Org-WriteNotes

# Kindle Scriptlet (sh_integration) — intentionally omits # DontUseFBInk
# so sh_integration prints the live sync status directly onto the E-Ink screen via FBInk.

APP_DIR="/mnt/us/org-writenotes"
BIN="$APP_DIR/bin/org-writenotes-kindle"
CONFIG="$APP_DIR/config.json"

"$BIN" --config "$CONFIG" --queue "$APP_DIR/queue" --sync-now

