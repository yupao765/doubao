package com.codex.doubaomictracker;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GestureProbeActivity extends Activity {
    public static final CopyOnWriteArrayList<Integer> actions = new CopyOnWriteArrayList<>();
    public static final CopyOnWriteArrayList<Long> times = new CopyOnWriteArrayList<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                actions.add(event.getActionMasked());
                times.add(event.getEventTime());
                return true;
            }
        });
    }
}
