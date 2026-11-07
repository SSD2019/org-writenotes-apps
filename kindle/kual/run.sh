#!/bin/sh
# KUAL lifecycle runner for Org-WriteNotes on Jailbroken Kindle Scribe
# Installed at: /mnt/us/extensions/org-writenotes/run.sh

EXT_DIR="/mnt/us/extensions/org-writenotes"
DATA_DIR="/mnt/us/org-writenotes"
BIN="$EXT_DIR/bin/org-writenotes-kindle"
CONFIG="$EXT_DIR/config.json"
LOG="$DATA_DIR/scribe.log"

mkdir -p "$DATA_DIR/queue" "$DATA_DIR/sketches"

MODE="${1:-launch}"

suspend_kindle_ui() {
    if command -v lipc-set-prop >/dev/null 2>&1; then
        # Prevent screensaver while sketching
        lipc-set-prop com.lab126.powerd preventScreenSaver 1 2>/dev/null || true
        # Pause the stock Kindle pillow/mesquite window manager so it doesn't draw over /dev/fb0
        lipc-set-prop com.lab126.appmgrd stop app 2>/dev/null || true
    fi
}

resume_kindle_ui() {
    if command -v lipc-set-prop >/dev/null 2>&1; then
        lipc-set-prop com.lab126.powerd preventScreenSaver 0 2>/dev/null || true
        lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.home 2>/dev/null || true
    fi
}

case "$MODE" in
    launch)
        suspend_kindle_ui
        "$BIN" --config "$CONFIG" --queue "$DATA_DIR/queue" --sketch "$DATA_DIR/sketches/scribe-note.svgz" >>"$LOG" 2>&1
        resume_kindle_ui
        ;;
    launch-landscape)
        suspend_kindle_ui
        "$BIN" --config "$CONFIG" --queue "$DATA_DIR/queue" --rotate 90 --sketch "$DATA_DIR/sketches/scribe-note.svgz" >>"$LOG" 2>&1
        resume_kindle_ui
        ;;
    sync)
        "$BIN" --config "$CONFIG" --queue "$DATA_DIR/queue" --sync-now >>"$LOG" 2>&1
        ;;
    refresh)
        "$BIN" --refresh >>"$LOG" 2>&1
        ;;
    *)
        "$BIN" "$@"
        ;;
esac

