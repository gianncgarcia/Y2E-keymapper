#!/system/bin/sh
# Keep the parent shell alive so the controller can stop exactly this process tree.
DATA="$1"
run_id=$(printf '%s' "$DATA" | cksum | cut -d ' ' -f 1)
RUN_DIR="/data/local/tmp/y2e-engine-$run_id"
mkdir -p "$RUN_DIR" || exit 1
chmod 700 "$RUN_DIR"
chmod 600 "$RUN_DIR/engine.apk" 2>/dev/null
# Load code only from Package Manager's installed APK, never the writable
# external-storage engine copy. Package installation verifies its signature.
INSTALLED_APK=$(pm path com.codex.keymapper | sed -n 's/^package://p' | head -n 1)
[ -n "$INSTALLED_APK" ] && [ -r "$INSTALLED_APK" ] || exit 1
cp "$INSTALLED_APK" "$RUN_DIR/engine.apk" || exit 1
chmod 444 "$RUN_DIR/engine.apk"
rm -f "$DATA/y2e-stop"
export CLASSPATH="$RUN_DIR/engine.apk"
app_process /system/bin com.codex.keymapper.engine.EngineMain "$DATA" &
engine_pid=$!
trap 'touch "$DATA/y2e-stop"; wait "$engine_pid"; exit' TERM INT HUP
wait "$engine_pid"
