#!/bin/sh
# Name: Org-WriteNotes
# Author: Org-WriteNotes
# DontUseFBInk

# Kindle Scriptlet (sh_integration) for Kindle Scribe (5.20.x+)
# Placed in: /mnt/us/documents/Org-WriteNotes.sh
# Appears directly in your Kindle Home Library as "Org-WriteNotes".

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

# Hide statusbar and park appmgrd in a blank webview so touches don't trigger Home Library books
stop statusbar >/dev/null 2>&1 || true
if command -v lipc-set-prop >/dev/null 2>&1; then
    lipc-set-prop com.lab126.powerd preventScreenSaver 1 >/dev/null 2>&1 || true
    lipc-set-prop com.lab126.appmgrd start app://com.lab126.webview/?url=x >/dev/null 2>&1 || true
fi

for i in $(seq 1 10); do
    [ "$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)" = "com.lab126.webview" ] && break
    sleep 1
done
sleep 1

"$BIN" \
    --config "$CONFIG" \
    --queue "$APP_DIR/queue" \
    --sketch "$APP_DIR/sketches/scribe-note.svgz" >>"$LOG" 2>&1

# Restore Kindle Home screen and statusbar on exit
if command -v lipc-set-prop >/dev/null 2>&1; then
    lipc-set-prop com.lab126.powerd preventScreenSaver 0 >/dev/null 2>&1 || true
    lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.home >/dev/null 2>&1 || true
fi
start statusbar >/dev/null 2>&1 || true
xrefresh >/dev/null 2>&1 || true
