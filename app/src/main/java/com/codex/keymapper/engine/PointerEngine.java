package com.codex.keymapper.engine;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

/** One event stream owns all simulated fingers, including camera movement and clicks. */
public final class PointerEngine {
    public interface Touch {
        void down(String id, float x, float y);
        void move(String id, float x, float y);
        void up(String id);
        default void upAt(String id, float x, float y) { up(id); }
        void cancel(String id);
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
        public boolean aimBoundary, aimAutoRelease = true, aimOffsetCorrection;
        public float aimBoundaryRadius = 0.25f;
        public int aimReleaseDelayMs = 200;
        public Dpad dpad;
        public DpadStick dpadStick;
    }
    public static final class Dpad {
        public final Point center;
        public int transitionDelayMs=16;
        public float diagonalAngle=45, movementStepMax=50;
        public int movementFrequency=60, movementStepMin=1;
        public boolean movementTuningEnabled;
        public final float radius, radiusOffset;
        public final boolean holdToChange;
        public final String up, down, left, right, changeRadiusKey;
        // Accept the legacy wire format so existing profiles retain placement,
        // radius and keys. XtMapper has no legacy movement-tuning parameters.
        Dpad(Point center, float radius, int releaseDelayMs, String up, String down, String left, String right,
             String changeRadiusKey, float radiusOffset, boolean holdToChange, float angle, int frequency, float stepLength) {
            this.center = center; this.radius = Math.max(0.02f, Math.min(0.45f, radius));
            this.radiusOffset = radiusOffset; this.holdToChange = holdToChange;
            this.changeRadiusKey = normalize(changeRadiusKey);
            this.up = normalize(up); this.down = normalize(down); this.left = normalize(left); this.right = normalize(right);
        }
    }
    public static final class DpadStick {
        public final Point center;
        public final float radius, radiusOffset, deadZone, stepLength;
        public final int releaseDelayMs, frequency;
        public final String changeRadiusKey;
        public final boolean holdToChange, reverseX, reverseY, swapXY, rightStick;
        DpadStick(Point center, float radius, int releaseDelayMs, String changeRadiusKey, float radiusOffset,
                  boolean holdToChange, boolean reverseX, boolean reverseY, boolean swapXY,
                  int frequency, float stepLength, float deadZone, boolean rightStick) {
            this.center = center; this.radius = Math.max(0.02f, Math.min(0.45f, radius));
            this.releaseDelayMs = Math.max(0, Math.min(1000, releaseDelayMs));
            this.changeRadiusKey = normalize(changeRadiusKey);
            this.radiusOffset = Math.max(-0.4f, Math.min(0.4f, radiusOffset));
            this.holdToChange = holdToChange; this.reverseX = reverseX; this.reverseY = reverseY;
            this.swapXY = swapXY; this.frequency = Math.max(1, Math.min(240, frequency));
            this.stepLength = Math.max(0.001f, Math.min(0.2f, stepLength));
            this.deadZone = Math.max(0f, Math.min(0.9f, deadZone)); this.rightStick = rightStick;
        }
    }
    private final Touch touch;
    private Profile profile;
    private final Set<String> pressed = new HashSet<>(), drags = new HashSet<>(), aimHeld = new HashSet<>();
    private XtDpadHandler xtDpad;
    private final Map<String, float[]> stickAxes = new HashMap<>(), stickPositions = new HashMap<>();
    private final Map<String, Long> stickReleaseAt = new HashMap<>(), stickLastUpdate = new HashMap<>();
    private final Set<String> stickPointers = new HashSet<>(), radiusHeldKeys = new HashSet<>();
    private int width = 1, height = 1;
    private boolean paused = true, keyboardMode, aimOn, aimDown;
    private float cursorX, cursorY, aimX, aimY;
    private float pendingAimX, pendingAimY;
    private long aimLastMotionAt;
    private boolean aimAutoReleased;
    private boolean radiusToggled;
    private String toggleModifier = "KEY_LEFTCTRL", toggleKey = "KEY_Q";
    private final Set<String> hotkeyModifierDown = new HashSet<>();
    private boolean overlayHidden, overlayTogglePending;

