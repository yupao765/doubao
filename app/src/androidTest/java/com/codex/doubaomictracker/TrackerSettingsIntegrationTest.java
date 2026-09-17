package com.codex.doubaomictracker;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TrackerSettingsIntegrationTest {
    @Test public void upgradeDoesNotInheritUnsafeInterruptionSetting() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("tracker_settings", Context.MODE_PRIVATE);
        String oldKey = "allow_interruption";
        String newKey = "allow_interruption_opt_in_v311";
        boolean oldExists = prefs.contains(oldKey);
        boolean newExists = prefs.contains(newKey);
        boolean oldValue = prefs.getBoolean(oldKey, false);
        boolean newValue = prefs.getBoolean(newKey, false);
        try {
            prefs.edit().putBoolean(oldKey, true).remove(newKey).commit();
            assertFalse(TrackerSettings.isInterruptionEnabled(context));
            TrackerSettings.setInterruptionEnabled(context, true);
            assertTrue(TrackerSettings.isInterruptionEnabled(context));
            TrackerSettings.setInterruptionEnabled(context, false);
            assertFalse(TrackerSettings.isInterruptionEnabled(context));
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (oldExists) edit.putBoolean(oldKey, oldValue); else edit.remove(oldKey);
            if (newExists) edit.putBoolean(newKey, newValue); else edit.remove(newKey);
            edit.commit();
        }
    }
}
