package com.codex.keymapper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;

import java.util.Locale;

/** Native controls own toolbar/dialog input; only the map surface handles placement. */
final class MappingEditorView extends FrameLayout {
    interface Host { void onChanged(); void onDone(); void onOpenApp(); }
    enum Mode { BROWSE, CAPTURE, AIM, MOVE, DPAD, DPAD_STICK }
    static final int PURPLE = 0xFF693CE5, WHITE = 0xFFF7F6FA, MUTED = 0xFFBDB9C5;
    private static final int PANEL = 0xFF25242A, PANEL_BORDER = 0xFF45414D;
    private static final int DPAD_CONTROL = -2001, DPAD_STICK_CONTROL = -2002;
    final String packageName;
    private final Host host;
    private final MapSurface surface;
    private final LinearLayout toolbar;
    private final TextView hint, connection;
    private final Button cancelInput;
    private final LinearLayout status;
    private FrameLayout dialog;
    private Mode mode = Mode.BROWSE;
    private float pointX, pointY;
    private boolean captureForProperties;
    private GameProfileStore.Binding original, candidate;
    private GameProfileStore.Dpad candidateDpad;
    private GameProfileStore.DpadStick candidateDpadStick;
    private int captureDpadDirection = -1;
    private boolean captureDpadShortcut, captureStickShortcut;
    private boolean previewDpad, previewDpadStick;
    private int globalFirstKey;
    private boolean globalCapture;
    private boolean globalKeyboardModeCapture;
    private int movingKey;
    private String notice = "";
    private boolean online = true;

