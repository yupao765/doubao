package com.codex.doubaomictracker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Captures only the authorized Doubao UID's playback, never screen pixels or files. */
public final class PlaybackReferenceService extends Service {
    static final String GRANT = "projection_grant";
    private static volatile PlaybackReferenceService instance;
    private static volatile String lastStatus = "尚未授权播放参考";
    final ReferenceBuffer buffer = new ReferenceBuffer();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private volatile AudioRecord recorder;
    private volatile boolean running;

    static PlaybackReferenceService current() { return instance; }
    static String status() { return lastStatus; }
    static boolean active() { return instance != null && instance.running; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("playback_reference", "豆包播放参考", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, PlaybackReferenceService.class)
                    .setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this, "playback_reference")
                    .setContentTitle("豆包播放参考已授权").setContentText("仅处理音频，不保存录音或画面")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "停止捕获", stop).build()).build();
            startForeground(2302, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent grant = intent.getParcelableExtra(GRANT);
            if (grant == null) throw new IllegalArgumentException("缺少系统授权");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, grant);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { lastStatus = "播放捕获已停止，需重新授权"; stopSelf(); }
            }, main);
            instance = this;
            running = true;
            lastStatus = "已授权，等待豆包播放音频";
            new Thread(this::capture, "DoubaoPlaybackReference").start();
        } catch (RuntimeException e) {
            lastStatus = "播放参考启动失败：" + e.getClass().getSimpleName();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void capture() {
        AudioRecord local = null;
        try {
            int uid = getPackageManager().getApplicationInfo(DoubaoWindowInspector.PACKAGE, 0).uid;
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUid(uid).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("播放捕获采样率不可用");
            local = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 5120)).build();
            recorder = local;
            if (!running) return;
            if (local.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("播放捕获初始化失败");
            local.startRecording();
            RecordingClock clock = new RecordingClock();
            short[] frame = new short[320];
            int offset = 0;
            long samples = 0;
            while (running) {
                int read = local.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                if (!running) break;
                if (read < 0) throw new IllegalStateException("播放捕获读取失败 " + read);
                if (read == 0) { Thread.sleep(10); continue; }
                offset += read;
                samples += read;
                if (offset != 320) continue;
                offset = 0;
                long end = clock.frameEnd(local, samples);
                if (end > 0) buffer.add(end, frame);
            }
        } catch (Exception e) {
            if (running) main.post(() -> {
                if (instance == this) {
                    lastStatus = "播放参考不可用：" + e.getClass().getSimpleName();
                    stopSelf();
                }
            });
        } finally {
            if (local != null) local.release();
            recorder = null;
            buffer.clear();
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        AudioRecord local = recorder;
        if (local != null) try { local.stop(); } catch (IllegalStateException ignored) { }
        buffer.clear();
        if (projection != null) { projection.stop(); projection = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
