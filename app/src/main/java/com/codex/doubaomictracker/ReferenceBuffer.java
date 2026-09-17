package com.codex.doubaomictracker;

import java.util.Arrays;

/** A bounded, memory-only timeline in AudioTimestamp.TIMEBASE_MONOTONIC nanoseconds. */
final class ReferenceBuffer {
    private final short[][] frames = new short[100][320];
    private final long[] times = new long[100];
    private int count;
    private int next;
    synchronized void add(long endNanos, short[] pcm) {
        if (pcm.length != 320 || endNanos <= 0) return;
        if (count > 0 && endNanos <= times[(next + 99) % 100]) return;
        System.arraycopy(pcm, 0, frames[next], 0, 320);
        times[next] = endNanos;
        next = (next + 1) % 100;
        count = Math.min(100, count + 1);
    }
    synchronized boolean read(long targetNanos, short[] output) {
        Arrays.fill(output, (short) 0);
        if (targetNanos <= 0 || output.length != 320) return false;
        int best = -1;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long difference = times[i] - targetNanos;
            if (difference >= -31250L && difference < 20000000L && difference < distance) {
                distance = difference;
                best = i;
            }
        }
        if (best < 0) return false;
        int overlap = (int) Math.round(distance / 62500.0);
        if (overlap < 0 || overlap > 320) return false;
        if (overlap > 0) {
            int previous = (best + 99) % 100;
            if (times[previous] <= 0 || Math.abs(times[best] - times[previous] - 20000000L) > 125000L)
                return false;
            System.arraycopy(frames[previous], 320 - overlap, output, 0, overlap);
        }
        System.arraycopy(frames[best], 0, output, overlap, 320 - overlap);
        return true;
    }
    synchronized void clear() {
        for (short[] frame : frames) Arrays.fill(frame, (short) 0);
        Arrays.fill(times, 0);
        count = 0;
        next = 0;
    }
    static float rms(short[] frame) {
        double sum = 0;
        for (short value : frame) { double x = value / 32768.0; sum += x * x; }
        return (float) Math.sqrt(sum / frame.length);
    }
}
