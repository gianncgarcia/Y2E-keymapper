package com.codex.keymapper;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GameProfileStore {
    static final int MOUSE_LEFT = -1001;
    static final int MOUSE_RIGHT = -1002;
    static final int MOUSE_MIDDLE = -1003;
    static final int MOUSE_BACK = -1004;
    static final int MOUSE_FORWARD = -1005;
    static final int MOUSE_WHEEL_UP = -1006;
    static final int MOUSE_WHEEL_DOWN = -1007;
    static final int MOUSE_AIM = -1010;
    private static final String PREFS = "game_mapper";
    private static final String PROFILES = "profiles_v1";
    private static final String EDITING = "editing";
    private static final String EDIT_PACKAGE = "editing_package";

    static final class Binding {
        final int keyCode;
        final float x;
        final float y;
        final boolean visible;
        final float sensitivityX, sensitivityY;
        final int aimKey;
        final boolean aimHold;
        final boolean aimBoundary, aimAutoRelease, aimOffsetCorrection;
        final float aimBoundaryRadius;
        final int aimReleaseDelayMs;

        Binding(int keyCode, float x, float y) {
            this(keyCode, x, y, true, 3f, 3f);
        }

        Binding(int keyCode, float x, float y, boolean visible, float sensitivityX, float sensitivityY) {
            this(keyCode, x, y, visible, sensitivityX, sensitivityY, MOUSE_MIDDLE, false);
        }

        Binding(int keyCode, float x, float y, boolean visible, float sensitivityX, float sensitivityY, int aimKey, boolean aimHold) {
            this(keyCode, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold,
                    false, 0.25f, true, 200, false);
        }

        Binding(int keyCode, float x, float y, boolean visible, float sensitivityX, float sensitivityY,
                int aimKey, boolean aimHold, boolean aimBoundary, float aimBoundaryRadius,
                boolean aimAutoRelease, int aimReleaseDelayMs, boolean aimOffsetCorrection) {
            this.keyCode = keyCode;
            this.x = Math.max(0f, Math.min(1f, x));
            this.y = Math.max(0f, Math.min(1f, y));
            this.visible = visible;
            this.sensitivityX = Math.max(0.2f, Math.min(8f, sensitivityX));
            this.sensitivityY = Math.max(0.2f, Math.min(8f, sensitivityY));
            this.aimKey = aimKey;
            this.aimHold = aimHold;
            this.aimBoundary = aimBoundary;
            this.aimBoundaryRadius = Math.max(0.05f, Math.min(0.45f, aimBoundaryRadius));
            this.aimAutoRelease = aimAutoRelease;
            this.aimReleaseDelayMs = Math.max(0, Math.min(2000, aimReleaseDelayMs));
            this.aimOffsetCorrection = aimOffsetCorrection;
        }

        Binding at(float x, float y) {
            return copy(keyCode, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold);
        }

        private Binding copy(int key, float px, float py, boolean show, float sx, float sy, int shortcut, boolean hold) {
            return new Binding(key, px, py, show, sx, sy, shortcut, hold, aimBoundary,
                    aimBoundaryRadius, aimAutoRelease, aimReleaseDelayMs, aimOffsetCorrection);
        }
        Binding withKey(int key) { return copy(key, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold); }
        Binding withAim(int key, boolean hold) { return copy(keyCode, x, y, visible, sensitivityX, sensitivityY, key, hold); }
        Binding withSensitivity(float sx, float sy) { return copy(keyCode, x, y, visible, sx, sy, aimKey, aimHold); }
        Binding withVisibility(boolean show) { return copy(keyCode, x, y, show, sensitivityX, sensitivityY, aimKey, aimHold); }
        Binding withAimOptions(boolean boundary, float boundaryRadius, boolean autoRelease,
                               int releaseDelay, boolean offsetCorrection) {
            return new Binding(keyCode, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold,
                    boundary, boundaryRadius, autoRelease, releaseDelay, offsetCorrection);
        }
        String aimKeyLabel() { return new Binding(aimKey, 0, 0).label(); }

        String label() {
            switch (keyCode) {
                case MOUSE_LEFT: return "LMB";
                case MOUSE_RIGHT: return "RMB";
                case MOUSE_MIDDLE: return "MMB";
                case MOUSE_BACK: return "M4";
                case MOUSE_FORWARD: return "M5";
                case MOUSE_WHEEL_UP: return "WHL+";
                case MOUSE_WHEEL_DOWN: return "WHL-";
                case MOUSE_AIM: return "AIM";
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_SPACE: return "SPACE";
                case KeyEvent.KEYCODE_ENTER: return "ENTER";
                case KeyEvent.KEYCODE_TAB: return "TAB";
                case KeyEvent.KEYCODE_ESCAPE: return "ESC";
                case KeyEvent.KEYCODE_SHIFT_LEFT:
                case KeyEvent.KEYCODE_SHIFT_RIGHT: return "SHIFT";
                case KeyEvent.KEYCODE_CTRL_LEFT:
                case KeyEvent.KEYCODE_CTRL_RIGHT: return "CTRL";
                case KeyEvent.KEYCODE_ALT_LEFT:
                case KeyEvent.KEYCODE_ALT_RIGHT: return "ALT";
                default:
                    String key = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
                    return key.length() > 8 ? key.substring(0, 8) : key;
            }
        }
    }

    static final class Profile {
        final String packageName;
        final String appName;
        final List<Binding> bindings;
        Dpad dpad;
        DpadStick dpadStick;
        Profile(String packageName, String appName, List<Binding> bindings) {
            this(packageName, appName, bindings, null, null);
        }
        Profile(String packageName, String appName, List<Binding> bindings, Dpad dpad, DpadStick dpadStick) {
            this.packageName = packageName;
            this.appName = appName;
            this.bindings = bindings;
            this.dpad = dpad;
            this.dpadStick = dpadStick;
        }
    }

    /** Keyboard directions that move one virtual joystick touch pointer. */
    static final class Dpad {
        final int upKey, downKey, leftKey, rightKey;
        final float x, y, radius, radiusOffset, angle, stepLength;
        final int releaseDelayMs, changeRadiusKey, frequency, transitionDelayMs, stepLengthMin;
        final boolean holdToChange;
        final boolean visible;

        Dpad(int upKey, int downKey, int leftKey, int rightKey, float x, float y,
             float radius, int releaseDelayMs, int changeRadiusKey, float radiusOffset,
             boolean holdToChange, float angle, int frequency, float stepLength, boolean visible) {
            this(upKey, downKey, leftKey, rightKey, x, y, radius, releaseDelayMs, changeRadiusKey,
                    radiusOffset, holdToChange, angle, frequency, stepLength, visible, 16);
        }
        Dpad(int upKey, int downKey, int leftKey, int rightKey, float x, float y,
             float radius, int releaseDelayMs, int changeRadiusKey, float radiusOffset,
             boolean holdToChange, float angle, int frequency, float stepLength, boolean visible, int transitionDelayMs) {
            this(upKey,downKey,leftKey,rightKey,x,y,radius,releaseDelayMs,changeRadiusKey,radiusOffset,
                    holdToChange,angle,frequency,stepLength,visible,transitionDelayMs,1);
        }
        Dpad(int upKey,int downKey,int leftKey,int rightKey,float x,float y,float radius,int releaseDelayMs,
             int changeRadiusKey,float radiusOffset,boolean holdToChange,float angle,int frequency,
             float stepLength,boolean visible,int transitionDelayMs,int stepLengthMin) {
            this.stepLengthMin=Math.max(1,Math.min(100,stepLengthMin));
            this.transitionDelayMs=Math.max(0,Math.min(50,transitionDelayMs));
            this.upKey = upKey; this.downKey = downKey; this.leftKey = leftKey; this.rightKey = rightKey;
            this.x = Math.max(0f, Math.min(1f, x)); this.y = Math.max(0f, Math.min(1f, y));
            this.radius = Math.max(0.02f, Math.min(0.45f, radius));
            this.releaseDelayMs = Math.max(0, Math.min(1000, releaseDelayMs)); this.visible = visible;
            this.changeRadiusKey = changeRadiusKey;
            this.radiusOffset = Math.max(-0.4f, Math.min(0.4f, radiusOffset));
            this.holdToChange = holdToChange;
            this.angle = Math.max(0f, Math.min(90f, angle));
            this.frequency = Math.max(1, Math.min(240, frequency));
            this.stepLength = Math.max(this.stepLengthMin, Math.min(100f, stepLength));
        }
        static Dpad defaults(float x, float y) {
            return new Dpad(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A,
                    KeyEvent.KEYCODE_D, x, y, 0.12f, 80, 0, 0.04f, false,
                    45f, 60, 50f, true);
        }
        Dpad at(float x, float y) { return new Dpad(upKey, downKey, leftKey, rightKey, x, y, radius, releaseDelayMs,
                changeRadiusKey, radiusOffset, holdToChange, angle, frequency, stepLength, visible, transitionDelayMs, stepLengthMin); }
        Dpad direction(int index, int key) {
            return new Dpad(index == 0 ? key : upKey, index == 1 ? key : downKey,
                    index == 2 ? key : leftKey, index == 3 ? key : rightKey,
                    x, y, radius, releaseDelayMs, changeRadiusKey, radiusOffset, holdToChange, angle, frequency, stepLength, visible, transitionDelayMs, stepLengthMin);
        }
        Dpad settings(float radius, int delay, boolean visible) {
            return new Dpad(upKey, downKey, leftKey, rightKey, x, y, radius, delay, changeRadiusKey,
                    radiusOffset, holdToChange, angle, frequency, stepLength, visible, transitionDelayMs, stepLengthMin);
        }
        Dpad shortcut(int key, float offset, boolean hold) {
            return new Dpad(upKey, downKey, leftKey, rightKey, x, y, radius, releaseDelayMs,
                    key, offset, hold, angle, frequency, stepLength, visible, transitionDelayMs, stepLengthMin);
        }
        Dpad movement(float angle, int frequency, float stepLength) {
            return new Dpad(upKey, downKey, leftKey, rightKey, x, y, radius, releaseDelayMs,
                    changeRadiusKey, radiusOffset, holdToChange, angle, frequency, stepLength, visible, transitionDelayMs, stepLengthMin);
        }
        Dpad transitionDelay(int value) {
            return new Dpad(upKey,downKey,leftKey,rightKey,x,y,radius,releaseDelayMs,changeRadiusKey,
                    radiusOffset,holdToChange,angle,frequency,stepLength,visible,value,stepLengthMin);
        }
        Dpad stepRange(int min,int max) {
            return new Dpad(upKey,downKey,leftKey,rightKey,x,y,radius,releaseDelayMs,changeRadiusKey,radiusOffset,
                    holdToChange,angle,frequency,max,visible,transitionDelayMs,min);
        }
        String label() {
            return new Binding(upKey, 0, 0).label() + "/" + new Binding(leftKey, 0, 0).label()
                    + "/" + new Binding(downKey, 0, 0).label() + "/" + new Binding(rightKey, 0, 0).label();
        }
    }

    /** Physical gamepad stick mapped to an on-screen movement joystick. */
    static final class DpadStick {
        final float x, y, radius, radiusOffset, deadZone, stepLength;
        final int releaseDelayMs, changeRadiusKey, frequency;
        final boolean rightStick, holdToChange, reverseX, reverseY, swapXY, visible;

        DpadStick(float x, float y, float radius, int releaseDelayMs, int changeRadiusKey,
                  float radiusOffset, boolean holdToChange, boolean reverseX, boolean reverseY,
                  boolean swapXY, int frequency, float stepLength, float deadZone,
                  boolean rightStick, boolean visible) {
            this.x = Math.max(0f, Math.min(1f, x)); this.y = Math.max(0f, Math.min(1f, y));
            this.radius = Math.max(0.02f, Math.min(0.45f, radius));
            this.releaseDelayMs = Math.max(0, Math.min(1000, releaseDelayMs));
            this.changeRadiusKey = changeRadiusKey;
            this.radiusOffset = Math.max(-0.4f, Math.min(0.4f, radiusOffset));
            this.holdToChange = holdToChange; this.reverseX = reverseX; this.reverseY = reverseY;
            this.swapXY = swapXY; this.frequency = Math.max(1, Math.min(240, frequency));
            this.stepLength = Math.max(0.001f, Math.min(0.2f, stepLength));
            this.deadZone = Math.max(0f, Math.min(0.9f, deadZone));
            this.rightStick = rightStick; this.visible = visible;
        }
        static DpadStick defaults(float x, float y) {
            return new DpadStick(x, y, 0.12f, 100, 0, 0.04f, false,
                    false, false, false, 60, 0.01f, 0.15f, false, true);
        }
        DpadStick at(float x, float y) {
            return new DpadStick(x, y, radius, releaseDelayMs, changeRadiusKey, radiusOffset,
                    holdToChange, reverseX, reverseY, swapXY, frequency, stepLength, deadZone, rightStick, visible);
        }
        DpadStick shortcut(int key, float offset, boolean hold) {
            return new DpadStick(x, y, radius, releaseDelayMs, key, offset, hold,
                    reverseX, reverseY, swapXY, frequency, stepLength, deadZone, rightStick, visible);
        }
        DpadStick settings(float radius, int delay, boolean reverseX, boolean reverseY, boolean swapXY,
                           int frequency, float stepLength, float deadZone, boolean rightStick, boolean visible) {
            return new DpadStick(x, y, radius, delay, changeRadiusKey, radiusOffset, holdToChange,
                    reverseX, reverseY, swapXY, frequency, stepLength, deadZone, rightStick, visible);
        }
        String axisLabel() { return rightStick ? "ABS_RX / ABS_RY" : "ABS_X / ABS_Y"; }
    }

    private GameProfileStore() { }

    static List<Profile> read(Context context) {
        String raw = prefs(context).getString(PROFILES, "[]");
        List<Profile> result = new ArrayList<>();
        boolean migrated = false;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject profileJson = array.optJSONObject(i);
                if (profileJson == null) continue;
                String packageName = profileJson.optString("package", "");
                String appName = profileJson.optString("name", packageName);
                if (packageName.isEmpty()) continue;
                List<Binding> bindings = new ArrayList<>();
                JSONArray bindingArray = profileJson.optJSONArray("bindings");
                if (bindingArray != null) {
                    for (int j = 0; j < bindingArray.length(); j++) {
                        JSONObject binding = bindingArray.optJSONObject(j);
                        if (binding == null) continue;
                        int keyCode = binding.optInt("key", 0);
                        if (keyCode != 0) {
                            bindings.add(new Binding(keyCode,
                                    (float) binding.optDouble("x", 0.5),
                                    (float) binding.optDouble("y", 0.5), binding.optBoolean("visible", true),
                                    (float) binding.optDouble("sensitivityX", 3),
                                    (float) binding.optDouble("sensitivityY", 3), binding.optInt("aimKey", MOUSE_MIDDLE),
                                    binding.optBoolean("aimHold", false), binding.optBoolean("aimBoundary", false),
                                    (float) binding.optDouble("aimBoundaryRadius", 0.25), binding.optBoolean("aimAutoRelease", true),
                                    binding.optInt("aimReleaseDelayMs", 200), binding.optBoolean("aimOffsetCorrection", false)));
                        }
                    }
                }
                JSONObject joystickJson = profileJson.optJSONObject("joystick");
                if (joystickJson != null) {
                    migrateLegacyMappings(joystickJson, bindings);
                    migrated = true;
                }
                Dpad dpad = null;
                JSONObject dpadJson = profileJson.optJSONObject("dpad");
                if (dpadJson != null) {
                    Dpad defaults = Dpad.defaults(0.2f, 0.75f);
                    dpad = new Dpad(dpadJson.optInt("upKey", defaults.upKey),
                            dpadJson.optInt("downKey", defaults.downKey), dpadJson.optInt("leftKey", defaults.leftKey),
                            dpadJson.optInt("rightKey", defaults.rightKey),
                            (float) dpadJson.optDouble("x", 0.2), (float) dpadJson.optDouble("y", 0.75),
                            (float) dpadJson.optDouble("radius", 0.12), dpadJson.optInt("releaseDelayMs", 80),
                            dpadJson.optInt("changeRadiusKey", defaults.changeRadiusKey),
                            (float) dpadJson.optDouble("radiusOffset", defaults.radiusOffset),
                            dpadJson.optBoolean("holdToChange", defaults.holdToChange),
                            dpadJson.optBoolean("movementTuningV1",false) ? (float)dpadJson.optDouble("angle",45) : 45f,
                            dpadJson.optBoolean("movementTuningV1",false) ? dpadJson.optInt("frequency",60) : 60,
                            dpadJson.optBoolean("movementTuningV1",false) ? (float)dpadJson.optDouble("stepLength",50) : 50f,
                            dpadJson.optBoolean("visible", true), dpadJson.optInt("transitionDelayMs",16),dpadJson.optInt("stepLengthMin",1));
                }
                DpadStick dpadStick = null;
                JSONObject stickJson = profileJson.optJSONObject("dpadStick");
                if (stickJson != null) {
                    DpadStick defaults = DpadStick.defaults(0.2f, 0.75f);
                    dpadStick = new DpadStick((float) stickJson.optDouble("x", defaults.x),
                            (float) stickJson.optDouble("y", defaults.y), (float) stickJson.optDouble("radius", defaults.radius),
                            stickJson.optInt("releaseDelayMs", defaults.releaseDelayMs),
                            stickJson.optInt("changeRadiusKey", defaults.changeRadiusKey),
                            (float) stickJson.optDouble("radiusOffset", defaults.radiusOffset),
                            stickJson.optBoolean("holdToChange", defaults.holdToChange),
                            stickJson.optBoolean("reverseX", defaults.reverseX), stickJson.optBoolean("reverseY", defaults.reverseY),
                            stickJson.optBoolean("swapXY", defaults.swapXY), stickJson.optInt("frequency", defaults.frequency),
                            (float) stickJson.optDouble("stepLength", defaults.stepLength),
                            (float) stickJson.optDouble("deadZone", defaults.deadZone),
                            stickJson.optBoolean("rightStick", defaults.rightStick), stickJson.optBoolean("visible", defaults.visible));
                }
                result.add(new Profile(packageName, appName, bindings, dpad, dpadStick));
            }
        } catch (JSONException ignored) {
            // Start with an empty list if preferences contain invalid JSON.
        }
        if (migrated) write(context, result);
        return result;
    }

    private static void migrateLegacyMappings(JSONObject old, List<Binding> bindings) {
        float x = (float) old.optDouble("x", 0.2);
        float y = (float) old.optDouble("y", 0.75);
        float radius = (float) old.optDouble("radius", 0.12);
        float speed = Math.min(1f, (float) old.optDouble("speed", 1f));
        float offset = (float) old.optDouble("radiusOffset", 0f);
        boolean offsetEnabled = old.optBoolean("enableRadiusChange", offset != 0f);
        float distance = Math.max(0.03f, radius + (offsetEnabled ? offset : 0f)) * speed;
        boolean visible = old.optBoolean("visible", true);
        putMigratedBinding(bindings, old.optInt("upKey", KeyEvent.KEYCODE_W), x, y - distance, visible);
        putMigratedBinding(bindings, old.optInt("downKey", KeyEvent.KEYCODE_S), x, y + distance, visible);
        putMigratedBinding(bindings, old.optInt("leftKey", KeyEvent.KEYCODE_A), x - distance, y, visible);
        putMigratedBinding(bindings, old.optInt("rightKey", KeyEvent.KEYCODE_D), x + distance, y, visible);
    }

    private static void putMigratedBinding(List<Binding> bindings, int key, float x, float y, boolean visible) {
        if (key == 0) return;
        bindings.removeIf(binding -> binding.keyCode == key);
        bindings.add(new Binding(key, x, y, visible, 3f, 3f));
    }

    static void write(Context context, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) {
            JSONObject profileJson = new JSONObject();
            JSONArray bindings = new JSONArray();
            try {
                profileJson.put("package", profile.packageName);
                profileJson.put("name", profile.appName);
                for (Binding binding : profile.bindings) {
                    JSONObject item = new JSONObject();
                    item.put("key", binding.keyCode);
                    item.put("x", binding.x);
                    item.put("y", binding.y);
                    item.put("visible", binding.visible);
                    item.put("sensitivityX", binding.sensitivityX);
                    item.put("sensitivityY", binding.sensitivityY);
                    item.put("aimKey", binding.aimKey);
                    item.put("aimHold", binding.aimHold);
                    item.put("aimBoundary", binding.aimBoundary);
                    item.put("aimBoundaryRadius", binding.aimBoundaryRadius);
                    item.put("aimAutoRelease", binding.aimAutoRelease);
                    item.put("aimReleaseDelayMs", binding.aimReleaseDelayMs);
                    item.put("aimOffsetCorrection", binding.aimOffsetCorrection);
                    bindings.put(item);
                }
                profileJson.put("bindings", bindings);
                if (profile.dpad != null) {
                    JSONObject dpad = new JSONObject();
                    dpad.put("upKey", profile.dpad.upKey); dpad.put("downKey", profile.dpad.downKey);
                    dpad.put("leftKey", profile.dpad.leftKey); dpad.put("rightKey", profile.dpad.rightKey);
                    dpad.put("x", profile.dpad.x); dpad.put("y", profile.dpad.y); dpad.put("radius", profile.dpad.radius);
                    dpad.put("releaseDelayMs", profile.dpad.releaseDelayMs); dpad.put("visible", profile.dpad.visible);
                    dpad.put("changeRadiusKey", profile.dpad.changeRadiusKey); dpad.put("radiusOffset", profile.dpad.radiusOffset);
                    dpad.put("holdToChange", profile.dpad.holdToChange); dpad.put("angle", profile.dpad.angle);
                    dpad.put("movementTuningV1",true); dpad.put("stepLengthMin",profile.dpad.stepLengthMin);
                    dpad.put("transitionDelayMs", profile.dpad.transitionDelayMs);
                    dpad.put("frequency", profile.dpad.frequency); dpad.put("stepLength", profile.dpad.stepLength);
                    profileJson.put("dpad", dpad);
                }
                if (profile.dpadStick != null) {
                    JSONObject stick = new JSONObject();
                    stick.put("x", profile.dpadStick.x); stick.put("y", profile.dpadStick.y);
                    stick.put("radius", profile.dpadStick.radius); stick.put("releaseDelayMs", profile.dpadStick.releaseDelayMs);
                    stick.put("changeRadiusKey", profile.dpadStick.changeRadiusKey); stick.put("radiusOffset", profile.dpadStick.radiusOffset);
                    stick.put("holdToChange", profile.dpadStick.holdToChange); stick.put("reverseX", profile.dpadStick.reverseX);
                    stick.put("reverseY", profile.dpadStick.reverseY); stick.put("swapXY", profile.dpadStick.swapXY);
                    stick.put("frequency", profile.dpadStick.frequency); stick.put("stepLength", profile.dpadStick.stepLength);
                    stick.put("deadZone", profile.dpadStick.deadZone); stick.put("rightStick", profile.dpadStick.rightStick);
                    stick.put("visible", profile.dpadStick.visible); profileJson.put("dpadStick", stick);
                }
                array.put(profileJson);
            } catch (JSONException ignored) {
                // Skip a malformed record and preserve the other profiles.
            }
        }
        prefs(context).edit().putString(PROFILES, array.toString()).apply();
    }

    static Profile find(Context context, String packageName) {
        if (packageName == null) return null;
        for (Profile profile : read(context)) {
            if (profile.packageName.equals(packageName)) return profile;
        }
        return null;
    }

    static void putBinding(Context context, String packageName, int keyCode, float x, float y) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) {
            if (profile.packageName.equals(packageName)) {
                profile.bindings.removeIf(binding -> binding.keyCode == keyCode);
                profile.bindings.add(new Binding(keyCode, x, y));
                write(context, profiles);
                return;
            }
        }
    }

    static void removeBinding(Context context, String packageName, int keyCode) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) {
            if (profile.packageName.equals(packageName)) {
                profile.bindings.removeIf(binding -> binding.keyCode == keyCode);
                write(context, profiles);
                return;
            }
        }
    }

    // Replace a binding in one preferences update, without silently stealing another key.
    static boolean saveBinding(Context context, String packageName, int oldKey, Binding binding) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) {
            if (!profile.packageName.equals(packageName)) continue;
            for (Binding other : profile.bindings)
                if (other.keyCode == binding.keyCode && other.keyCode != oldKey) return false;
            profile.bindings.removeIf(other -> other.keyCode == oldKey);
            profile.bindings.add(binding);
            write(context, profiles);
            return true;
        }
        return false;
    }

    static void saveDpad(Context context, String packageName, Dpad dpad) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) if (profile.packageName.equals(packageName)) {
            profile.dpad = dpad; write(context, profiles); return;
        }
    }

    static void saveDpadStick(Context context, String packageName, DpadStick stick) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) if (profile.packageName.equals(packageName)) {
            profile.dpadStick = stick; write(context, profiles); return;
        }
    }

    static void removeDpad(Context context, String packageName) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) if (profile.packageName.equals(packageName)) profile.dpad = null;
        write(context, profiles);
    }

    static void removeDpadStick(Context context, String packageName) {
        List<Profile> profiles = read(context);
        for (Profile profile : profiles) if (profile.packageName.equals(packageName)) profile.dpadStick = null;
        write(context, profiles);
    }

    static void removeProfile(Context context, String packageName) {
        List<Profile> profiles = read(context);
        profiles.removeIf(profile -> profile.packageName.equals(packageName));
        write(context, profiles);
        if (packageName.equals(editingPackage(context))) setEditing(context, "", false);
    }

    static void setEditing(Context context, String packageName, boolean editing) {
        prefs(context).edit().putBoolean(EDITING, editing)
                .putString(EDIT_PACKAGE, packageName == null ? "" : packageName).apply();
    }

    static boolean isEditing(Context context) {
        return prefs(context).getBoolean(EDITING, false);
    }

    static String editingPackage(Context context) {
        return prefs(context).getString(EDIT_PACKAGE, "");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
