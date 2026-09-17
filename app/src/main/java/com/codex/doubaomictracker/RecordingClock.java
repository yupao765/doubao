package com.codex.doubaomictracker;

import android.media.AudioRecord;
import android.media.AudioTimestamp;

final class RecordingClock {
    private final AudioTimestamp timestamp = new AudioTimestamp();
    long frameEnd(AudioRecord record, long samplesRead) {
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return -1;
        long end = timestamp.nanoTime + (samplesRead - timestamp.framePosition) * 1000000000L / 16000;
        long age = System.nanoTime() - end;
        return age >= -200000000L && age <= 2000000000L ? end : -1;
    }
}
