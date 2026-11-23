package com.codex.k2er.v2.engine;

import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Long-lived ADB-shell process. No new process is launched for individual mouse movements. */
public final class EngineMain {
    private static final Pattern EVENT = Pattern.compile("(/dev/input/[^: ]+):\\s+(EV_\\w+)\\s+(\\w+)\\s+(\\w+)");
    private static final Pattern FOCUS = Pattern.compile("\\s([\\w.]+)/[^\\s}]+");
    private final File directory;
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(8192);
    private final Map<String, float[]> deltas = new HashMap<>();
    private volatile boolean running = true;
    private volatile String focused = "";
    private volatile long focusUpdated;
    private Process reader;
    private PointerEngine engine;
    private long mouseUpdated, eventCount;
    private String aimState = "";

    private EngineMain(File directory) { this.directory = directory; }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if (args.length != 1) throw new IllegalArgumentException("App data directory required");
        EngineMain main = new EngineMain(new File(args[0]));
        try { main.run(); }
        catch (Throwable error) {
            error.printStackTrace(); main.write("k2er-status.txt", "error|" + error.getMessage());
            main.write("k2er-daemon-version.txt", "");
        }
    }
    private void run() throws Exception {
        AndroidTouch touch = new AndroidTouch(); engine = new PointerEngine(touch);
        new File(directory, "k2er-stop").delete();
        Thread focusThread = new Thread(this::watchFocus, "K2er-focus"); focusThread.setDaemon(true); focusThread.start();
        reader = new ProcessBuilder("getevent", "-lt").redirectError(new File(directory, "k2er-reader.log")).start();
        Thread eventThread = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (!events.offer(line)) { events.clear(); events.offer("RESET"); }
                }
            } catch (Exception error) { error.printStackTrace(); }
            events.clear(); events.offer("READER_STOPPED");
            }, "K2er-events");
        eventThread.setDaemon(true); eventThread.start();
        String config = "", game = "", display = "";
        long lastConfig = 0, lastHeartbeat = 0, lastCursor = 0, lastControl = 0, lastFrame = 0;
        boolean paused = true;
        write("k2er-daemon-version.txt", "4"); write("k2er-status.txt", "running");
        try {
            while (!new File(directory, "k2er-stop").exists()) {
                long now = SystemClock.uptimeMillis();
                if (now - lastConfig >= 100) {
                    lastConfig = now;
                    String maps = read("k2er-maps.tsv"), size = read("k2er-display.txt");
                    if (!maps.equals(config) || !focused.equals(game) || !size.equals(display)) {
                        config = maps; game = focused; display = size; deltas.clear();
                        String[] dimensions = size.trim().split("\\|");
                        int w = dimensions.length == 2 ? Integer.parseInt(dimensions[0]) : 1080;
                        int h = dimensions.length == 2 ? Integer.parseInt(dimensions[1]) : 1920;
                        engine.configure(parseProfile(maps, game), w, h);
                    }
                }
                if (now - lastControl >= 8) {
                    lastControl = now;
                    String edit = read("k2er-edit.txt");
                    paused = !edit.isEmpty() && !edit.startsWith("|") || now - focusUpdated > 4000;
                    engine.pause(paused);
                    if (paused) deltas.clear();
                }
                String line = events.poll(4, TimeUnit.MILLISECONDS);
                if (line != null) {
                    if (line.equals("READER_STOPPED")) throw new IllegalStateException("Mouse/keyboard reader stopped; reconnect K2er");
                    if (line.equals("RESET")) { engine.reset(); deltas.clear(); }
                    else consume(line, now, paused);
                }
                // A report normally flushes both axes together. Flush missing SYN_REPORT within one frame.
                if (now - lastFrame >= 8) { flushAll(now); lastFrame = now; }
                engine.tick(now);
                String state = focused + (engine.aimOn() ? "|on" : "|off");
                if (!state.equals(aimState)) { aimState = state; write("k2er-aim-state.txt", state); }
                if (now - lastCursor >= 16 && (mouseUpdated >= lastCursor || engine.aimOn())) {
                    lastCursor = now;
                    write("k2er-cursor.txt", focused + "|" + engine.cursorX() + "|" + engine.cursorY()
                            + "|" + (engine.aimOn() ? "aim" : "mouse") + "|" + System.currentTimeMillis());
                }
                if (now - lastHeartbeat >= 1000) {
                    lastHeartbeat = now; write("k2er-heartbeat", Long.toString(System.currentTimeMillis()));
                    write("k2er-input-count.txt", Long.toString(eventCount));
                }
            }
        } finally {
            try { engine.reset(); } catch (Exception error) { error.printStackTrace(); }
            running = false; reader.destroy();
            write("k2er-aim-state.txt", "|off"); write("k2er-cursor.txt", "");
            write("k2er-daemon-version.txt", ""); write("k2er-status.txt", "stopped");
        }
    }
    private void consume(String line, long now, boolean paused) {
        Matcher match = EVENT.matcher(line); if (!match.find()) return;
        String device = match.group(1), type = match.group(2), code = match.group(3), value = match.group(4);
        if (type.equals("EV_KEY") && (code.startsWith("KEY_") || code.startsWith("BTN_"))) {
            if (code.equals("BTN_TOUCH") || code.startsWith("BTN_TOOL_")) return;
            flush(device, now);
            if (value.equals("DOWN") || value.equals("UP")) {
                engine.key(device, code, value.equals("DOWN")); eventCount++;
                if (code.startsWith("BTN_")) mouseUpdated = now;
            }
        } else if (type.equals("EV_REL") && !paused) {
            int delta = (int) Long.parseLong(value, 16);
            if (code.equals("REL_X") || code.equals("REL_Y")) {
                float[] pending = deltas.computeIfAbsent(device, ignored -> new float[2]);
                pending[code.equals("REL_X") ? 0 : 1] += delta; mouseUpdated = now; eventCount++;
            } else if (code.equals("REL_WHEEL") && delta != 0) { engine.wheel(device, delta); eventCount++; }
        }
        if (paused) deltas.clear();
    }
    private void flush(String device, long now) {
        float[] pending = deltas.remove(device);
        if (pending != null) engine.motion(device, pending[0], pending[1], now);
    }
    private void flushAll(long now) {
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
                PointerEngine.Point point = new PointerEngine.Point(Float.parseFloat(fields[2]), Float.parseFloat(fields[3]));
                if (fields[1].equals("AIM")) {
                    result.aim = point;
                    if (fields.length >= 6) { result.gainX = Float.parseFloat(fields[4]); result.gainY = Float.parseFloat(fields[5]); }
                    result.aimKey = fields.length >= 7 ? fields[6] : "BTN_MIDDLE";
                    result.hold = fields.length >= 8 && fields[7].equals("hold");
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
                    focused = found[0]; focusUpdated = SystemClock.uptimeMillis(); write("k2er-focus.txt", focused);
                }
                Thread.sleep(300);
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
            temp.setReadable(true, false); temp.setWritable(true, false);
            if (temp.renameTo(target)) { target.setReadable(true, false); target.setWritable(true, false); }
        } catch (Exception error) { error.printStackTrace(); }
    }
}
