# Y2E — Keyboard / Mouse / Gamepad → Touch Emulator for Android

Y2E (project name **KeyMapper**, package `com.codex.keymapper`) is an Android keymapping tool that turns a physical keyboard, mouse, and gamepad into simulated touch-screen gestures. It lets games without native hardware-input support be played with a keyboard and mouse on an Android tablet, using only Android's own input-injection APIs — no root required.

Current build: **Y2E 4.19** (versionCode 41, daemon protocol 19). See [`SAVED-STATE.txt`](SAVED-STATE.txt) for the full development and release history.

## How it works

Y2E is split into two cooperating processes, because the normal app has no privilege to inject touches — only the ADB shell user does.

```
   Physical keyboard / mouse / gamepad
                 │  (Linux evdev devices)
                 ▼
 ┌───────────────────────────────┐        external-file IPC        ┌────────────────────────────────┐
 │  Engine daemon (ADB shell)    │ ◄────────────────────────────►  │  App (com.codex.keymapper)     │
 │  EngineMain  (getevent -lt)   │      y2e-maps.tsv, focus,       │  KeyMappingService (overlay    │
 │  PointerEngine (input logic)  │      aim state, heartbeat…      │  foreground service, editor)   │
 │  AndroidTouch (injection)     │                                 │  MappingEditorView, profiles   │
 └───────────────────────────────┘                                 └────────────────────────────────┘
                 │
                 ▼
     Simulated finger events → the game
```

### 1. Engine daemon (runs as ADB shell)

- `EngineMain.java` is a long-lived process started through wireless debugging (paired from the app). It does **not** spawn a process per event.
- It streams `getevent -lt` to read raw key/mouse/gamepad events from `/dev/input`, parses them into an internal queue (8192 events), and runs an engine tick.
- A background thread polls the focused window via `dumpsys` so per-game profiles switch automatically when you change apps.
- Touch injection happens in `AndroidTouch.java`, which builds real `MotionEvent`s (up to 10 concurrent fingers, `TOOL_TYPE_FINGER`, `SOURCE_TOUCHSCREEN`) and injects them via reflection into `InputManager.injectInputEvent`. This requires "USB debugging (Security settings)" to be enabled on the device.

### 2. App process (UI + configuration)

