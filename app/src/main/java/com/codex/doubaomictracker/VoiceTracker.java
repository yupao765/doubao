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
        void onFrame(long capturedAt, float rms, boolean speech, boolean silenced, EchoStatus echo, ReferenceStatus reference);
        void onError(String message);
    }
    private final Listener listener;
    private final boolean echoEnabled;
    private final boolean referenceMode;
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private Thread worker;

    public VoiceTracker(boolean echoEnabled, boolean referenceMode, Listener listener) {
        this.echoEnabled = echoEnabled;
        this.referenceMode = referenceMode;
        this.listener = listener;
    }
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
        EchoProcessor echo = null;
        SoftwareEcho software = null;
        try {
            echo = new EchoProcessor(echoEnabled && !referenceMode);
            if (referenceMode) software = new SoftwareEcho();
            int minimum = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("不支持16kHz录音");
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(!referenceMode && echo.useCommunicationSource() && Build.VERSION.SDK_INT >= 30
                            ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            : MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
            echo.attach(local.getAudioSessionId());
            vad = Vad.builder().setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_320).setMode(Mode.AGGRESSIVE)
                    .setSpeechDurationMs(0).setSilenceDurationMs(0).build();
            local.startRecording();
            short[] frame = new short[320];
            short[] far = new short[320];
            short[] clean = new short[320];
            RecordingClock clock = new RecordingClock();
            BargeInCalibration calibration = new BargeInCalibration();
            PlaybackReferenceService lastReference = null;
            int lastRoute = -1;
            long samples = 0;
            int offset = 0;
            while (running) {
                int read = local.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                if (!running) break;
                if (read < 0) throw new IllegalStateException("录音读取失败，代码 " + read);
                if (read == 0) { Thread.sleep(10); continue; }
                offset += read;
                samples += read;
                if (offset != frame.length) continue;
                offset = 0;
                AudioRecordingConfiguration config = local.getActiveRecordingConfiguration();
                boolean silenced = config != null && config.isClientSilenced();
                float rawRms = ReferenceBuffer.rms(frame);
                float level = rawRms;
                short[] vadFrame = frame;
                ReferenceStatus reference = new ReferenceStatus(false, rawRms, 0, "语音打断关闭");
                if (referenceMode) {
                    PlaybackReferenceService source = PlaybackReferenceService.current();
                    int route = config == null || config.getAudioDevice() == null ? -1 : config.getAudioDevice().getId();
                    if (source != lastReference || route != lastRoute) {
                        software.close();
                        software = new SoftwareEcho();
                        calibration = new BargeInCalibration();
                        lastReference = source;
                        lastRoute = route;
                    }
                    long end = clock.frameEnd(local, samples);
                    // Wait briefly for the matching playback samples, without shifting the acoustic timeline.
                    boolean aligned = end > 0 && source != null && source.buffer.read(end, far);
                    long deadline = Math.min(System.nanoTime() + 30000000L, end + 80000000L);
                    while (!aligned && running && end > 0 && source != null
                            && source == PlaybackReferenceService.current() && System.nanoTime() < deadline) {
                        Thread.sleep(2);
                        aligned = source.buffer.read(end, far);
                    }
                    if (!running) break;
                    if (!aligned) java.util.Arrays.fill(far, (short) 0);
                    software.process(frame, far, clean);
                    level = ReferenceBuffer.rms(clean);
                    float farRms = ReferenceBuffer.rms(far);
                    boolean ready = calibration.update(System.nanoTime() / 1000000L, aligned, farRms, rawRms, level);
                    String state = source == null ? "打断未授权，播放时保护"
                            : !aligned ? "播放参考时间未对齐"
                            : farRms < 0.0003f ? "未收到有效豆包播放参考"
                            : ready ? "参考消回声就绪（实验）" : "参考已收到，等待回声收敛";
                    reference = new ReferenceStatus(ready, rawRms, farRms, state);
                    vadFrame = clean;
                }
                listener.onFrame(SystemClock.elapsedRealtime(),
                        level, vad.isSpeech(vadFrame), silenced, echo.inspect(config), reference);
            }
        } catch (Exception | LinkageError e) {
            if (running) listener.onError("录音不可用：" + e.getMessage());
        } finally {
            running = false;
            recorder = null;
            if (echo != null) echo.close();
            if (software != null) software.close();
            if (local != null) local.release();
            if (vad != null) vad.close();
        }
    }
}
