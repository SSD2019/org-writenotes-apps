#!/bin/sh
# Name: Org-WriteNotes
# Author: Org-WriteNotes
# Icon:

# Kindle Scriptlet (sh_integration) for Kindle Scribe (5.20.x+)
# Placed in: /mnt/us/documents/Org-WriteNotes.sh
# Appears directly in your Kindle Home Library as "Org-WriteNotes".
# Note: Intentionally omits "# DontUseFBInk" (matching jobman.sh) so sh_integration_launcher
# initializes /dev/fb0 cleanly before the detached child takes over.

# Detach from sh_integration_launcher so the Home screen doesn't redraw over our canvas
if [ "$1" != "--detached" ]; then
    if command -v setsid > /dev/null 2>&1; then
        setsid sh /mnt/us/documents/Org-WriteNotes.sh --detached > /dev/null 2>&1 < /dev/null &
    else
        nohup sh /mnt/us/documents/Org-WriteNotes.sh --detached > /dev/null 2>&1 < /dev/null &
    fi
    exit 0
fi

sleep 2

APP_DIR="/mnt/us/org-writenotes"
BIN="$APP_DIR/bin/org-writenotes-kindle"
CONFIG="$APP_DIR/config.json"
LOG="$APP_DIR/scribe.log"

mkdir -p "$APP_DIR/queue" "$APP_DIR/sketches"

# If Jobman UI is still open (e.g. when using Jobman Wi-Fi SSH where usbConfigured never fires),
# stop the jobman.sh wrapper first (so it doesn't restore booklet.home) and then stop the jobman UI binary,
# while leaving dropbearmulti (Wi-Fi/USB SSH daemon) untouched.
pkill -f "jobman.sh --detached" >/dev/null 2>&1 || true
killall jobman >/dev/null 2>&1 || true
killall org-writenotes-kindle >/dev/null 2>&1 || true

# Hide statusbar and park appmgrd in a blank webview so touches don't trigger Home Library books
stop statusbar >/dev/null 2>&1 || true
if command -v lipc-set-prop >/dev/null 2>&1; then
    if [ "$(lipc-get-prop com.lab126.powerd state 2>/dev/null)" != "active" ]; then
        lipc-set-prop com.lab126.powerd powerButton 1 >/dev/null 2>&1 || true
        sleep 2
    fi
    lipc-set-prop com.lab126.powerd preventScreenSaver 1 >/dev/null 2>&1 || true
    lipc-set-prop com.lab126.cmd ensureConnection "wifi" >/dev/null 2>&1 || true
    if [ "$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)" != "com.lab126.webview" ]; then
        lipc-set-prop com.lab126.appmgrd start app://com.lab126.webview/?url=x >/dev/null 2>&1 || true
        for i in $(seq 1 15); do
            [ "$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)" = "com.lab126.webview" ] && break
            sleep 1
        done
        sleep 2
    fi
fi

# Pause Kindle Scribe WetInkHandler so it does not clear /dev/fb0 on pen-up
killall -STOP WetInkHandler >/dev/null 2>&1 || true

"$BIN" \
    --config "$CONFIG" \
    --queue "$APP_DIR/queue" \
    --sketch "$APP_DIR/sketches/scribe-note.svgz" >>"$LOG" 2>&1

# Resume Kindle Scribe WetInkHandler and restore Home screen and statusbar on exit
killall -CONT WetInkHandler >/dev/null 2>&1 || true
if command -v lipc-set-prop >/dev/null 2>&1; then
    lipc-set-prop com.lab126.powerd preventScreenSaver 0 >/dev/null 2>&1 || true
    lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.home >/dev/null 2>&1 || true
fi
start statusbar >/dev/null 2>&1 || true
xrefresh >/dev/null 2>&1 || true
