package com.codex.doubaomictracker;

import android.content.Context;
import android.content.SharedPreferences;

public final class TrackerSettings {
    public static final int MIN_SENSITIVITY = 1;
    public static final int MAX_SENSITIVITY = 10;
    public static final int DEFAULT_SENSITIVITY = 6;

    private static final String PREFERENCES = "tracker_settings";
    private static final String KEY_SENSITIVITY = "microphone_sensitivity";

    private TrackerSettings() {
    }

    public static int getSensitivity(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return clamp(preferences.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public static void setSensitivity(Context context, int level) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SENSITIVITY, clamp(level))
                .apply();
    }

    public static float minimumSpeechRms(Context context) {
        int level = getSensitivity(context);
        return 0.0045f + (MAX_SENSITIVITY - level) * 0.0011f;
    }

    public static float ambientMultiplier(Context context) {
        int level = getSensitivity(context);
        return 1.40f + (MAX_SENSITIVITY - level) * 0.16f;
    }

    private static int clamp(int value) {
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, value));
    }
}