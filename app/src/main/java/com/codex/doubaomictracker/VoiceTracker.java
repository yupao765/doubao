package com.codex.doubaomictracker;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

public class VoiceTracker {
    public interface Listener {
        void onVoiceStarted();

        void onSilence();

        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final long SILENCE_RELEASE_MS = 2000L;
    private static final float MIN_SPEECH_RMS = 0.018f;

    private final Listener listener;
    private volatile boolean running;
    private Thread worker;
    private AudioRecord recorder;

    public VoiceTracker(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::captureLoop, "DoubaoVoiceTracker");
        worker.start();
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
    }

    private void captureLoop() {
        boolean speaking = false;
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

            float ambient = 0.010f;
            int activeFrames = 0;
            long lastVoiceAt = 0L;

            while (running) {
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    continue;
                }

                float rms = calculateRms(buffer, read);
                float threshold = Math.max(MIN_SPEECH_RMS, ambient * 2.6f);
                boolean voiceNow = rms > threshold;
                long now = System.currentTimeMillis();

                if (voiceNow) {
                    activeFrames++;
                    lastVoiceAt = now;
                } else {
                    activeFrames = 0;
                    ambient = ambient * 0.96f + rms * 0.04f;
                }

                if (!speaking && activeFrames >= 2) {
                    speaking = true;
                    listener.onVoiceStarted();
                } else if (speaking && now - lastVoiceAt >= SILENCE_RELEASE_MS) {
                    speaking = false;
                    activeFrames = 0;
                    listener.onSilence();
                }
            }
        } catch (SecurityException e) {
            notifyError("没有麦克风权限");
        } catch (Exception e) {
            notifyError("麦克风监听异常：" + e.getMessage());
        } finally {
            if (speaking) {
                listener.onSilence();
            }
            releaseRecorder();
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
