/*
 * Virtual joystick direction handler: shell-engine scheduling, complete
 * held-state/opposite-key resolution, superseded-event cancellation and
 * walking-radius shortcut.
 */
package com.codex.keymapper.engine;

/** Circular joystick with bounded continuous direction drags. */
final class DpadHandler {
    private final PointerEngine.Touch touch;
    private final String[] keys;
    private final long[] pressOrder = new long[4];
    private long pressSequence;
    private final boolean[] held = new boolean[4]; // up, down, left, right
    private boolean moving;
    private float startX,startY,targetX,targetY;
    private double startAngle,angleTravel;
    private float startRadius,targetRadius;
    private long transitionStart,transitionEnd;
    private final float centerX, centerY, baseRadius, radiusOffset, unit;
    private final int width, height;
    private final String radiusKey;
    private final boolean holdToChange;
    private boolean radiusHeld, radiusToggled;
    private final int delayMillis;
    private final boolean tuningEnabled;
    private final float diagonalX,diagonalY,stepPixels;
    private final double intervalMs;
    private double nextMoveAt;
    private float maxStep;
    private long revision;
    private float currentX, currentY;
    private boolean touching;

    DpadHandler(PointerEngine.Touch touch, PointerEngine.Dpad dpad, int width, int height) {
        this.touch=touch; this.width=width; this.height=height;
        keys=new String[]{dpad.up,dpad.down,dpad.left,dpad.right};
        centerX=clamp(dpad.center.x*width,width); centerY=clamp(dpad.center.y*height,height);
        unit=Math.min(width,height); baseRadius=dpad.radius; radiusOffset=dpad.radiusOffset;
        radiusKey=dpad.changeRadiusKey; holdToChange=dpad.holdToChange;
        tuningEnabled=dpad.movementTuningEnabled;
        double angle=Math.toRadians(dpad.diagonalAngle);
        float sx=(float)Math.sin(angle),sy=(float)Math.cos(angle),scale=Math.max(sx,sy);
        diagonalX=sx;diagonalY=sy;
        stepPixels=(dpad.movementStepMin+dpad.movementStepMax)/2f;
        intervalMs=1000.0/dpad.movementFrequency;
        delayMillis=dpad.transitionDelayMs; // Duration of a bounded linear drag, not a queued jump.
    }
    boolean handles(String key) { return index(key)>=0; }
    private int index(String key) {
        for(int i=0;i<keys.length;i++) if(keys[i].equals(key)) return i;
        return -1;
    }
    void key(String key, boolean down, long now) {
        int i=index(key); if(i<0)return;
        if (held[i] == down) return;
        tick(now); // Preserve elapsed movement when rapid key changes retarget the drag.
        held[i]=down;
        if (down) pressOrder[i]=++pressSequence;
        revision++;
        update(now);
    }
    boolean radiusKey(String key, boolean down, long now, boolean repeat) {
        if (radiusKey.isEmpty() || !radiusKey.equals(key)) return false;
        if (repeat || radiusHeld == down) return true;
        tick(now);
        radiusHeld=down;
        if (!holdToChange && down) radiusToggled=!radiusToggled;
        update(now);
        return true;
    }
    private void update(long now) {
        // Modified from upstream: derive both axes from the full held state,
        // using last-pressed priority and restoring the still-held opposite on release.
        int x=axis(2,3);
        int y=axis(0,1);
        if (x==0 && y==0) {
            moving=false;
            if (touching) { touch.up("dpad"); touching=false; revision++; }
            return;
        }
        if (!touching) {
            touch.down("dpad",centerX,centerY); touching=true;
            currentX=centerX; currentY=centerY; nextMoveAt=now+intervalMs; revision++;
        }
        boolean walking=holdToChange?radiusHeld:radiusToggled;
        float radius=Math.max(0.02f,Math.min(0.45f,baseRadius+(walking?radiusOffset:0f)))*unit;
        maxStep=Math.max(1f,stepPixels);
        float horizontal=x,vertical=y;
        if(x!=0 && y!=0){horizontal*=tuningEnabled?diagonalX:0.70710678f;vertical*=tuningEnabled?diagonalY:0.70710678f;}
        float nextX=clamp(centerX+horizontal*radius,width),nextY=clamp(centerY+vertical*radius,height);
        if (moving && nextX==targetX && nextY==targetY) return;
        // Retarget from the last position actually sent. Never replay an old
        // direction, overshoot an endpoint, or lift/recenter between keys.
        startX=currentX;startY=currentY;targetX=nextX;targetY=nextY;
        startRadius=(float)Math.hypot(startX-centerX,startY-centerY);
        targetRadius=(float)Math.hypot(targetX-centerX,targetY-centerY);
        double targetAngle=Math.atan2(targetY-centerY,targetX-centerX);
        startAngle=startRadius<0.01f?targetAngle:Math.atan2(startY-centerY,startX-centerX);
        angleTravel=shortestAngle(targetAngle-startAngle);
        transitionStart=now;transitionEnd=now+delayMillis;
        moving=currentX!=targetX || currentY!=targetY;
        if(delayMillis==0)tick(now);
    }
    private static double shortestAngle(double radians) {
        while(radians>Math.PI)radians-=2*Math.PI;
        while(radians<-Math.PI)radians+=2*Math.PI;
        return radians;
    }
    private static float clamp(float value,int size) {
        return Math.max(1f,Math.min(Math.max(1,size-2),value));
    }
    private int axis(int negative, int positive) {
        if (held[negative] && held[positive]) return pressOrder[positive]>pressOrder[negative]?1:-1;
        return held[positive]?1:held[negative]?-1:0;
    }
    void tick(long now) {
        if (!moving || !touching) return;
        if(tuningEnabled && now<nextMoveAt)return;
        float t=transitionEnd<=transitionStart?1f:Math.max(0f,Math.min(1f,
                (float)(now-transitionStart)/(transitionEnd-transitionStart)));
        double desiredAngle=startAngle+angleTravel*t;
        float desiredRadius=startRadius+(targetRadius-startRadius)*t;
        float currentRadius=(float)Math.hypot(currentX-centerX,currentY-centerY);
        double currentAngle=currentRadius<0.01f?desiredAngle:Math.atan2(currentY-centerY,currentX-centerX);
        double da=shortestAngle(desiredAngle-currentAngle);
        float dr=desiredRadius-currentRadius;
        double fraction=1;
        if(tuningEnabled) {
            // Limit travel along the arc, rather than shortening a chord
            // through the center. Radius changes only between endpoint radii
            // (including intentional walking/running radius changes).
            double distance=Math.abs(dr)+Math.max(currentRadius,desiredRadius)*Math.abs(da);
            if(distance>maxStep)fraction=maxStep/distance;
            nextMoveAt=now+intervalMs;
        }
        float radius=currentRadius+(float)(dr*fraction);
        double angle=currentAngle+da*fraction;
        float x=clamp(centerX+(float)Math.cos(angle)*radius,width);
        float y=clamp(centerY+(float)Math.sin(angle)*radius,height);
        if(t>=1f && fraction>=1){x=targetX;y=targetY;}
        if(x!=currentX || y!=currentY) {
            touch.move("dpad",x,y);currentX=x;currentY=y;revision++;
        }
        if(currentX==targetX && currentY==targetY)moving=false;
    }
    void reset() {
        moving=false;java.util.Arrays.fill(held,false);java.util.Arrays.fill(pressOrder,0);pressSequence=0;
        touching=false;radiusHeld=false;radiusToggled=false;revision++;
    }
    long revision(){return revision;}
    String diagnosticState(){return "dpad held="+java.util.Arrays.toString(held)+" touch="+touching
            +" current="+currentX+","+currentY+" center="+centerX+","+centerY+" target="+targetX+","+targetY+" moving="+moving;}
}
