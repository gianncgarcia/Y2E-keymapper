package com.codex.k2er.v2;

import android.content.Context;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

final class MappingStore {
    static final String PREFS = "key_mapper";
    static final String MAPPINGS = "mappings";

    static final int[] SOURCE_CODES = {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_SEARCH
    };

    static final String[] SOURCE_NAMES = {
            "A", "B", "C", "D", "E", "F", "Enter", "Space", "Tab", "Escape",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
            "Volume Up", "Volume Down", "Play / Pause", "Camera", "Search"
    };

    static final String[] ACTION_IDS = {
            "back", "home", "recents", "notifications", "quick_settings", "volume_up",
            "volume_down", "browser", "calculator", "lock_screen"
    };

    static final String[] ACTION_NAMES = {
            "Back", "Home", "Recents", "Notifications", "Quick Settings", "Volume Up",
            "Volume Down", "Open Browser", "Open Calculator", "Lock Screen"
    };

    static final class Mapping {
        int sourceCode;
        String actionId;
        boolean enabled;

        Mapping(int sourceCode, String actionId, boolean enabled) {
            this.sourceCode = sourceCode;
            this.actionId = actionId;
            this.enabled = enabled;
        }
    }

    private MappingStore() { }

    static List<Mapping> read(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MAPPINGS, "");
        List<Mapping> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;

        String[] rows = raw.split("\\n");
        for (String row : rows) {
            String[] parts = row.split(",", -1);
            if (parts.length != 3) continue;
            try {
                int code = Integer.parseInt(parts[0]);
                String action = parts[1];
                boolean enabled = "1".equals(parts[2]);
                if (isKnownSource(code) && isKnownAction(action)) {
                    result.add(new Mapping(code, action, enabled));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries left by an interrupted write.
            }
        }
        return result;
    }

    static void write(Context context, List<Mapping> mappings) {
        StringBuilder value = new StringBuilder();
        for (Mapping mapping : mappings) {
            if (value.length() > 0) value.append('\n');
            value.append(mapping.sourceCode).append(',')
                    .append(mapping.actionId).append(',')
                    .append(mapping.enabled ? '1' : '0');
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(MAPPINGS, value.toString()).apply();
    }

    static boolean isKnownSource(int code) {
        for (int sourceCode : SOURCE_CODES) if (sourceCode == code) return true;
        return false;
    }

    static boolean isKnownAction(String id) {
        for (String actionId : ACTION_IDS) if (actionId.equals(id)) return true;
        return false;
    }

    static String sourceName(int code) {
        for (int i = 0; i < SOURCE_CODES.length; i++) {
            if (SOURCE_CODES[i] == code) return SOURCE_NAMES[i];
        }
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
    }

    static String actionName(String id) {
        for (int i = 0; i < ACTION_IDS.length; i++) {
            if (ACTION_IDS[i].equals(id)) return ACTION_NAMES[i];
        }
        return id;
    }

    static int actionIndex(String id) {
        for (int i = 0; i < ACTION_IDS.length; i++) {
            if (ACTION_IDS[i].equals(id)) return i;
        }
        return 0;
    }
}
