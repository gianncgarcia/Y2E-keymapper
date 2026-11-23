#!/system/bin/sh
# Stop only K2er workers belonging to this app's data directory. Old releases
# waited on a foreground getevent pipeline and could not promptly handle TERM.
DATA="$1"
DAEMON_SCRIPT="$DATA/input_daemon.sh"
PID_FILE="$DATA/k2er-daemon.pid"

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
    touch "$DATA/k2er-stop"
    sleep 0.15
    for target in $(ps -A -o PID,ARGS | awk -v script="$DAEMON_SCRIPT" -v data="$DATA" \
        '($2 == "sh" || $2 == "/system/bin/sh") && $3 == script && $4 == data { print $1 }'); do
        # Revalidate after each tree is removed: descendants in the snapshot
        # may already have exited. Avoid reading a dead process's /proc file.
        if ps -p "$target" -o ARGS | awk -v script="$DAEMON_SCRIPT" -v data="$DATA" \
            '($1 == "sh" || $1 == "/system/bin/sh") && $2 == script && $3 == data { found=1 } END { exit !found }'; then
            stop_tree "$target"
        fi
    done
    rm -f "$PID_FILE" "$DATA/k2er-heartbeat" "$DATA/k2er-daemon-version.txt"
    rm -f "$DATA/k2er-stop"
    printf '|off\n' > "$DATA/k2er-aim-state.txt"
    printf 'stopped\n' > "$DATA/k2er-status.txt"
}

case "$2" in
    start)
        stop_existing
        nohup sh "$DAEMON_SCRIPT" "$DATA" >> "$DATA/k2er-daemon.log" 2>&1 </dev/null &
        daemon_pid=$!
        printf '%s\n' "$daemon_pid" > "$PID_FILE"
        attempts=0
        while [ "$attempts" -lt 30 ] && [ ! -s "$DATA/k2er-heartbeat" ]; do
            sleep 0.1
            attempts=$((attempts + 1))
        done
        if kill -0 "$daemon_pid" 2>/dev/null && [ -s "$DATA/k2er-heartbeat" ]; then
            echo "K2ER_DAEMON_STARTED:$daemon_pid"
        else
            echo K2ER_DAEMON_START_FAILED
            exit 1
        fi
        ;;
    stop)
        stop_existing
        echo K2ER_DAEMON_STOPPED
        ;;
    *) exit 2 ;;
esac
