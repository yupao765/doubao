package com.codex.doubaomictracker;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

/** Capture only. Conversation and gesture state belongs to the service thread. */
public final class VoiceTracker {
    public interface Listener {
        void onFrame(long capturedAt, float rms, boolean speech, boolean silenced);
        void onError(String message);
    }
    private final Listener listener;
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private Thread worker;

    public VoiceTracker(Listener listener) { this.listener = listener; }
    public synchronized void start() {
        if (worker != null) return;
        running = true;
        worker = new Thread(this::capture, "DoubaoAudioCapture");
        worker.start();
    }
    public void stop() {
        running = false;
        AudioRecord current = recorder;
        if (current != null) {
            try { current.stop(); } catch (IllegalStateException ignored) { }
        }
    }
    @SuppressLint("MissingPermission") // Service checks permission; revocation is reported as an error.
    private void capture() {
        AudioRecord local = null;
        VadWebRTC vad = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("不支持16kHz录音");
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 640 * 8));
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
            local = builder.build();
            recorder = local;
            if (!running) return;
            if (local.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("麦克风初始化失败");
            vad = Vad.builder().setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_320).setMode(Mode.AGGRESSIVE)
                    .setSpeechDurationMs(0).setSilenceDurationMs(0).build();
            local.startRecording();
            short[] frame = new short[320];
            int offset = 0;
            while (running) {
                int read = local.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                if (!running) break;
                if (read < 0) throw new IllegalStateException("录音读取失败，代码 " + read);
                if (read == 0) { Thread.sleep(10); continue; }
                offset += read;
                if (offset != frame.length) continue;
                offset = 0;
                boolean silenced = false;
                if (Build.VERSION.SDK_INT >= 29) {
                    AudioRecordingConfiguration config = local.getActiveRecordingConfiguration();
                    silenced = config != null && config.isClientSilenced();
                }
                double energy = 0;
                for (short value : frame) {
                    double normalized = value / 32768.0;
                    energy += normalized * normalized;
                }
                listener.onFrame(SystemClock.elapsedRealtime(),
                        (float) Math.sqrt(energy / frame.length), vad.isSpeech(frame), silenced);
            }
        } catch (Exception | LinkageError e) {
            if (running) listener.onError("录音不可用：" + e.getMessage());
        } finally {
            running = false;
            recorder = null;
            if (local != null) local.release();
            if (vad != null) vad.close();
        }
    }
}
