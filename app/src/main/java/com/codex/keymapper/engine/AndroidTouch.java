package com.codex.keymapper.engine;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/** Runs only as the ADB shell process; the normal app has no injection privilege. */
public final class AndroidTouch implements PointerEngine.Touch {
    private static final class Finger {
        int id; float x, y;
        Finger(int id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }
    private final LinkedHashMap<String, Finger> fingers = new LinkedHashMap<>();
    private final MotionEvent.PointerProperties[][] propertiesByCount = new MotionEvent.PointerProperties[11][];
    private final MotionEvent.PointerCoords[][] coordsByCount = new MotionEvent.PointerCoords[11][];
    private final Object manager;
    private final Method inject;
    private long downTime;
    public AndroidTouch() throws Exception {
        for (int count = 1; count <= 10; count++) {
            propertiesByCount[count] = new MotionEvent.PointerProperties[count];
            coordsByCount[count] = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                propertiesByCount[count][i] = new MotionEvent.PointerProperties();
                propertiesByCount[count][i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordsByCount[count][i] = new MotionEvent.PointerCoords();
                coordsByCount[count][i].pressure = 1;
                coordsByCount[count][i].size = 0.05f;
            }
        }
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
        if (finger.x == x && finger.y == y) return;
        finger.x = x; finger.y = y; send(MotionEvent.ACTION_MOVE);
    }
    private int indexOf(String name) {
        int index = 0;
        for (String key : fingers.keySet()) { if (key.equals(name)) return index; index++; }
        return -1;
    }
    @Override public void up(String name) {
        if (!fingers.containsKey(name)) return;
        int index = indexOf(name);
        send(fingers.size() == 1 ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        fingers.remove(name);
    }
    @Override public void upAt(String name, float x, float y) {
        Finger finger = fingers.get(name); if (finger == null) return;
        finger.x = x; finger.y = y;
        up(name);
    }
    @Override public void cancel(String name) {
        if (!fingers.containsKey(name)) return;
        int index = indexOf(name);
        int action = fingers.size() == 1 ? MotionEvent.ACTION_CANCEL
                : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        send(action, MotionEvent.FLAG_CANCELED);
        fingers.remove(name);
    }
    @Override public void cancel() {
        if (!fingers.isEmpty()) { send(MotionEvent.ACTION_CANCEL, MotionEvent.FLAG_CANCELED); fingers.clear(); }
    }
    private void send(int action) {
        send(action, 0);
    }
    private void send(int action, int flags) {
        int count = fingers.size();
        MotionEvent.PointerProperties[] properties = propertiesByCount[count];
        MotionEvent.PointerCoords[] coords = coordsByCount[count];
        int i = 0;
        for (Finger f : fingers.values()) {
            properties[i].id = f.id;
            coords[i].x = f.x; coords[i].y = f.y; i++;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
        try {
            if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IllegalStateException("Android rejected touch injection");
        } catch (Exception error) { throw new IllegalStateException("Enable USB debugging (Security settings) for simulated touches", error); }
        finally { event.recycle(); }
    }

}