    MappingEditorView(Context context, String name, Host host) {
        super(context);
        packageName = name; this.host = host;
        setFocusable(true); setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setContentDescription("Y2E mapping editor");
        surface = new MapSurface(context);
        addView(surface, new LayoutParams(-1, -1));
        toolbar = column();
        toolbar.setBackground(strokedBackground(0xF5222226, 0xFF4A4354, 13));
        toolbar.setElevation(dp(8));
        toolbar.setPadding(dp(7), dp(6), dp(7), dp(6));
        LinearLayout actions = row();
        TextView brand = label("Y2E", 18, WHITE, true);
        brand.setGravity(Gravity.CENTER); brand.setContentDescription("Open Y2E app; long press for mouse rate");
        brand.setOnClickListener(v -> host.onOpenApp());
        brand.setOnLongClickListener(v->{showMouseRateSettings();return true;});
        actions.addView(brand, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView dragHandle=label("::",18,MUTED,true);
        dragHandle.setGravity(Gravity.CENTER);dragHandle.setContentDescription("Drag editor toolbar");
        actions.addView(dragHandle,size(28,40));
        installToolbarDrag(dragHandle);
        actions.addView(icon(android.R.drawable.ic_menu_agenda, "Mapped keys", this::showKeyList), size(40, 40));
        actions.addView(icon(android.R.drawable.ic_menu_add, "Add control", this::showAdd), size(40, 40));
        actions.addView(icon(android.R.drawable.ic_menu_manage, "Editor settings", this::showSettings), size(40, 40));
        Button done = button("✓", true, host::onDone); done.setTextSize(18); done.setContentDescription("Done editing");
        actions.addView(done, size(40, 40));
        toolbar.addView(actions, new LinearLayout.LayoutParams(-1, dp(40)));
        hint = label("", 11, WHITE, false); hint.setPadding(dp(5), dp(5), dp(5), dp(2));
        toolbar.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        status = row();
        connection = label("", 10, MUTED, false); connection.setPadding(dp(5), 0, 0, 0);
        status.addView(connection, new LinearLayout.LayoutParams(0, -2, 1));
        cancelInput = button("Cancel", false, this::cancelPending);
        status.addView(cancelInput, size(72, 30));
        toolbar.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LayoutParams position = new LayoutParams(dp(320), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = dp(14);
        addView(toolbar, position);
        refresh();
    }

    private SharedPreferences options() { return getContext().getSharedPreferences("editor_options", 0); }
    static boolean keyboardInputEnabled(Context context) {
        return context.getSharedPreferences("editor_options", 0).getBoolean("keyboard_input_enabled", false);
    }
    static void setKeyboardInputEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("editor_options", 0).edit().putBoolean("keyboard_input_enabled", enabled).apply();
        DaemonFiles.setKeyboardMode(context, enabled);
    }
    static void syncKeyboardInputPreference(Context context, boolean enabled) {
        context.getSharedPreferences("editor_options", 0).edit().putBoolean("keyboard_input_enabled", enabled).apply();
    }
    static boolean labelsVisible(Context context) { return context.getSharedPreferences("editor_options", 0).getBoolean("labels", true); }
    static int markerSize(Context context) { return context.getSharedPreferences("editor_options", 0).getInt("size", 44); }
    static float markerOpacity(Context context) { return context.getSharedPreferences("editor_options", 0).getInt("opacity", 65) / 100f; }
    static boolean overlayToggleConfigured(Context context) {
        return context.getSharedPreferences("editor_options", 0)
                .getBoolean("overlay_toggle_configured", false);
    }
    static int overlayToggleModifier(Context context) {
        return overlayToggleConfigured(context) ? context.getSharedPreferences("editor_options", 0)
                .getInt("overlay_toggle_modifier", 0) : 0;
    }
    static int overlayToggleKey(Context context) {
        return overlayToggleConfigured(context) ? context.getSharedPreferences("editor_options", 0)
                .getInt("overlay_toggle_key", 0) : 0;
    }
    static String overlayToggleLabel(Context context) {
        if (!overlayToggleConfigured(context) || overlayToggleKey(context) == 0) return "Off";
        String key = new GameProfileStore.Binding(overlayToggleKey(context), 0, 0).label();
        int modifier = overlayToggleModifier(context);
        if (modifier == 0) return key;
        return new GameProfileStore.Binding(modifier, 0, 0).label() + " + " + key;
    }
    static boolean keyboardModeHotkeyConfigured(Context context) {
        return context.getSharedPreferences("editor_options", 0)
                .getBoolean("keyboard_mode_hotkey_configured", false);
    }
    static int keyboardModeHotkeyModifier(Context context) {
        return keyboardModeHotkeyConfigured(context) ? context.getSharedPreferences("editor_options", 0)
                .getInt("keyboard_mode_hotkey_modifier", 0) : 0;
    }
    static int keyboardModeHotkeyKey(Context context) {
        return keyboardModeHotkeyConfigured(context) ? context.getSharedPreferences("editor_options", 0)
                .getInt("keyboard_mode_hotkey_key", 0) : 0;
    }
    static String keyboardModeHotkeyLabel(Context context) {
        if (!keyboardModeHotkeyConfigured(context) || keyboardModeHotkeyKey(context) == 0) return "Off";
        String key = new GameProfileStore.Binding(keyboardModeHotkeyKey(context), 0, 0).label();
        int modifier = keyboardModeHotkeyModifier(context);
        if (modifier == 0) return key;
        return new GameProfileStore.Binding(modifier, 0, 0).label() + " + " + key;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams size(int w, int h) { return new LinearLayout.LayoutParams(dp(w), dp(h)); }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.leftMargin = dp(3); params.rightMargin = dp(3);
        return params;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private GradientDrawable strokedBackground(int color, int stroke, int radius) {
        GradientDrawable shape = background(color, radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String title, boolean primary, Runnable action) {
        Button v = new Button(getContext()); v.setText(title); v.setTextSize(12); v.setAllCaps(false);
        v.setTextColor(WHITE); v.setMinWidth(0); v.setMinimumWidth(0); v.setMinHeight(0); v.setMinimumHeight(0);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(8), dp(3), dp(8), dp(3));
        v.setBackground(strokedBackground(primary ? PURPLE : 0xFF302E35,
                primary ? 0xFF8F72F0 : PANEL_BORDER, 10));
        v.setOnClickListener(ignored -> action.run()); return v;
    }
    private ImageButton icon(int drawable, String description, Runnable action) {
        ImageButton v = new ImageButton(getContext()); v.setImageResource(drawable);
        v.setImageTintList(ColorStateList.valueOf(WHITE)); v.setBackground(strokedBackground(0xFF302E35, PANEL_BORDER, 10));
        v.setPadding(dp(10), dp(10), dp(10), dp(10)); v.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        v.setContentDescription(description);
        v.setOnClickListener(ignored -> action.run()); return v;
    }
    private void installToolbarDrag(View grip) {
        grip.setOnTouchListener(new OnTouchListener() {
            float startX, startY; int left, top; boolean dragged, longPressed;
            final Runnable longPress=()->{if(!dragged)longPressed=grip.performLongClick();};
            final int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
            public boolean onTouch(View v, MotionEvent event) {
                LayoutParams p = (LayoutParams) toolbar.getLayoutParams();
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); left = p.leftMargin; top = p.topMargin; dragged=false;longPressed=false;grip.postDelayed(longPress,600);
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if(Math.hypot(event.getRawX()-startX,event.getRawY()-startY)>slop){dragged=true;grip.removeCallbacks(longPress);}
                    if(!dragged)return true;
                    p.leftMargin = Math.max(0, Math.min(getWidth() - toolbar.getWidth(), left + Math.round(event.getRawX() - startX)));
                    p.topMargin = Math.max(0, Math.min(getHeight() - toolbar.getHeight(), top + Math.round(event.getRawY() - startY)));
                    toolbar.setLayoutParams(p);
                } else if(event.getActionMasked()==MotionEvent.ACTION_UP) {
                    grip.removeCallbacks(longPress);
                    if(!dragged && !longPressed && Math.hypot(event.getRawX()-startX,event.getRawY()-startY)<=slop)v.performClick();
                } else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragged=true;grip.removeCallbacks(longPress);}
                return true;
            }
        });
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        LayoutParams p = (LayoutParams) toolbar.getLayoutParams();
        p.width = Math.min(dp(360), Math.max(dp(280), w - dp(24)));
        p.leftMargin = Math.max(0, Math.min(w - p.width, p.leftMargin));
        p.topMargin = Math.max(0, Math.min(Math.max(0, h - toolbar.getHeight()), p.topMargin));
        toolbar.setLayoutParams(p);
        if (dialog != null) resizeDialog();
    }
    void setOnline(boolean value) { if (online != value) { online = value; refresh(); } }
    private GameProfileStore.Profile profile() { return GameProfileStore.find(getContext(), packageName); }
    private GameProfileStore.Binding find(int key) {
        GameProfileStore.Profile p = profile();
        if (p != null) for (GameProfileStore.Binding b : p.bindings) if (b.keyCode == key) return b;
        return null;
    }
    private void changed() { host.onChanged(); surface.invalidate(); }
    private void refresh() {
        String guide = mode == Mode.CAPTURE ? "Press a key or mouse button for the waiting circle."
                : mode == Mode.AIM ? "Tap the camera area, then choose the aim shortcut in its settings."
                : mode == Mode.DPAD ? "Tap the center of the game's virtual movement joystick."
                : mode == Mode.DPAD_STICK ? "Tap the center of the game's virtual movement joystick."
                : mode == Mode.MOVE ? "Tap the new position for this control."
                : "Tap a spot to add a key. Tap a circle to edit; drag it to move.";
        hint.setText(notice.isEmpty() ? guide : notice);
        hint.setTextColor(notice.isEmpty() ? WHITE : 0xFFFFCF82);
        hint.setVisibility(mode == Mode.BROWSE ? GONE : VISIBLE);
        status.setVisibility(mode == Mode.BROWSE ? GONE : VISIBLE);
        connection.setText(online ? "Changes save automatically" : "Input disconnected · editor stays open; changes are saved");
        surface.invalidate();
    }
    private void clearPending() {
        mode = Mode.BROWSE; notice = ""; captureForProperties = false; movingKey = 0;
        globalFirstKey = 0; globalCapture = false;
        globalKeyboardModeCapture = false;
        captureDpadDirection = -1; captureDpadShortcut = false; captureStickShortcut = false;
        previewDpad = false; previewDpadStick = false;
    }
    private void cancelPending() {
        boolean restore = captureForProperties;
        boolean restoreDpad = captureDpadDirection >= 0 || captureDpadShortcut;
        boolean restoreStick = captureStickShortcut;
        clearPending();
        if (restore && candidate != null) showPropertiesPanel();
        else if (restoreDpad && candidateDpad != null) showDpadProperties();
        else if (restoreStick && candidateDpadStick != null) showDpadStickProperties();
        refresh();
    }
    private void startCapture(float x, float y, boolean forProperties) {
        dismissDialog(); mode = Mode.CAPTURE; notice = "";
        pointX = clamp(x); pointY = clamp(y); captureForProperties = forProperties;
        requestFocus(); refresh();
    }
    private float clamp(float f) { return Math.max(0, Math.min(1, f)); }
    private void acceptKey(int code) {
        if (mode != Mode.CAPTURE || code == 0) return;
        if (DaemonFiles.kernelKeyName(code).isEmpty()) { notice = "This key is not supported. Choose another key."; refresh(); return; }
        if ((captureDpadDirection >= 0 || captureDpadShortcut) && conflictsWithGlobalShortcut(code)) {
            notice="That key is reserved by a global shortcut. Choose another key."; refresh(); return;
        }
        if (captureDpadDirection >= 0 && candidateDpad != null) {
            GameProfileStore.Binding aim=find(GameProfileStore.MOUSE_AIM);
            if(aim!=null && aim.aimKey==code) { notice="That key is the aim shortcut."; refresh(); return; }
            if (code==candidateDpad.changeRadiusKey) { notice="That key is the walking shortcut."; refresh(); return; }
            for (int i = 0; i < 4; i++) if (i != captureDpadDirection && dpadKey(candidateDpad, i) == code) {
                notice = "Each Dpad direction needs a different key."; refresh(); return;
            }
            candidateDpad = candidateDpad.direction(captureDpadDirection, code);
            clearPending(); showDpadProperties(); refresh(); return;
        }
        if (captureDpadShortcut && candidateDpad != null) {
            for(int i=0;i<4;i++)if(dpadKey(candidateDpad,i)==code) {
                notice="Walking shortcut must differ from direction keys."; refresh(); return;
            }
            GameProfileStore.Binding aim=find(GameProfileStore.MOUSE_AIM);
            if(aim!=null && aim.aimKey==code) { notice="That key is the aim shortcut."; refresh(); return; }
            candidateDpad = candidateDpad.shortcut(code, candidateDpad.radiusOffset, candidateDpad.holdToChange);
            clearPending(); showDpadProperties(); refresh(); return;
        }
        if (captureStickShortcut && candidateDpadStick != null) {
            candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, code, null,
                    null, null, null, null, null, null, null, null, null);
            clearPending(); showDpadStickProperties(); refresh(); return;
        }
        int oldKey = captureForProperties && original != null ? original.keyCode : 0;
        boolean aimShortcut = captureForProperties && candidate.keyCode == GameProfileStore.MOUSE_AIM;
        if (aimShortcut && (conflictsWithGlobalShortcut(code)
                || profile()!=null && profile().dpad!=null && (profile().dpad.changeRadiusKey==code
                || dpadKey(profile().dpad,0)==code || dpadKey(profile().dpad,1)==code
                || dpadKey(profile().dpad,2)==code || dpadKey(profile().dpad,3)==code))) {
            notice="That key is reserved for movement, walking or a global shortcut."; refresh(); return;
        }
        if (aimShortcut && (code == GameProfileStore.MOUSE_WHEEL_UP || code == GameProfileStore.MOUSE_WHEEL_DOWN)) {
            notice = "Choose a keyboard key or mouse button for aim."; refresh(); return;
        }
        if (!aimShortcut && find(code) != null && code != oldKey) {
            notice = new GameProfileStore.Binding(code, 0, 0).label() + " already has a mapping. Edit that circle or choose another key.";
            refresh(); return;
        }
        if (captureForProperties) {
            candidate = aimShortcut ? candidate.withAim(code, candidate.aimHold) : candidate.withKey(code);
            clearPending(); showPropertiesPanel();
        } else {
            if (GameProfileStore.saveBinding(getContext(), packageName, 0, new GameProfileStore.Binding(code, pointX, pointY))) {
                clearPending(); changed();
            }
        }
        refresh();
    }
    private boolean conflictsWithGlobalShortcut(int code) {
        return code!=0 && (code==overlayToggleModifier(getContext()) || code==overlayToggleKey(getContext())
                || code==keyboardModeHotkeyModifier(getContext()) || code==keyboardModeHotkeyKey(getContext()));
    }
    private void captureGlobalKeyDown(int code) {
        if (!globalCapture || mode != Mode.CAPTURE || code == 0) return;
        if (DaemonFiles.kernelKeyName(code).isEmpty()) {
            notice = "This key is not supported. Choose another key."; refresh(); return;
        }
        if (globalFirstKey == 0) {
            globalFirstKey = code;
            notice = "Holding " + new GameProfileStore.Binding(code, 0, 0).label()
                    + ". Press another key for a combo, or release for one key.";
            refresh();
        } else if (globalFirstKey != code) {
            saveGlobalShortcut(globalFirstKey, code);
        }
    }
    private void captureGlobalKeyUp(int code) {
        if (!globalCapture || mode != Mode.CAPTURE || globalFirstKey != code) return;
        // A lone key is committed on release. A two-key shortcut was already
        // committed when its second key went down.
        saveGlobalShortcut(code, 0);
    }
    private void saveGlobalShortcut(int first, int second) {
        for(GameProfileStore.Profile p:GameProfileStore.read(getContext())) {
            for(GameProfileStore.Binding b:p.bindings) if(b.keyCode==GameProfileStore.MOUSE_AIM
                    && (b.aimKey==first || b.aimKey==second)) {
                notice="Shortcut conflicts with a game's aim key."; refresh(); return;
            }
            if(p.dpad!=null)for(int i=0;i<4;i++)if(dpadKey(p.dpad,i)==first || dpadKey(p.dpad,i)==second) {
                notice="Shortcut conflicts with a game's movement key."; refresh(); return;
            }
            if(p.dpad!=null && p.dpad.changeRadiusKey!=0 && (p.dpad.changeRadiusKey==first || p.dpad.changeRadiusKey==second)) {
                notice="Shortcut conflicts with a game's walking key."; refresh(); return;
            }
        }
        boolean keyboardMode = globalKeyboardModeCapture;
        if (keyboardMode) {
            options().edit().putBoolean("keyboard_mode_hotkey_configured", true)
                    .putInt("keyboard_mode_hotkey_modifier", second == 0 ? 0 : first)
                    .putInt("keyboard_mode_hotkey_key", second == 0 ? first : second).apply();
        } else {
            options().edit().putBoolean("overlay_toggle_configured", true)
                    .putInt("overlay_toggle_modifier", second == 0 ? 0 : first)
                    .putInt("overlay_toggle_key", second == 0 ? first : second).apply();
        }
        clearPending(); showSettings(); changed(); refresh();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) acceptKey(GameProfileStore.MOUSE_BACK);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) goBack();
            return true;
        }
        if (findFocus() instanceof EditText) return super.dispatchKeyEvent(event);
        if (mode == Mode.CAPTURE) {
            if (globalCapture) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) captureGlobalKeyDown(code);
                else if (event.getAction() == KeyEvent.ACTION_UP) captureGlobalKeyUp(code);
            } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) acceptKey(code);
            return true;
        }
        if (code == KeyEvent.KEYCODE_ESCAPE && event.getAction() == KeyEvent.ACTION_UP) goBack();
        // The editor has focus: game key events must not leak into a hidden game control.
        return true;
    }
    private void goBack() {
        if (dialog != null) { dismissDialog(); clearPending(); refresh(); }
        else if (mode != Mode.BROWSE) cancelPending();
        else host.onDone();
    }
    private void showAdd() {
        clearPending();
        LinearLayout body = column();
        body.addView(section("ADD CONTROL"));
        body.addView(button("Key / mouse button", false, () -> { dismissDialog(); refresh(); }), new LinearLayout.LayoutParams(-1, dp(42)));
        GameProfileStore.Profile p = profile();
        body.addView(button(p != null && p.dpad != null ? "Edit Dpad · WASD" : "Dpad · WASD",
                false, p != null && p.dpad != null ? () -> editDpad(p.dpad) : this::startDpadPlacement),
                new LinearLayout.LayoutParams(-1, dp(42)));
        body.addView(button(p != null && p.dpadStick != null ? "Edit Dpad (Stick)" : "Dpad (Stick)",
                false, p != null && p.dpadStick != null ? () -> editDpadStick(p.dpadStick) : this::startDpadStickPlacement),
                new LinearLayout.LayoutParams(-1, dp(42)));
        body.addView(button("Aim · mouse movement", false, () -> { dismissDialog(); mode = Mode.AIM; refresh(); }), new LinearLayout.LayoutParams(-1, dp(42)));
        showDialog("Add control", body, null); refresh();
    }
    private void startDpadPlacement() {
        dismissDialog(); mode = Mode.DPAD; notice = ""; requestFocus(); refresh();
    }
    private void startDpadStickPlacement() {
        dismissDialog(); mode = Mode.DPAD_STICK; notice = ""; requestFocus(); refresh();
    }
    private void showKeyList() {
        clearPending(); LinearLayout body = column();
        GameProfileStore.Profile p = profile();
        if (p != null) for (GameProfileStore.Binding b : p.bindings) {
            Button entry = button(b.label() + "  ·  " + Math.round(b.x * 100) + "% / " + Math.round(b.y * 100) + "%", false, () -> edit(b));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42)); lp.bottomMargin = dp(5); body.addView(entry, lp);
        }
        if (p != null && p.dpad != null) body.addView(button("Dpad · " + p.dpad.label() + " · "
                + Math.round(p.dpad.x * 100) + "% / " + Math.round(p.dpad.y * 100) + "%", false, () -> editDpad(p.dpad)),
                new LinearLayout.LayoutParams(-1, dp(42)));
        if (p != null && p.dpadStick != null) body.addView(button("Dpad (Stick) · " + p.dpadStick.axisLabel() + " · "
                + Math.round(p.dpadStick.x * 100) + "% / " + Math.round(p.dpadStick.y * 100) + "%", false,
                () -> editDpadStick(p.dpadStick)), new LinearLayout.LayoutParams(-1, dp(42)));
        if (p == null || p.bindings.isEmpty() && p.dpad == null && p.dpadStick == null)
            body.addView(label("No controls yet. Tap Add to place your first control.", 14, MUTED, false));
        showDialog("Mapped keys", body, null); refresh();
    }
    private void edit(GameProfileStore.Binding binding) { clearPending(); original = binding; candidate = binding; showPropertiesPanel(); refresh(); }
    private void editDpad(GameProfileStore.Dpad value) {
        clearPending(); candidateDpad = value; showDpadProperties(); refresh();
    }
    private void editDpadStick(GameProfileStore.DpadStick value) {
        clearPending(); candidateDpadStick = value; showDpadStickProperties(); refresh();
    }
    private int dpadKey(GameProfileStore.Dpad value, int index) {
        switch (index) {
            case 0: return value.upKey;
            case 1: return value.downKey;
            case 2: return value.leftKey;
            default: return value.rightKey;
        }
    }
    private void captureDpadKey(int direction) {
        dismissDialog(); mode = Mode.CAPTURE; captureDpadDirection = direction;
        captureForProperties = false; captureDpadShortcut = false; captureStickShortcut = false;
        notice = "Press the key for " + new String[]{"up", "down", "left", "right"}[direction] + ".";
        requestFocus(); refresh();
    }
    private void captureRadiusShortcut() {
        dismissDialog(); mode = Mode.CAPTURE; captureStickShortcut = true;
        captureDpadDirection = -1; captureDpadShortcut = false; captureForProperties = false;
        notice = "Press a key to change the stick radius."; requestFocus(); refresh();
    }
    private void captureDpadRadiusShortcut() {
        dismissDialog(); mode = Mode.CAPTURE; captureDpadShortcut = true;
        captureDpadDirection = -1; captureStickShortcut = false; captureForProperties = false;
        notice = "Press a key to change the Dpad radius."; requestFocus(); refresh();
    }
    private GameProfileStore.DpadStick copyStick(GameProfileStore.DpadStick s, Float x, Float y, Float radius,
            Integer delay, Integer key, Float offset, Boolean hold, Boolean reverseX, Boolean reverseY,
            Boolean swapXY, Integer frequency, Float stepLength, Float deadZone, Boolean rightStick, Boolean visible) {
        return new GameProfileStore.DpadStick(x == null ? s.x : x, y == null ? s.y : y,
                radius == null ? s.radius : radius, delay == null ? s.releaseDelayMs : delay,
                key == null ? s.changeRadiusKey : key, offset == null ? s.radiusOffset : offset,
                hold == null ? s.holdToChange : hold, reverseX == null ? s.reverseX : reverseX,
                reverseY == null ? s.reverseY : reverseY, swapXY == null ? s.swapXY : swapXY,
                frequency == null ? s.frequency : frequency, stepLength == null ? s.stepLength : stepLength,
                deadZone == null ? s.deadZone : deadZone, rightStick == null ? s.rightStick : rightStick,
                visible == null ? s.visible : visible);
    }
    private GameProfileStore.Dpad copyDpad(GameProfileStore.Dpad s, Float x, Float y, Float radius,
            Integer delay, Integer key, Float offset, Boolean hold, Float angle, Integer frequency,
            Float stepLength, Boolean visible) {
        return new GameProfileStore.Dpad(s.upKey, s.downKey, s.leftKey, s.rightKey,
                x == null ? s.x : x, y == null ? s.y : y, radius == null ? s.radius : radius,
                delay == null ? s.releaseDelayMs : delay, key == null ? s.changeRadiusKey : key,
                offset == null ? s.radiusOffset : offset, hold == null ? s.holdToChange : hold,
                angle == null ? s.angle : angle, frequency == null ? s.frequency : frequency,
                stepLength == null ? s.stepLength : stepLength, visible == null ? s.visible : visible, s.transitionDelayMs,s.stepLengthMin);
    }
    private void showDpadProperties() {
        if (candidateDpad == null) return;
        previewDpad = true; previewDpadStick = false;
        LinearLayout body = column();
        body.addView(label("One virtual joystick pointer follows the pressed directions. These keys take priority over matching individual mappings.", 13, MUTED, false));
        body.addView(section("DIRECTION KEYS"));
        String[] labels = {"Up", "Down", "Left", "Right"};
        for (int row = 0; row < 2; row++) {
            LinearLayout keys = row();
            for (int col = 0; col < 2; col++) {
                int direction = row * 2 + col;
                keys.addView(button(labels[direction] + " · " + new GameProfileStore.Binding(
                        dpadKey(candidateDpad, direction), 0, 0).label(), false,
                        () -> captureDpadKey(direction)), weighted());
            }
            body.addView(keys);
        }
        body.addView(section("RADIUS PREVIEW"));
        RadiusPreview radiusPreview = new RadiusPreview(0xFFC3A7FF);
        radiusPreview.setRadius(candidateDpad.radius, candidateDpad.changeRadiusKey == 0 ? 0 : candidateDpad.radiusOffset);
        body.addView(radiusPreview, new LinearLayout.LayoutParams(-1, dp(80)));
        body.addView(slider("Radius", candidateDpad.radius, 0.02f, 0.40f, value -> {
            candidateDpad = copyDpad(candidateDpad, null, null, value, null, null, null, null,
                    null, null, null, null);
            radiusPreview.setRadius(value, candidateDpad.changeRadiusKey == 0 ? 0 : candidateDpad.radiusOffset);
            surface.invalidate();
        }));
        String radiusShortcut = candidateDpad.changeRadiusKey == 0 ? "Off"
                : new GameProfileStore.Binding(candidateDpad.changeRadiusKey, 0, 0).label();
        body.addView(button("Walking radius shortcut: " + radiusShortcut, false, this::captureDpadRadiusShortcut),
                new LinearLayout.LayoutParams(-1, dp(42)));
        CheckBox holdRadius = new CheckBox(getContext()); holdRadius.setText("Hold shortcut to walk (off = toggle)");
        holdRadius.setTextColor(WHITE); holdRadius.setChecked(candidateDpad.holdToChange);
        holdRadius.setOnCheckedChangeListener((v, checked) -> candidateDpad = copyDpad(candidateDpad,
                null, null, null, null, null, null, checked, null, null, null, null));
        body.addView(holdRadius, new LinearLayout.LayoutParams(-1, dp(40)));
        body.addView(slider("Walking radius offset", candidateDpad.radiusOffset, -0.25f, 0.25f, value -> {
            candidateDpad = copyDpad(candidateDpad, null, null, null, null, null, value, null,
                    null, null, null, null);
            radiusPreview.setRadius(candidateDpad.radius, candidateDpad.changeRadiusKey == 0 ? 0 : value);
            surface.invalidate();
        }));
        View anglePreview=new View(getContext()) {
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas){
                if(candidateDpad==null)return;
                float cx=getWidth()*.42f,cy=getHeight()*.82f,r=getHeight()*.65f;
                float angle=candidateDpad.angle;
                float dx=(float)Math.sin(Math.toRadians(angle))*r,dy=(float)Math.cos(Math.toRadians(angle))*r;
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(0xFF82758F);
                canvas.drawLine(cx,cy,cx,cy-r,p);canvas.drawLine(cx,cy,cx+r,cy,p);
                p.setColor(0xFFC3A7FF);canvas.drawLine(cx,cy,cx+dx,cy-dy,p);
                canvas.drawArc(cx-r*.6f,cy-r*.6f,cx+r*.6f,cy+r*.6f,-90,angle,false,p);
                p.setStyle(Paint.Style.FILL);canvas.drawCircle(cx+dx,cy-dy,dp(4),p);
                p.setTextSize(dp(13));canvas.drawText(String.format(Locale.US,"%.1f degrees",angle),cx+r+dp(14),cy-r*.3f,p);
            }
        };
        anglePreview.setContentDescription("Live diagonal angle preview, zero is vertical and ninety is horizontal");
        body.addView(anglePreview,new LinearLayout.LayoutParams(-1,dp(100)));
        body.addView(slider("Angle (0?90 degrees)",candidateDpad.angle,0,90,value->{
            candidateDpad=copyDpad(candidateDpad,null,null,null,null,null,null,null,value,null,null,null);
            anglePreview.setContentDescription("Diagonal angle "+Math.round(value)+" degrees");
            anglePreview.invalidate();surface.invalidate();
        }));
        body.addView(slider("Frequency (updates/sec)",candidateDpad.frequency,1,120,value->
            candidateDpad=copyDpad(candidateDpad,null,null,null,null,null,null,null,null,Math.round(value),null,null)));
        TextView stepLabel=label("Step length: "+candidateDpad.stepLengthMin+"?"+Math.round(candidateDpad.stepLength)+" px",13,WHITE,false);
        body.addView(stepLabel);
        LinearLayout rangeSliders=column();
        android.widget.SeekBar minStep=new android.widget.SeekBar(getContext()),maxStep=new android.widget.SeekBar(getContext());
        minStep.setMax(99);maxStep.setMax(99);minStep.setProgress(candidateDpad.stepLengthMin-1);maxStep.setProgress(Math.round(candidateDpad.stepLength)-1);
        minStep.setContentDescription("Minimum step length in pixels");maxStep.setContentDescription("Maximum step length in pixels");
        android.widget.SeekBar.OnSeekBarChangeListener rangeChange=new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){
                if(!user)return;
                int low=minStep.getProgress()+1,high=maxStep.getProgress()+1;
                if(low>high){if(bar==minStep){high=low;maxStep.setProgress(high-1);}else{low=high;minStep.setProgress(low-1);}}
                candidateDpad=candidateDpad.stepRange(low,high);stepLabel.setText("Step length: "+low+"?"+high+" px");
            }
            public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}
        };
        minStep.setOnSeekBarChangeListener(rangeChange);maxStep.setOnSeekBarChangeListener(rangeChange);
        rangeSliders.addView(minStep);rangeSliders.addView(maxStep);body.addView(rangeSliders);
        body.addView(label("Uses the midpoint of the range for consistent speed. Smaller steps or lower frequency reach the target more slowly.",11,MUTED,false));
        body.addView(slider("Direction transition (ms)", candidateDpad.transitionDelayMs, 0, 50,
                value -> candidateDpad = candidateDpad.transitionDelay(Math.round(value))));
        body.addView(label("Direction changes travel around the joystick edge; W uses the same duration. Walking changes the radius.", 11, MUTED, false));
        body.addView(label("Circular movement: every direction uses the selected radius.", 12, MUTED, false));
        CheckBox visible = new CheckBox(getContext()); visible.setText("Show Dpad marker during gameplay");
        visible.setTextColor(WHITE); visible.setButtonTintList(ColorStateList.valueOf(0xFFA787FF));
        visible.setChecked(candidateDpad.visible);
        visible.setOnCheckedChangeListener((v, checked) -> candidateDpad = copyDpad(candidateDpad,
                null, null, null, null, null, null, null, null, null, null, checked));
        body.addView(visible, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout footer = row();
        footer.addView(icon(android.R.drawable.ic_menu_delete, "Delete Dpad", () -> {
            GameProfileStore.removeDpad(getContext(), packageName); dismissDialog(); clearPending(); changed(); refresh();
        }), size(48, 48));
        footer.addView(button("Move", false, () -> {
            movingKey = DPAD_CONTROL; dismissDialog(); mode = Mode.MOVE; refresh();
        }), weighted());
        footer.addView(button("Cancel", false, () -> { dismissDialog(); clearPending(); refresh(); }), weighted());
        footer.addView(button("Save", true, () -> {
            GameProfileStore.saveDpad(getContext(), packageName, candidateDpad);
            dismissDialog(); clearPending(); changed(); refresh();
        }), weighted());
        showDialog("Dpad · WASD", body, footer);
    }
    private void showDpadStickProperties() {
        if (candidateDpadStick == null) return;
        previewDpadStick = true; previewDpad = false;
        LinearLayout body = column();
        body.addView(label("The gamepad's physical stick moves the virtual joystick at this point.", 13, MUTED, false));
        body.addView(section("RADIUS PREVIEW"));
        RadiusPreview radiusPreview = new RadiusPreview(0xFF8FE4D0);
        radiusPreview.setRadius(candidateDpadStick.radius,
                candidateDpadStick.changeRadiusKey == 0 ? 0 : candidateDpadStick.radiusOffset);
        body.addView(radiusPreview, new LinearLayout.LayoutParams(-1, dp(80)));
        body.addView(section("INPUT"));
        body.addView(button("Physical input · " + candidateDpadStick.axisLabel() + "  ·  Change", false,
                () -> {
                    candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, !candidateDpadStick.rightStick, null);
                    showDpadStickProperties(); refresh();
                }),
                new LinearLayout.LayoutParams(-1, dp(42)));
        String shortcutLabel = candidateDpadStick.changeRadiusKey == 0 ? "Off"
                : new GameProfileStore.Binding(candidateDpadStick.changeRadiusKey, 0, 0).label();
        body.addView(button("Change radius shortcut · " + shortcutLabel, false, this::captureRadiusShortcut),
                new LinearLayout.LayoutParams(-1, dp(42)));
        CheckBox hold = new CheckBox(getContext()); hold.setText("Hold shortcut to change radius");
        hold.setTextColor(WHITE); hold.setButtonTintList(ColorStateList.valueOf(0xFFA787FF)); hold.setChecked(candidateDpadStick.holdToChange);
        hold.setOnCheckedChangeListener((v, checked) -> candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null,
                null, null, checked, null, null, null, null, null, null, null, null));
        body.addView(hold, new LinearLayout.LayoutParams(-1, dp(40)));
        body.addView(slider("Radius", candidateDpadStick.radius, 0.02f, 0.40f, value ->
                {
                    candidateDpadStick = copyStick(candidateDpadStick, null, null, value, null, null, null, null,
                            null, null, null, null, null, null, null, null);
                    radiusPreview.setRadius(value,
                            candidateDpadStick.changeRadiusKey == 0 ? 0 : candidateDpadStick.radiusOffset);
                    surface.invalidate();
                }));
        body.addView(slider("Release delay (ms)", candidateDpadStick.releaseDelayMs, 0, 500, value ->
                candidateDpadStick = copyStick(candidateDpadStick, null, null, null, Math.round(value), null, null, null,
                        null, null, null, null, null, null, null, null)));
        body.addView(slider("Radius offset", candidateDpadStick.radiusOffset, -0.25f, 0.25f, value -> {
            candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, value, null,
                    null, null, null, null, null, null, null, null);
            radiusPreview.setRadius(candidateDpadStick.radius,
                    candidateDpadStick.changeRadiusKey == 0 ? 0 : candidateDpadStick.radiusOffset);
            surface.invalidate();
        }));
        body.addView(slider("Dead zone", candidateDpadStick.deadZone, 0, 0.70f, value ->
                candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, null, null,
                        null, null, null, null, null, value, null, null)));
        body.addView(slider("Frequency (updates/sec)", candidateDpadStick.frequency, 1, 120, value ->
                candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, null, null,
                        null, null, null, Math.round(value), null, null, null, null)));
        body.addView(slider("Step length", candidateDpadStick.stepLength, 0.001f, 0.10f, value ->
                candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, null, null,
                        null, null, null, null, value, null, null, null)));
        body.addView(section("AXIS OPTIONS"));
        addStickCheck(body, "Reverse X", candidateDpadStick.reverseX, checked -> candidateDpadStick = copyStick(candidateDpadStick,
                null, null, null, null, null, null, null, checked, null, null, null, null, null, null, null));
        addStickCheck(body, "Reverse Y", candidateDpadStick.reverseY, checked -> candidateDpadStick = copyStick(candidateDpadStick,
                null, null, null, null, null, null, null, null, checked, null, null, null, null, null, null));
        addStickCheck(body, "Switch X and Y", candidateDpadStick.swapXY, checked -> candidateDpadStick = copyStick(candidateDpadStick,
                null, null, null, null, null, null, null, null, null, checked, null, null, null, null, null));
        addStickCheck(body, "Show Dpad Stick marker during gameplay", candidateDpadStick.visible,
                checked -> candidateDpadStick = copyStick(candidateDpadStick, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, checked));
        LinearLayout footer = row();
        footer.addView(icon(android.R.drawable.ic_menu_delete, "Delete Dpad Stick", () -> {
            GameProfileStore.removeDpadStick(getContext(), packageName); dismissDialog(); clearPending(); changed(); refresh();
        }), size(48, 48));
        footer.addView(button("Move", false, () -> {
            movingKey = DPAD_STICK_CONTROL; dismissDialog(); mode = Mode.MOVE; refresh();
        }), weighted());
        footer.addView(button("Cancel", false, () -> { dismissDialog(); clearPending(); refresh(); }), weighted());
        footer.addView(button("Save", true, () -> {
            GameProfileStore.saveDpadStick(getContext(), packageName, candidateDpadStick);
            dismissDialog(); clearPending(); changed(); refresh();
        }), weighted());
        showDialog("Dpad (Stick)", body, footer);
    }
    private interface CheckedChange { void change(boolean checked); }
    private final class RadiusPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent;
        private float radius, offset;
        RadiusPreview(int accent) { super(MappingEditorView.this.getContext()); this.accent = accent; setContentDescription("Live joystick radius preview"); }
        void setRadius(float value) { radius = value; invalidate(); }
        void setRadius(float value, float offset) { radius = value; this.offset = offset; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float maxRadius = Math.max(dp(18), Math.min(getWidth(), getHeight()) * 0.36f);
            float currentRadius = maxRadius * Math.max(0.02f, Math.min(0.40f, radius)) / 0.40f;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF29272E);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xFF5A5661);
            canvas.drawCircle(cx, cy, maxRadius, paint);
            float adjustedRadius = maxRadius * Math.max(0.02f, Math.min(0.40f, radius + offset)) / 0.40f;
            if (offset != 0 && Math.abs(adjustedRadius - currentRadius) > dp(2)) {
                paint.setAlpha(140); canvas.drawCircle(cx, cy, adjustedRadius, paint); paint.setAlpha(255);
            }
            paint.setStrokeWidth(dp(2)); paint.setColor(accent); canvas.drawCircle(cx, cy, currentRadius, paint);
            canvas.drawLine(cx, cy, cx + currentRadius, cy, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx + currentRadius, cy, dp(5), paint);
            paint.setColor(MUTED); paint.setTextSize(dp(10)); paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("CENTER", dp(8), dp(15), paint);
        }
    }
    private void addStickCheck(LinearLayout body, String title, boolean checked, CheckedChange change) {
        CheckBox box = new CheckBox(getContext()); box.setText(title); box.setTextColor(WHITE);
        box.setButtonTintList(ColorStateList.valueOf(0xFFA787FF)); box.setChecked(checked);
        box.setOnCheckedChangeListener((v, value) -> change.change(value));
        body.addView(box, new LinearLayout.LayoutParams(-1, dp(42)));
    }
    private void showPropertiesPanel() {
        boolean aiming = candidate.keyCode == GameProfileStore.MOUSE_AIM;
        final EditText[] releaseDelayInput = new EditText[1];
        LinearLayout body = column();
        LinearLayout shortcut = row(); shortcut.addView(label("Shortcut", 15, WHITE, false), new LinearLayout.LayoutParams(0, dp(50), 1));
        shortcut.addView(button((aiming ? candidate.aimKeyLabel() : candidate.label()) + "  ·  Change", false,
                () -> startCapture(candidate.x, candidate.y, true)), size(150, 42));
        body.addView(shortcut);
        TextView action = label(aiming ? "Mouse movement controls the camera while aim is on." : "Action: tap the game at this circle's position.", 13, MUTED, false);
        action.setPadding(0, dp(8), 0, dp(12)); body.addView(action);
        if (aiming) {
            CheckBox hold = new CheckBox(getContext()); hold.setText("Hold shortcut to aim (off = toggle)"); hold.setTextColor(WHITE);
            hold.setButtonTintList(ColorStateList.valueOf(0xFFA787FF)); hold.setChecked(candidate.aimHold);
            hold.setOnCheckedChangeListener((v, checked) -> candidate = candidate.withAim(candidate.aimKey, checked));
            body.addView(hold, new LinearLayout.LayoutParams(-1, dp(40)));
            LinearLayout boundaryRadius = slider("Boundary radius", candidate.aimBoundaryRadius, 0.05f, 0.45f,
                    value -> updateAimOptions(null, value, null, null, null));
            boundaryRadius.setVisibility(candidate.aimBoundary ? View.VISIBLE : View.GONE);
            addAimSwitch(body, "Boundary", candidate.aimBoundary, checked -> {
                updateAimOptions(checked, null, null, null, null);
                boundaryRadius.setVisibility(checked ? View.VISIBLE : View.GONE);
            });
            body.addView(boundaryRadius);
            body.addView(slider("Sensitivity X", candidate.sensitivityX, 0.2f, 8f, value ->
                    candidate = candidate.withSensitivity(value, candidate.sensitivityY)));
            body.addView(slider("Sensitivity Y", candidate.sensitivityY, 0.2f, 8f, value ->
                    candidate = candidate.withSensitivity(candidate.sensitivityX, value)));
            LinearLayout delayRow = row();
            delayRow.addView(label("Release delay (ms)", 15, WHITE, false), new LinearLayout.LayoutParams(0, dp(48), 1));
            EditText delay = new EditText(getContext()); releaseDelayInput[0]=delay; delay.setSingleLine(true);
            delay.setInputType(InputType.TYPE_CLASS_NUMBER);
            delay.setFocusable(true); delay.setFocusableInTouchMode(true); delay.setShowSoftInputOnFocus(true);
            delay.setTextColor(WHITE); delay.setHintTextColor(MUTED); delay.setHint("200");
            delay.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); delay.setText(String.valueOf(candidate.aimReleaseDelayMs));
            delay.setSelectAllOnFocus(true); delay.setBackgroundTintList(ColorStateList.valueOf(0xFFAAA6B0));
            delay.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
            delay.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    InputMethodManager input = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (input != null) delay.post(() -> input.showSoftInput(delay, InputMethodManager.SHOW_IMPLICIT));
                }
            });
            delay.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    try {
                        int value = Integer.parseInt(s.toString());
                        if(value>2000) { delay.setError("Enter 0 to 2000 ms"); return; }
                        delay.setError(null); updateAimOptions(null, null, null, value, null);
                    } catch (NumberFormatException ignored) { delay.setError("Enter 0 to 2000 ms"); }
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            delayRow.addView(delay, size(92, 48));
            addAimSwitch(body, "Auto Release", candidate.aimAutoRelease, checked -> {
                updateAimOptions(null, null, checked, null, null);
            });
            body.addView(delayRow);
            TextView releaseHelp = label("When Auto Release is on, this idle time lifts the camera touch. Turning aim off always releases it immediately.", 11, MUTED, false);
            releaseHelp.setPadding(dp(4), 0, dp(4), dp(6)); body.addView(releaseHelp);
            addAimSwitch(body, "Offset correction", candidate.aimOffsetCorrection,
                    checked -> updateAimOptions(null, null, null, null, checked));
        }
        CheckBox visible = new CheckBox(getContext()); visible.setText("Show this key during gameplay"); visible.setTextColor(WHITE);
        visible.setButtonTintList(ColorStateList.valueOf(0xFFA787FF)); visible.setChecked(candidate.visible);
        visible.setOnCheckedChangeListener((v, checked) -> candidate = candidate.withVisibility(checked));
        body.addView(visible, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout footer = row();
        footer.addView(icon(android.R.drawable.ic_menu_delete, "Delete mapping", () -> {
            GameProfileStore.removeBinding(getContext(), packageName, original.keyCode);
            dismissDialog(); clearPending(); changed(); refresh();
        }), size(48, 48));
        footer.addView(button("Move", false, () -> {
            movingKey = original.keyCode; dismissDialog(); mode = Mode.MOVE; refresh();
        }), weighted());
        footer.addView(button("Cancel", false, () -> { dismissDialog(); clearPending(); refresh(); }), weighted());
        footer.addView(button("Save", true, () -> {
            if (releaseDelayInput[0] != null && (releaseDelayInput[0].getError()!=null
                    || releaseDelayInput[0].getText().length()==0)) {
                releaseDelayInput[0].setError("Enter 0 to 2000 ms"); releaseDelayInput[0].requestFocus(); return;
            }
            if (GameProfileStore.saveBinding(getContext(), packageName, original.keyCode, candidate)) {
                dismissDialog(); clearPending(); changed(); refresh();
            }
        }), weighted());
        showDialog(aiming ? "Aiming mode" : "Tap", body, footer);
        if (aiming) surface.previewAimBoundary(candidate.x, candidate.y, candidate.aimBoundary, candidate.aimBoundaryRadius);
    }
    private void updateAimOptions(Boolean boundary, Float boundaryRadius, Boolean autoRelease,
                                  Integer releaseDelay, Boolean offsetCorrection) {
        candidate = candidate.withAimOptions(
                boundary == null ? candidate.aimBoundary : boundary,
                boundaryRadius == null ? candidate.aimBoundaryRadius : boundaryRadius,
                autoRelease == null ? candidate.aimAutoRelease : autoRelease,
                releaseDelay == null ? candidate.aimReleaseDelayMs : releaseDelay,
                offsetCorrection == null ? candidate.aimOffsetCorrection : offsetCorrection);
        if (candidate.keyCode == GameProfileStore.MOUSE_AIM && (boundary != null || boundaryRadius != null))
            surface.previewAimBoundary(candidate.x, candidate.y, candidate.aimBoundary, candidate.aimBoundaryRadius);
    }
    private void addAimSwitch(LinearLayout body, String title, boolean checked, CheckedChange change) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = label(title, 15, WHITE, false);
        row.addView(text, new LinearLayout.LayoutParams(0, dp(50), 1));
        Switch toggle = new Switch(getContext()); toggle.setChecked(checked);
        toggle.setThumbTintList(ColorStateList.valueOf(checked ? PURPLE : 0xFFE5E3E8));
        toggle.setTrackTintList(ColorStateList.valueOf(checked ? 0x80693CE5 : 0x665B5861));
        toggle.setOnCheckedChangeListener((button, value) -> {
            toggle.setThumbTintList(ColorStateList.valueOf(value ? PURPLE : 0xFFE5E3E8));
            toggle.setTrackTintList(ColorStateList.valueOf(value ? 0x80693CE5 : 0x665B5861));
            change.change(value);
        });
        row.addView(toggle, size(56, 48)); body.addView(row);
    }
    private TextView section(String title) {
        TextView value = label(title, 11, 0xFFC3A7FF, true);
        value.setPadding(0, dp(8), 0, dp(3));
        return value;
    }
    interface FloatChange { void onValue(float value); }
    private String sliderValueLabel(String title, float value) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("frequency") || lower.contains("release delay")
                || lower.contains("circle size") || lower.contains("opacity")) {
            return String.format(Locale.US, "%s   %d", title, Math.round(value));
        }
        return String.format(Locale.US, "%s   %.2f", title, value);
    }
    private LinearLayout slider(String title, float initial, float min, float max, FloatChange change) {
        LinearLayout box = column(); TextView text = label("", 13, WHITE, false);
        text.setText(sliderValueLabel(title, initial)); box.addView(text);
        SeekBar slider = new SeekBar(getContext()); slider.setMax(100); slider.setProgress(Math.round((initial - min) / (max - min) * 100));
        slider.setProgressTintList(ColorStateList.valueOf(0xFFA787FF)); slider.setThumbTintList(ColorStateList.valueOf(0xFFCCB8FF));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v, int value, boolean fromUser) {
                float result = min + (max - min) * value / 100f;
                text.setText(sliderValueLabel(title, result)); if (fromUser) change.onValue(result);
            }
            public void onStartTrackingTouch(SeekBar v) { }
            public void onStopTrackingTouch(SeekBar v) { }
        });
        slider.setContentDescription(title);
        box.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        box.setPadding(0, 0, 0, dp(4));
        return box;
    }
    private void showMouseRateSettings() {
        LinearLayout body=column();int selected=options().getInt("mouse_processing_hz",500);
        body.addView(button("Open Y2E app",false,()->host.onOpenApp()),new LinearLayout.LayoutParams(-1,dp(42)));
        body.addView(label("Mouse processing rate: "+selected+" Hz",15,WHITE,true));
        for(int rate:new int[]{250,500,1000})body.addView(button((rate==selected?"Selected: ":"")+rate+" Hz",rate==selected,()->{
            options().edit().putInt("mouse_processing_hz",rate).apply();
            DaemonFiles.setMouseRate(getContext(),rate);showMouseRateSettings();
        }),new LinearLayout.LayoutParams(-1,dp(42)));
        body.addView(label("Controls Y2E mouse-event processing. Hardware polling stays unchanged; actual timing depends on Android and your mouse. 1000 Hz uses more CPU.",12,MUTED,false));
        showDialog("Mouse processing",body,null);
    }
    private void showSettings() {
        clearPending(); LinearLayout body = column();
        CheckBox visible = new CheckBox(getContext()); visible.setText("Show mapped keys during gameplay"); visible.setTextColor(WHITE);
        visible.setChecked(labelsVisible(getContext()));
        visible.setButtonTintList(ColorStateList.valueOf(0xFFA787FF));
        visible.setOnCheckedChangeListener((v, checked) -> {
            options().edit().putBoolean("labels", checked).apply();
        });
        body.addView(visible, new LinearLayout.LayoutParams(-1, dp(40)));
        body.addView(slider("Circle size", markerSize(getContext()), 32, 64, value -> {
            float previousSize = markerSize(getContext());
            options().edit().putInt("size", Math.round(value)).apply();
            surface.invalidateMarkerAppearance(previousSize);
        }));
        body.addView(slider("Gameplay opacity %", markerOpacity(getContext()) * 100, 20, 75, value -> {
            options().edit().putInt("opacity", Math.round(value)).apply(); surface.invalidateMarkerAppearance();
        }));
        body.addView(button("Mouse processing rate",false,this::showMouseRateSettings),new LinearLayout.LayoutParams(-1,dp(42)));
        body.addView(section("INPUT DIAGNOSTICS"));
        body.addView(button("WASD and joystick tracker",false,()->getContext().startActivity(
                new android.content.Intent(getContext(),InputTrackerActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))),new LinearLayout.LayoutParams(-1,dp(42)));
        body.addView(section("DEVICE ACTIVATION"));
        TextView activationHelp = label("Restart the Y2E app to rebuild the overlay and show the saved keybinds again.", 13, MUTED, false);
        activationHelp.setPadding(0, dp(3), 0, dp(8)); body.addView(activationHelp);
        body.addView(button("Restart Y2E app", false, this::restartY2E),
                new LinearLayout.LayoutParams(-1, dp(42)));
        body.addView(section("TEXT INPUT"));
        CheckBox deviceKeyboard = new CheckBox(getContext());
        deviceKeyboard.setText("Allow Android keyboard in games"); deviceKeyboard.setTextColor(WHITE);
        deviceKeyboard.setButtonTintList(ColorStateList.valueOf(0xFFA787FF));
        deviceKeyboard.setChecked(keyboardInputEnabled(getContext()));
        deviceKeyboard.setOnCheckedChangeListener((v, checked) -> {
            setKeyboardInputEnabled(getContext(), checked);
        });
        body.addView(deviceKeyboard, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView keyboardHelp = label("Turn this on before tapping a text box. Y2E releases mouse capture and pauses mapped touches so the game can show and receive Android keyboard input. You can also long press the floating Y2E button to toggle this mode; tap it to restore controls.", 12, MUTED, false);
        keyboardHelp.setPadding(dp(4), 0, dp(4), dp(8)); body.addView(keyboardHelp);
        Button keyboardShortcut = button("Keyboard mode shortcut  ·  " + keyboardModeHotkeyLabel(getContext()), false,
                () -> startGlobalCapture(true));
        keyboardShortcut.setOnLongClickListener(view -> {
            options().edit().remove("keyboard_mode_hotkey_configured")
                    .remove("keyboard_mode_hotkey_modifier").remove("keyboard_mode_hotkey_key").apply();
            showSettings(); changed(); return true;
        });
        body.addView(keyboardShortcut, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView keyboardShortcutNote = label("Tap to set; hold one key and press a second for a two-key shortcut. Hold the button to clear.", 11, MUTED, false);
        keyboardShortcutNote.setPadding(dp(4), dp(3), 0, dp(2)); body.addView(keyboardShortcutNote);
        body.addView(section("OVERLAY TOGGLE SHORTCUT"));
        TextView shortcutHelp = label("One key or two keys together hide/show the Y2E controls. Input simulation stays active.", 13, MUTED, false);
        shortcutHelp.setPadding(0, dp(3), 0, dp(8)); body.addView(shortcutHelp);
        Button shortcut = button("Hide/show controls  ·  " + overlayToggleLabel(getContext()), false,
                () -> startGlobalCapture(false));
        shortcut.setOnLongClickListener(view -> {
            options().edit().remove("overlay_toggle_configured")
                    .remove("overlay_toggle_modifier").remove("overlay_toggle_key").apply();
            showSettings(); changed(); return true;
        });
        body.addView(shortcut, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView shortcutNote = label("Tap to set. Hold to clear.", 11, MUTED, false);
        shortcutNote.setPadding(dp(4), dp(3), 0, 0); body.addView(shortcutNote);
        TextView help = label("Drag the Y2E title to move the toolbar. Game touches pass through saved labels after Done.", 13, MUTED, false);
        help.setPadding(0, dp(12), 0, dp(12)); body.addView(help);
        showDialog("Editor settings", body, null); refresh();
    }
    private void restartY2E() {
        Context context = getContext();
        String editingPackage = GameProfileStore.editingPackage(context);
        notice = "Restarting Y2E app…"; refresh();
        KeyMappingService.stop(context);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            KeyMappingService.start(context);
            // Restore the editor session after the service's normal startup
            // cleanup so the saved key circles appear again automatically.
            if (!editingPackage.isEmpty()) {
                GameProfileStore.setEditing(context, editingPackage, true);
                DaemonFiles.setCaptureMode(context, editingPackage, false);
            }
            Toast.makeText(context, "Y2E restarted; controls refreshed.", Toast.LENGTH_LONG).show();
        }, 450);
    }
    private void startGlobalCapture(boolean keyboardMode) {
        dismissDialog(); mode = Mode.CAPTURE; globalCapture = true; globalFirstKey = 0;
        globalKeyboardModeCapture = keyboardMode;
        notice = "Press one key, or hold the first key and press a second.";
        captureForProperties = false; requestFocus(); refresh();
    }
    private int dialogWidth() {
        return Math.min(dp(520), Math.max(dp(280), getWidth() - dp(32)));
    }
    private int dialogHeight() {
        return Math.min(dp(680), Math.max(dp(260), Math.round(getHeight() * 0.88f)));
    }
    private void applyDialogSpacing(LinearLayout content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            if (i < content.getChildCount() - 1 && params.bottomMargin == 0) {
                params.bottomMargin = dp(4);
                child.setLayoutParams(params);
            }
        }
    }
    private void showDialog(String title, LinearLayout content, LinearLayout footer) {
        dismissDialog();
        dialog = new FrameLayout(getContext()); dialog.setBackgroundColor(0x66000000); dialog.setClickable(true);
        LinearLayout card = column(); card.setBackground(strokedBackground(PANEL, PANEL_BORDER, 16));
        card.setPadding(dp(16), dp(8), dp(16), dp(10));
        card.setClickable(true);
        LinearLayout header = row();
        header.addView(label(title, 18, WHITE, true), new LinearLayout.LayoutParams(0, dp(42), 1));
        header.addView(icon(android.R.drawable.ic_menu_close_clear_cancel, "Close panel", () -> { dismissDialog(); clearPending(); refresh(); }), size(40, 40));
        card.addView(header);
        int maxScrollHeight = Math.max(dp(120), dialogHeight() - dp(116));
        ScrollView scroll = new ScrollView(getContext()) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int available = mode == MeasureSpec.UNSPECIFIED
                        ? maxScrollHeight : Math.min(maxScrollHeight, MeasureSpec.getSize(heightMeasureSpec));
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        applyDialogSpacing(content);
        content.setPadding(0, dp(3), 0, dp(5)); scroll.addView(content);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        if (footer != null) { footer.setPadding(0, dp(8), 0, 0); card.addView(footer); }
        else card.addView(button("Close", true, () -> { dismissDialog(); refresh(); }), new LinearLayout.LayoutParams(-1, dp(42)));
        LayoutParams cp = new LayoutParams(dialogWidth(), -2, Gravity.CENTER);
        dialog.addView(card, cp); addView(dialog, new LayoutParams(-1, -1)); requestFocus();
    }
    private void resizeDialog() {
        View card = dialog.getChildAt(0); LayoutParams p = (LayoutParams) card.getLayoutParams();
        p.width = dialogWidth(); p.height = -2; card.setLayoutParams(p);
    }
    private void dismissDialog() {
        if (dialog != null) removeView(dialog);
        dialog = null;
        surface.clearAimBoundaryPreview();
    }

    static int mouseCode(int button) {
        if ((button & MotionEvent.BUTTON_PRIMARY) != 0) return GameProfileStore.MOUSE_LEFT;
        if ((button & MotionEvent.BUTTON_SECONDARY) != 0) return GameProfileStore.MOUSE_RIGHT;
        if ((button & MotionEvent.BUTTON_TERTIARY) != 0) return GameProfileStore.MOUSE_MIDDLE;
        if ((button & MotionEvent.BUTTON_BACK) != 0) return GameProfileStore.MOUSE_BACK;
        if ((button & MotionEvent.BUTTON_FORWARD) != 0) return GameProfileStore.MOUSE_FORWARD;
        return 0;
    }
    private final class MapSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private int pointer = -1, dragKey;
        private float downX, downY, offsetX, offsetY, dragX, dragY;
        private boolean moved, consumedGesture;
        private boolean aimBoundaryPreview;
        private boolean previewBoundaryEnabled;
        private float previewAimX, previewAimY, previewAimRadius;
        MapSurface(Context context) { super(context); setContentDescription("Mapping surface. Tap a control to add a key."); }
        void previewAimBoundary(float x, float y, boolean enabled, float radiusFraction) {
            invalidateAimPreviewBounds(previewAimX, previewAimY, previewAimRadius);
            aimBoundaryPreview = true;
            previewAimX = x; previewAimY = y;
            previewBoundaryEnabled = enabled; previewAimRadius = radiusFraction;
            invalidateAimPreviewBounds(previewAimX, previewAimY, previewAimRadius);
        }
        void clearAimBoundaryPreview() {
            if (!aimBoundaryPreview) return;
            invalidateAimPreviewBounds(previewAimX, previewAimY, previewAimRadius);
            aimBoundaryPreview = false;
        }
        private void invalidateAimPreviewBounds(float x, float y, float radiusFraction) {
            if (getWidth() <= 0 || getHeight() <= 0) { invalidate(); return; }
            float radius = Math.max(dp(markerSize(getContext()) / 2f + 2),
                    radiusFraction * Math.min(getWidth(), getHeight())) + dp(4);
            invalidateMarkerBounds(x * getWidth(), y * getHeight(), radius);
        }
        void invalidateMarkerAppearance() { invalidateMarkerAppearance(markerSize(getContext())); }
        void invalidateMarkerAppearance(float previousSize) {
            GameProfileStore.Profile p = profile();
            if (p == null || getWidth() <= 0 || getHeight() <= 0) { invalidate(); return; }
            float markerRadius = dp(Math.max(markerSize(getContext()), previousSize) / 2f + 2);
            float unit = Math.min(getWidth(), getHeight());
            for (GameProfileStore.Binding binding : p.bindings) {
                invalidateMarkerBounds(binding.x * getWidth(), binding.y * getHeight(), markerRadius);
                if (binding.keyCode == GameProfileStore.MOUSE_AIM && binding.aimBoundary)
                    invalidateMarkerBounds(binding.x * getWidth(), binding.y * getHeight(),
                            binding.aimBoundaryRadius * unit + dp(4));
            }
            if (p.dpad != null) invalidateMarkerBounds(p.dpad.x * getWidth(), p.dpad.y * getHeight(),
                    Math.max(markerRadius, (p.dpad.radius + Math.abs(p.dpad.radiusOffset)) * unit + dp(3)));
            if (p.dpadStick != null) invalidateMarkerBounds(p.dpadStick.x * getWidth(), p.dpadStick.y * getHeight(),
                    Math.max(markerRadius, (p.dpadStick.radius + Math.abs(p.dpadStick.radiusOffset)) * unit + dp(3)));
        }
        private void invalidateMarkerBounds(float x, float y, float radius) {
            int left = Math.max(0, (int) (x - radius)), top = Math.max(0, (int) (y - radius));
            int right = Math.min(getWidth(), (int) (x + radius + 1));
            int bottom = Math.min(getHeight(), (int) (y + radius + 1));
            if (right > left && bottom > top) invalidate(left, top, right, bottom);
        }
        private int opacityColor(int color, float factor) {
            int alpha = Math.max(0, Math.min(255, Math.round(Color.alpha(color) * factor)));
            return (color & 0x00FFFFFF) | (alpha << 24);
        }
        private int hit(float x, float y) {
            GameProfileStore.Profile p = profile(); if (p == null) return 0;
            float controlRadius = dp(markerSize(getContext()) / 2f + 6);
            if (p.dpad != null && Math.hypot(x - p.dpad.x * getWidth(), y - p.dpad.y * getHeight()) <= controlRadius)
                return DPAD_CONTROL;
            if (p.dpadStick != null && Math.hypot(x - p.dpadStick.x * getWidth(), y - p.dpadStick.y * getHeight()) <= controlRadius)
                return DPAD_STICK_CONTROL;
            for (int i = p.bindings.size() - 1; i >= 0; i--) {
                GameProfileStore.Binding b = p.bindings.get(i);
                if (Math.hypot(x - b.x * getWidth(), y - b.y * getHeight()) <= dp(markerSize(getContext()) / 2f + 6)) return b.keyCode;
            }
            return 0;
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0x1A000000);
            GameProfileStore.Profile p = profile();
            if (p != null) for (GameProfileStore.Binding b : p.bindings) {
                if (mode == Mode.CAPTURE && captureForProperties && original != null && b.keyCode == original.keyCode) continue;
                float x = moved && dragKey == b.keyCode ? dragX : b.x * getWidth();
                float y = moved && dragKey == b.keyCode ? dragY : b.y * getHeight();
                if (!aimBoundaryPreview && b.keyCode == GameProfileStore.MOUSE_AIM && b.aimBoundary)
                    drawAimBoundary(canvas, x, y, b.aimBoundaryRadius * Math.min(getWidth(), getHeight()));
                drawCircle(canvas, x, y, b.label(), false, b.visible);
            }
            if (aimBoundaryPreview && previewBoundaryEnabled)
                drawAimBoundary(canvas, previewAimX * getWidth(), previewAimY * getHeight(),
                        previewAimRadius * Math.min(getWidth(), getHeight()));
            GameProfileStore.Dpad dpad = previewDpad && candidateDpad != null ? candidateDpad : p == null ? null : p.dpad;
            GameProfileStore.DpadStick stick = previewDpadStick && candidateDpadStick != null ? candidateDpadStick : p == null ? null : p.dpadStick;
            if (dpad != null) drawGroup(canvas,
                    moved && dragKey == DPAD_CONTROL ? dragX : dpad.x * getWidth(),
                    moved && dragKey == DPAD_CONTROL ? dragY : dpad.y * getHeight(),
                    dpad.radius, dpad.radiusOffset, dpad.changeRadiusKey != 0, "WASD", false, dpad.angle);
            if (stick != null) drawGroup(canvas,
                    moved && dragKey == DPAD_STICK_CONTROL ? dragX : stick.x * getWidth(),
                    moved && dragKey == DPAD_STICK_CONTROL ? dragY : stick.y * getHeight(),
                    stick.radius, stick.radiusOffset, stick.changeRadiusKey != 0, "STICK", true, 45f);
            if (mode == Mode.CAPTURE) drawCircle(canvas, pointX * getWidth(), pointY * getHeight(), "?", true, true);
        }
        private void drawAimBoundary(Canvas canvas, float x, float y, float radius) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
            paint.setColor(opacityColor(0xFF67D7F2, markerOpacity(getContext())));
            paint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            canvas.drawCircle(x, y, radius, paint);
            paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
        }
        private void drawGroup(Canvas canvas, float x, float y, float radiusFraction, float offset,
                               boolean offsetEnabled, String title, boolean stick, float diagonalAngle) {
            float opacity = markerOpacity(getContext());
            float extent = Math.max(dp(20), radiusFraction * Math.min(getWidth(), getHeight()));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
            paint.setColor(opacityColor(stick ? 0xFF8FE4D0 : 0xFFC3A7FF, opacity));
            paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
            canvas.drawCircle(x, y, extent, paint);
            paint.setPathEffect(null);
            if (!stick) {
                drawDpadDirections(canvas, x, y, extent, diagonalAngle, opacityColor(0xBFC3A7FF, opacity));
                if (offsetEnabled && offset != 0f) {
                    float adjusted = Math.max(0.02f, Math.min(0.45f, radiusFraction + offset))
                            * Math.min(getWidth(), getHeight());
                    drawDpadDirections(canvas, x, y, adjusted, diagonalAngle, opacityColor(0xBFE5C66B, opacity));
                }
            }
            if (offsetEnabled && offset != 0f) {
                float adjusted = Math.max(0.02f, Math.min(0.45f, radiusFraction + offset))
                        * Math.min(getWidth(), getHeight());
                if (Math.abs(adjusted - extent) > dp(2)) {
                    paint.setColor(opacityColor(0xFFE5C66B, opacity)); paint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(4)}, 0));
                    canvas.drawCircle(x, y, adjusted, paint);
                    paint.setPathEffect(null);
                }
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(opacityColor(0xE51A1720, opacity)); canvas.drawCircle(x, y, dp(24), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(opacityColor(stick ? 0xFF8FE4D0 : 0xFFC3A7FF, opacity));
            canvas.drawCircle(x, y, dp(24), paint); paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(dp(10));
            paint.setColor(opacityColor(WHITE, opacity));
            canvas.drawText(title, x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }
        private void drawDpadDirections(Canvas canvas, float x, float y, float radius, float angle, int color) {
            float dx=(float)Math.sin(Math.toRadians(angle)),dy=(float)Math.cos(Math.toRadians(angle));
            float diagonalX = radius*dx;
            float diagonalY = radius*dy;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(color);
            canvas.drawLine(x - radius, y, x + radius, y, paint);
            canvas.drawLine(x, y - radius, x, y + radius, paint);
            canvas.drawLine(x - diagonalX, y - diagonalY, x + diagonalX, y + diagonalY, paint);
            canvas.drawLine(x - diagonalX, y + diagonalY, x + diagonalX, y - diagonalY, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2)
                canvas.drawCircle(x + sx * diagonalX, y + sy * diagonalY, dp(3), paint);
        }
        private void drawCircle(Canvas canvas, float x, float y, String text, boolean waiting, boolean visible) {
            float radius = dp(markerSize(getContext()) / 2f);
            float opacity = waiting ? 1f : markerOpacity(getContext());
            paint.setStyle(Paint.Style.FILL); paint.setColor(opacityColor(0xC018181B, opacity)); canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
            paint.setColor(opacityColor(waiting ? 0xFFC3A7FF : visible ? WHITE : MUTED, opacity));
            if (waiting) paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
            canvas.drawCircle(x, y, radius, paint); paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(14));
            while (paint.measureText(text) > radius * 1.75f && paint.getTextSize() > dp(8)) paint.setTextSize(paint.getTextSize() - 1);
            paint.setColor(opacityColor(WHITE, opacity));
            canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint);
            if (waiting) { paint.setTextSize(dp(10)); canvas.drawText("PRESS KEY", x, y + radius + dp(15), paint); }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0); downX = event.getX(); downY = event.getY();
                moved = false; consumedGesture = false; dragKey = 0;
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) && mode == Mode.CAPTURE) {
                    int key = mouseCode(event.getButtonState());
                    acceptKey(key == 0 ? GameProfileStore.MOUSE_LEFT : key);
                    consumedGesture = true;
                } else if (mode == Mode.BROWSE) {
                    dragKey = hit(downX, downY); GameProfileStore.Binding b = find(dragKey);
                    if (b != null) { offsetX = b.x * getWidth() - downX; offsetY = b.y * getHeight() - downY; }
                    else if (dragKey == DPAD_CONTROL) {
                        GameProfileStore.Profile p = profile(); if (p != null && p.dpad != null) {
                            offsetX = p.dpad.x * getWidth() - downX; offsetY = p.dpad.y * getHeight() - downY;
                        }
                    } else if (dragKey == DPAD_STICK_CONTROL) {
                        GameProfileStore.Profile p = profile(); if (p != null && p.dpadStick != null) {
                            offsetX = p.dpadStick.x * getWidth() - downX; offsetY = p.dpadStick.y * getHeight() - downY;
                        }
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
                consumedGesture = true; pointer = -1; dragKey = 0; moved = false; invalidate(); return true;
            }
            int index = event.findPointerIndex(pointer);
            if (index < 0 || consumedGesture) return true;
            float x = event.getX(index), y = event.getY(index);
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(x - downX, y - downY) > slop) moved = true;
                if (moved && dragKey != 0) {
                    float radius = dp(markerSize(getContext()) / 2f);
                    dragX = Math.max(radius, Math.min(getWidth() - radius, x + offsetX));
                    dragY = Math.max(radius, Math.min(getHeight() - radius, y + offsetY)); invalidate();
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (moved && dragKey != 0) {
                    if (dragKey == DPAD_CONTROL) {
                        GameProfileStore.Profile p = profile();
                        if (p != null && p.dpad != null) { GameProfileStore.saveDpad(getContext(), packageName, p.dpad.at(dragX / getWidth(), dragY / getHeight())); changed(); }
                    } else if (dragKey == DPAD_STICK_CONTROL) {
                        GameProfileStore.Profile p = profile();
                        if (p != null && p.dpadStick != null) { GameProfileStore.saveDpadStick(getContext(), packageName, p.dpadStick.at(dragX / getWidth(), dragY / getHeight())); changed(); }
                    } else {
                        GameProfileStore.Binding b = find(dragKey);
                        if (b != null) { GameProfileStore.saveBinding(getContext(), packageName, b.keyCode, b.at(dragX / getWidth(), dragY / getHeight())); changed(); }
                    }
                } else if (!moved) tapped(x, y);
                pointer = -1; dragKey = 0; moved = false; invalidate();
            }
            return true;
        }
        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return super.onGenericMotionEvent(event);
            if (mode == Mode.CAPTURE && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scroll != 0) acceptKey(scroll > 0 ? GameProfileStore.MOUSE_WHEEL_UP : GameProfileStore.MOUSE_WHEEL_DOWN);
            } else if (mode == Mode.CAPTURE && event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                    && event.getActionButton() != MotionEvent.BUTTON_PRIMARY) {
                consumedGesture = true; acceptKey(mouseCode(event.getActionButton()));
            }
            // Primary mouse clicks already arrive as ACTION_DOWN/UP; never dispatch them twice.
            return true;
        }
        private void tapped(float x, float y) {
            if (mode == Mode.CAPTURE) return; // Keep the waiting point fixed until input or Cancel.
            if (mode == Mode.DPAD) {
                candidateDpad = GameProfileStore.Dpad.defaults(x / getWidth(), y / getHeight());
                clearPending(); showDpadProperties(); refresh(); return;
            }
            if (mode == Mode.DPAD_STICK) {
                candidateDpadStick = GameProfileStore.DpadStick.defaults(x / getWidth(), y / getHeight());
                clearPending(); showDpadStickProperties(); refresh(); return;
            }
            if (mode == Mode.AIM) {
                GameProfileStore.Binding previous = find(GameProfileStore.MOUSE_AIM);
                GameProfileStore.Binding b = previous == null ? new GameProfileStore.Binding(GameProfileStore.MOUSE_AIM, x / getWidth(), y / getHeight()) : previous.at(x / getWidth(), y / getHeight());
                GameProfileStore.saveBinding(getContext(), packageName, previous == null ? 0 : previous.keyCode, b);
                clearPending(); changed(); edit(b); return;
            }
            if (mode == Mode.MOVE) {
                if (movingKey == DPAD_CONTROL) {
                    GameProfileStore.Profile p = profile();
                    if (p != null && p.dpad != null) GameProfileStore.saveDpad(getContext(), packageName,
                            p.dpad.at(x / getWidth(), y / getHeight()));
                    clearPending(); changed(); refresh(); return;
                }
                if (movingKey == DPAD_STICK_CONTROL) {
                    GameProfileStore.Profile p = profile();
                    if (p != null && p.dpadStick != null) GameProfileStore.saveDpadStick(getContext(), packageName,
                            p.dpadStick.at(x / getWidth(), y / getHeight()));
                    clearPending(); changed(); refresh(); return;
                }
                GameProfileStore.Binding b = find(movingKey);
                if (b != null) { GameProfileStore.saveBinding(getContext(), packageName, b.keyCode, b.at(x / getWidth(), y / getHeight())); changed(); }
                clearPending(); refresh(); return;
            }
            int selected = hit(x, y);
            if (selected == DPAD_CONTROL) { GameProfileStore.Profile p = profile(); if (p != null && p.dpad != null) editDpad(p.dpad); return; }
            if (selected == DPAD_STICK_CONTROL) { GameProfileStore.Profile p = profile(); if (p != null && p.dpadStick != null) editDpadStick(p.dpadStick); return; }
            GameProfileStore.Binding b = find(selected);
            if (b != null) edit(b);
            else startCapture(x / getWidth(), y / getHeight(), false);
        }
    }
}
