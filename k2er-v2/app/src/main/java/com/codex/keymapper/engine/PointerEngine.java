package com.codex.k2er.v2.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One event stream owns all simulated fingers, including camera movement and clicks. */
public final class PointerEngine {
    public interface Touch {
        void down(String id, float x, float y);
        void move(String id, float x, float y);
        void up(String id);
        void cancel();
    }
    public static final class Point {
        public final float x, y;
        public Point(float x, float y) { this.x = x; this.y = y; }
    }
    public static final class Profile {
        public final Map<String, Point> keys = new HashMap<>();
        public Point aim;
        public String aimKey = "BTN_MIDDLE";
        public boolean hold;
        public float gainX = 3, gainY = 3;
    }
    private final Touch touch;
    private Profile profile;
    private final Set<String> pressed = new HashSet<>(), drags = new HashSet<>(), aimHeld = new HashSet<>();
    private int width = 1, height = 1;
    private boolean paused = true, aimOn, aimDown;
    private float cursorX, cursorY, aimX, aimY;
    private long lastAimMotion;

    public PointerEngine(Touch touch) { this.touch = touch; }
    public void configure(Profile profile, int width, int height) {
        reset(); this.profile = profile; this.width = Math.max(1, width); this.height = Math.max(1, height);
        cursorX = width / 2f; cursorY = height / 2f;
    }
    public void pause(boolean value) { if (value != paused) { reset(); paused = value; } }
    public void reset() {
        touch.cancel(); pressed.clear(); drags.clear(); aimHeld.clear(); aimOn = false; aimDown = false;
    }
    public boolean aimOn() { return aimOn; }
    public float cursorX() { return cursorX; }
    public float cursorY() { return cursorY; }
    public static String normalize(String key) {
        if (key.equals("BTN_MOUSE") || key.equals("BTN_LEFT")) return "BTN_LEFT";
        if (key.equals("BTN_A")) return "BTN_SOUTH";
        if (key.equals("BTN_B")) return "BTN_EAST";
        if (key.equals("BTN_X")) return "BTN_NORTH";
        if (key.equals("BTN_Y")) return "BTN_WEST";
        return key;
    }
    public void key(String device, String rawKey, boolean down) {
        if (paused || profile == null) return;
        String key = normalize(rawKey), id = device + ":" + key;
        if (down ? !pressed.add(id) : !pressed.remove(id)) return;
        if (profile.aim != null && key.equals(normalize(profile.aimKey))) {
            if (profile.hold) {
                if (down) aimHeld.add(id); else aimHeld.remove(id);
                setAim(!aimHeld.isEmpty());
            } else if (down) setAim(!aimOn);
        }
        Point mapped = profile.keys.get(key);
        if (mapped != null) {
            if (down) touch.down(id, mapped.x * width, mapped.y * height); else touch.up(id);
        } else if (key.equals("BTN_LEFT") && !key.equals(normalize(profile.aimKey))) {
            if (down && !aimOn) { drags.add(id); touch.down(id, cursorX, cursorY); }
            else if (!down) { drags.remove(id); touch.up(id); }
        }
    }
    private void setAim(boolean on) {
        if (aimOn == on) return;
        aimOn = on;
        if (!on) releaseAim();
        if (on) { for (String drag : drags) touch.up(drag); drags.clear(); }
    }
    private void releaseAim() { if (aimDown) touch.up("aim"); aimDown = false; }
    public void motion(String device, float dx, float dy, long now) {
        if (paused || profile == null || dx == 0 && dy == 0) return;
        if (aimOn && profile.aim != null) {
            if (!aimDown) {
                aimX = bound(profile.aim.x * width, width); aimY = bound(profile.aim.y * height, height);
                touch.down("aim", aimX, aimY); aimDown = true;
            }
            float nextX = aimX + dx * profile.gainX, nextY = aimY + dy * profile.gainY;
            // Recenter only at the edge, keeping the same finger down during normal movement.
            if (nextX < 1 || nextX >= width - 1 || nextY < 1 || nextY >= height - 1) {
                releaseAim(); aimX = bound(profile.aim.x * width, width); aimY = bound(profile.aim.y * height, height);
                touch.down("aim", aimX, aimY); aimDown = true;
                nextX = aimX + dx * profile.gainX; nextY = aimY + dy * profile.gainY;
            }
            aimX = bound(nextX, width); aimY = bound(nextY, height);
            touch.move("aim", aimX, aimY); lastAimMotion = now;
        } else {
            cursorX = bound(cursorX + dx * 2, width); cursorY = bound(cursorY + dy * 2, height);
            String drag = device + ":BTN_LEFT";
            if (drags.contains(drag)) touch.move(drag, cursorX, cursorY);
        }
    }
    public void wheel(String device, int direction) {
        if (paused || profile == null) return;
        Point target = profile.keys.get(direction > 0 ? "WHEEL_UP" : "WHEEL_DOWN");
        if (target != null) { String id = device + ":wheel"; touch.down(id, target.x * width, target.y * height); touch.up(id); }
    }
    public void tick(long now) {
        // Release an idle camera finger, avoiding long presses and stuck touches after unplugging.
        if (aimDown && now - lastAimMotion > 140) releaseAim();
    }
    private float bound(float value, int limit) { return Math.max(1, Math.min(Math.max(1, limit - 2), value)); }
}
