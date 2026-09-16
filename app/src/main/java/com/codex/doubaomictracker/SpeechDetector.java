package com.codex.doubaomictracker;

/** Pure timing logic driven by monotonic capture timestamps on one thread. */
final class SpeechDetector {
    boolean speaking;
    boolean awaitingQuiet;
    float noise = 0.0005f;
    float onset;
    float ending;
    float rms;
    long quietSince = -1;
    long candidateSince = -1;
    long startedAt;
    long lastFrame = -1;
    private int delay = 400;

    void accept(long now, float level, boolean vad, float minimum,
                float noiseMultiplier, float configuredEnd, int releaseMs, boolean automatic) {
        if (lastFrame >= 0 && now <= lastFrame) return;
        if (lastFrame >= 0 && now - lastFrame > 200) reset();
        lastFrame = now;
        rms = level;
        delay = releaseMs;
        if (!vad) noise += (level < noise ? 0.12f : 0.025f) * (level - noise);
        onset = Math.max(minimum, noise * noiseMultiplier);
        // Saved sliders must never invert the start/end hysteresis.
        ending = Math.min(onset * 0.8f,
                automatic ? Math.max(configuredEnd, noise * 1.35f) : configuredEnd);
        boolean voiced = vad && level >= (speaking ? ending : onset);
        if (awaitingQuiet) {
            if (!voiced) {
                if (quietSince < 0) quietSince = now;
                if (now - quietSince >= 200) { awaitingQuiet = false; quietSince = -1; }
            } else quietSince = -1;
            return;
        }
        if (!speaking) {
            if (voiced) {
                if (candidateSince < 0) candidateSince = now;
                if (now - candidateSince >= 60) {
                    speaking = true;
                    startedAt = now;
                    quietSince = -1;
                }
            } else candidateSince = -1;
        } else if (voiced) quietSince = -1;
        else if (quietSince < 0) quietSince = now;
        tick(now);
    }
    void tick(long now) {
        if (!speaking) return;
        if (now - startedAt >= 30000) {
            reset();
            awaitingQuiet = true;
        } else if (quietSince >= 0 && now - quietSince >= delay) reset();
    }
    long remaining(long now) {
        return quietSince < 0 ? -1 : Math.max(0, delay - (now - quietSince));
    }
    void reset() {
        speaking = false;
        candidateSince = -1;
        quietSince = -1;
        awaitingQuiet = false;
    }
}
