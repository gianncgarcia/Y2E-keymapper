package com.codex.k2er.v2;

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

        Binding(int keyCode, float x, float y) {
            this(keyCode, x, y, true, 3f, 3f);
        }

        Binding(int keyCode, float x, float y, boolean visible, float sensitivityX, float sensitivityY) {
            this(keyCode, x, y, visible, sensitivityX, sensitivityY, MOUSE_MIDDLE, false);
        }

        Binding(int keyCode, float x, float y, boolean visible, float sensitivityX, float sensitivityY, int aimKey, boolean aimHold) {
            this.keyCode = keyCode;
            this.x = Math.max(0f, Math.min(1f, x));
            this.y = Math.max(0f, Math.min(1f, y));
            this.visible = visible;
            this.sensitivityX = Math.max(0.2f, Math.min(8f, sensitivityX));
            this.sensitivityY = Math.max(0.2f, Math.min(8f, sensitivityY));
            this.aimKey = aimKey;
            this.aimHold = aimHold;
        }

        Binding at(float x, float y) {
            return new Binding(keyCode, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold);
        }

        Binding withKey(int key) { return new Binding(key, x, y, visible, sensitivityX, sensitivityY, aimKey, aimHold); }
        Binding withAim(int key, boolean hold) { return new Binding(keyCode, x, y, visible, sensitivityX, sensitivityY, key, hold); }
        Binding withSensitivity(float sx, float sy) { return new Binding(keyCode, x, y, visible, sx, sy, aimKey, aimHold); }
        Binding withVisibility(boolean show) { return new Binding(keyCode, x, y, show, sensitivityX, sensitivityY, aimKey, aimHold); }
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

        Profile(String packageName, String appName, List<Binding> bindings) {
            this.packageName = packageName;
            this.appName = appName;
            this.bindings = bindings;
        }
    }

    private GameProfileStore() { }

    static List<Profile> read(Context context) {
        String raw = prefs(context).getString(PROFILES, "[]");
        List<Profile> result = new ArrayList<>();
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
                                    binding.optBoolean("aimHold", false)));
                        }
                    }
                }
                result.add(new Profile(packageName, appName, bindings));
            }
        } catch (JSONException ignored) {
            // Start with an empty list if preferences contain invalid JSON.
        }
        return result;
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
                    bindings.put(item);
                }
                profileJson.put("bindings", bindings);
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
