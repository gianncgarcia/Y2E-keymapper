package com.codex.keymapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PathEffect;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.FileObserver;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

public class KeyMappingService extends Service {
    private static final String CHANNEL = "y2e_mapper_active";
    private static final float GAMEPLAY_MARKER_WINDOW_ALPHA = 0.75f;
    private static final Typeface MARKER_TYPEFACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private MappingEditorView editor;
    private MarkerLayer markers;
    private FloatingButton floating;
    private MouseCaptureView mouseCapture;
    private WindowManager.LayoutParams floatingParams;
    private FileObserver markerStateObserver;
    private final Object markerStateLock = new Object();
    private boolean markerStateUpdatePending;
    private long lastMarkerFrame;
    private String lastGame = "", pendingPackage = "";
    private long pendingSince, lastSync;
    private int screenWidth, screenHeight;
    private boolean appNavigationPending;
    private long appNavigationDeadline;
    private final java.util.Map<String,GameProfileStore.Profile> profileCache=new java.util.HashMap<>();
    private final java.util.Set<String> captureProfiles = new java.util.HashSet<>();
    private final Runnable poller = new Runnable() {
        @Override public void run() { pollState(); handler.postDelayed(this, 300); }
    };
    private final Runnable mouseTicker = new Runnable() {
        @Override public void run() {
            String activePackage = DaemonFiles.activePackage(KeyMappingService.this);
            // Gameplay owns the pointer. The editor, opened from the Y2E
            // logo, releases it so Android can show its normal black cursor.
            boolean gameplay = !appNavigationPending && !GameProfileStore.isEditing(KeyMappingService.this)
                    && DaemonFiles.isDaemonAlive(KeyMappingService.this)
                    && (captureProfiles.contains(activePackage)
                    || !lastGame.isEmpty() && activePackage.equals(lastGame));
            boolean keyboardMode = MappingEditorView.keyboardInputEnabled(KeyMappingService.this);
            if (gameplay && !keyboardMode) {
                showMouseCapture();
            } else {
                hideMouseCapture();
            }
            handler.postDelayed(this, 150);
        }
    };
    private final Runnable markerStateUpdate = new Runnable() {
        @Override public void run() {
            synchronized (markerStateLock) {
                markerStateUpdatePending = false;
                lastMarkerFrame = SystemClock.uptimeMillis();
            }
            if (markers != null && markers.getVisibility() == View.VISIBLE)
                markers.updateDynamicState();
        }
    };

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, KeyMappingService.class));
    }
    public static void stop(Context context) {
        context.stopService(new Intent(context, KeyMappingService.class));
    }
    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Y2E mapper", NotificationManager.IMPORTANCE_LOW));
        startForeground(2401, notification());
        GameProfileStore.setEditing(this, "", false);
        MappingEditorView.setKeyboardInputEnabled(this, MappingEditorView.keyboardInputEnabled(this));
        DaemonFiles.setCaptureMode(this, "", false);
        DaemonFiles.setOverlayHidden(this, false); DaemonFiles.setNativeCursorMode(this, false);
        DaemonFiles.setLiveTrackerEnabled(this,getSharedPreferences("editor_options",0).getBoolean("input_tracker_visible",false));
        updateDisplay();
        syncProfiles();
        startMarkerStateObserver();
        handler.post(poller);
        handler.post(mouseTicker);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(2401, notification()); return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (markerStateObserver != null) markerStateObserver.stopWatching();
        handler.removeCallbacksAndMessages(null);
        GameProfileStore.setEditing(this, "", false);
        hideMouseCapture(); hideEditor(); hideMarkers(); hideFloating();
        DaemonFiles.setOverlayHidden(this, false); DaemonFiles.setNativeCursorMode(this, false);
        DaemonFiles.setCaptureMode(this, "", false);
        super.onDestroy();
    }
    private Notification notification() {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Y2E is active").setContentText("Game controls and floating editor are ready")
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void updateDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
    }
    private void syncProfiles() {
        captureProfiles.clear();profileCache.clear();
        for (GameProfileStore.Profile p : GameProfileStore.read(this)) {captureProfiles.add(p.packageName);profileCache.put(p.packageName,p);}
        DaemonFiles.syncProfiles(this, screenWidth, screenHeight);
        lastSync = SystemClock.uptimeMillis();
    }
    private void startMarkerStateObserver() {
        markerStateObserver = new FileObserver(DaemonFiles.directory(this).getPath(),
                FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if ("y2e-keyboard-mode.txt".equals(path)) {
                    handler.post(KeyMappingService.this::syncKeyboardModeFromDaemon);
                } else if ("y2e-cursor.txt".equals(path) || "y2e-aim-state.txt".equals(path) || "y2e-dpad-live.txt".equals(path)) {
                    scheduleMarkerStateUpdate();
                }
            }
        };
        markerStateObserver.startWatching();
    }
    private void syncKeyboardModeFromDaemon() {
        boolean enabled = DaemonFiles.keyboardModeEnabled(this);
        if (enabled != MappingEditorView.keyboardInputEnabled(this)) {
            MappingEditorView.syncKeyboardInputPreference(this, enabled);
            if (floating != null) floating.invalidate();
        }
        if (enabled) hideMarkers();
        else if (markers != null) markers.updateDynamicState();
    }
    private void scheduleMarkerStateUpdate() {
        long delay;
        synchronized (markerStateLock) {
            if (markerStateUpdatePending) return;
            markerStateUpdatePending = true;
            long elapsed = SystemClock.uptimeMillis() - lastMarkerFrame;
            delay = Math.max(0L, 33L - elapsed);
        }
        handler.postDelayed(markerStateUpdate, delay);
    }
    private void pollState() {
        updateDisplay();
        if (SystemClock.uptimeMillis() - lastSync >= 1200) syncProfiles();
        boolean online = DaemonFiles.isDaemonAlive(this);
        String active = DaemonFiles.activePackage(this);
        if(appNavigationPending) {
            if(active.equals(getPackageName()) || SystemClock.uptimeMillis()>=appNavigationDeadline) {
                appNavigationPending=false;DaemonFiles.setCaptureMode(this,"",false);
            }
            else return;
        }
        boolean hidden = DaemonFiles.isOverlayHidden(this);
        String editing = GameProfileStore.editingPackage(this);
        if (GameProfileStore.isEditing(this) && profileCache.get(editing) != null) {
            if (editing.equals(active) || active.isEmpty() || transientPackage(active)) {
                pendingPackage = ""; hideFloating(); hideMarkers(); showEditor(editing);
            } else if (packageChangeConfirmed(active)) {
                // Suspend the same editor instance; an app switch must not erase a waiting key/dialog.
                if (editor != null) editor.setVisibility(View.GONE);
                DaemonFiles.setCaptureMode(this, "", false);
                showGameControls(active);
            }
            if (editor != null) editor.setOnline(online);
            return;
        }
        if (GameProfileStore.isEditing(this)) GameProfileStore.setEditing(this, "", false);
        hideEditor();
        if (profileCache.get(active) != null) {
            pendingPackage = ""; lastGame = active;
            if (hidden) { hideVisuals(); } else showGameControls(active);
        } else if ((active.isEmpty() || transientPackage(active)) && profileCache.get(lastGame) != null) {
            if (hidden) { hideVisuals(); } else showGameControls(lastGame);
        } else if (packageChangeConfirmed(active)) { hideFloating(); hideMarkers(); }
    }
    private boolean packageChangeConfirmed(String active) {
        if (!active.equals(pendingPackage)) {
            pendingPackage = active; pendingSince = SystemClock.uptimeMillis(); return false;
        }
        return SystemClock.uptimeMillis() - pendingSince > 1800;
    }
    private boolean transientPackage(String name) {
        return name.equals("com.google.android.gms") || name.startsWith("com.google.android.play.games")
                || name.equals("com.android.systemui") || name.equals("com.android.permissioncontroller")
                || name.equals("com.google.android.permissioncontroller")
                || name.startsWith("com.google.android.inputmethod") || name.startsWith("com.samsung.android.honeyboard");
    }
    private boolean canShow() { return windows != null && Settings.canDrawOverlays(this); }
    private WindowManager.LayoutParams fullScreen(boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (touchable) params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
        if (android.os.Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        if (android.os.Build.VERSION.SDK_INT >= 28)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // Keep the gameplay window below Android's obscuring-opacity limit.
        // Marker opacity is applied inside MarkerLayer so the game cursor can
        // retain a constant appearance as the user adjusts key opacity.
        if (!touchable) params.alpha = GAMEPLAY_MARKER_WINDOW_ALPHA;
        return params;
    }
    private void removeOverlay(View view) { try { windows.removeView(view); } catch (Exception ignored) { } }
    private void showGameControls(String name) {
        if (profileCache.get(name) == null) { hideFloating(); hideMarkers(); return; }
        lastGame = name; showMarkers(name); showFloating(name);
    }
    private void showMarkers(String name) {
        GameProfileStore.Profile profile = profileCache.get(name);
        if (!canShow() || profile == null || MappingEditorView.keyboardInputEnabled(this)) {
            hideMarkers(); return;
        }
        if (markers != null && markers.packageName.equals(name) && markers.isAttachedToWindow()) {
            boolean becameVisible = markers.getVisibility() != View.VISIBLE;
            if (becameVisible) {
                markers.setVisibility(View.VISIBLE);
            }
            if (becameVisible) markers.updateDynamicState();
            return;
        }
        hideMarkers(); markers = new MarkerLayer(name, profile);
        try { windows.addView(markers, fullScreen(false)); markers.updateDynamicState(); }
        catch (Exception ignored) { markers = null; }
    }
    private void hideMarkers() { if (markers != null) removeOverlay(markers); markers = null; }
    /** Hide only the visual windows while preserving the input service state. */
    private void hideVisuals() {
        if (markers != null) markers.setVisibility(View.INVISIBLE);
        if (floating != null) floating.setVisibility(View.INVISIBLE);
    }
    private void showFloating(String name) {
        if (!canShow() || editor != null && editor.getVisibility() == View.VISIBLE) return;
        if (floating != null && floating.packageName.equals(name) && floating.isAttachedToWindow()) {
            floating.setVisibility(View.VISIBLE);
            return;
        }
        hideFloating(); floating = new FloatingButton(name);
        int size = dp(58);
        floatingParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences position = getSharedPreferences("editor_options", MODE_PRIVATE);
        int maxX = Math.max(0, screenWidth - size), maxY = Math.max(0, screenHeight - size);
        if (position.getBoolean("floating_position_saved", false)) {
            floatingParams.x = Math.round(position.getFloat("floating_position_x", 0.9f) * maxX);
            floatingParams.y = Math.round(position.getFloat("floating_position_y", 0.5f) * maxY);
        } else {
            floatingParams.x = Math.max(dp(8), maxX - dp(10));
            floatingParams.y = Math.max(dp(60), Math.min(maxY, screenHeight / 2));
        }
        try { windows.addView(floating, floatingParams); }
        catch (Exception ignored) { floating = null; floatingParams = null; }
    }
    private void hideFloating() {
        if (floating != null) removeOverlay(floating);
        floating = null; floatingParams = null;
    }
    private void saveFloatingPosition() {
        if (floatingParams == null) return;
        int size = dp(58), maxX = Math.max(1, screenWidth - size), maxY = Math.max(1, screenHeight - size);
        getSharedPreferences("editor_options", MODE_PRIVATE).edit()
                .putBoolean("floating_position_saved", true)
                .putFloat("floating_position_x", Math.max(0f, Math.min(1f, floatingParams.x / (float) maxX)))
                .putFloat("floating_position_y", Math.max(0f, Math.min(1f, floatingParams.y / (float) maxY)))
                .apply();
    }
    private void openEditor(String name) {
        if (profileCache.get(name) == null) return;
        hideMouseCapture(); GameProfileStore.setEditing(this, name, true);
        DaemonFiles.setCaptureMode(this, name, false);
        pendingPackage = ""; hideFloating(); hideMarkers(); showEditor(name);
    }
    private void showEditor(String name) {
        if (!canShow()) return;
        if (editor != null && editor.packageName.equals(name) && editor.isAttachedToWindow()) {
            if (editor.getVisibility() != View.VISIBLE) {
                DaemonFiles.setCaptureMode(this, name, false);
                editor.setVisibility(View.VISIBLE); editor.requestFocus();
            }
            return;
        }
        hideEditor();
        DaemonFiles.setCaptureMode(this, name, false);
        editor = new MappingEditorView(this, name, new MappingEditorView.Host() {
            @Override public void onChanged() { syncProfiles(); }
            @Override public void onDone() { finishEditing(); }
            @Override public void onOpenApp() {
                GameProfileStore.setEditing(KeyMappingService.this,"",false);
                DaemonFiles.setCaptureMode(KeyMappingService.this,name,false);
                appNavigationPending=true;appNavigationDeadline=SystemClock.uptimeMillis()+4000;
                hideEditor();hideMouseCapture();hideMarkers();hideFloating();syncProfiles();
                startActivity(new Intent(KeyMappingService.this,MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        });
        try { windows.addView(editor, fullScreen(true)); editor.requestFocus(); }
        catch (Exception exception) {
            android.util.Log.e("Y2E", "Could not open editor", exception);
            editor = null; GameProfileStore.setEditing(this, "", false); DaemonFiles.setCaptureMode(this, "", false);
        }
    }
    private void hideEditor() {
        if (editor == null) return;
        removeOverlay(editor); editor = null;
    }
    private void finishEditing() {
        String name = GameProfileStore.editingPackage(this);
        GameProfileStore.setEditing(this, "", false); hideEditor();
        DaemonFiles.setCaptureMode(this, "", false);
        pendingPackage = ""; syncProfiles(); showGameControls(name);
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int scaledAlpha(int color, float scale) {
        int alpha = Math.max(0, Math.min(255, Math.round(Color.alpha(color) * scale)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
    private void drawMarker(Canvas canvas, Paint paint, float x, float y, String label,
                            boolean waiting, float alphaScale) {
        float radius = dp(MappingEditorView.markerSize(this) / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scaledAlpha(waiting ? 0xE532214B : 0xD914141B, alphaScale));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(scaledAlpha(waiting ? 0xFFC79AFF : Color.WHITE, alphaScale));
        if (waiting) paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(3)}, 0));
        canvas.drawCircle(x, y, radius, paint); paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(MARKER_TYPEFACE); paint.setTextSize(dp(13));
        while (paint.measureText(label) > dp(42) && paint.getTextSize() > dp(8)) paint.setTextSize(paint.getTextSize() - 1);
        paint.setColor(scaledAlpha(Color.WHITE, alphaScale));
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
        if (waiting) {
            paint.setTextSize(dp(9)); paint.setColor(scaledAlpha(0xFFE5D3FF, alphaScale));
            canvas.drawText("PRESS KEY", x, y + dp(39), paint);
        }
    }
    private final class MarkerLayer extends View {
        final String packageName;
        final GameProfileStore.Profile profile;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PathEffect offsetDash = new DashPathEffect(new float[]{dp(5), dp(4)}, 0);
        private final PathEffect boundaryDash = new DashPathEffect(new float[]{dp(6), dp(4)}, 0);
        private final float markerAlphaScale;
        private final float markerRadius;
        private final boolean labelsVisible;
        private boolean aimActive, cursorVisible, keyboardMode;
        private float cursorX, cursorY;
        private String trackerRaw="", trackerPosition="Waiting for input";
        private boolean trackerVisible, trackerTouch;
        private final boolean[] trackerKeys=new boolean[4];
        private float trackerDx,trackerDy;
        private final java.util.regex.Pattern trackerCoords=java.util.regex.Pattern.compile(
                "current=([-0-9.]+),([-0-9.]+) center=([-0-9.]+),([-0-9.]+)");
        MarkerLayer(String name, GameProfileStore.Profile profile) {
            super(KeyMappingService.this); packageName = name; this.profile = profile;
            markerAlphaScale = Math.min(1f, MappingEditorView.markerOpacity(KeyMappingService.this)
                    / GAMEPLAY_MARKER_WINDOW_ALPHA);
            markerRadius = dp(MappingEditorView.markerSize(KeyMappingService.this) / 2f) + dp(2);
            labelsVisible = MappingEditorView.labelsVisible(KeyMappingService.this);
        }

        /** Read changing state on file events; cursor motion only invalidates its old/new bounds. */
        void updateDynamicState() {
            boolean showTracker=getSharedPreferences("editor_options",0).getBoolean("input_tracker_visible",false);
            String raw="";
            if(showTracker)try{raw=new String(java.nio.file.Files.readAllBytes(new java.io.File(
                    DaemonFiles.directory(KeyMappingService.this),"y2e-dpad-live.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception ignored){}
            if(showTracker!=trackerVisible || !raw.equals(trackerRaw)) {
                trackerVisible=showTracker;trackerRaw=raw;trackerTouch=raw.contains("touch=true");
                java.util.Arrays.fill(trackerKeys,false);trackerDx=trackerDy=0;trackerPosition="Waiting for mapped input";
                int begin=raw.indexOf('['),end=raw.indexOf(']');
                if(begin>=0 && end>begin){String[] flags=raw.substring(begin+1,end).split(",");for(int i=0;i<4&&i<flags.length;i++)trackerKeys[i]=flags[i].trim().equals("true");}
                java.util.regex.Matcher matcher=trackerCoords.matcher(raw);
                if(matcher.find())try{
                    float x=Float.parseFloat(matcher.group(1)),y=Float.parseFloat(matcher.group(2));
                    float cx=Float.parseFloat(matcher.group(3)),cy=Float.parseFloat(matcher.group(4));
                    float radius=profile.dpad==null?1:Math.max(1,profile.dpad.radius*Math.min(getWidth(),getHeight()));
                    trackerDx=Math.max(-1,Math.min(1,(x-cx)/radius));trackerDy=Math.max(-1,Math.min(1,(y-cy)/radius));
                    trackerPosition="Injected: "+Math.round(x)+", "+Math.round(y)+(trackerTouch?"  DOWN":"  UP");
                }catch(NumberFormatException ignored){}
                invalidate(0,dp(90),dp(340),dp(215));
            }
            boolean nextAimActive = DaemonFiles.isAimActive(KeyMappingService.this, packageName);
            boolean nextKeyboardMode = MappingEditorView.keyboardInputEnabled(KeyMappingService.this);
            boolean nextCursorVisible = false;
            float nextCursorX = 0, nextCursorY = 0;
            if (!nextAimActive && !nextKeyboardMode) {
                String[] cursor = DaemonFiles.cursorState(KeyMappingService.this).trim().split("\\|");
                if (cursor.length == 5 && cursor[0].equals(packageName)) {
                    try {
                        nextCursorX = Float.parseFloat(cursor[1]);
                        nextCursorY = Float.parseFloat(cursor[2]);
                        nextCursorVisible = nextCursorX >= 0 && nextCursorY >= 0
                                && nextCursorX <= getWidth() && nextCursorY <= getHeight();
                    } catch (NumberFormatException ignored) { }
                }
            }
            boolean aimChanged = aimActive != nextAimActive;
            boolean keyboardModeChanged = keyboardMode != nextKeyboardMode;
            boolean cursorChanged = cursorVisible != nextCursorVisible
                    || Float.compare(cursorX, nextCursorX) != 0
                    || Float.compare(cursorY, nextCursorY) != 0;
            boolean oldCursorVisible = cursorVisible;
            float oldCursorX = cursorX, oldCursorY = cursorY;
            aimActive = nextAimActive;
            keyboardMode = nextKeyboardMode;
            cursorVisible = nextCursorVisible;
            cursorX = nextCursorX; cursorY = nextCursorY;
            if (aimChanged || keyboardModeChanged) invalidate();
            else if (cursorChanged) invalidateCursorBounds(oldCursorVisible, oldCursorX, oldCursorY,
                    nextCursorVisible, nextCursorX, nextCursorY);
        }

        private void invalidateCursorBounds(boolean oldVisible, float oldX, float oldY,
                                            boolean newVisible, float newX, float newY) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (oldVisible) invalidateCursorArea(oldX, oldY);
            if (newVisible) invalidateCursorArea(newX, newY);
        }

        private void invalidateCursorArea(float x, float y) {
            int pad = dp(20), width = getWidth(), height = getHeight();
            int left = Math.max(0, (int) x - pad);
            int top = Math.max(0, (int) y - pad);
            int right = Math.min(width, (int) x + pad + 1);
            int bottom = Math.min(height, (int) y + pad + 1);
            if (right > left && bottom > top) invalidate(left, top, right, bottom);
        }

        private boolean intersectsClip(Rect clip, float x, float y, float radius) {
            return x + radius >= clip.left && x - radius <= clip.right
                    && y + radius >= clip.top && y - radius <= clip.bottom;
        }

        @Override protected void onDraw(Canvas canvas) {
            Rect clip = canvas.getClipBounds();
            if (clip.isEmpty()) return;
            boolean showKeyLabels = !keyboardMode && labelsVisible;
            if (profile != null && showKeyLabels) for (GameProfileStore.Binding binding : profile.bindings) {
                if (!binding.visible) continue;
                float x = binding.x * getWidth(), y = binding.y * getHeight();
                if (binding.keyCode == GameProfileStore.MOUSE_AIM && binding.aimBoundary) {
                    float boundary = binding.aimBoundaryRadius * Math.min(getWidth(), getHeight());
                    if (intersectsClip(clip, x, y, boundary + dp(2)))
                        drawAimBoundary(canvas, paint, x, y, boundary, markerAlphaScale, boundaryDash);
                }
                if (intersectsClip(clip, x, y, markerRadius))
                    drawMarker(canvas, paint, x, y,
                            binding.keyCode == GameProfileStore.MOUSE_AIM && aimActive ? "AIM ON" : binding.label(),
                            false, markerAlphaScale);
            }
            if (profile != null && showKeyLabels) {
                if (profile.dpad != null && profile.dpad.visible) {
                    float x = profile.dpad.x * getWidth(), y = profile.dpad.y * getHeight();
                    float unit = Math.min(getWidth(), getHeight());
                    float guide = profile.dpad.radius * unit;
                    if (intersectsClip(clip, x, y, markerRadius))
                        drawMarker(canvas, paint, x, y, "WASD", false, markerAlphaScale);
                    if (intersectsClip(clip, x, y, guide + dp(2)))
                        drawXtDpadGuides(canvas, paint, x, y, guide, profile.dpad.angle, markerAlphaScale);
                    if (profile.dpad.changeRadiusKey != 0 && profile.dpad.radiusOffset != 0f) {
                        float walking=Math.max(0.02f,Math.min(0.45f,profile.dpad.radius+profile.dpad.radiusOffset))*unit;
                        if (intersectsClip(clip,x,y,walking+dp(2))) {
                            paint.setPathEffect(offsetDash);
                            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.25f));
                            paint.setColor(scaledAlpha(0xBFE5C66B,markerAlphaScale));
                            canvas.drawCircle(x,y,walking,paint);
                            paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
                        }
                    }
                }
                if (profile.dpadStick != null && profile.dpadStick.visible) {
                    float x = profile.dpadStick.x * getWidth(), y = profile.dpadStick.y * getHeight();
                    float unit = Math.min(getWidth(), getHeight());
                    float guide = profile.dpadStick.radius * unit;
                    if (profile.dpadStick.changeRadiusKey != 0 && profile.dpadStick.radiusOffset != 0f)
                        guide = Math.max(guide, Math.max(0.02f,
                                Math.min(0.45f, profile.dpadStick.radius + profile.dpadStick.radiusOffset)) * unit);
                    if (intersectsClip(clip, x, y, markerRadius))
                        drawMarker(canvas, paint, x, y, "STICK", false, markerAlphaScale);
                    if (intersectsClip(clip, x, y, guide + dp(2)))
                        drawRadiusGuides(canvas, paint, x, y, profile.dpadStick.radius,
                                profile.dpadStick.radiusOffset, profile.dpadStick.changeRadiusKey != 0, markerAlphaScale,
                                getWidth(), getHeight(), offsetDash);
                }
            }
            if (!keyboardMode && !aimActive && cursorVisible
                    && intersectsClip(clip, cursorX, cursorY, dp(18)))
                drawGameCursor(canvas, paint, cursorX, cursorY);
            if(trackerVisible && !keyboardMode) drawInputTracker(canvas);
        }

        private void drawInputTracker(Canvas canvas) {
            float x=dp(12), y=dp(100);
            paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xED211C2B);
            canvas.drawRoundRect(x,y,x+dp(310),y+dp(100),dp(8),dp(8),paint);
            paint.setTextAlign(Paint.Align.LEFT);paint.setTextSize(dp(11));paint.setColor(Color.WHITE);
            canvas.drawText("INPUT TRACKER | Live only",x+dp(8),y+dp(18),paint);
            String[] names={"W","S","A","D"};
            for(int i=0;i<4;i++){
                float left=x+dp(8+i*35);
                paint.setColor(trackerKeys[i]?0xFF76E3B3:0xFF51485D);canvas.drawRect(left,y+dp(27),left+dp(29),y+dp(55),paint);
                paint.setColor(0xFF15111C);canvas.drawText(names[i],left+dp(10),y+dp(45),paint);
            }
            paint.setColor(Color.WHITE);canvas.drawText(trackerPosition,x+dp(8),y+dp(78),paint);
            float cx=x+dp(263),cy=y+dp(52),r=dp(23);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));canvas.drawRect(cx-r,cy-r,cx+r,cy+r,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(trackerTouch?0xFFA787FF:0xFF77717F);
            canvas.drawCircle(cx+(trackerTouch?trackerDx*r:0),cy+(trackerTouch?trackerDy*r:0),dp(4),paint);
        }
        private void drawGameCursor(Canvas canvas, Paint paint, float x, float y) {
            float outer = dp(11), inner = dp(4);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xE86B3CE5); canvas.drawCircle(x, y, inner, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(0xFFF0E8FF);
            canvas.drawCircle(x, y, outer, paint);
            canvas.drawLine(x - dp(16), y, x - dp(7), y, paint);
            canvas.drawLine(x + dp(7), y, x + dp(16), y, paint);
            canvas.drawLine(x, y - dp(16), x, y - dp(7), paint);
            canvas.drawLine(x, y + dp(7), x, y + dp(16), paint);
        }
    }
    private void drawXtDpadGuides(Canvas canvas, Paint paint, float x, float y, float r, float angle, float alphaScale) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.25f));
        paint.setColor(scaledAlpha(0xBFA787FF, alphaScale)); paint.setPathEffect(null);
        canvas.drawCircle(x,y,r,paint);
        float sx=(float)Math.sin(Math.toRadians(angle)),sy=(float)Math.cos(Math.toRadians(angle));
        float dx=r*sx,dy=r*sy;
        canvas.drawLine(x-dx, y-dy, x+dx, y+dy, paint);
        canvas.drawLine(x-dx, y+dy, x+dx, y-dy, paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private void drawRadiusGuides(Canvas canvas, Paint paint, float x, float y, float radius, float offset,
                                  boolean offsetEnabled, float alphaScale,
                                  int width, int height, PathEffect offsetDash) {
        float unit = Math.min(width, height);
        float base = Math.max(dp(2), radius * unit);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.25f));
        paint.setColor(scaledAlpha(0xBFA787FF, alphaScale)); paint.setPathEffect(null);
        canvas.drawCircle(x, y, base, paint);
        if (offsetEnabled && offset != 0f) {
            float adjusted = Math.max(0.02f, Math.min(0.45f, radius + offset)) * unit;
            if (Math.abs(adjusted - base) > dp(2)) {
                paint.setColor(scaledAlpha(0xBFE5C66B, alphaScale));
                paint.setPathEffect(offsetDash);
                canvas.drawCircle(x, y, adjusted, paint);
                paint.setPathEffect(null);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }
    private void drawAimBoundary(Canvas canvas, Paint paint, float x, float y, float radius,
                                 float alphaScale, PathEffect boundaryDash) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(scaledAlpha(0xC267D7F2, alphaScale));
        paint.setPathEffect(boundaryDash);
        canvas.drawCircle(x, y, radius, paint);
        paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
    }
    private void showMouseCapture() {
        if (!canShow() || mouseCapture != null) return;
        mouseCapture = new MouseCaptureView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(2, 2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.alpha = 0.01f;
        params.setTitle("Y2E game cursor capture");
        try { windows.addView(mouseCapture, params); mouseCapture.requestFocus(); }
        catch (Exception error) { android.util.Log.e("Y2E", "Mouse capture failed", error); mouseCapture = null; }
    }
    private void hideMouseCapture() {
        if (mouseCapture == null) return;
        mouseCapture.releasePointerCapture(); removeOverlay(mouseCapture); mouseCapture = null;
        DaemonFiles.pointerCaptureState(this, false);
    }
    private final class MouseCaptureView extends View {
        MouseCaptureView() { super(KeyMappingService.this); setFocusable(true); setFocusableInTouchMode(true); }
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) requestPointerCapture();
        }
        @Override public void onPointerCaptureChange(boolean captured) {
            super.onPointerCaptureChange(captured); DaemonFiles.pointerCaptureState(KeyMappingService.this, captured);
        }
        @Override public boolean onCapturedPointerEvent(MotionEvent event) { return true; }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            // The capture view must stay focused for keyboard mapping, but
            // Android's hardware volume controls must remain system keys.
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
            return true;
        }
    }
    private final class FloatingButton extends View {
        final String packageName;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;
        int startX, startY;
        long downAt;
        boolean dragged;
        final int slop = ViewConfiguration.get(KeyMappingService.this).getScaledTouchSlop();
        FloatingButton(String name) {
            super(KeyMappingService.this); packageName = name;
            setContentDescription("Open Y2E editor");
        }
        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xF219151F); canvas.drawCircle(x, y, dp(25), paint);
            boolean keyboardMode = MappingEditorView.keyboardInputEnabled(KeyMappingService.this);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(keyboardMode ? 0xFF61D7BE : 0xFFB47AFF);
            canvas.drawCircle(x, y, dp(24), paint);
            paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(Color.WHITE);
            canvas.drawLine(x - dp(8), y, x + dp(8), y, paint); canvas.drawLine(x, y - dp(8), x, y + dp(8), paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(dp(7));
            canvas.drawText(keyboardMode ? "KB" : "Y2E", x, y + dp(17), paint);
            setContentDescription(keyboardMode ? "Tap to restore Y2E controls" : "Open Y2E editor; long press for device keyboard mode");
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (floatingParams == null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = floatingParams.x; startY = floatingParams.y; downAt = SystemClock.uptimeMillis(); dragged = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.hypot(dx, dy) > slop) dragged = true;
                    if (dragged) {
                        floatingParams.x = Math.max(0, Math.min(screenWidth - getWidth(), startX + Math.round(dx)));
                        floatingParams.y = Math.max(0, Math.min(screenHeight - getHeight(), startY + Math.round(dy)));
                        try { windows.updateViewLayout(this, floatingParams); } catch (Exception ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragged) saveFloatingPosition();
                    if (!dragged) {
                        boolean keyboardMode = MappingEditorView.keyboardInputEnabled(KeyMappingService.this);
                        boolean longPress = SystemClock.uptimeMillis() - downAt >= 650;
                        if (longPress) MappingEditorView.setKeyboardInputEnabled(KeyMappingService.this, !keyboardMode);
                        else if (keyboardMode) MappingEditorView.setKeyboardInputEnabled(KeyMappingService.this, false);
                        else openEditor(packageName);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return true;
            }
        }
    }
}
