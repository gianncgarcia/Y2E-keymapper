package com.codex.k2er.v2;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class DaemonFiles {
    private static final String MAPS = "k2er-maps.tsv";
    private static final String DISPLAY = "k2er-display.txt";
    private static final String EDIT = "k2er-edit.txt";
    private static final String FOCUS = "k2er-focus.txt";
    private static final String CAPTURE = "k2er-capture.txt";
    private static final String HEARTBEAT = "k2er-heartbeat";
    private static final String AIM_STATE = "k2er-aim-state.txt";
    private static final AtomicInteger TAP_ID = new AtomicInteger();

    private DaemonFiles() { }

    static File directory(Context context) {
        File directory = context.getExternalFilesDir(null);
        return directory != null ? directory : context.getFilesDir();
    }

    static boolean isDaemonAlive(Context context) {
        File heartbeat = new File(directory(context), HEARTBEAT);
        return heartbeat.isFile() && System.currentTimeMillis() - heartbeat.lastModified() < 15000L
                && read(new File(directory(context), "k2er-daemon-version.txt")).trim().equals("4");
    }

    static String activePackage(Context context) {
        return read(new File(directory(context), FOCUS)).trim();
    }

    static boolean isAimActive(Context context, String packageName) {
        return read(new File(directory(context), AIM_STATE)).trim().equals(packageName + "|on");
    }

    static String cursorState(Context context) { return read(new File(directory(context), "k2er-cursor.txt")); }
    static void pointerCaptureState(Context context, boolean captured) {
        write(new File(directory(context), "k2er-pointer-captured.txt"), captured ? "yes" : "no");
    }

    static void syncProfiles(Context context, int width, int height) {
        StringBuilder maps = new StringBuilder();
        List<GameProfileStore.Profile> profiles = GameProfileStore.read(context);
        for (GameProfileStore.Profile profile : profiles) {
            maps.append(profile.packageName).append("|PROFILE|0|0\n");
            for (GameProfileStore.Binding binding : profile.bindings) {
                if (binding.keyCode == GameProfileStore.MOUSE_AIM) {
                    appendMap(maps, profile, binding, "AIM");
                    continue;
                }
                String kernelKey = kernelKeyName(binding.keyCode);
                if (kernelKey.isEmpty()) continue;
                appendMap(maps, profile, binding, kernelKey);
                String alias = kernelKeyAlias(binding.keyCode);
                if (!alias.isEmpty()) appendMap(maps, profile, binding, alias);
            }
        }
        write(new File(directory(context), MAPS), maps.toString());
        if (width > 0 && height > 0) {
            write(new File(directory(context), DISPLAY), width + "|" + height + "\n");
        }
    }

    static void setCaptureMode(Context context, String packageName, boolean waiting) {
        String value = packageName == null || packageName.isEmpty()
                ? "|0\n" : packageName + "|" + (waiting ? "1" : "0") + "\n";
        write(new File(directory(context), EDIT), value);
    }

    static String consumeCapturedKeyName(Context context) {
        File file = new File(directory(context), CAPTURE);
        String key = read(file).trim();
        if (!key.isEmpty()) write(file, "");
        return key;
    }

    static void enqueueTap(Context context, int x, int y) {
        File root = directory(context);
        String id = SystemClock.elapsedRealtimeNanos() + "-" + TAP_ID.incrementAndGet();
        File temporary = new File(root, "tap." + id + ".tmp");
        File ready = new File(root, "tap." + id + ".ready");
        if (write(temporary, x + "|" + y + "\n") && temporary.renameTo(ready)) {
            makeShellReadable(ready);
        } else {
            temporary.delete();
        }
    }

    static int keyCodeFromKernelName(String name) {
        if (name == null || name.isEmpty()) return KeyEvent.KEYCODE_UNKNOWN;
        if (name.startsWith("KEY_")) {
            String key = name.substring(4);
            if (key.length() == 1 && key.charAt(0) >= 'A' && key.charAt(0) <= 'Z') {
                return KeyEvent.KEYCODE_A + key.charAt(0) - 'A';
            }
            if (key.length() == 1 && key.charAt(0) >= '0' && key.charAt(0) <= '9') {
                return KeyEvent.KEYCODE_0 + key.charAt(0) - '0';
            }
            switch (key) {
                case "ENTER": return KeyEvent.KEYCODE_ENTER;
                case "SPACE": return KeyEvent.KEYCODE_SPACE;
                case "TAB": return KeyEvent.KEYCODE_TAB;
                case "ESC": return KeyEvent.KEYCODE_ESCAPE;
                case "LEFTSHIFT": return KeyEvent.KEYCODE_SHIFT_LEFT;
                case "RIGHTSHIFT": return KeyEvent.KEYCODE_SHIFT_RIGHT;
                case "LEFTCTRL": return KeyEvent.KEYCODE_CTRL_LEFT;
                case "RIGHTCTRL": return KeyEvent.KEYCODE_CTRL_RIGHT;
                case "LEFTALT": return KeyEvent.KEYCODE_ALT_LEFT;
                case "RIGHTALT": return KeyEvent.KEYCODE_ALT_RIGHT;
                case "UP": return KeyEvent.KEYCODE_DPAD_UP;
                case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
                case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
                case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
                default:
                    if (key.startsWith("F")) {
                        try {
                            int number = Integer.parseInt(key.substring(1));
                            if (number >= 1 && number <= 12) return KeyEvent.KEYCODE_F1 + number - 1;
                        } catch (NumberFormatException ignored) { }
                    }
                    return KeyEvent.KEYCODE_UNKNOWN;
            }
        }
        switch (name) {
            case "WHEEL_UP": return GameProfileStore.MOUSE_WHEEL_UP;
            case "WHEEL_DOWN": return GameProfileStore.MOUSE_WHEEL_DOWN;
            case "BTN_MOUSE":
            case "BTN_LEFT": return GameProfileStore.MOUSE_LEFT;
            case "BTN_RIGHT": return GameProfileStore.MOUSE_RIGHT;
            case "BTN_MIDDLE": return GameProfileStore.MOUSE_MIDDLE;
            case "BTN_SIDE": return GameProfileStore.MOUSE_BACK;
            case "BTN_EXTRA": return GameProfileStore.MOUSE_FORWARD;
            case "BTN_SOUTH":
            case "BTN_A": return KeyEvent.KEYCODE_BUTTON_A;
            case "BTN_EAST":
            case "BTN_B": return KeyEvent.KEYCODE_BUTTON_B;
            case "BTN_WEST":
            case "BTN_X": return KeyEvent.KEYCODE_BUTTON_X;
            case "BTN_NORTH":
            case "BTN_Y": return KeyEvent.KEYCODE_BUTTON_Y;
            case "BTN_TL": return KeyEvent.KEYCODE_BUTTON_L1;
            case "BTN_TR": return KeyEvent.KEYCODE_BUTTON_R1;
            case "BTN_SELECT": return KeyEvent.KEYCODE_BUTTON_SELECT;
            case "BTN_START": return KeyEvent.KEYCODE_BUTTON_START;
            case "BTN_THUMBL": return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case "BTN_THUMBR": return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    static String kernelKeyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return "KEY_" + (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return "KEY_" + (char) ('0' + keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "KEY_F" + (keyCode - KeyEvent.KEYCODE_F1 + 1);
        }
        switch (keyCode) {
            case GameProfileStore.MOUSE_WHEEL_UP: return "WHEEL_UP";
            case GameProfileStore.MOUSE_WHEEL_DOWN: return "WHEEL_DOWN";
            case GameProfileStore.MOUSE_LEFT: return "BTN_LEFT";
            case GameProfileStore.MOUSE_RIGHT: return "BTN_RIGHT";
            case GameProfileStore.MOUSE_MIDDLE: return "BTN_MIDDLE";
            case GameProfileStore.MOUSE_BACK: return "BTN_SIDE";
            case GameProfileStore.MOUSE_FORWARD: return "BTN_EXTRA";
            case KeyEvent.KEYCODE_ENTER: return "KEY_ENTER";
            case KeyEvent.KEYCODE_SPACE: return "KEY_SPACE";
            case KeyEvent.KEYCODE_TAB: return "KEY_TAB";
            case KeyEvent.KEYCODE_ESCAPE: return "KEY_ESC";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "KEY_LEFTSHIFT";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "KEY_RIGHTSHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "KEY_LEFTCTRL";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "KEY_RIGHTCTRL";
            case KeyEvent.KEYCODE_ALT_LEFT: return "KEY_LEFTALT";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "KEY_RIGHTALT";
            case KeyEvent.KEYCODE_DPAD_UP: return "KEY_UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "KEY_DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "KEY_LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "KEY_RIGHT";
            case KeyEvent.KEYCODE_BUTTON_A: return "BTN_SOUTH";
            case KeyEvent.KEYCODE_BUTTON_B: return "BTN_EAST";
            case KeyEvent.KEYCODE_BUTTON_X: return "BTN_WEST";
            case KeyEvent.KEYCODE_BUTTON_Y: return "BTN_NORTH";
            case KeyEvent.KEYCODE_BUTTON_L1: return "BTN_TL";
            case KeyEvent.KEYCODE_BUTTON_R1: return "BTN_TR";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "BTN_SELECT";
            case KeyEvent.KEYCODE_BUTTON_START: return "BTN_START";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "BTN_THUMBL";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "BTN_THUMBR";
            default: return "";
        }
    }

    private static String kernelKeyAlias(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "BTN_A";
            case KeyEvent.KEYCODE_BUTTON_B: return "BTN_B";
            case KeyEvent.KEYCODE_BUTTON_X: return "BTN_X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "BTN_Y";
            default: return "";
        }
    }

    private static void appendMap(StringBuilder maps, GameProfileStore.Profile profile,
                                  GameProfileStore.Binding binding, String kernelKey) {
        maps.append(profile.packageName).append('|')
                .append(kernelKey).append('|')
                .append(binding.x).append('|')
                .append(binding.y);
        if (binding.keyCode == GameProfileStore.MOUSE_AIM)
            maps.append('|').append(binding.sensitivityX).append('|').append(binding.sensitivityY)
                    .append('|').append(kernelKeyName(binding.aimKey)).append('|').append(binding.aimHold ? "hold" : "toggle");
        maps.append('\n');
    }

    private static String read(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean write(File file, String value) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        File temporary = new File(parent, file.getName() + "." + SystemClock.elapsedRealtimeNanos() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            makeShellReadable(temporary);
            if (temporary.renameTo(file)) {
                makeShellReadable(file);
                return true;
            }
            file.delete();
            boolean renamed = temporary.renameTo(file);
            if (renamed) makeShellReadable(file);
            return renamed;
        } catch (IOException ignored) {
            temporary.delete();
            return false;
        }
    }

    private static void makeShellReadable(File file) {
        file.setReadable(true, false);
        file.setWritable(true, false);
    }
}
