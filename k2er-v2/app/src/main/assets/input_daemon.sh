#!/system/bin/sh
# Keep the parent shell alive so the controller can stop exactly this process tree.
DATA="$1"
run_id=$(printf '%s' "$DATA" | cksum | cut -d ' ' -f 1)
RUN_DIR="/data/local/tmp/k2er-engine-$run_id"
mkdir -p "$RUN_DIR" || exit 1
chmod 700 "$RUN_DIR"
chmod 600 "$RUN_DIR/engine.apk" 2>/dev/null
cp "$DATA/k2er-engine.apk" "$RUN_DIR/engine.apk" || exit 1
chmod 444 "$RUN_DIR/engine.apk"
rm -f "$DATA/k2er-stop"
export CLASSPATH="$RUN_DIR/engine.apk"
app_process /system/bin com.codex.k2er.v2.engine.EngineMain "$DATA" &
engine_pid=$!
trap 'touch "$DATA/k2er-stop"; wait "$engine_pid"; exit' TERM INT HUP
wait "$engine_pid"
