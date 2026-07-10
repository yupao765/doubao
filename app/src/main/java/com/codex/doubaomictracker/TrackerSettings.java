package com.codex.doubaomictracker;

import android.content.Context;
import android.content.SharedPreferences;

public final class TrackerSettings {
    public static final int MIN_SENSITIVITY = 1;
    public static final int MAX_SENSITIVITY = 10;
    public static final int DEFAULT_SENSITIVITY = 6;
    public static final int MIN_RELEASE_DELAY_MS = 200;
    public static final int MAX_RELEASE_DELAY_MS = 2000;
    public static final int RELEASE_DELAY_STEP_MS = 100;
    public static final int DEFAULT_RELEASE_DELAY_MS = 400;
    public static final int MIN_ENDING_VOLUME_TENTHS_PERCENT = 2;
    public static final int MAX_ENDING_VOLUME_TENTHS_PERCENT = 30;
    public static final int DEFAULT_ENDING_VOLUME_TENTHS_PERCENT = 6;

    private static final String PREFERENCES = "tracker_settings";
    private static final String KEY_SENSITIVITY = "microphone_sensitivity";
    private static final String KEY_RELEASE_DELAY_MS = "release_delay_ms";
    private static final String KEY_ENDING_VOLUME = "ending_volume_tenths_percent";
    private static final String KEY_AUTO_ENDING = "automatic_ending_detection";

    private TrackerSettings() {
    }

    public static int getSensitivity(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return clampSensitivity(preferences.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public static void setSensitivity(Context context, int level) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SENSITIVITY, clampSensitivity(level))
                .apply();
    }

    public static int getReleaseDelayMs(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        int value = preferences.getInt(KEY_RELEASE_DELAY_MS, DEFAULT_RELEASE_DELAY_MS);
        return clampReleaseDelay(value);
    }

    public static void setReleaseDelayMs(Context context, int value) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RELEASE_DELAY_MS, clampReleaseDelay(value))
                .apply();
    }

    public static int getEndingVolumeTenthsPercent(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        int value = preferences.getInt(KEY_ENDING_VOLUME, DEFAULT_ENDING_VOLUME_TENTHS_PERCENT);
        return clampEndingVolume(value);
    }

    public static void setEndingVolumeTenthsPercent(Context context, int value) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ENDING_VOLUME, clampEndingVolume(value))
                .apply();
    }

    public static float endingVolumeRms(Context context) {
        return getEndingVolumeTenthsPercent(context) / 1000f;
    }

    public static boolean isAutomaticEndingEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_ENDING, true);
    }

    public static void setAutomaticEndingEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_ENDING, enabled)
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

    private static int clampSensitivity(int value) {
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, value));
    }

    private static int clampReleaseDelay(int value) {
        int clamped = Math.max(MIN_RELEASE_DELAY_MS, Math.min(MAX_RELEASE_DELAY_MS, value));
        return MIN_RELEASE_DELAY_MS
                + Math.round((clamped - MIN_RELEASE_DELAY_MS) / (float) RELEASE_DELAY_STEP_MS)
                * RELEASE_DELAY_STEP_MS;
    }

    private static int clampEndingVolume(int value) {
        return Math.max(
                MIN_ENDING_VOLUME_TENTHS_PERCENT,
                Math.min(MAX_ENDING_VOLUME_TENTHS_PERCENT, value)
        );
    }
}