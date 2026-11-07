#!/bin/sh
# KUAL / CLI lifecycle runner for Org-WriteNotes on Jailbroken Kindle Scribe
# Installed at: /mnt/us/org-writenotes/run.sh (and /mnt/us/extensions/org-writenotes/run.sh)

DATA_DIR="/mnt/us/org-writenotes"
EXT_DIR="/mnt/us/extensions/org-writenotes"
if [ -x "$DATA_DIR/bin/org-writenotes-kindle" ]; then
    BIN="$DATA_DIR/bin/org-writenotes-kindle"
else
    BIN="$EXT_DIR/bin/org-writenotes-kindle"
fi
if [ -f "$DATA_DIR/config.json" ]; then
    CONFIG="$DATA_DIR/config.json"
else
    CONFIG="$EXT_DIR/config.json"
fi
LOG="$DATA_DIR/scribe.log"

mkdir -p "$DATA_DIR/queue" "$DATA_DIR/sketches"

MODE="${1:-launch}"

suspend_kindle_ui() {
    # Stop Jobman UI if still running on screen (leaving dropbearmulti SSH daemon alive)
    pkill -f "jobman.sh --detached" 2>/dev/null || true
    killall jobman 2>/dev/null || true
    killall org-writenotes-kindle 2>/dev/null || true

    stop statusbar 2>/dev/null || true
    if command -v lipc-set-prop >/dev/null 2>&1; then
        if [ "$(lipc-get-prop com.lab126.powerd state 2>/dev/null)" != "active" ]; then
            lipc-set-prop com.lab126.powerd powerButton 1 2>/dev/null || true
            sleep 2
        fi
        # Prevent screensaver and keep Wi-Fi active while sketching
        lipc-set-prop com.lab126.powerd preventScreenSaver 1 2>/dev/null || true
        lipc-set-prop com.lab126.cmd ensureConnection "wifi" 2>/dev/null || true
        # Park appmgrd in a blank webview so touches do not activate Home Library books
        if [ "$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)" != "com.lab126.webview" ]; then
            lipc-set-prop com.lab126.appmgrd start app://com.lab126.webview/?url=x 2>/dev/null || true
            for i in $(seq 1 15); do
                [ "$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)" = "com.lab126.webview" ] && break
                sleep 1
            done
            sleep 2
        fi
    fi
    killall -STOP WetInkHandler 2>/dev/null || true
}

resume_kindle_ui() {
    killall -CONT WetInkHandler 2>/dev/null || true
    if command -v lipc-set-prop >/dev/null 2>&1; then
        lipc-set-prop com.lab126.powerd preventScreenSaver 0 2>/dev/null || true
        lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.home 2>/dev/null || true
    fi
    start statusbar 2>/dev/null || true
    xrefresh 2>/dev/null || true
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