    public PointerEngine(Touch touch) { this.touch = touch; }
    public void configure(Profile profile, int width, int height) {
        reset(); this.profile = profile; this.width = Math.max(1, width); this.height = Math.max(1, height);
        cursorX = width / 2f; cursorY = height / 2f;
        xtDpad = profile == null || profile.dpad == null ? null
                : new XtDpadHandler(touch, profile.dpad, this.width, this.height);
    }
    public void pause(boolean value) { if (value != paused) { reset(); paused = value; } }
    public void setKeyboardMode(boolean value) {
        if (keyboardMode == value) return;
        keyboardMode = value;
        if (value) reset();
    }
    public void reset() {
        touch.cancel(); pressed.clear(); drags.clear(); aimHeld.clear(); hotkeyModifierDown.clear();
        if (xtDpad != null) xtDpad.reset();
        stickAxes.clear(); stickPositions.clear(); stickReleaseAt.clear(); stickLastUpdate.clear();
        stickPointers.clear(); radiusHeldKeys.clear(); radiusToggled = false;
        aimOn = false; aimDown = false; overlayTogglePending = false;
        aimLastMotionAt = 0; aimAutoReleased = false; pendingAimX=pendingAimY=0;
    }
    public boolean aimOn() { return aimOn; }
    public boolean isDpadKey(String key) {
        return xtDpad != null && xtDpad.handles(normalize(key));
    }
    public String dpadDiagnosticState() { return xtDpad == null ? "xtmapper inactive" : xtDpad.diagnosticState(); }
    public long dpadRevision() { return xtDpad == null ? -1 : xtDpad.revision(); }
    public void setOverlayHotkey(String modifier, String key) {
        toggleModifier = modifier == null ? "" : normalize(modifier);
        toggleKey = key == null ? "" : normalize(key);
    }
    public void setOverlayHidden(boolean hidden) {
        if (overlayHidden == hidden) return;
        overlayHidden = hidden;
    }
    public boolean consumeOverlayToggle() {
        boolean toggled = overlayTogglePending;
        overlayTogglePending = false;
        return toggled;
    }
    public boolean overlayHidden() { return overlayHidden; }
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
        key(device, rawKey, down, System.currentTimeMillis());
    }
    public void key(String device, String rawKey, boolean down, long now) {
        key(device, rawKey, down, now, false);
    }
    public void key(String device, String rawKey, boolean down, long now, boolean repeat) {
        if (paused || profile == null) return;
        String key = normalize(rawKey), id = device + ":" + key;
        boolean modifier = key.equals(toggleModifier);
        if (modifier) {
            if (down) hotkeyModifierDown.add(id); else hotkeyModifierDown.remove(id);
        }

        if (!keyboardMode && xtDpad != null && xtDpad.handles(key)) {
            if (!repeat) xtDpad.key(key, down, now);
            return;
        }

        if (!keyboardMode && xtDpad != null && xtDpad.radiusKey(key, down, now, repeat)) return;
        if (repeat) return;
        if (down ? !pressed.add(id) : !pressed.remove(id)) return;

        // Keep the visual-only mode's overlay shortcut available while all
        // mapped game touches are suspended.
        if (!modifier && !repeat && down && key.equals(toggleKey)
                && (toggleModifier.isEmpty() || !hotkeyModifierDown.isEmpty())) {
            overlayHidden = !overlayHidden; overlayTogglePending = true;
            return;
        }
        if (keyboardMode) return;

        boolean radiusShortcut = false;
        if (profile.dpadStick != null && !profile.dpadStick.changeRadiusKey.isEmpty()
                && key.equals(profile.dpadStick.changeRadiusKey)) {
            if (profile.dpadStick.holdToChange) {
                if (down) radiusHeldKeys.add(id); else radiusHeldKeys.remove(id);
            } else if (down && !repeat) radiusToggled = !radiusToggled;
            radiusShortcut = true;
        }
        if (radiusShortcut) return;

        if (modifier) return;
        // The aim shortcut is the master switch for a profile. It must remain
        // usable while the rest of the mapper is disabled.
        if (profile.aim != null && key.equals(normalize(profile.aimKey))) {
            if (profile.hold) {
                if (down) aimHeld.add(id); else aimHeld.remove(id);
                setAim(!aimHeld.isEmpty(), now);
            } else if (down && !repeat) setAim(!aimOn, now);
            return;
        }

        // In normal gameplay the purple cursor is the mouse pointer. A left
        // click must touch the point under that cursor, even when an older
        // profile also contains a fixed BTN_LEFT mapping.
        if (key.equals("BTN_LEFT") && !aimOn) {
            if (down) beginDirectMouse(id, cursorX, cursorY); else endDirectMouse(id);
            return;
        }

        // Each mapped key owns the device:key pointer, just like K2er.
        Point mapped = profile.keys.get(key);
        if (mapped != null) {
            if (down) touch.down(id, mapped.x * width, mapped.y * height); else touch.up(id);
        }
    }
    private void beginDirectMouse(String id, float x, float y) { drags.add(id); touch.down(id, x, y); }
    private void endDirectMouse(String id) { if (drags.remove(id)) touch.up(id); }
    /** Feed a normalized physical gamepad axis in the range -1..1. */
    public void axis(String device, String axis, float value, long now) {
        if (paused || keyboardMode || profile == null || profile.dpadStick == null) return;
        DpadStick stick = profile.dpadStick;
        String xAxis = stick.rightStick ? "ABS_RX" : "ABS_X";
        String yAxis = stick.rightStick ? "ABS_RY" : "ABS_Y";
        if (!axis.equals(xAxis) && !axis.equals(yAxis)) return;
        float[] state = stickAxes.computeIfAbsent(device, ignored -> new float[2]);
        state[axis.equals(xAxis) ? 0 : 1] = Math.max(-1f, Math.min(1f, value));
        float x = state[0], y = state[1];
        if (stick.swapXY) { float temp = x; x = y; y = temp; }
        if (stick.reverseX) x = -x;
        if (stick.reverseY) y = -y;
        float magnitude = (float) Math.hypot(x, y);
        String pointer = "stick:" + device;
        if (magnitude <= stick.deadZone) {
            if (stickPointers.contains(pointer)) {
                touch.move(pointer, bound(stick.center.x * width, width), bound(stick.center.y * height, height));
                stickReleaseAt.put(pointer, now + stick.releaseDelayMs);
                if (stick.releaseDelayMs == 0) releaseStick(pointer);
            }
            return;
        }
        float adjusted = Math.min(1f, (magnitude - stick.deadZone) / Math.max(0.001f, 1f - stick.deadZone));
        float factor = adjusted / magnitude;
        x *= factor; y *= factor;
        float radius = stick.radius;
        boolean change = stick.holdToChange ? !radiusHeldKeys.isEmpty() : radiusToggled;
        if (change) radius += stick.radiusOffset;
        radius = Math.max(0.02f, Math.min(0.45f, radius));
        float scale = radius * Math.min(width, height);
        float targetX = bound(stick.center.x * width + x * scale, width);
        float targetY = bound(stick.center.y * height + y * scale, height);
        stickReleaseAt.remove(pointer);
        long interval = Math.max(1, 1000L / stick.frequency);
        Long last = stickLastUpdate.get(pointer);
        if (stickPointers.contains(pointer) && last != null && now - last < interval) return;
        float[] previous = stickPositions.get(pointer);
        float minStep = stick.stepLength * Math.min(width, height);
        if (stickPointers.contains(pointer) && previous != null
                && Math.hypot(targetX - previous[0], targetY - previous[1]) < minStep) return;
        if (!stickPointers.contains(pointer)) { touch.down(pointer, targetX, targetY); stickPointers.add(pointer); }
        else touch.move(pointer, targetX, targetY);
        stickPositions.put(pointer, new float[]{targetX, targetY}); stickLastUpdate.put(pointer, now);
    }

    private void releaseStick(String pointer) {
        touch.up(pointer); stickPointers.remove(pointer); stickReleaseAt.remove(pointer);
        stickPositions.remove(pointer); stickLastUpdate.remove(pointer);
    }
    private void setAim(boolean on, long now) {
        if (aimOn == on) return;
        aimOn = on;
        aimAutoReleased = false; aimLastMotionAt = on ? now : 0;
        if (!on) {
            pendingAimX=pendingAimY=0;
            releaseAim();
            for (String drag : new HashSet<>(drags)) endDirectMouse(drag);
            aimHeld.clear();
        } else {
            for (String drag : new HashSet<>(drags)) endDirectMouse(drag);
            // Start the camera finger immediately. Waiting for the first
            // mouse report makes aim feel like it misses its first movement.
            if (profile != null && profile.aim != null) {
                aimX = bound(profile.aim.x * width, width);
                aimY = bound(profile.aim.y * height, height);
                touch.down("aim", aimX, aimY); aimDown = true;
            }
        }
    }
    private void releaseAim() { if (aimDown) touch.up("aim"); aimDown = false; }
    public void motion(String device, float dx, float dy, long now) {
        if (paused || profile == null || dx == 0 && dy == 0) return;
        if (keyboardMode) {
            if (!aimOn) {
                cursorX = bound(cursorX + dx * 2, width);
                cursorY = bound(cursorY + dy * 2, height);
            }
            return;
        }
        // Keep a separate in-game cursor position while aim is off. This is
        // only visual state; no simulated touch is emitted in this mode.
        if (!aimOn) {
            cursorX = bound(cursorX + dx * 2, width); cursorY = bound(cursorY + dy * 2, height);
            if (profile.aim != null) {
                // With an AIM mapping present, direct mouse clicks are still
                // allowed while aim is off. Keep the touch finger moving
                // until BTN_LEFT is released so games receive a drag.
                String drag = device + ":BTN_LEFT";
                if (drags.contains(drag)) touch.move(drag, cursorX, cursorY);
                return;
            }
        }
        if (aimOn && profile.aim != null) {
            aimLastMotionAt = now;
            if (!aimDown) {
                if (aimAutoReleased && profile.aimOffsetCorrection) {
                    aimX = bound(profile.aim.x * width, width); aimY = bound(profile.aim.y * height, height);
                }
                if (!aimAutoReleased) {
                    aimX = bound(profile.aim.x * width, width); aimY = bound(profile.aim.y * height, height);
                }
                touch.down("aim", aimX, aimY); aimDown = true; aimAutoReleased = false;
            }
            float nextX = aimX + dx * profile.gainX, nextY = aimY + dy * profile.gainY;
            float anchorX = bound(profile.aim.x * width, width);
            float anchorY = bound(profile.aim.y * height, height);
            if (profile.aimBoundary) {
                pendingAimX += dx * profile.gainX;
                pendingAimY += dy * profile.gainY;
                drainBoundaryAim();
                return;
            } else if (nextX < 1 || nextX >= width - 1 || nextY < 1 || nextY >= height - 1) {
                // Without a radius boundary, keep the existing screen-edge
                // recenter behavior when the virtual touch reaches the edge.
                // End the edge pointer before recreating it at the anchor. This
                // avoids a reverse move from the edge, which the game reads as
                // a camera flick in the opposite direction.
                touch.up("aim");
                aimX = anchorX; aimY = anchorY;
                touch.down("aim", aimX, aimY); aimDown = true;
                nextX = aimX + dx * profile.gainX;
                nextY = aimY + dy * profile.gainY;
            }
            nextX = bound(nextX, width); nextY = bound(nextY, height);
            if (Float.compare(nextX, aimX) != 0 || Float.compare(nextY, aimY) != 0) {
                aimX = nextX; aimY = nextY;
                touch.move("aim", aimX, aimY);
            }
        } else if (profile.aim == null) {
            String drag = device + ":BTN_LEFT";
            if (drags.contains(drag)) touch.move(drag, cursorX, cursorY);
        }
    }
    private void drainBoundaryAim() {
        if (!aimOn || !aimDown || profile == null || !profile.aimBoundary) return;
        float ax=bound(profile.aim.x*width,width), ay=bound(profile.aim.y*height,height);
        float r=Math.max(1f,Math.min(profile.aimBoundaryRadius*Math.min(width,height),
                Math.min(Math.min(ax-1,width-2-ax),Math.min(ay-1,height-2-ay))));
        // Bound injection work per pass; retain any remainder for the next tick.
        for(int step=0;step<8 && (pendingAimX!=0 || pendingAimY!=0);step++) {
            float rx=pendingAimX, ry=pendingAimY, px=aimX-ax, py=aimY-ay;
            float endX=px+rx, endY=py+ry;
            if(Math.hypot(endX,endY)<=r) {
                aimX=bound(aimX+rx,width); aimY=bound(aimY+ry,height);
                touch.move("aim",aimX,aimY);pendingAimX=pendingAimY=0;break;
            }
            double a=(double)rx*rx+(double)ry*ry;
            double b=2*((double)px*rx+(double)py*ry);
            double c=(double)px*px+(double)py*py-(double)r*r;
            float t=(float)Math.max(0,Math.min(1,(-b+Math.sqrt(Math.max(0,b*b-4*a*c)))/(2*a)));
            float edgeX=bound(aimX+rx*t,width), edgeY=bound(aimY+ry*t,height);
            if(edgeX!=aimX || edgeY!=aimY)touch.move("aim",edgeX,edgeY);
            pendingAimX=rx*(1-t);pendingAimY=ry*(1-t);
            touch.up("aim");touch.down("aim",ax,ay);aimX=ax;aimY=ay;
        }
    }
    public void wheel(String device, int direction) {
        wheel(device, direction, System.currentTimeMillis());
    }
    public void wheel(String device, int direction, long now) {
        if (paused || keyboardMode || profile == null) return;
        Point target = profile.keys.get(direction > 0 ? "WHEEL_UP" : "WHEEL_DOWN");
        if (target != null) { String id = device + ":wheel"; touch.down(id, target.x * width, target.y * height); touch.up(id); }
    }
    public void tick(long now) {
        if (xtDpad != null) xtDpad.tick(now);
        drainBoundaryAim();
        Iterator<Map.Entry<String, Long>> it = stickReleaseAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now >= entry.getValue()) { String pointer = entry.getKey(); it.remove(); releaseStick(pointer); }
        }
        if (aimOn && aimDown && profile != null && profile.aimAutoRelease
                && pendingAimX == 0 && pendingAimY == 0
                && aimLastMotionAt != 0 && now - aimLastMotionAt >= profile.aimReleaseDelayMs) {
            releaseAim(); aimAutoReleased = true;
        }
    }
    private float bound(float value, int limit) { return Math.max(1, Math.min(Math.max(1, limit - 2), value)); }
}
