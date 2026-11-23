package com.codex.k2er.v2.engine;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Runs only as the ADB shell process; the normal app has no injection privilege. */
public final class AndroidTouch implements PointerEngine.Touch {
    private static final class Finger {
        int id; float x, y;
        Finger(int id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }
    private final LinkedHashMap<String, Finger> fingers = new LinkedHashMap<>();
    private final Object manager;
    private final Method inject;
    private long downTime;
    public AndroidTouch() throws Exception {
        Class<?> type;
        try { type = Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch (ClassNotFoundException oldAndroid) { type = Class.forName("android.hardware.input.InputManager"); }
        manager = type.getMethod("getInstance").invoke(null);
        inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
    }
    @Override public void down(String name, float x, float y) {
        if (fingers.containsKey(name) || fingers.size() >= 10) return;
        int id = 0;
        while (containsId(id)) id++;
        if (fingers.isEmpty()) downTime = SystemClock.uptimeMillis();
        fingers.put(name, new Finger(id, x, y));
        send(fingers.size() == 1 ? MotionEvent.ACTION_DOWN
                : MotionEvent.ACTION_POINTER_DOWN | ((fingers.size() - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
    }
    private boolean containsId(int id) { for (Finger f : fingers.values()) if (f.id == id) return true; return false; }
    @Override public void move(String name, float x, float y) {
        Finger finger = fingers.get(name); if (finger == null) return;
        finger.x = x; finger.y = y; send(MotionEvent.ACTION_MOVE);
    }
    @Override public void up(String name) {
        if (!fingers.containsKey(name)) return;
        int index = new ArrayList<>(fingers.keySet()).indexOf(name);
        send(fingers.size() == 1 ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        fingers.remove(name);
    }
    @Override public void cancel() {
        if (!fingers.isEmpty()) { send(MotionEvent.ACTION_CANCEL); fingers.clear(); }
    }
    private void send(int action) {
        int count = fingers.size();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        int i = 0;
        for (Finger f : fingers.values()) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = f.id; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = f.x; coords[i].y = f.y; coords[i].pressure = 1; coords[i].size = 0.05f; i++;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IllegalStateException("Android rejected touch injection");
        } catch (Exception error) { throw new IllegalStateException("Enable USB debugging (Security settings) for simulated touches", error); }
        finally { event.recycle(); }
    }
}
