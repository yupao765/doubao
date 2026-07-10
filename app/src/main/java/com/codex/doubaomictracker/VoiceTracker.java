package com.codex.doubaomictracker;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

public class VoiceTracker {
    public interface Listener {
        void onVoiceStarted();

        void onSilence();

        void onSuppressedByPlayback();

        void onEndingCountdown(float rms, float endingThreshold, long remainingMs);

        void onVolumeLevel(float rms, float threshold, boolean speaking, boolean suppressed);

        void onError(String message);
    }

    public interface Gate {
        boolean shouldSuppressVoice();
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_DURATION_MS = 100;
    private static final float AUTOMATIC_ENDING_AMBIENT_MULTIPLIER = 1.35f;
    private static final long AUDIO_STALL_FRAME_GAP_MS = 450L;
    private static final long AUDIO_STALL_FORCE_MIN_MS = 1200L;
    private static final long MAX_SPEECH_HOLD_MS = 30000L;

    private final Context context;
    private final Listener listener;
    private final Gate gate;
    private final Object stateLock = new Object();
    private volatile boolean running;
    private volatile boolean speaking;
    private volatile long lastVoiceAt;
    private volatile long lastAudioFrameAt;
    private volatile long quietSince;
    private volatile long speechStartedAt;
    private Thread worker;
    private Thread silenceWatcher;
    private AudioRecord recorder;

    public VoiceTracker(Context context, Listener listener, Gate gate) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.gate = gate;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        speaking = false;
        lastVoiceAt = 0L;
        lastAudioFrameAt = 0L;
        quietSince = 0L;
        speechStartedAt = 0L;
        worker = new Thread(this::captureLoop, "DoubaoVoiceTracker");
        silenceWatcher = new Thread(this::watchSilenceTimeout, "DoubaoSilenceWatcher");
        worker.start();
        silenceWatcher.start();
    }

    public synchronized void stop() {
        running = false;
        AudioRecord local = recorder;
        if (local != null) {
            try {
                local.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (silenceWatcher != null) {
            silenceWatcher.interrupt();
        }
        signalSilenceIfNeeded();
    }

    private void captureLoop() {
        try {
            int minBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBufferBytes <= 0) {
                notifyError("当前设备不支持 16kHz 单声道麦克风采样");
                return;
            }

            int bufferBytes = Math.max(
                    minBufferBytes,
                    SAMPLE_RATE * FRAME_DURATION_MS / 1000 * 2
            );
            short[] buffer = new short[bufferBytes / 2];

            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setPrivacySensitive(false);
            }
            recorder = builder.build();

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError("麦克风初始化失败");
                return;
            }

            recorder.startRecording();

            float ambient = 0.0025f;
            float smoothedRms = 0f;
            long lastSuppressedNoticeAt = 0L;

            while (running) {
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    continue;
                }

                float rms = calculateRms(buffer, read);
                smoothedRms = smoothedRms == 0f
                        ? rms
                        : smoothedRms * 0.55f + rms * 0.45f;
                float threshold = Math.max(
                        TrackerSettings.minimumSpeechRms(context),
                        ambient * TrackerSettings.ambientMultiplier(context)
                );
                long now = System.currentTimeMillis();
                lastAudioFrameAt = now;
                boolean suppressed = gate != null && gate.shouldSuppressVoice();

                if (suppressed) {
                    signalSilenceIfNeeded();
                    listener.onVolumeLevel(rms, threshold, false, true);
                    if (now - lastSuppressedNoticeAt > 900L) {
                        listener.onSuppressedByPlayback();
                        lastSuppressedNoticeAt = now;
                    }
                    continue;
                }

                if (!speaking) {
                    if (smoothedRms > threshold) {
                        signalVoice(now);
                    } else {
                        ambient = ambient * 0.96f + Math.min(smoothedRms, threshold) * 0.04f;
                    }
                } else {
                    float endingThreshold = calculateEndingThreshold(ambient);
                    if (smoothedRms >= endingThreshold) {
                        signalVoice(now);
                    } else {
                        if (quietSince == 0L) {
                            quietSince = now;
                        }
                        long releaseDelayMs = TrackerSettings.getReleaseDelayMs(context);
                        long quietDuration = now - quietSince;
                        listener.onEndingCountdown(
                                smoothedRms,
                                endingThreshold,
                                Math.max(0L, releaseDelayMs - quietDuration)
                        );
                        if (quietDuration >= releaseDelayMs) {
                            signalSilenceIfNeeded();
                        }
                    }
                }

                listener.onVolumeLevel(rms, threshold, speaking, false);
            }
        } catch (SecurityException e) {
            notifyError("没有麦克风权限");
        } catch (Exception e) {
            if (running) {
                notifyError("麦克风监听异常：" + e.getMessage());
            }
        } finally {
            signalSilenceIfNeeded();
            releaseRecorder();
        }
    }

    private void watchSilenceTimeout() {
        while (running) {
            boolean shouldRelease;
            synchronized (stateLock) {
                long now = System.currentTimeMillis();
                long releaseDelayMs = TrackerSettings.getReleaseDelayMs(context);
                boolean volumeDropped = quietSince > 0L
                        && now - quietSince >= releaseDelayMs;
                long stalledReleaseMs = Math.max(
                        AUDIO_STALL_FORCE_MIN_MS,
                        releaseDelayMs + 400L
                );
                boolean audioReadStalled = lastAudioFrameAt > 0L
                        && now - lastAudioFrameAt >= AUDIO_STALL_FRAME_GAP_MS
                        && lastVoiceAt > 0L
                        && now - lastVoiceAt >= stalledReleaseMs;
                boolean maximumHoldReached = speechStartedAt > 0L
                        && now - speechStartedAt >= MAX_SPEECH_HOLD_MS;
                shouldRelease = speaking
                        && (volumeDropped || audioReadStalled || maximumHoldReached);
                if (shouldRelease) {
                    speaking = false;
                    quietSince = 0L;
                    speechStartedAt = 0L;
                }
            }
            if (shouldRelease) {
                listener.onSilence();
            }
            try {
                Thread.sleep(80L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private void signalVoice(long now) {
        boolean shouldStart = false;
        synchronized (stateLock) {
            lastVoiceAt = now;
            quietSince = 0L;
            if (!speaking) {
                speaking = true;
                speechStartedAt = now;
                shouldStart = true;
            }
        }
        if (shouldStart) {
            listener.onVoiceStarted();
        }
    }

    private void signalSilenceIfNeeded() {
        boolean shouldRelease = false;
        synchronized (stateLock) {
            if (speaking) {
                speaking = false;
                quietSince = 0L;
                speechStartedAt = 0L;
                shouldRelease = true;
            }
        }
        if (shouldRelease) {
            listener.onSilence();
        }
    }

    private float calculateRms(short[] buffer, int read) {
        double sum = 0.0;
        for (int i = 0; i < read; i++) {
            double sample = buffer[i] / 32768.0;
            sum += sample * sample;
        }
        return (float) Math.sqrt(sum / Math.max(1, read));
    }

    private float calculateEndingThreshold(float ambient) {
        float configured = TrackerSettings.endingVolumeRms(context);
        if (!TrackerSettings.isAutomaticEndingEnabled(context)) {
            return configured;
        }
        return Math.max(configured, ambient * AUTOMATIC_ENDING_AMBIENT_MULTIPLIER);
    }

    private void releaseRecorder() {
        AudioRecord local = recorder;
        recorder = null;
        if (local != null) {
            try {
                local.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyError(String message) {
        running = false;
        listener.onError(message);
    }
}