package com.codex.doubaomictracker;

/** Rejects blind AEC enablement: require aligned far-end audio and measured attenuation. */
final class BargeInCalibration {
    private int frames;
    private int reduced;
    private boolean calibrated;
    private long lastAligned = -1;
    private long lastFar = -1;
    boolean update(long now, boolean aligned, float far, float raw, float clean) {
        if (lastAligned >= 0 && now - lastAligned > 200) {
            frames = 0;
            reduced = 0;
            calibrated = false;
            lastFar = -1;
        }
        if (!aligned) return false;
        lastAligned = now;
        if (far >= 0.0003f) {
            lastFar = now;
            frames++;
            if (raw >= 0.0003f && clean < raw * 0.35f) reduced++;
            else if (!calibrated) reduced = 0;
        }
        if (frames >= 50 && reduced >= 20) calibrated = true;
        return calibrated && lastFar >= 0 && now - lastFar <= 300;
    }
}
