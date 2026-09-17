package com.codex.doubaomictracker;

/** Conservative playback fallback; this does not identify a speaker. */
final class PlaybackGate {
    private boolean playing;
    private long startedAt;
    private long tailUntil;
    boolean blocked;

    void update(long now, boolean active, boolean allowInterruption, boolean echoReady) {
        if (active && !playing) startedAt = now;
        if (!active && playing) tailUntil = now + 300;
        playing = active;
        // Let the acoustic path settle at playback start; discard the speaker tail at the end.
        blocked = active ? !allowInterruption || !echoReady || now - startedAt < 250
                : now < tailUntil;
    }

    boolean allowsGesture(boolean observationOnly, boolean foregroundReady) {
        return !observationOnly && foregroundReady && !blocked;
    }
}
