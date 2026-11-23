package com.codex.k2er.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** Native controls own toolbar/dialog input; only the map surface handles placement. */
final class MappingEditorView extends FrameLayout {
    interface Host { void onChanged(); void onDone(); }
    enum Mode { BROWSE, CAPTURE, AIM, MOVE }
    static final int PURPLE = 0xFF693CE5, WHITE = 0xFFF7F6FA, MUTED = 0xFFBDB9C5;
    final String packageName;
    private final Host host;
    private final MapSurface surface;
    private final LinearLayout toolbar;
    private final TextView hint, connection;
    private final Button cancelInput;
    private FrameLayout dialog;
    private Mode mode = Mode.BROWSE;
    private float pointX, pointY;
    private boolean captureForProperties;
    private GameProfileStore.Binding original, candidate;
    private int movingKey;
    private String notice = "";
    private boolean online = true;

    MappingEditorView(Context context, String name, Host host) {
        super(context);
        packageName = name; this.host = host;
        setFocusable(true); setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setContentDescription("K2er mapping editor");
        surface = new MapSurface(context);
        addView(surface, new LayoutParams(-1, -1));
        toolbar = column();
        toolbar.setBackground(background(0xFA202024, 10));
        toolbar.setElevation(dp(8));
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(8));
        LinearLayout actions = row();
        TextView brand = label("K2er", 19, WHITE, true);
        brand.setGravity(Gravity.CENTER); brand.setContentDescription("Drag editor toolbar");
        actions.addView(brand, new LinearLayout.LayoutParams(dp(62), dp(48)));
        installToolbarDrag(brand);
        actions.addView(icon(android.R.drawable.ic_menu_agenda, "Mapped keys", this::showKeyList), size(48, 48));
        actions.addView(button("+ Add", false, this::showAdd), weighted());
        actions.addView(button("Aim", false, () -> { dismissDialog(); clearPending(); mode = Mode.AIM; refresh(); }), weighted());
        actions.addView(icon(android.R.drawable.ic_menu_manage, "Editor settings", this::showSettings), size(48, 48));
        actions.addView(button("Done", true, host::onDone), size(68, 44));
        toolbar.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        hint = label("", 11, WHITE, false); hint.setPadding(dp(6), dp(5), dp(6), dp(3));
        toolbar.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        TextView help = label("Keyboard, gamepad, and mouse inputs map to game buttons. Aim uses mouse movement for camera control.", 10, MUTED, false);
        help.setPadding(dp(6), 0, dp(6), dp(3));
        toolbar.addView(help, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout status = row();
        connection = label("", 10, MUTED, false); connection.setPadding(dp(6), 0, 0, 0);
        status.addView(connection, new LinearLayout.LayoutParams(0, -2, 1));
        cancelInput = button("Cancel input", false, this::cancelPending);
        status.addView(cancelInput, size(105, 34));
        toolbar.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LayoutParams position = new LayoutParams(dp(520), -2, Gravity.TOP | Gravity.LEFT);
        position.leftMargin = dp(12); position.topMargin = dp(18);
        addView(toolbar, position);
        refresh();
    }

