package com.codex.keymapper;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Optional local input diagnostics. Only mapped Dpad keys are recorded. */
public final class InputTrackerActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state, history;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,500);}};
    private File file(String name){return new File(DaemonFiles.directory(this),name);}
    private String read(File f){try{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}catch(Exception e){return "Waiting for input.";}}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    private void button(LinearLayout root,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setOnClickListener(v->action.run());root.addView(b);}
    @Override public void onCreate(Bundle b){super.onCreate(b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,24,24,24);root.setBackgroundColor(0xFF211F27);
        root.addView(text("WASD + joystick tracker",23));
        root.addView(text("Live held keys and Y2E injected joystick position. No keystroke history is recorded or saved.",14));
        Switch visible=new Switch(this);visible.setText("Show live keys and injected joystick in-game");visible.setTextColor(Color.WHITE);visible.setChecked(getSharedPreferences("editor_options",0).getBoolean("input_tracker_visible",false));
        visible.setOnCheckedChangeListener((v,on)->{
            getSharedPreferences("editor_options",0).edit().putBoolean("input_tracker_visible",on).apply();
            DaemonFiles.setLiveTrackerEnabled(this,on);
        });
        DaemonFiles.setLiveTrackerEnabled(this,visible.isChecked());root.addView(visible);
        root.addView(text("Live display adds optional file updates and overlay drawing. Turn it off for the lowest overhead.",12));
        state=text("",13);root.addView(state);
        button(root,"Back",this::finish);
        history=text("",11);history.setTypeface(Typeface.MONOSPACE);history.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(history);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void update(){state.setText("Live tracker | daemon "+(DaemonFiles.isDaemonAlive(this)?"running":"offline")+"\n"+read(file("y2e-dpad-live.txt")));}
    @Override public void onResume(){super.onResume();handler.post(refresh);}
    @Override public void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
