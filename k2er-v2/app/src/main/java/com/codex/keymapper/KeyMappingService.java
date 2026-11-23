package com.codex.k2er.v2;

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
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
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
    private static final String CHANNEL = "k2er_mapper_active";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private MappingEditorView editor;
    private MarkerLayer markers;
    private FloatingButton floating;
    private MouseCaptureView mouseCapture;
    private WindowManager.LayoutParams floatingParams;
    private String lastGame = "", pendingPackage = "";
    private long pendingSince, lastSync;
    private int screenWidth, screenHeight;
    private final Runnable poller = new Runnable() {
        @Override public void run() { pollState(); handler.postDelayed(this, 300); }
    };
    private final Runnable mouseTicker = new Runnable() {
        @Override public void run() {
            boolean aiming = !GameProfileStore.isEditing(KeyMappingService.this)
                    && DaemonFiles.isDaemonAlive(KeyMappingService.this)
                    && lastGame.equals(DaemonFiles.activePackage(KeyMappingService.this))
                    && DaemonFiles.isAimActive(KeyMappingService.this, lastGame);
            if (aiming) showMouseCapture(); else hideMouseCapture();
            if (markers != null) markers.invalidate();
            handler.postDelayed(this, 33);
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
                CHANNEL, "K2er mapper", NotificationManager.IMPORTANCE_LOW));
        startForeground(2401, notification());
        GameProfileStore.setEditing(this, "", false);
        DaemonFiles.setCaptureMode(this, "", false);
        updateDisplay();
        syncProfiles();
        handler.post(poller);
        handler.post(mouseTicker);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(2401, notification()); return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        GameProfileStore.setEditing(this, "", false);
        hideMouseCapture(); hideEditor(); hideMarkers(); hideFloating();
        DaemonFiles.setCaptureMode(this, "", false);
        super.onDestroy();
    }
    private Notification notification() {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("K2er is active").setContentText("Game controls and floating editor are ready")
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void updateDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
    }
    private void syncProfiles() {
        DaemonFiles.syncProfiles(this, screenWidth, screenHeight);
        lastSync = SystemClock.uptimeMillis();
        if (markers != null) markers.invalidate();
    }
    private void pollState() {
        updateDisplay();
        if (SystemClock.uptimeMillis() - lastSync >= 1200) syncProfiles();
        boolean online = DaemonFiles.isDaemonAlive(this);
        String active = DaemonFiles.activePackage(this);
        String editing = GameProfileStore.editingPackage(this);
        if (GameProfileStore.isEditing(this) && GameProfileStore.find(this, editing) != null) {
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
        if (GameProfileStore.find(this, active) != null) {
            pendingPackage = ""; lastGame = active; showGameControls(active);
        } else if ((active.isEmpty() || transientPackage(active)) && GameProfileStore.find(this, lastGame) != null) {
            showGameControls(lastGame);
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
        else flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (android.os.Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        if (android.os.Build.VERSION.SDK_INT >= 28)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // Keep non-touchable gameplay labels below Android's obscuring-opacity limit.
        if (!touchable) params.alpha = MappingEditorView.markerOpacity(this);
        return params;
    }
    private void removeOverlay(View view) { try { windows.removeView(view); } catch (Exception ignored) { } }
    private void showGameControls(String name) {
        if (GameProfileStore.find(this, name) == null) { hideFloating(); hideMarkers(); return; }
        lastGame = name; showMarkers(name); showFloating(name);
    }
    private void showMarkers(String name) {
        GameProfileStore.Profile profile = GameProfileStore.find(this, name);
        if (!canShow() || profile == null) { hideMarkers(); return; }
        if (markers != null && markers.packageName.equals(name) && markers.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) markers.getLayoutParams();
            float opacity = MappingEditorView.markerOpacity(this);
            if (params.alpha != opacity) { params.alpha = opacity; windows.updateViewLayout(markers, params); }
            return;
        }
        hideMarkers(); markers = new MarkerLayer(name);
        try { windows.addView(markers, fullScreen(false)); } catch (Exception ignored) { markers = null; }
    }
    private void hideMarkers() { if (markers != null) removeOverlay(markers); markers = null; }
    private void showFloating(String name) {
        if (!canShow() || editor != null && editor.getVisibility() == View.VISIBLE) return;
        if (floating != null && floating.packageName.equals(name) && floating.isAttachedToWindow()) return;
        hideFloating(); floating = new FloatingButton(name);
        int size = dp(58);
        floatingParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = Math.max(dp(8), screenWidth - size - dp(10));
        floatingParams.y = Math.max(dp(60), screenHeight / 2);
        try { windows.addView(floating, floatingParams); }
        catch (Exception ignored) { floating = null; floatingParams = null; }
    }
    private void hideFloating() {
        if (floating != null) removeOverlay(floating);
        floating = null; floatingParams = null;
    }
    private void openEditor(String name) {
        if (GameProfileStore.find(this, name) == null) return;
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
        });
        try { windows.addView(editor, fullScreen(true)); editor.requestFocus(); }
        catch (Exception exception) {
            android.util.Log.e("K2er", "Could not open editor", exception);
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
    private String fit(String text, Paint paint, float width) {
        if (paint.measureText(text) <= width) return text;
        int end = text.length();
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : text.substring(0, end) + "…";
    }
    private void drawMarker(Canvas canvas, Paint paint, float x, float y, String label, boolean waiting) {
        float radius = dp(MappingEditorView.markerSize(this) / 2f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(waiting ? 0xE532214B : 0xD914141B);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(waiting ? 0xFFC79AFF : Color.WHITE);
        if (waiting) paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(3)}, 0));
        canvas.drawCircle(x, y, radius, paint); paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); paint.setTextSize(dp(13));
        while (paint.measureText(label) > dp(42) && paint.getTextSize() > dp(8)) paint.setTextSize(paint.getTextSize() - 1);
        paint.setColor(Color.WHITE);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
        if (waiting) {
            paint.setTextSize(dp(9)); paint.setColor(0xFFE5D3FF);
            canvas.drawText("PRESS KEY", x, y + dp(39), paint);
        }
    }
    private final class MarkerLayer extends View {
        final String packageName;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MarkerLayer(String name) { super(KeyMappingService.this); packageName = name; }
        @Override protected void onDraw(Canvas canvas) {
            GameProfileStore.Profile profile = GameProfileStore.find(KeyMappingService.this, packageName);
            boolean aiming = DaemonFiles.isAimActive(KeyMappingService.this, packageName);
            if (profile != null && MappingEditorView.labelsVisible(KeyMappingService.this)) for (GameProfileStore.Binding binding : profile.bindings)
                if (binding.visible) drawMarker(canvas, paint, binding.x * getWidth(), binding.y * getHeight(),
                        binding.keyCode == GameProfileStore.MOUSE_AIM && aiming ? "AIM ON" : binding.label(), false);
            if (!aiming) {
                String[] cursor = DaemonFiles.cursorState(KeyMappingService.this).trim().split("\\|");
                try {
                    if (cursor.length == 5 && cursor[0].equals(packageName) && System.currentTimeMillis() - Long.parseLong(cursor[4]) < 3000) {
                        float x = Float.parseFloat(cursor[1]), y = Float.parseFloat(cursor[2]);
                        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(0xFFE1C7FF);
                        canvas.drawCircle(x, y, dp(7), paint);
                        canvas.drawLine(x - dp(11), y, x + dp(11), y, paint); canvas.drawLine(x, y - dp(11), x, y + dp(11), paint);
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
    }
    private void showMouseCapture() {
        if (!canShow() || mouseCapture != null) return;
        mouseCapture = new MouseCaptureView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(2, 2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.alpha = 0.01f;
        params.setTitle("K2er mouse aim capture");
        try { windows.addView(mouseCapture, params); mouseCapture.requestFocus(); }
        catch (Exception error) { android.util.Log.e("K2er", "Mouse capture failed", error); mouseCapture = null; }
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
        @Override public boolean dispatchKeyEvent(KeyEvent event) { return true; }
    }
    private final class FloatingButton extends View {
        final String packageName;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;
        int startX, startY;
        boolean dragged;
        final int slop = ViewConfiguration.get(KeyMappingService.this).getScaledTouchSlop();
        FloatingButton(String name) {
            super(KeyMappingService.this); packageName = name; setContentDescription("Open K2er key mapper");
        }
        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xF219151F); canvas.drawCircle(x, y, dp(25), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(0xFFB47AFF);
            canvas.drawCircle(x, y, dp(24), paint);
            paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(Color.WHITE);
            canvas.drawLine(x - dp(8), y, x + dp(8), y, paint); canvas.drawLine(x, y - dp(8), x, y + dp(8), paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(dp(7)); canvas.drawText("K2er", x, y + dp(17), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (floatingParams == null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = floatingParams.x; startY = floatingParams.y; dragged = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.hypot(dx, dy) > slop) dragged = true;
                    if (dragged) {
                        floatingParams.x = Math.max(0, Math.min(screenWidth - getWidth(), startX + Math.round(dx)));
                        floatingParams.y = Math.max(0, Math.min(screenHeight - getHeight(), startY + Math.round(dy)));
                        try { windows.updateViewLayout(this, floatingParams); } catch (Exception ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP: if (!dragged) openEditor(packageName); return true;
                default: return true;
            }
        }
    }
}
