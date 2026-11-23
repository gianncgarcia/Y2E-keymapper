#!/system/bin/sh
# Stop only Y2E workers belonging to this app's data directory. Old releases
# waited on a foreground getevent pipeline and could not promptly handle TERM.
DATA="$1"
LEGACY_SCRIPT="$DATA/input_daemon.sh"
run_id=$(printf '%s' "$DATA" | cksum | cut -d ' ' -f 1)
RUN_DIR="/data/local/tmp/y2e-engine-$run_id"
DAEMON_SCRIPT="$RUN_DIR/input_daemon.sh"
PID_FILE="$DATA/y2e-daemon.pid"

stop_tree() {
    local target="$1" child
    case "$target" in ''|*[!0-9]*) return ;; esac
    [ "$target" -gt 1 ] || return
    kill -STOP "$target" 2>/dev/null || return
    for child in $(ps -A -o PID,PPID | awk -v parent="$target" '$2 == parent { print $1 }'); do
        stop_tree "$child"
    done
    kill -KILL "$target" 2>/dev/null
}

stop_existing() {
    local target
    touch "$DATA/y2e-stop"
    sleep 0.15
    for target in $(ps -A -o PID,ARGS | awk -v script="$DAEMON_SCRIPT" -v legacy="$LEGACY_SCRIPT" -v data="$DATA" \
        '($2 == "sh" || $2 == "/system/bin/sh") && ($3 == script || $3 == legacy) && $4 == data { print $1 }'); do
        # Revalidate after each tree is removed: descendants in the snapshot
        # may already have exited. Avoid reading a dead process's /proc file.
        if ps -p "$target" -o ARGS | awk -v script="$DAEMON_SCRIPT" -v legacy="$LEGACY_SCRIPT" -v data="$DATA" \
            '($1 == "sh" || $1 == "/system/bin/sh") && ($2 == script || $2 == legacy) && $3 == data { found=1 } END { exit !found }'; then
            stop_tree "$target"
        fi
    done
    rm -f "$PID_FILE" "$DATA/y2e-heartbeat" "$DATA/y2e-daemon-version.txt"
    rm -f "$DATA/y2e-stop"
    printf '|off\n' > "$DATA/y2e-aim-state.txt"
    printf 'stopped\n' > "$DATA/y2e-status.txt"
}

case "$2" in
    start)
        stop_existing
        umask 077
        mkdir -p "$RUN_DIR" && chmod 700 "$RUN_DIR" || exit 1
        INSTALLED_APK=$(pm path com.codex.keymapper | sed -n 's/^package://p' | head -n 1)
        [ -n "$INSTALLED_APK" ] && [ -r "$INSTALLED_APK" ] || exit 1
        unzip -p "$INSTALLED_APK" assets/input_daemon.sh > "$DAEMON_SCRIPT.tmp" || exit 1
        mv "$DAEMON_SCRIPT.tmp" "$DAEMON_SCRIPT" || exit 1
        nohup sh "$DAEMON_SCRIPT" "$DATA" >> "$DATA/y2e-daemon.log" 2>&1 </dev/null &
        daemon_pid=$!
        printf '%s\n' "$daemon_pid" > "$PID_FILE"
        attempts=0
        while [ "$attempts" -lt 30 ] && [ ! -s "$DATA/y2e-heartbeat" ]; do
            sleep 0.1
            attempts=$((attempts + 1))
        done
        if kill -0 "$daemon_pid" 2>/dev/null && [ -s "$DATA/y2e-heartbeat" ]; then
            echo "Y2E_DAEMON_STARTED:$daemon_pid"
        else
            echo Y2E_DAEMON_START_FAILED
            exit 1
        fi
        ;;
    stop)
        stop_existing
        echo Y2E_DAEMON_STOPPED
        ;;
    *) exit 2 ;;
esac