- `MainActivity` — setup, daemon status/heartbeat, wireless-ADB pairing and activation (`WirelessActivation.kt`, using the [adbeesh](https://github.com/indidevs/adbeesh) library for mDNS pairing).
- `KeyMappingService` — foreground service that owns the translucent overlay: the floating Y2E toolbar (tap opens the app, separate drag grip), the in-game marker layer, and mouse capture.
- `MappingEditorView` — the visual editor for placing key targets, the WASD joystick, the analog-stick joystick, and the aim point, with live radius/angle guides.
- `GameProfileStore` / `MappingStore` — per-game profiles and key mappings saved as JSON/TSV in app-external files.
- `InputTrackerActivity` — optional diagnostics overlay (held keys, injected coordinates, miniature joystick diagram, live state at ≤20 Hz, F8 snapshot capture).

### 3. File-based IPC

The two processes communicate through files in the app's external storage (watched with `FileObserver` / change-driven writes):

| File | Purpose |
|---|---|
| `y2e-maps.tsv` | Key/mouse/joy mappings + profile settings pushed to the engine |
| `y2e-display.txt` | Display geometry |
| `y2e-edit.txt` | Editor placement updates |
| `y2e-focus.txt` | Focused app package (engine → app) |
| `y2e-heartbeat` | Daemon liveness (stale after 15 s) |
| `y2e-daemon-version.txt` | Daemon protocol version (currently 19) |
| `y2e-aim-state.txt` | Whether aim capture is on for the focused game |
| `y2e-cursor-mode.txt` / `y2e-overlay-hidden.txt` | Keyboard mode / overlay visibility |
| `y2e-status.txt` | Daemon status/errors |
| `y2e-live-*.txt` | Optional live tracker state (transient, ≤20 Hz) |

## Input emulation behavior

### Ordinary mapped keys
Each mapped key owns an **independent touch pointer** at its configured screen coordinate: `DOWN` on press, `UP` on release, tracked per `device:key`. Repeat events cannot recreate a released key, and stale state is cleared if Android reports the release on a different input node.

### WASD / Dpad (the joystick emulator)
- A single virtual-stick pointer starts at the joystick center (`DOWN`), holds ~12 ms, then moves toward the combined direction of all held keys.
- Direction combination uses **square geometry**: diagonals use full square offsets with fixed branch priorities.
- Y2E extension: **last-pressed opposite key wins independently per axis** (W held + S pressed → S; S released → W resumes). Queued moves superseded by a release are cancelled so an old move cannot execute late.
- **Walking radius shortcut**: a hotkey switches the stick to a smaller/larger radius (hold or toggle, with offset); transitions update the held direction without restarting the touch.
- Configurable per profile: center, radius, release delay (0–50 ms), direction-key assignment.
- **One continuous touch**: direction changes never lift the finger; the final release lifts at the last injected position (no recenter swipe). Release delay keeps the neutral touch down briefly before lifting.
- Safety: positions are clamped to screen bounds, and all simulated touches are cancelled if the keyboard/mouse disappears or a gamepad disconnects (prevents stuck movement).

### Dpad (Stick) — physical gamepad
Maps a gamepad's left/right analog stick to an on-screen virtual joystick. Axis ranges are read from `getevent` capabilities; supports dead zone, axis reverse/swap, radius, frequency, and step length.

### Mouse aim
- Mouse deltas are accumulated and combined into **one aim update per engine frame**, keeping a single continuous camera touch down while aim is active (instead of releasing after 140 ms idle).
- Tunable per profile: X/Y sensitivity (gain), **Boundary** radius (the touch is recentered after reaching it, carrying remaining motion across passes), **Auto Release** (lift after configured mouse-idle delay, 0–2000 ms validation), and **Offset correction** (resume at the aim point vs. last position after auto-release).
- Mouse processing rate is selectable (250/500/1000 Hz → 4/2/1 ms batching); hardware polling is unchanged.

## Building

Requirements: JDK 17, Android SDK (compileSdk 37 / buildTools 37.0.0), Gradle wrapper.

```powershell
# local.properties (not committed) must point at your SDK:
#   sdk.dir=C:\\path\\to\\Android\\Sdk
gradle assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Installing / updating on a device

1. Pair and connect over wireless ADB (the app guides this; `WirelessActivation.kt` handles mDNS pairing).
2. Build and install the APK (`gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`).
3. Activation extracts the controller and engine from the installed APK into a private, shell-owned directory; the daemon loads the engine from the installed APK, not the mutable copy.
4. After changing daemon code: refresh `y2e-engine.apk` and restart `daemon_control.sh` — installing a new APK alone does not restart the running daemon.

## Regression checks

`checks/run-engine-checks.ps1` runs standalone Java checks against the engine logic (no device needed):

| Check | Covers |
|---|---|
| `ReviewCheck.java` | Delivery starvation, short taps, last-pressed restoration, walking radius, screen bounds, aim distance conservation |
| `TransitionDelayCheck.java` | 0/8/16/50 ms direction delays, continuous touch across overlapping changes |
| `WImmediateCheck.java` | W-immediate forward behavior, lateral cleanup on return to W-only |
| `MovementTuningCheck.java` | Angle targets, frequency limits, midpoint step caps, no overshoot |
| `NoOvershootCheck.java` | Monotonic diagonal→cardinal transitions, endpoint bounds |

## Repository layout

```
app/src/main/java/com/codex/keymapper/          App side (overlay service, editor, profiles, activation)
app/src/main/java/com/codex/keymapper/engine/   Daemon side (EngineMain, PointerEngine, AndroidTouch, DpadHandler)
checks/                                          Regression runner + standalone Java checks
SAVED-STATE.txt                                  Full version history and design decisions
```

## License

GPL-3.0 — see [`LICENSE`](LICENSE). If you distribute builds of Y2E, you must make the corresponding source available under the same license.
