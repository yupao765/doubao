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

        void onVolumeLevel(float rms, float threshold, boolean speaking, boolean suppressed);

        void onError(String message);
    }

    public interface Gate {
        boolean shouldSuppressVoice();
    }

    private static final int SAMPLE_RATE = 16000;
    private static final long SILENCE_RELEASE_MS = 2000L;

    private final Context context;
    private final Listener listener;
    private final Gate gate;
    private final Object stateLock = new Object();
    private volatile boolean running;
    private volatile boolean speaking;
    private volatile long lastVoiceAt;
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

            int bufferBytes = Math.max(minBufferBytes, SAMPLE_RATE / 5 * 2);
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
            long lastSuppressedNoticeAt = 0L;

            while (running) {
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    continue;
                }

                float rms = calculateRms(buffer, read);
                float threshold = Math.max(
                        TrackerSettings.minimumSpeechRms(context),
                        ambient * TrackerSettings.ambientMultiplier(context)
                );
                long now = System.currentTimeMillis();
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

                boolean voiceNow = speaking
                        ? rms > threshold * 0.72f
                        : rms > threshold;

                if (voiceNow) {
                    signalVoice(now);
                } else if (!speaking) {
                    ambient = ambient * 0.97f + Math.min(rms, threshold) * 0.03f;
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
                shouldRelease = speaking
                        && lastVoiceAt > 0L
                        && System.currentTimeMillis() - lastVoiceAt >= SILENCE_RELEASE_MS;
                if (shouldRelease) {
                    speaking = false;
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
            if (!speaking) {
                speaking = true;
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