    private SharedPreferences options() { return getContext().getSharedPreferences("editor_options", 0); }
    static boolean labelsVisible(Context context) { return context.getSharedPreferences("editor_options", 0).getBoolean("labels", true); }
    static int markerSize(Context context) { return context.getSharedPreferences("editor_options", 0).getInt("size", 44); }
    static float markerOpacity(Context context) { return context.getSharedPreferences("editor_options", 0).getInt("opacity", 65) / 100f; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams size(int w, int h) { return new LinearLayout.LayoutParams(dp(w), dp(h)); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(44), 1); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String title, boolean primary, Runnable action) {
        Button v = new Button(getContext()); v.setText(title); v.setTextSize(12); v.setAllCaps(false);
        v.setTextColor(WHITE); v.setMinWidth(0); v.setMinimumWidth(0); v.setMinHeight(0); v.setMinimumHeight(0);
        v.setPadding(dp(6), dp(4), dp(6), dp(4));
        v.setBackground(background(primary ? PURPLE : 0xFF303035, 22));
        v.setOnClickListener(ignored -> action.run()); return v;
    }
    private ImageButton icon(int drawable, String description, Runnable action) {
        ImageButton v = new ImageButton(getContext()); v.setImageResource(drawable);
        v.setImageTintList(ColorStateList.valueOf(WHITE)); v.setBackgroundColor(Color.TRANSPARENT);
        v.setPadding(dp(12), dp(12), dp(12), dp(12)); v.setContentDescription(description);
        v.setOnClickListener(ignored -> action.run()); return v;
    }
    private void installToolbarDrag(View grip) {
        grip.setOnTouchListener(new OnTouchListener() {
            float startX, startY; int left, top;
            public boolean onTouch(View v, MotionEvent event) {
                LayoutParams p = (LayoutParams) toolbar.getLayoutParams();
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); left = p.leftMargin; top = p.topMargin;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    p.leftMargin = Math.max(0, Math.min(getWidth() - toolbar.getWidth(), left + Math.round(event.getRawX() - startX)));
                    p.topMargin = Math.max(0, Math.min(getHeight() - toolbar.getHeight(), top + Math.round(event.getRawY() - startY)));
                    toolbar.setLayoutParams(p);
                }
                return true;
            }
        });
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        LayoutParams p = (LayoutParams) toolbar.getLayoutParams();
        p.width = Math.min(dp(520), Math.max(dp(300), w - dp(24)));
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
                : mode == Mode.MOVE ? "Tap the new position for this control."
                : "Tap a spot to add a key. Tap a circle to edit; drag it to move.";
        hint.setText(notice.isEmpty() ? guide : notice);
        hint.setTextColor(notice.isEmpty() ? WHITE : 0xFFFFCF82);
        cancelInput.setVisibility(mode == Mode.BROWSE ? GONE : VISIBLE);
        connection.setText(online ? "Changes save automatically" : "Input disconnected · editor stays open; changes are saved");
        surface.invalidate();
    }
    private void clearPending() { mode = Mode.BROWSE; notice = ""; captureForProperties = false; movingKey = 0; }
    private void cancelPending() {
        boolean restore = captureForProperties;
        clearPending();
        if (restore && candidate != null) showPropertiesPanel();
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
        int oldKey = captureForProperties && original != null ? original.keyCode : 0;
        boolean aimShortcut = captureForProperties && candidate.keyCode == GameProfileStore.MOUSE_AIM;
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
        if (mode == Mode.CAPTURE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) acceptKey(code);
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
        body.addView(button("Tap · keyboard or mouse button", false, () -> { dismissDialog(); refresh(); }), new LinearLayout.LayoutParams(-1, dp(52)));
        TextView copy = label("Place a circle on a game button, then press its shortcut.", 13, MUTED, false);
        copy.setPadding(dp(8), dp(8), dp(8), dp(16)); body.addView(copy);
        body.addView(button("Aiming mode · mouse movement", false, () -> { dismissDialog(); mode = Mode.AIM; refresh(); }), new LinearLayout.LayoutParams(-1, dp(52)));
        TextView help = label("Place it in the camera area. Choose a keyboard or mouse shortcut, then toggle or hold to aim.", 13, MUTED, false);
        help.setPadding(dp(8), dp(8), dp(8), dp(16)); body.addView(help);
        showDialog("Add control", body, null); refresh();
    }
    private void showKeyList() {
        clearPending(); LinearLayout body = column();
        GameProfileStore.Profile p = profile();
        if (p != null) for (GameProfileStore.Binding b : p.bindings) {
            Button entry = button(b.label() + "  ·  " + Math.round(b.x * 100) + "% / " + Math.round(b.y * 100) + "%", false, () -> edit(b));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.bottomMargin = dp(6); body.addView(entry, lp);
        }
        if (p == null || p.bindings.isEmpty()) body.addView(label("No keys yet. Tap a game control to add your first mapping.", 14, MUTED, false));
        showDialog("Mapped keys", body, null); refresh();
    }
    private void edit(GameProfileStore.Binding binding) { clearPending(); original = binding; candidate = binding; showPropertiesPanel(); refresh(); }
    private void showPropertiesPanel() {
        boolean aiming = candidate.keyCode == GameProfileStore.MOUSE_AIM;
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
            body.addView(hold, new LinearLayout.LayoutParams(-1, dp(48)));
            body.addView(slider("Sensitivity X", candidate.sensitivityX, 0.2f, 8f, value ->
                    candidate = candidate.withSensitivity(value, candidate.sensitivityY)));
            body.addView(slider("Sensitivity Y", candidate.sensitivityY, 0.2f, 8f, value ->
                    candidate = candidate.withSensitivity(candidate.sensitivityX, value)));
        }
        CheckBox visible = new CheckBox(getContext()); visible.setText("Show this key during gameplay"); visible.setTextColor(WHITE);
        visible.setButtonTintList(ColorStateList.valueOf(0xFFA787FF)); visible.setChecked(candidate.visible);
        visible.setOnCheckedChangeListener((v, checked) -> candidate = candidate.withVisibility(checked));
        body.addView(visible, new LinearLayout.LayoutParams(-1, dp(48)));
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
            if (GameProfileStore.saveBinding(getContext(), packageName, original.keyCode, candidate)) {
                dismissDialog(); clearPending(); changed(); refresh();
            }
        }), weighted());
        showDialog(aiming ? "Aiming mode" : "Tap", body, footer);
    }
    interface FloatChange { void onValue(float value); }
    private LinearLayout slider(String title, float initial, float min, float max, FloatChange change) {
        LinearLayout box = column(); TextView text = label("", 13, WHITE, false);
        text.setText(String.format(Locale.US, "%s   %.2f", title, initial)); box.addView(text);
        SeekBar slider = new SeekBar(getContext()); slider.setMax(100); slider.setProgress(Math.round((initial - min) / (max - min) * 100));
        slider.setProgressTintList(ColorStateList.valueOf(0xFFA787FF)); slider.setThumbTintList(ColorStateList.valueOf(0xFFCCB8FF));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v, int value, boolean fromUser) {
                float result = min + (max - min) * value / 100f;
                text.setText(String.format(Locale.US, "%s   %.2f", title, result)); if (fromUser) change.onValue(result);
            }
            public void onStartTrackingTouch(SeekBar v) { }
            public void onStopTrackingTouch(SeekBar v) { }
        });
        slider.setContentDescription(title); box.addView(slider, new LinearLayout.LayoutParams(-1, dp(44))); return box;
    }
    private void showSettings() {
        clearPending(); LinearLayout body = column();
        CheckBox visible = new CheckBox(getContext()); visible.setText("Show mapped keys during gameplay"); visible.setTextColor(WHITE);
        visible.setChecked(labelsVisible(getContext()));
        visible.setButtonTintList(ColorStateList.valueOf(0xFFA787FF));
        visible.setOnCheckedChangeListener((v, checked) -> { options().edit().putBoolean("labels", checked).apply(); changed(); });
        body.addView(visible, new LinearLayout.LayoutParams(-1, dp(48)));
        body.addView(slider("Circle size", markerSize(getContext()), 32, 64, value -> {
            options().edit().putInt("size", Math.round(value)).apply(); changed();
        }));
        body.addView(slider("Gameplay opacity %", markerOpacity(getContext()) * 100, 20, 75, value -> {
            options().edit().putInt("opacity", Math.round(value)).apply(); changed();
        }));
        TextView help = label("Drag the K2er title to move the toolbar. Game touches pass through saved labels after Done.", 13, MUTED, false);
        help.setPadding(0, dp(12), 0, dp(12)); body.addView(help);
        showDialog("Editor settings", body, null); refresh();
    }
    private void showDialog(String title, LinearLayout content, LinearLayout footer) {
        dismissDialog();
        dialog = new FrameLayout(getContext()); dialog.setBackgroundColor(0x66000000); dialog.setClickable(true);
        LinearLayout card = column(); card.setBackground(background(0xFF232326, 12)); card.setPadding(dp(16), dp(10), dp(16), dp(12));
        card.setClickable(true);
        LinearLayout header = row();
        header.addView(label(title, 20, WHITE, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(icon(android.R.drawable.ic_menu_close_clear_cancel, "Close panel", () -> { dismissDialog(); clearPending(); refresh(); }), size(48, 48));
        card.addView(header);
        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(false); scroll.addView(content);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (footer != null) { footer.setPadding(0, dp(12), 0, 0); card.addView(footer); }
        else card.addView(button("Close", true, () -> { dismissDialog(); refresh(); }), new LinearLayout.LayoutParams(-1, dp(44)));
        LayoutParams cp = new LayoutParams(Math.min(dp(480), getWidth() - dp(32)), Math.min(dp(390), getHeight() - dp(48)), Gravity.CENTER);
        dialog.addView(card, cp); addView(dialog, new LayoutParams(-1, -1)); requestFocus();
    }
    private void resizeDialog() {
        View card = dialog.getChildAt(0); LayoutParams p = (LayoutParams) card.getLayoutParams();
        p.width = Math.min(dp(480), getWidth() - dp(32)); p.height = Math.min(dp(390), getHeight() - dp(48)); card.setLayoutParams(p);
    }
    private void dismissDialog() { if (dialog != null) removeView(dialog); dialog = null; }

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
        MapSurface(Context context) { super(context); setContentDescription("Mapping surface. Tap a control to add a key."); }
        private int hit(float x, float y) {
            GameProfileStore.Profile p = profile(); if (p == null) return 0;
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
                drawCircle(canvas, moved && dragKey == b.keyCode ? dragX : b.x * getWidth(),
                        moved && dragKey == b.keyCode ? dragY : b.y * getHeight(), b.label(), false, b.visible);
            }
            if (mode == Mode.CAPTURE) drawCircle(canvas, pointX * getWidth(), pointY * getHeight(), "?", true, true);
        }
        private void drawCircle(Canvas canvas, float x, float y, String text, boolean waiting, boolean visible) {
            float radius = dp(markerSize(getContext()) / 2f);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xC018181B); canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(waiting ? 0xFFC3A7FF : visible ? WHITE : MUTED);
            if (waiting) paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
            canvas.drawCircle(x, y, radius, paint); paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(14));
            while (paint.measureText(text) > radius * 1.75f && paint.getTextSize() > dp(8)) paint.setTextSize(paint.getTextSize() - 1);
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
                    GameProfileStore.Binding b = find(dragKey);
                    if (b != null) { GameProfileStore.saveBinding(getContext(), packageName, b.keyCode, b.at(dragX / getWidth(), dragY / getHeight())); changed(); }
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
            if (mode == Mode.AIM) {
                GameProfileStore.Binding previous = find(GameProfileStore.MOUSE_AIM);
                GameProfileStore.Binding b = previous == null ? new GameProfileStore.Binding(GameProfileStore.MOUSE_AIM, x / getWidth(), y / getHeight()) : previous.at(x / getWidth(), y / getHeight());
                GameProfileStore.saveBinding(getContext(), packageName, previous == null ? 0 : previous.keyCode, b);
                clearPending(); changed(); edit(b); return;
            }
            if (mode == Mode.MOVE) {
                GameProfileStore.Binding b = find(movingKey);
                if (b != null) { GameProfileStore.saveBinding(getContext(), packageName, b.keyCode, b.at(x / getWidth(), y / getHeight())); changed(); }
                clearPending(); refresh(); return;
            }
            GameProfileStore.Binding b = find(hit(x, y));
            if (b != null) edit(b); else startCapture(x / getWidth(), y / getHeight(), false);
        }
    }
}
