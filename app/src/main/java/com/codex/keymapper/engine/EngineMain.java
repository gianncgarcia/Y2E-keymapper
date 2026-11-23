package com.codex.keymapper.engine;

import android.os.Looper;
import android.os.FileObserver;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Long-lived ADB-shell process. No new process is launched for individual mouse movements. */
public final class EngineMain {
    private static final Pattern EVENT = Pattern.compile("(/dev/input/[^: ]+):\\s+(EV_\\w+)\\s+(\\w+)\\s+(\\w+)");
    private static final Pattern FOCUS = Pattern.compile("\\s([\\w.]+)/[^\\s}]+");
    private static final Pattern DEVICE = Pattern.compile("add device\\s+\\d+:\\s+(/dev/input/[^\\s]+)");
    private static final Pattern ABS_RANGE = Pattern.compile("\\b(ABS_[A-Z0-9_]+)\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
    private final File directory;
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(8192);
    private final Map<String, float[]> deltas = new HashMap<>();
    private boolean liveDpad;
    private long lastLiveWrite;
    private long lastLiveRevision=Long.MIN_VALUE;
    private final Map<String, Set<String>> heldKeysByDevice = new HashMap<>();
    private final Set<String> keyboardHotkeyHeld = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> overlayHotkeyHeld = new HashSet<>();
    private final Map<String, Map<String, int[]>> axisRanges = new HashMap<>();
    private final Set<String> gamepadDevices = new HashSet<>();
    private final Object capabilityLock = new Object();
    private volatile boolean running = true;
    private volatile String focused = "";
    private volatile long focusUpdated;
    private Process reader;
    private PointerEngine engine;
    private FileObserver inputObserver;
    private long mouseUpdated, eventCount;
    private int mouseIntervalMs=2;
    private String aimState = "";
    private String keyboardHotkeyModifier = "", keyboardHotkeyKey = "";
    private String overlayHotkeyModifier = "", overlayHotkeyKey = "";
    private boolean keyboardHotkeyLatched, overlayHotkeyLatched;

    private EngineMain(File directory) { this.directory = directory; }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if (args.length != 1) throw new IllegalArgumentException("App data directory required");
        EngineMain main = new EngineMain(new File(args[0]));
        try { main.run(); }
        catch (Throwable error) {
            error.printStackTrace(); main.write("y2e-status.txt", "error|" + error.getMessage());
            main.write("y2e-daemon-version.txt", "");
        }
    }
    private void run() throws Exception {
        AndroidTouch touch = new AndroidTouch(); engine = new PointerEngine(touch);
        new File(directory, "y2e-stop").delete();
        loadInputCapabilities();
        Thread focusThread = new Thread(this::watchFocus, "Y2E-focus"); focusThread.setDaemon(true); focusThread.start();
        reader = new ProcessBuilder("getevent", "-lt").redirectError(new File(directory, "y2e-reader.log")).start();
        Thread eventThread = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (!events.offer(line)) { events.clear(); events.offer("RESET"); }
                }
            } catch (Exception error) { error.printStackTrace(); }
            events.clear(); events.offer("READER_STOPPED");
        }, "Y2E-events");
        eventThread.setDaemon(true); eventThread.start();
        startInputObserver();
        String config = "", game = "", display = "", hotkeyConfig = "", keyboardHotkeyConfig = "";
        long lastConfig = 0, lastHeartbeat = 0, lastCursor = 0, lastControl = 0, lastFrame = 0;
        boolean paused = true, keyboardMode = false;
        boolean profileActive = false;
        String configuredGame="", configuredMaps="", configuredDisplay="";
        write("y2e-daemon-version.txt", "19"); write("y2e-status.txt", "running");
        try {
            while (!new File(directory, "y2e-stop").exists()) {
                long now = SystemClock.uptimeMillis();
                if (now - lastConfig >= 100) {
                    lastConfig = now;
                    String maps = read("y2e-maps.tsv"), size = read("y2e-display.txt");
                    if (!maps.equals(config) || !focused.equals(game) || !size.equals(display)) {
                        config = maps; game = focused; display = size; deltas.clear();
                        mouseUpdated = now; lastCursor = 0;
                        String[] dimensions = size.trim().split("\\|");
                        int w = dimensions.length == 2 ? Integer.parseInt(dimensions[0]) : 1080;
                        int h = dimensions.length == 2 ? Integer.parseInt(dimensions[1]) : 1920;
                        PointerEngine.Profile parsed = parseProfile(maps, game);
                        // Android briefly reports System UI or the keyboard
                        // while an overlay window changes. Keep the current
                        // game profile through those transient focus reports
                        // so hiding the editor cannot stop input injection.
                        if (!(parsed == null && profileActive && transientFocus(game))) {
                            if (!configuredGame.equals(game) || !configuredMaps.equals(maps) || !configuredDisplay.equals(size)) {
                                clearHeldKeysByDevice();
                                engine.configure(parsed, w, h);
                                configuredGame=game; configuredMaps=maps; configuredDisplay=size;
                            }
                            profileActive = parsed != null;
                        }
                    }
                }
                if (now - lastControl >= 40) {
                    lastControl = now;
                    String requestedRate=read("y2e-mouse-rate.txt");
                    mouseIntervalMs=requestedRate.equals("250")?4:requestedRate.equals("1000")?1:2;
                    boolean liveRequested=read("y2e-dpad-live-enabled.txt").equals("1");
                    if(liveRequested && !liveDpad)lastLiveRevision=Long.MIN_VALUE;
                    liveDpad=liveRequested;
                    String edit = read("y2e-edit.txt");
                    keyboardMode = read("y2e-keyboard-mode.txt").equals("1");
                    // A temporary focus-probe delay must not pause a profile.
                    // Overlay visibility changes can briefly delay dumpsys
                    // while the game itself is still the active window.
                    paused = !edit.isEmpty() && !edit.startsWith("|");
                    engine.setKeyboardMode(keyboardMode);
                    engine.pause(paused);
                    String hotkey = read("y2e-overlay-hotkey.txt");
                    if (!hotkey.equals(hotkeyConfig)) {
                        hotkeyConfig = hotkey;
                        String[] keys = hotkey.split("\\|", -1);
                        overlayHotkeyModifier = keys.length > 0 ? keys[0] : "";
                        overlayHotkeyKey = keys.length > 1 ? keys[1] : "";
                        overlayHotkeyHeld.clear(); overlayHotkeyLatched = false;
                        // An empty file means the user has not configured a
                        // toggle. Do not silently restore a default shortcut.
                        engine.setOverlayHotkey(keys.length > 0 ? keys[0] : "",
                                keys.length > 1 ? keys[1] : "");
                    }
                    String keyboardHotkey = read("y2e-keyboard-hotkey.txt");
                    if (!keyboardHotkey.equals(keyboardHotkeyConfig)) {
                        keyboardHotkeyConfig = keyboardHotkey;
                        String[] keys = keyboardHotkey.split("\\|", -1);
                        keyboardHotkeyModifier = keys.length > 0 ? keys[0] : "";
                        keyboardHotkeyKey = keys.length > 1 ? keys[1] : "";
                        keyboardHotkeyHeld.clear(); keyboardHotkeyLatched = false;
                    }
                    engine.setOverlayHidden(read("y2e-overlay-hidden.txt").equals("1"));
                    if (paused) { deltas.clear(); clearHeldKeysByDevice(); }
                }
                String line = events.poll(mouseIntervalMs, TimeUnit.MILLISECONDS);
                // Consume queued keyboard transitions before advancing touch
                // motion, so one HID report's diagonal changes stay together.
                int batch = 0;
                while (line != null) {
                    now = SystemClock.uptimeMillis();
                    if (line.equals("READER_STOPPED")) throw new IllegalStateException("Mouse/keyboard reader stopped; reconnect Y2E");
                    if (line.equals("RESET")) resetInputState();
                    else if (line.startsWith("DEVICE_LOST:")) {
                        resetInputState();
                        write("y2e-last-input-reset.txt", line.substring("DEVICE_LOST:".length())
                                + "|" + System.currentTimeMillis());
                    } else consume(line, now, paused, keyboardMode);
                    if (++batch >= 256) break;
                    line = events.poll();
                }
                // Flush mouse movement in frame-sized batches; key transitions are handled immediately.
                if (now - lastFrame >= mouseIntervalMs) {
                    flushAll(now);
                    lastFrame = now;
                }
                engine.tick(now);
                if(liveDpad && now-lastLiveWrite>=50 && engine.dpadRevision()!=lastLiveRevision) {
                    lastLiveWrite=now;lastLiveRevision=engine.dpadRevision();
                    write("y2e-dpad-live.txt",engine.dpadDiagnosticState());
                }
                String state = focused + (engine.aimOn() ? "|on" : "|off");
                if (!state.equals(aimState)) { aimState = state; write("y2e-aim-state.txt", state); }
                // Cursor state is only used while aim is off. Avoid writing
                // a file for every aim frame so mouse movement gets the
                // engine thread's full time budget.
                if (!engine.aimOn() && now - lastCursor >= 33 && mouseUpdated >= lastCursor) {
                    lastCursor = now;
                    write("y2e-cursor.txt", focused + "|" + engine.cursorX() + "|" + engine.cursorY()
                            + "|" + (engine.aimOn() ? "aim" : "mouse") + "|" + System.currentTimeMillis());
                }
                if (now - lastHeartbeat >= 1000) {
                    lastHeartbeat = now; write("y2e-heartbeat", Long.toString(System.currentTimeMillis()));
                    if (!paused && !focused.isEmpty() && !engine.aimOn())
                        write("y2e-cursor.txt", focused + "|" + engine.cursorX() + "|" + engine.cursorY()
                                + "|mouse|" + System.currentTimeMillis());
                    write("y2e-input-count.txt", Long.toString(eventCount));
                }
            }
        } finally {
            if (inputObserver != null) inputObserver.stopWatching();
            try { engine.reset(); } catch (Exception error) { error.printStackTrace(); }
            running = false; reader.destroy();
            write("y2e-aim-state.txt", "|off"); write("y2e-cursor.txt", "");
            write("y2e-daemon-version.txt", ""); write("y2e-status.txt", "stopped");
        }
    }
    private static boolean transientFocus(String name) {
        return name.equals("com.android.systemui")
                || name.equals("com.android.permissioncontroller")
                || name.equals("com.google.android.permissioncontroller")
                || name.startsWith("com.google.android.inputmethod")
                || name.startsWith("com.samsung.android.honeyboard")
                || name.startsWith("com.google.android.play.games");
    }
    private void startInputObserver() {
        inputObserver = new FileObserver("/dev/input", FileObserver.CREATE | FileObserver.MOVED_TO
                | FileObserver.DELETE | FileObserver.MOVED_FROM) {
            @Override public void onEvent(int event, String path) {
                if (path == null || !path.matches("event\\d+")) return;
                String device = "/dev/input/" + path;
                if ((event & (FileObserver.CREATE | FileObserver.MOVED_TO)) != 0) {
                    Thread capabilities = new Thread(EngineMain.this::loadInputCapabilities, "Y2E-input-capabilities");
                    capabilities.setDaemon(true); capabilities.start();
                    return;
                }
                boolean wasGamepad;
                synchronized (capabilityLock) {
                    wasGamepad = gamepadDevices.remove(device);
                    axisRanges.remove(device);
                }
                boolean hadHeldKeys;
                synchronized (heldKeysByDevice) {
                    Set<String> held = heldKeysByDevice.remove(device);
                    hadHeldKeys = held != null && !held.isEmpty();
                }
                boolean hadKeyboardHotkey = false;
                synchronized (keyboardHotkeyHeld) {
                    String prefix = device + ":";
                    for (String held : keyboardHotkeyHeld) {
                        if (held.startsWith(prefix)) { hadKeyboardHotkey = true; break; }
                    }
                }
                if ((hadHeldKeys || hadKeyboardHotkey || wasGamepad) && !events.offer("DEVICE_LOST:" + device)) {
                    events.clear(); events.offer("RESET");
                }
            }
        };
        inputObserver.startWatching();
    }
    private void resetInputState() {
        engine.reset(); deltas.clear(); clearHeldKeysByDevice();
        keyboardHotkeyHeld.clear(); keyboardHotkeyLatched = false;
        overlayHotkeyHeld.clear(); overlayHotkeyLatched = false;
    }
    private void clearHeldKeysByDevice() {
        synchronized (heldKeysByDevice) { heldKeysByDevice.clear(); }
    }
    private void trackHeldKey(String device, String key, boolean down) {
        synchronized (heldKeysByDevice) {
            if (down) {
                heldKeysByDevice.computeIfAbsent(device, ignored -> new HashSet<>()).add(key);
            } else {
                for (Set<String> held : heldKeysByDevice.values()) held.remove(key);
                heldKeysByDevice.values().removeIf(Set::isEmpty);
            }
        }
    }
    private void consume(String line, long now, boolean paused, boolean keyboardMode) {
        Matcher match = EVENT.matcher(line); if (!match.find()) return;
        String device = match.group(1), type = match.group(2), code = match.group(3), value = match.group(4);
        if (type.equals("EV_SYN")) {
            if (code.equals("SYN_DROPPED")) {
                // The kernel dropped part of this device's event stream. Any
                // held direction may now be missing its release, so cancel all
                // simulated fingers and rebuild from fresh input.
                resetInputState();
            }
            return;
        }
        if (type.equals("EV_KEY") && (code.startsWith("KEY_") || code.startsWith("BTN_"))) {
            if (code.equals("BTN_TOUCH") || code.startsWith("BTN_TOOL_")) return;
            flush(device, now);
            if (value.equals("DOWN") || value.equals("UP") || value.equals("REPEAT")) {
                boolean down = !value.equals("UP");
                boolean overlayHotkey = handleOverlayToggleHotkey(device, code, down);
                boolean keyboardHotkey = !overlayHotkey && handleKeyboardModeHotkey(device, code, down);
                if (!overlayHotkey && !keyboardHotkey)
                    engine.key(device, code, down, now, value.equals("REPEAT"));
                if (!paused && !keyboardMode && !overlayHotkey && !keyboardHotkey)
                    trackHeldKey(device, code, down);
                if (engine.consumeOverlayToggle())
                    write("y2e-overlay-hidden.txt", engine.overlayHidden() ? "1" : "0");
                eventCount++;
                if (code.startsWith("BTN_")) mouseUpdated = now;
            }
        } else if (type.equals("EV_REL") && !paused) {
            int delta = (int) Long.parseLong(value, 16);
            if (code.equals("REL_X") || code.equals("REL_Y")) {
                float[] pending = deltas.computeIfAbsent(device, ignored -> new float[2]);
                pending[code.equals("REL_X") ? 0 : 1] += delta; mouseUpdated = now; eventCount++;
            } else if (code.equals("REL_WHEEL") && delta != 0) { engine.wheel(device, delta, now); eventCount++; }
        } else if (type.equals("EV_ABS") && !paused
                && (code.equals("ABS_X") || code.equals("ABS_Y") || code.equals("ABS_RX") || code.equals("ABS_RY"))) {
            try {
                synchronized (capabilityLock) { if (!gamepadDevices.contains(device)) return; }
                int raw = (int) Long.parseLong(value, 16);
                float normalized = normalizeAxis(device, code, raw);
                engine.axis(device, code, normalized, now);
                eventCount++;
            } catch (NumberFormatException ignored) { }
        }
        if (paused) deltas.clear();
    }

    private boolean handleKeyboardModeHotkey(String device, String key, boolean down) {
        boolean isModifier = !keyboardHotkeyModifier.isEmpty() && key.equals(keyboardHotkeyModifier);
        boolean isTrigger = !keyboardHotkeyKey.isEmpty() && key.equals(keyboardHotkeyKey);
        if (!isModifier && !isTrigger) return false;
        String id = device + ":" + key;
        if (down) keyboardHotkeyHeld.add(id); else keyboardHotkeyHeld.remove(id);
        boolean modifierDown = keyboardHotkeyModifier.isEmpty()
                || keyboardHotkeyHeld.contains(device + ":" + keyboardHotkeyModifier);
        boolean keyDown = keyboardHotkeyHeld.contains(device + ":" + keyboardHotkeyKey);
        // Only reserve the trigger key when the configured modifier is held.
        // This lets Ctrl+Q reach the overlay toggle even though Alt+Q shares Q.
        if (isTrigger && down && modifierDown && keyDown && !keyboardHotkeyLatched) {
            boolean enabled = read("y2e-keyboard-mode.txt").trim().equals("1");
            write("y2e-keyboard-mode.txt", enabled ? "0" : "1");
            keyboardHotkeyLatched = true;
        }
        boolean consume = isModifier || isTrigger && keyboardHotkeyLatched;
        if (!keyboardHotkeyHeld.contains(device + ":" + keyboardHotkeyKey)
                && (keyboardHotkeyModifier.isEmpty()
                || !keyboardHotkeyHeld.contains(device + ":" + keyboardHotkeyModifier))) {
            keyboardHotkeyLatched = false;
        }
        return consume;
    }

    private boolean handleOverlayToggleHotkey(String device, String key, boolean down) {
        boolean isModifier = !overlayHotkeyModifier.isEmpty() && key.equals(overlayHotkeyModifier);
        boolean isTrigger = !overlayHotkeyKey.isEmpty() && key.equals(overlayHotkeyKey);
        if (!isModifier && !isTrigger) return false;

        String id = device + ":" + key;
        if (down) overlayHotkeyHeld.add(id); else overlayHotkeyHeld.remove(id);
        boolean modifierDown = overlayHotkeyModifier.isEmpty()
                || overlayHotkeyHeld.contains(device + ":" + overlayHotkeyModifier);
        boolean triggerDown = overlayHotkeyHeld.contains(device + ":" + overlayHotkeyKey);
        if (isTrigger && down && modifierDown && triggerDown && !overlayHotkeyLatched) {
            boolean hidden = read("y2e-overlay-hidden.txt").equals("1");
            write("y2e-overlay-hidden.txt", hidden ? "0" : "1");
            overlayHotkeyLatched = true;
        }

        boolean consume = isModifier || isTrigger && overlayHotkeyLatched;
        if (!overlayHotkeyHeld.contains(device + ":" + overlayHotkeyKey)
                && (overlayHotkeyModifier.isEmpty()
                || !overlayHotkeyHeld.contains(device + ":" + overlayHotkeyModifier))) {
            overlayHotkeyLatched = false;
        }
        return consume;
    }

    private void loadInputCapabilities() {
        Process process = null;
        Map<String, Map<String, int[]>> discoveredRanges = new HashMap<>();
        Set<String> discoveredGamepads = new HashSet<>();
        try {
            process = new ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start();
            String device = "";
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = output.readLine()) != null) {
                    Matcher header = DEVICE.matcher(line);
                    if (header.find()) { device = header.group(1); continue; }
                    if (device.isEmpty()) continue;
                    if (line.contains("BTN_GAMEPAD") || line.contains("BTN_JOYSTICK") || line.contains("BTN_SOUTH")
                            || line.contains("BTN_A") || line.contains("BTN_TRIGGER")) discoveredGamepads.add(device);
                    Matcher range = ABS_RANGE.matcher(line);
                    if (range.find()) {
                        try {
                            discoveredRanges.computeIfAbsent(device, ignored -> new HashMap<>())
                                    .put(range.group(1), new int[]{Integer.parseInt(range.group(2)), Integer.parseInt(range.group(3))});
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Keep keyboard/mouse mapping available if this Android build omits getevent capability listing.
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
        synchronized (capabilityLock) {
            gamepadDevices.addAll(discoveredGamepads);
            axisRanges.putAll(discoveredRanges);
        }
    }

    private float normalizeAxis(String device, String axis, int raw) {
        int[] range;
        synchronized (capabilityLock) {
            Map<String, int[]> ranges = axisRanges.get(device);
            int[] found = ranges == null ? null : ranges.get(axis);
            range = found == null ? null : found.clone();
        }
        if (range == null || range[1] <= range[0]) return Math.max(-1f, Math.min(1f, raw / 32767f));
        float center = (range[0] + range[1]) / 2f;
        float span = raw >= center ? range[1] - center : center - range[0];
        if (span <= 0f) return 0f;
        return Math.max(-1f, Math.min(1f, (raw - center) / span));
    }
    private void flush(String device, long now) {
        if (engine.aimOn()) {
            flushAll(now);
            return;
        }
        float[] pending = deltas.remove(device);
        if (pending != null) engine.motion(device, pending[0], pending[1], now);
    }
    private void flushAll(long now) {
        if (engine.aimOn()) {
            float dx = 0f, dy = 0f;
            for (float[] pending : deltas.values()) { dx += pending[0]; dy += pending[1]; }
            deltas.clear();
            if (dx != 0f || dy != 0f) engine.motion("aim", dx, dy, now);
            return;
        }
        for (Map.Entry<String, float[]> entry : deltas.entrySet()) engine.motion(entry.getKey(), entry.getValue()[0], entry.getValue()[1], now);
        deltas.clear();
    }
    static PointerEngine.Profile parseProfile(String maps, String active) {
        if (active.isEmpty()) return null;
        PointerEngine.Profile result = null;
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\|");
            if (fields.length < 4 || !fields[0].equals(active)) continue;
            if (result == null) result = new PointerEngine.Profile();
            if (fields[1].equals("PROFILE")) continue;
            try {
                if (fields[1].equals("DPAD") && fields.length >= 16) {
                    result.dpad = new PointerEngine.Dpad(new PointerEngine.Point(
                            Float.parseFloat(fields[2]), Float.parseFloat(fields[3])), Float.parseFloat(fields[4]),
                            Integer.parseInt(fields[5]), fields[6], fields[7], fields[8], fields[9], fields[10],
                            Float.parseFloat(fields[11]), fields[12].equals("1"), Float.parseFloat(fields[13]),
                            Integer.parseInt(fields[14]), Float.parseFloat(fields[15]));
                    if(fields.length>=19 && fields[17].equals("tune2")) {
                        result.dpad.movementTuningEnabled=true;
                        result.dpad.diagonalAngle=Math.max(0,Math.min(90,Float.parseFloat(fields[13])));
                        result.dpad.movementFrequency=Math.max(1,Math.min(120,Integer.parseInt(fields[14])));
                        result.dpad.movementStepMax=Math.max(1,Math.min(100,Float.parseFloat(fields[15])));
                        result.dpad.movementStepMin=Math.max(1,Math.min((int)result.dpad.movementStepMax,Integer.parseInt(fields[18])));
                    }
                    result.dpad.transitionDelayMs = fields.length>=17 ? Math.max(0,Math.min(50,Integer.parseInt(fields[16]))) : 16;
                    continue;
                }
                if (fields[1].equals("DPAD_STICK") && fields.length >= 16) {
                    result.dpadStick = new PointerEngine.DpadStick(new PointerEngine.Point(
                            Float.parseFloat(fields[2]), Float.parseFloat(fields[3])), Float.parseFloat(fields[4]),
                            Integer.parseInt(fields[5]), fields[6], Float.parseFloat(fields[7]),
                            fields[8].equals("1"), fields[9].equals("1"), fields[10].equals("1"), fields[11].equals("1"),
                            Integer.parseInt(fields[12]), Float.parseFloat(fields[13]), Float.parseFloat(fields[14]),
                            fields[15].equals("1"));
                    continue;
                }
                PointerEngine.Point point = new PointerEngine.Point(Float.parseFloat(fields[2]), Float.parseFloat(fields[3]));
                if (fields[1].equals("AIM")) {
                    result.aim = point;
                    if (fields.length >= 6) { result.gainX = Float.parseFloat(fields[4]); result.gainY = Float.parseFloat(fields[5]); }
                    result.aimKey = fields.length >= 7 ? fields[6] : "BTN_MIDDLE";
                    result.hold = fields.length >= 8 && fields[7].equals("hold");
                    result.aimBoundary = fields.length >= 9 && fields[8].equals("1");
                    if (fields.length >= 14 && fields[13].equals("aim2")) {
                        result.aimAutoRelease = fields[9].equals("1");
                        result.aimReleaseDelayMs = Math.max(0, Math.min(2000, Integer.parseInt(fields[10])));
                        result.aimOffsetCorrection = fields[11].equals("1");
                        result.aimBoundaryRadius = Math.max(0.05f, Math.min(0.45f, Float.parseFloat(fields[12])));
                    } else if (fields.length >= 13) {
                        // Legacy maps have one extra boolean in field 9.
                        result.aimAutoRelease = fields[10].equals("1");
                        result.aimReleaseDelayMs = Math.max(0, Math.min(2000, Integer.parseInt(fields[11])));
                        result.aimOffsetCorrection = fields[12].equals("1");
                    }
                } else result.keys.put(PointerEngine.normalize(fields[1]), point);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }
    private void watchFocus() {
        while (running) {
            try {
                Process process = new ProcessBuilder("dumpsys", "activity", "activities").redirectErrorStream(true).start();
                final String[] found = {null};
                Thread output = new Thread(() -> {
                    try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = lines.readLine()) != null) if (found[0] == null && (line.contains("topResumedActivity") || line.contains("mResumedActivity"))) {
                            Matcher matcher = FOCUS.matcher(line); if (matcher.find()) found[0] = matcher.group(1);
                        }
                    } catch (Exception ignored) { }
                });
                output.setDaemon(true); output.start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy();
                output.join(200);
                if (found[0] != null) {
                    if(!focused.equals(found[0])) { focused = found[0]; write("y2e-focus.txt", focused); }
                    focusUpdated = SystemClock.uptimeMillis();
                }
                Thread.sleep(1000);
            } catch (Exception error) { error.printStackTrace(); }
        }
    }
    private String read(String name) {
        try { return new String(Files.readAllBytes(new File(directory, name).toPath()), StandardCharsets.UTF_8).trim(); }
        catch (Exception ignored) { return ""; }
    }
    private void write(String name, String value) {
        try {
            File target = new File(directory, name), temp = new File(directory, name + ".engine.tmp");
            Files.write(temp.toPath(), value.getBytes(StandardCharsets.UTF_8));
            temp.setReadable(true, false); temp.setWritable(false, false); temp.setWritable(true, true);
            if (temp.renameTo(target)) { target.setReadable(true, false); target.setWritable(false, false); target.setWritable(true, true); }
        } catch (Exception error) { error.printStackTrace(); }
    }
}
