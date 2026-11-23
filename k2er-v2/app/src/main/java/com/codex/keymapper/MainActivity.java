package com.codex.k2er.v2;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 80;
    private static final int BG = 0xFF101014;
    private static final int SURFACE = 0xFF19191F;
    private static final int SURFACE_ALT = 0xFF222229;
    private static final int TEXT = 0xFFF5F3FA;
    private static final int MUTED = 0xFFAAA6B4;
    private static final int PURPLE = 0xFF9C5CFF;
    private static final int PURPLE_DARK = 0xFF6C35D8;
    private static final int BORDER = 0xFF302F39;
    private static final int GREEN = 0xFF70D49A;
    private String displayedProfilePackage;
    private boolean pendingWirelessConsent;
    private InputManager inputManager;
    private final Handler setupHandler = new Handler(Looper.getMainLooper());
    private String lastSetupState = "";
    private final Runnable setupPoller = new Runnable() {
        @Override
        public void run() {
            String current = setupState();
            if (!current.equals(lastSetupState)) {
                lastSetupState = current;
                refreshVisibleScreen();
            }
            setupHandler.postDelayed(this, 1000);
        }
    };
    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            runOnUiThread(MainActivity.this::refreshVisibleScreen);
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            runOnUiThread(MainActivity.this::refreshVisibleScreen);
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            runOnUiThread(MainActivity.this::refreshVisibleScreen);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(inputDeviceListener, new Handler(Looper.getMainLooper()));
        }
        showHome();
    }

    @Override
    protected void onDestroy() {
        setupHandler.removeCallbacks(setupPoller);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(inputDeviceListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (displayedProfilePackage == null) showHome();
        else {
            GameProfileStore.Profile profile = GameProfileStore.find(this, displayedProfilePackage);
            if (profile != null) showProfile(profile);
            else showHome();
        }
        lastSetupState = setupState();
        setupHandler.removeCallbacks(setupPoller);
        setupHandler.postDelayed(setupPoller, 1000);
    }

    @Override
    protected void onPause() {
        setupHandler.removeCallbacks(setupPoller);
        super.onPause();
    }

    private String setupState() {
        return WirelessActivation.isPaired(this) + "|" + DaemonFiles.isDaemonAlive(this)
                + "|" + isOverlayPermissionGranted() + "|" + isBatteryUnrestricted()
                + "|" + WirelessActivation.savedStatus(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && pendingWirelessConsent) {
            pendingWirelessConsent = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWirelessPairing();
            } else {
                Toast.makeText(this, "Notifications are needed to enter the Wireless Debugging pairing code.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showHome() {
        displayedProfilePackage = null;
        ScrollView scroll = scroll();
        LinearLayout root = page();
        scroll.addView(root);
        root.addView(brandHeader(), fullWidth(48));

        TextView eyebrow = label("GAMING CONTROLS");
        LinearLayout.LayoutParams eyebrowParams = fullWidth(0);
        eyebrowParams.topMargin = dp(28);
        root.addView(eyebrow, eyebrowParams);

        TextView title = text("Your games.\nYour controls.", 31, TEXT, true);
        title.setLineSpacing(0, 1.02f);
        LinearLayout.LayoutParams titleParams = fullWidth(0);
        titleParams.topMargin = dp(8);
        root.addView(title, titleParams);

        TextView subtitle = text("Map keyboard, mouse, and controller inputs to your game’s touch controls.", 15, MUTED, false);
        subtitle.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subtitleParams = fullWidth(0);
        subtitleParams.topMargin = dp(9);
        subtitleParams.bottomMargin = dp(24);
        root.addView(subtitle, subtitleParams);

        LinearLayout setup = panel();
        LinearLayout setupContent = column();
        setupContent.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout setupHeader = row();
        TextView setupTitle = text("Device setup", 16, TEXT, true);
        setupHeader.addView(setupTitle, weighted(1));
        int readyCount = (DaemonFiles.isDaemonAlive(this) ? 1 : 0)
                + (isOverlayPermissionGranted() ? 1 : 0)
                + (isBatteryUnrestricted() ? 1 : 0);
        TextView setupProgress = text(readyCount + " / 3", 11, MUTED, true);
        setupHeader.addView(setupProgress, wrap());
        setupContent.addView(setupHeader, fullWidth(0));

        TextView devicesTitle = text("Connected devices", 12, TEXT, true);
        LinearLayout.LayoutParams devicesTitleParams = fullWidth(0);
        devicesTitleParams.topMargin = dp(10);
        setupContent.addView(devicesTitle, devicesTitleParams);
        TextView devicesSummary = text(connectedInputDeviceSummary(), 11, MUTED, false);
        LinearLayout.LayoutParams devicesSummaryParams = fullWidth(0);
        devicesSummaryParams.topMargin = dp(3);
        devicesSummaryParams.bottomMargin = dp(2);
        setupContent.addView(devicesSummary, devicesSummaryParams);

        addSetupRow(setupContent, "ADB input daemon",
                "Read keyboard and mouse events; simulate taps in your game",
                DaemonFiles.isDaemonAlive(this) ? "RUNNING" : "START",
                DaemonFiles.isDaemonAlive(this), this::beginWirelessActivation);
        addSetupRow(setupContent, "Display over other apps",
                "Show the key editor above your game",
                isOverlayPermissionGranted() ? "ALLOWED" : "ALLOW",
                isOverlayPermissionGranted(), this::requestOverlayPermission);
        addSetupRow(setupContent, "Battery usage",
                "Keep Android from stopping K2er",
                isBatteryUnrestricted() ? "UNRESTRICTED" : "NO RESTRICTIONS",
                isBatteryUnrestricted(), this::requestBatteryExemption);

        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("poco") || manufacturer.contains("blackshark")) {
            TextView touchInjectionNote = text(
                    "For simulated game taps, enable Developer options → USB debugging (Security settings).",
                    11, MUTED, false);
            touchInjectionNote.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams noteParams = fullWidth(0);
            noteParams.topMargin = dp(8);
            setupContent.addView(touchInjectionNote, noteParams);
        }

        boolean paired = WirelessActivation.isPaired(this);
        String savedWirelessStatus = WirelessActivation.savedStatus(this);
        boolean daemonActive = DaemonFiles.isDaemonAlive(this);
        boolean wirelessActive = paired && daemonActive;
        String wirelessDetail = paired
                ? (wirelessActive ? "Connected over Wi-Fi · input daemon active"
                    : "Paired · reconnect to start the current K2er input service")
                : "Activate with Android Wireless Debugging";
        boolean wirelessWorking = savedWirelessStatus.startsWith("Searching")
                || savedWirelessStatus.startsWith("Pairing")
                || savedWirelessStatus.startsWith("Scanning")
                || savedWirelessStatus.startsWith("Connect port")
                || savedWirelessStatus.startsWith("Paired. Scanning")
                || savedWirelessStatus.startsWith("Starting the K2er")
                || savedWirelessStatus.startsWith("Wireless activation is already running");
        boolean wirelessFailed = savedWirelessStatus.toLowerCase().contains("failed")
                || savedWirelessStatus.toLowerCase().contains("timeout")
                || savedWirelessStatus.toLowerCase().contains("timed out")
                || savedWirelessStatus.startsWith("Wireless Debugging port scan timed out");
        if (wirelessWorking || wirelessFailed) {
            wirelessDetail = savedWirelessStatus;
        }
        addSetupRow(setupContent, "Wireless debugging",
                wirelessDetail,
                wirelessWorking ? "SCANNING" : wirelessActive ? "CONNECTED" : paired ? "CONNECT" : "ACTIVATE",
                paired || wirelessWorking, this::beginWirelessActivation);
        if (paired) {
            TextView forget = text("Forget wireless pairing", 12, MUTED, true);
            forget.setGravity(Gravity.CENTER);
            forget.setPadding(0, dp(9), 0, dp(2));
            forget.setOnClickListener(v -> confirmForgetWirelessPairing());
            setupContent.addView(forget, fullWidth(0));
        }
        if (daemonActive) {
            TextView stopDaemon = text("Stop K2er input daemon", 12, MUTED, true);
            stopDaemon.setGravity(Gravity.CENTER);
            stopDaemon.setPadding(0, dp(9), 0, dp(2));
            stopDaemon.setOnClickListener(v -> confirmStopDaemon());
            setupContent.addView(stopDaemon, fullWidth(0));
        }
        setup.addView(setupContent);
        LinearLayout.LayoutParams setupParams = fullWidth(0);
        setupParams.bottomMargin = dp(25);
        root.addView(setup, setupParams);

        LinearLayout headingRow = row();
        TextView gamesHeading = text("Game profiles", 21, TEXT, true);
        headingRow.addView(gamesHeading, weighted(1));
        TextView count = text(String.valueOf(GameProfileStore.read(this).size()), 13, MUTED, true);
        headingRow.addView(count, wrap());
        root.addView(headingRow, fullWidth(0));
        TextView gameHint = text("Choose a game to build its layout.", 13, MUTED, false);
        gameHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(gameHint, fullWidth(0));

        List<GameProfileStore.Profile> profiles = GameProfileStore.read(this);
        if (profiles.isEmpty()) {
            LinearLayout empty = panel();
            LinearLayout emptyContent = column();
            emptyContent.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyContent.setPadding(dp(18), dp(25), dp(18), dp(22));
            TextView monogram = text("＋", 26, TEXT, false);
            monogram.setGravity(Gravity.CENTER);
            monogram.setBackground(round(PURPLE_DARK, PURPLE_DARK, 16));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(54), dp(54));
            emptyContent.addView(monogram, badgeParams);
            TextView emptyTitle = text("No games added yet", 17, TEXT, true);
            emptyTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emptyTitleParams = fullWidth(0);
            emptyTitleParams.topMargin = dp(13);
            emptyContent.addView(emptyTitle, emptyTitleParams);
            TextView emptyCopy = text("Add a game to place keyboard keys over its on-screen controls.", 13, MUTED, false);
            emptyCopy.setGravity(Gravity.CENTER);
            emptyCopy.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams copyParams = fullWidth(0);
            copyParams.topMargin = dp(6);
            copyParams.bottomMargin = dp(17);
            emptyContent.addView(emptyCopy, copyParams);
            TextView addFirst = button("＋   Add a game", PURPLE_DARK, TEXT);
            addFirst.setOnClickListener(v -> openGamePicker());
            emptyContent.addView(addFirst, fullWidth(46));
            empty.addView(emptyContent);
            root.addView(empty, fullWidth(0));
        } else {
            for (GameProfileStore.Profile profile : profiles) {
                root.addView(profileRow(profile), fullWidth(0));
            }
            TextView addGame = button("＋   Add another game", SURFACE_ALT, TEXT);
            addGame.setOnClickListener(v -> openGamePicker());
            LinearLayout.LayoutParams addParams = fullWidth(46);
            addParams.topMargin = dp(10);
            root.addView(addGame, addParams);
        }

        TextView footer = text("KEYBOARD  ·  GAMEPAD  ·  TOUCH", 10, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams footerParams = fullWidth(0);
        footerParams.topMargin = dp(24);
        footerParams.bottomMargin = dp(14);
        root.addView(footer, footerParams);
        setContentView(scroll);
    }

    private void addSetupRow(LinearLayout parent, String title, String detail,
                             String actionLabel, boolean ready, Runnable action) {
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = fullWidth(1);
        dividerParams.topMargin = dp(9);
        dividerParams.bottomMargin = dp(9);
        parent.addView(divider, dividerParams);

        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout descriptions = column();
        TextView name = text(title, 13, TEXT, true);
        TextView explanation = text(detail, 11, MUTED, false);
        explanation.setMaxLines(2);
        explanation.setLineSpacing(dp(2), 1f);
        descriptions.addView(name, fullWidth(0));
        LinearLayout.LayoutParams explanationParams = fullWidth(0);
        explanationParams.topMargin = dp(3);
        descriptions.addView(explanation, explanationParams);
        LinearLayout.LayoutParams descriptionParams = weighted(1);
        descriptionParams.rightMargin = dp(8);
        item.addView(descriptions, descriptionParams);

        TextView control = button(actionLabel, ready ? SURFACE_ALT : PURPLE_DARK, TEXT);
        control.setTextSize(10);
        control.setMinWidth(dp(86));
        control.setPadding(dp(7), 0, dp(7), 0);
        item.addView(control, new LinearLayout.LayoutParams(dp(94), dp(36)));
        item.setOnClickListener(v -> action.run());
        control.setOnClickListener(v -> action.run());
        parent.addView(item, fullWidth(0));
    }

    private boolean isOverlayPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private String connectedInputDeviceSummary() {
        int keyboards = 0;
        int mice = 0;
        int gamepads = 0;
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) keyboards++;
            if ((sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) mice++;
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) gamepads++;
        }
        return "Keyboards " + keyboards + "  ·  Mice " + mice + "  ·  Gamepads " + gamepads;
    }

    private boolean isBatteryUnrestricted() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestOverlayPermission() {
        if (isOverlayPermissionGranted()) {
            Toast.makeText(this, "Overlay access is already allowed.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(this, "Open K2er in Android’s app permissions to allow overlays.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryExemption() {
        if (isBatteryUnrestricted()) {
            Toast.makeText(this, "K2er already has no battery restrictions.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void beginWirelessActivation() {
        boolean paired = WirelessActivation.isPaired(this);
        String message = paired
                ? "K2er will reconnect through Wireless Debugging and start its input daemon with Android's shell user. It reads connected keyboard, mouse, and controller input and simulates taps at your saved game controls."
                : "Turn on Wireless Debugging and choose “Pair device with pairing code.” K2er will find the pairing port; enter the 6-digit code in the K2er notification. It then starts an input daemon through ADB shell. You can revoke the pairing in Wireless Debugging settings.";
        new AlertDialog.Builder(this)
                .setTitle(paired ? "Reconnect wirelessly?" : "Activate with Wireless Debugging")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(paired ? "Connect" : "Continue", (dialog, which) -> requestNotificationPermission())
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingWirelessConsent = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        startWirelessPairing();
    }

    private void startWirelessPairing() {
        boolean alreadyPaired = WirelessActivation.isPaired(this);
        KeyMappingService.start(this);
        WirelessActivation.activate(this, new WirelessActivation.Listener() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> refreshVisibleScreen());
            }

            @Override
            public void onFinished(String message) {
                runOnUiThread(() -> {
                    refreshVisibleScreen();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    refreshVisibleScreen();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
        if (alreadyPaired) return;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void confirmForgetWirelessPairing() {
        new AlertDialog.Builder(this)
                .setTitle("Forget Wireless Debugging pairing?")
                .setMessage("This removes K2er’s saved ADB pairing key. You can pair again any time.")
                .setNegativeButton("Keep pairing", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    WirelessActivation.forgetPairing(this);
                    showHome();
                })
                .show();
    }

    private void confirmStopDaemon() {
        new AlertDialog.Builder(this)
                .setTitle("Stop K2er input daemon?")
                .setMessage("This stops keyboard, mouse, and controller mapping until you connect again through Wireless Debugging.")
                .setNegativeButton("Keep running", null)
                .setPositiveButton("Stop", (dialog, which) -> WirelessActivation.deactivate(this,
                        new WirelessActivation.Listener() {
                            @Override public void onStatus(String message) { }

                            @Override
                            public void onFinished(String message) {
                                runOnUiThread(() -> {
                                    KeyMappingService.stop(MainActivity.this);
                                    showHome();
                                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                                });
                            }

                            @Override
                            public void onError(String message) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
                            }
                        }))
                .show();
    }

    private void refreshVisibleScreen() {
        android.view.ViewGroup container = findViewById(android.R.id.content);
        View previous = container.getChildAt(0);
        int scrollY = previous == null ? 0 : previous.getScrollY();
        if (displayedProfilePackage == null) showHome();
        else {
            GameProfileStore.Profile profile = GameProfileStore.find(this, displayedProfilePackage);
            if (profile != null) showProfile(profile);
            else showHome();
        }
        View refreshed = container.getChildAt(0);
        if (refreshed instanceof ScrollView) refreshed.post(() -> refreshed.scrollTo(0, scrollY));
    }

    private View brandHeader() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("K", 20, TEXT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(PURPLE_DARK, PURPLE_DARK, 12));
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView wordmark = text("K2er", 13, TEXT, true);
        wordmark.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams wordParams = wrap();
        wordParams.leftMargin = dp(11);
        header.addView(wordmark, wordParams);
        return header;
    }

    private View profileRow(GameProfileStore.Profile profile) {
        LinearLayout card = panel();
        LinearLayout content = row();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(13), dp(12), dp(13), dp(12));
        TextView icon = text(profile.appName.isEmpty() ? "G" : profile.appName.substring(0, 1).toUpperCase(), 17, TEXT, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(0xFF39215C, 0xFF5B348A, 13));
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = column();
        TextView name = text(profile.appName, 16, TEXT, true);
        TextView details = text(profile.bindings.size() + " keys mapped", 12, MUTED, false);
        texts.addView(name, fullWidth(0));
        LinearLayout.LayoutParams detailParams = fullWidth(0);
        detailParams.topMargin = dp(3);
        texts.addView(details, detailParams);
        LinearLayout.LayoutParams textsParams = weighted(1);
        textsParams.leftMargin = dp(12);
        content.addView(texts, textsParams);
        TextView arrow = text("›", 25, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        content.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(40)));
        content.setOnClickListener(v -> showProfile(profile));
        content.setOnLongClickListener(v -> { confirmRemoveProfile(profile); return true; });
        card.addView(content);
        LinearLayout.LayoutParams params = fullWidth(0);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private void showProfile(GameProfileStore.Profile profile) {
        displayedProfilePackage = profile.packageName;
        ScrollView scroll = scroll();
        LinearLayout root = page();
        scroll.addView(root);

        LinearLayout top = row();
        TextView back = text("‹", 29, TEXT, false);
        back.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(44)));
        back.setOnClickListener(v -> showHome());
        TextView screenLabel = text("GAME PROFILE", 11, MUTED, true);
        screenLabel.setLetterSpacing(0.12f);
        screenLabel.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(screenLabel, weighted(1));
        TextView removeProfile = text("Remove profile", 12, 0xFFFF9C9C, true);
        removeProfile.setGravity(Gravity.CENTER);
        removeProfile.setPadding(dp(10), dp(10), dp(10), dp(10));
        top.addView(removeProfile, wrap());
        removeProfile.setOnClickListener(v -> confirmRemoveProfile(profile));
        root.addView(top, fullWidth(44));

        TextView title = text(profile.appName, 28, TEXT, true);
        LinearLayout.LayoutParams titleParams = fullWidth(0);
        titleParams.topMargin = dp(17);
        root.addView(title, titleParams);
        TextView subtitle = text("Keyboard and mouse touch layout", 14, MUTED, false);
        LinearLayout.LayoutParams subParams = fullWidth(0);
        subParams.topMargin = dp(4);
        subParams.bottomMargin = dp(20);
        root.addView(subtitle, subParams);

        LinearLayout ready = panel();
        LinearLayout readyContent = row();
        readyContent.setGravity(Gravity.CENTER_VERTICAL);
        readyContent.setPadding(dp(14), dp(13), dp(14), dp(13));
        View dot = new View(this);
        boolean mapperReady = DaemonFiles.isDaemonAlive(this) && isOverlayPermissionGranted();
        dot.setBackground(round(mapperReady ? GREEN : PURPLE, mapperReady ? GREEN : PURPLE, 10));
        readyContent.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        LinearLayout statusTexts = column();
        TextView statusTitle = text(mapperReady ? "K2er input is ready" : "Finish device setup", 14, TEXT, true);
        TextView statusCopy = text(mapperReady ? "The floating K2er button appears when this game is open." : "Allow overlays and connect through Wireless Debugging.", 12, MUTED, false);
        statusTexts.addView(statusTitle, fullWidth(0));
        LinearLayout.LayoutParams statusCopyParams = fullWidth(0);
        statusCopyParams.topMargin = dp(3);
        statusTexts.addView(statusCopy, statusCopyParams);
        LinearLayout.LayoutParams statusTextsParams = weighted(1);
        statusTextsParams.leftMargin = dp(11);
        readyContent.addView(statusTexts, statusTextsParams);
        ready.addView(readyContent);
        root.addView(ready, fullWidth(0));

        TextView edit = button("Open game", PURPLE_DARK, TEXT);
        LinearLayout.LayoutParams editParams = fullWidth(49);
        editParams.topMargin = dp(13);
        root.addView(edit, editParams);
        edit.setOnClickListener(v -> startEditing(profile));

        TextView instructions = text("Open the game and tap the floating K2er logo. Tap a game control to place a waiting circle, then press a key or mouse button. Tap a saved circle to change or delete it; drag it to move. Done returns to the game.", 13, MUTED, false);
        instructions.setLineSpacing(dp(3), 1f);
        instructions.setPadding(dp(1), dp(14), dp(1), dp(20));
        root.addView(instructions, fullWidth(0));

        LinearLayout mappingHeader = row();
        TextView mappingTitle = text("Mapped keys", 20, TEXT, true);
        mappingHeader.addView(mappingTitle, weighted(1));
        TextView mappingCount = text(String.valueOf(profile.bindings.size()), 12, MUTED, true);
        mappingHeader.addView(mappingCount, wrap());
        root.addView(mappingHeader, fullWidth(0));
        TextView mappingSubtitle = text("Each key triggers a touch at its saved point.", 12, MUTED, false);
        mappingSubtitle.setPadding(0, dp(4), 0, dp(11));
        root.addView(mappingSubtitle, fullWidth(0));

        if (profile.bindings.isEmpty()) {
            LinearLayout empty = panel();
            TextView copy = text("No controls mapped yet. Open the game, tap the floating K2er + button, and place your first key.", 13, MUTED, false);
            copy.setLineSpacing(dp(3), 1f);
            copy.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.addView(copy);
            root.addView(empty, fullWidth(0));
        } else {
            for (GameProfileStore.Binding binding : profile.bindings) {
                LinearLayout item = panel();
                LinearLayout itemRow = row();
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(13), dp(9), dp(9), dp(9));
                TextView key = text(binding.label(), 13, TEXT, true);
                key.setGravity(Gravity.CENTER);
                key.setBackground(round(0xFF2B2437, 0xFF5B348A, 8));
                itemRow.addView(key, new LinearLayout.LayoutParams(dp(62), dp(36)));
                TextView target = text("Touch point  ·  " + Math.round(binding.x * 100) + "% / " + Math.round(binding.y * 100) + "%", 12, MUTED, false);
                LinearLayout.LayoutParams targetParams = weighted(1);
                targetParams.leftMargin = dp(10);
                itemRow.addView(target, targetParams);
                TextView remove = text("×", 20, MUTED, false);
                remove.setGravity(Gravity.CENTER);
                itemRow.addView(remove, new LinearLayout.LayoutParams(dp(38), dp(38)));
                remove.setOnClickListener(v -> {
                    GameProfileStore.removeBinding(this, profile.packageName, binding.keyCode);
                    DaemonFiles.syncProfiles(this, getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    showProfile(GameProfileStore.find(this, profile.packageName));
                });
                item.addView(itemRow);
                LinearLayout.LayoutParams itemParams = fullWidth(0);
                itemParams.bottomMargin = dp(7);
                root.addView(item, itemParams);
            }
        }
        setContentView(scroll);
    }

    private void confirmRemoveProfile(GameProfileStore.Profile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + profile.appName + " profile?")
                .setMessage("This removes its saved mappings from K2er. The app stays installed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    GameProfileStore.removeProfile(this, profile.packageName);
                    DaemonFiles.syncProfiles(this, getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    showHome();
                })
                .show();
    }

    private void startEditing(GameProfileStore.Profile profile) {
        if (!isOverlayPermissionGranted()) {
            Toast.makeText(this, "Allow K2er to display over other apps first.", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (!DaemonFiles.isDaemonAlive(this)) {
            Toast.makeText(this, "Start the K2er input daemon from Device setup first.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(profile.packageName);
        if (launch == null) {
            Toast.makeText(this, "This game can’t be launched from its profile.", Toast.LENGTH_SHORT).show();
            return;
        }
        GameProfileStore.setEditing(this, "", false);
        KeyMappingService.start(this);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        Toast.makeText(this, "Tap the floating K2er + button in the game to open the editor.", Toast.LENGTH_LONG).show();
    }

    private void openGamePicker() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager packageManager = getPackageManager();
        List<ResolveInfo> apps;
        if (Build.VERSION.SDK_INT >= 33) {
            apps = packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0));
        } else {
            apps = packageManager.queryIntentActivities(launcher, 0);
        }
        apps.removeIf(info -> getPackageName().equals(info.activityInfo.packageName));
        Collections.sort(apps, Comparator.comparing(info -> String.valueOf(info.loadLabel(packageManager)).toLowerCase()));
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps were found.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(packageManager);
        new AlertDialog.Builder(this)
                .setTitle("Choose a game")
                .setItems(labels, (dialog, which) -> addProfile(apps.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addProfile(ResolveInfo info) {
        String packageName = info.activityInfo.packageName;
        if (GameProfileStore.find(this, packageName) != null) {
            Toast.makeText(this, "That game is already in your profiles.", Toast.LENGTH_SHORT).show();
            return;
        }
        String appName = String.valueOf(info.loadLabel(getPackageManager()));
        List<GameProfileStore.Profile> profiles = GameProfileStore.read(this);
        profiles.add(new GameProfileStore.Profile(packageName, appName, new ArrayList<>()));
        GameProfileStore.write(this, profiles);
        DaemonFiles.syncProfiles(this, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        showProfile(GameProfileStore.find(this, packageName));
    }

    private ScrollView scroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        return scroll;
    }

    private LinearLayout page() {
        LinearLayout root = column();
        root.setPadding(dp(20), dp(17), dp(20), dp(25));
        return root;
    }

    private LinearLayout panel() {
        LinearLayout box = column();
        box.setBackground(round(SURFACE, BORDER, 14));
        return box;
    }

    private TextView label(String value) {
        TextView view = text(value, 11, PURPLE, true);
        view.setLetterSpacing(0.16f);
        return view;
    }

    private TextView button(String value, int fill, int color) {
        TextView view = text(value, 13, color, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(fill, fill, 11));
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth(int heightDp) {
        return new LinearLayout.LayoutParams(-1, heightDp == 0 ? -2 : dp(heightDp));
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private LinearLayout.LayoutParams weighted(float value) {
        return new LinearLayout.LayoutParams(0, -2, value);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
