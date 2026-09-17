package com.codex.doubaomictracker;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DoubaoAccessibilityService extends AccessibilityService {
    private static DoubaoAccessibilityService instance;
    private static final String CHANNEL = "doubao_mic_tracker";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService windowWorker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> diagnostics = new ArrayDeque<>();
    private SpeechDetector detector = new SpeechDetector();
    private PlaybackGate playbackGate = new PlaybackGate();
    private EchoStatus echoStatus;
    private boolean observationOnly;
    private boolean allowInterruption;
    private boolean playbackActive;
    private long nextPlaybackPoll;
    private float measuredRms;
    private String lastEchoDiagnostic = "";
    private DoubaoWindowInspector.Snapshot screen = DoubaoWindowInspector.Snapshot.unknown();
    private VoiceTracker audio;
    private HoldController hold;
    private PressGesture pressGesture;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private View overlay;
    private TextView status;
    private Button startButton;
    private Button stopButton;
    private boolean tracking;
    private boolean destroyed;
    private boolean scanning;
    private boolean micSilenced;
    private boolean lastVad;
    private boolean loggedSpeaking;
    private long session;
    private long windowRevision;
    private long lastScan;
    private long lastKnownForeground;
    private long lastAudio;
    private long lastDisplay;
    private long lastLog;
    private long gestureStarted;
    private long verificationStarted;
    private float holdX;
    private float holdY;
    private String message = "待启动";
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            tick();
            if (tracking || hold.busy()) main.postDelayed(this, 40);
        }
    };

    public static boolean isRunning() { return instance != null; }
    public static DoubaoAccessibilityService getInstance() { return instance; }
    public String getDiagnostics() {
        return "DoubaoVoiceFollower 3.1.0\nAndroid " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT
                + " " + Build.MANUFACTURER + " " + Build.MODEL + "\nBuild " + Build.DISPLAY
                + "\n" + (echoStatus == null ? "aec=not started" : echoStatus.diagnostic())
                + "\n" + String.join("\n", diagnostics);
    }

    public void stopForSettings() { stopTracking("设置已保存，待重新启动"); }

    @Override protected void onServiceConnected() {
        instance = this;
        windowManager = getSystemService(WindowManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "豆包语音跟随", NotificationManager.IMPORTANCE_LOW));
        hold = new HoldController(new HoldController.Driver() {
            @Override public void dispatch(long token, boolean first, boolean finish) {
                dispatchStroke(token, first, finish);
            }
            @Override public void changed(String event) {
                message = event;
                log(event);
                if (hold.state == HoldController.State.VERIFYING) verificationStarted = now();
            }
        });
        showOverlay();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (hold == null || destroyed || !tracking && !hold.busy()) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowRevision++;
            // Window transitions invalidate start permission before asynchronous inspection.
            screen = DoubaoWindowInspector.Snapshot.unknown();
        }
        scanWindow();
    }
    @Override public void onInterrupt() { stopTracking("无障碍服务被中断"); }
    @Override public void onDestroy() {
        destroyed = true;
        stopTracking("服务已停止");
        if (overlay != null) windowManager.removeView(overlay);
        overlay = null;
        windowWorker.shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public void showOverlay() {
        if (overlay != null || destroyed) return;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(8), dp(8), dp(8), dp(8));
        column.setBackground(background(0xF222252A));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        column.addView(status, new LinearLayout.LayoutParams(-1,
                Math.max(dp(54), status.getLineHeight() * 3 + dp(8))));
        LinearLayout row = new LinearLayout(this);
        startButton = button("启用麦克风跟踪", 0xFF176BFF);
        stopButton = button("结束跟踪", 0xFF454B54);
        startButton.setOnClickListener(v -> startTracking());
        stopButton.setOnClickListener(v -> stopTracking("已停止"));
        row.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams stopLayout = new LinearLayout.LayoutParams(0, dp(48), 1);
        stopLayout.leftMargin = dp(6);
        row.addView(stopButton, stopLayout);
        column.addView(row);
        overlayParams = new WindowManager.LayoutParams(
                Math.min(dp(290), getResources().getDisplayMetrics().widthPixels - dp(16)),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = dp(8);
        overlayParams.y = dp(100);
        status.setOnTouchListener(new View.OnTouchListener() {
            float x, y;
            int initialX, initialY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY();
                    initialX = overlayParams.x; initialY = overlayParams.y;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    overlayParams.x = Math.max(0, Math.min(getResources().getDisplayMetrics().widthPixels
                            - overlay.getWidth(), initialX + Math.round(event.getRawX() - x)));
                    overlayParams.y = Math.max(0, Math.min(getResources().getDisplayMetrics().heightPixels
                            - overlay.getHeight(), initialY + Math.round(event.getRawY() - y)));
                    windowManager.updateViewLayout(overlay, overlayParams);
                    return true;
                }
                return true;
            }
        });
        overlay = column;
        windowManager.addView(overlay, overlayParams);
        render();
    }

    private void startTracking() {
        if (tracking || hold.busy()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = "缺少麦克风权限"; render(); return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            message = "此模式需要Android 10及以上"; render(); return;
        }
        try {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent action = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("豆包语音跟随").setContentText("麦克风跟踪已开启")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(action)
                    .setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(2301, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(2301, notification);
            }
        } catch (RuntimeException e) {
            message = "麦克风启动受限，请回本应用后重试"; log(e.toString()); render(); return;
        }
        long currentSession = ++session;
        detector = new SpeechDetector();
        playbackGate = new PlaybackGate();
        echoStatus = null;
        lastEchoDiagnostic = "";
        nextPlaybackPoll = 0;
        playbackActive = false;
        measuredRms = 0;
        observationOnly = TrackerSettings.isObservationOnly(this);
        allowInterruption = TrackerSettings.isInterruptionEnabled(this);
        hold.reset();
        micSilenced = false;
        loggedSpeaking = false;
        lastAudio = now();
        tracking = true;
        message = "等待豆包前台";
        audio = new VoiceTracker(TrackerSettings.isEchoEnabled(this), new VoiceTracker.Listener() {
            @Override public void onFrame(long at, float rms, boolean vad, boolean silenced, EchoStatus echo) {
                main.post(() -> {
                    if (!tracking || session != currentSession || now() - at > 200) return;
                    lastAudio = at;
                    micSilenced = silenced;
                    lastVad = vad;
                    measuredRms = rms;
                    // Drop any start candidate when the actual capture preprocessing changes.
                    if (echoStatus != null && echoStatus.ready() != echo.ready()) detector.reset();
                    echoStatus = echo;
                    String echoDiagnostic = echo.diagnostic();
                    if (!lastEchoDiagnostic.equals(echoDiagnostic)) {
                        lastEchoDiagnostic = echoDiagnostic;
                        log(echoDiagnostic);
                    }
                    if (silenced) { stopTracking("麦克风被系统静音，已停止跟踪"); return; }
                    updatePlayback();
                    boolean transientWindowGap = screen.foreground == DoubaoWindowInspector.Foreground.UNKNOWN
                            && now() - lastKnownForeground < 450 && unlocked();
                    if ((allowsAudio() || transientWindowGap) && (observationOnly || !playbackGate.blocked)) {
                        detector.accept(at, rms, vad, TrackerSettings.minimumSpeechRms(DoubaoAccessibilityService.this),
                                TrackerSettings.ambientMultiplier(DoubaoAccessibilityService.this),
                                TrackerSettings.endingVolumeRms(DoubaoAccessibilityService.this),
                                TrackerSettings.getReleaseDelayMs(DoubaoAccessibilityService.this),
                                TrackerSettings.isAutomaticEndingEnabled(DoubaoAccessibilityService.this));
                    } else detector.reset();
                    tick();
                });
            }
            @Override public void onError(String error) {
                main.post(() -> { if (tracking && session == currentSession) stopTracking(error); });
            }
        });
        audio.start();
        log("启动跟踪 observationOnly=" + observationOnly + " allowInterruption=" + allowInterruption
                + " echoRequested=" + TrackerSettings.isEchoEnabled(this));
        main.removeCallbacks(heartbeat);
        main.post(heartbeat);
    }

    private void stopTracking(String reason) {
        tracking = false;
        ++session;
        if (audio != null) { audio.stop(); audio = null; }
        detector.reset();
        if (hold != null) hold.update(now(), false, false, false, false);
        message = reason;
        log(reason);
        stopForeground(STOP_FOREGROUND_REMOVE);
        main.removeCallbacks(heartbeat);
        if (hold != null && hold.busy()) main.post(heartbeat);
        render();
    }

    private void tick() {
        long time = now();
        updatePlayback();
        if (tracking && time - lastAudio > 800) { stopTracking("麦克风数据中断，已停止跟踪"); return; }
        if (!destroyed && time - lastScan >= 180) scanWindow();
        detector.tick(time);
        boolean foreground = unlocked() && screen.foreground == DoubaoWindowInspector.Foreground.DOUBAO
                && time - screen.at < 600;
        boolean continueAllowed = foreground || unlocked()
                && screen.foreground == DoubaoWindowInspector.Foreground.UNKNOWN
                && time - lastKnownForeground < 450;
        if (!continueAllowed || !observationOnly && playbackGate.blocked) detector.reset();
        if (tracking && loggedSpeaking != detector.speaking) {
            loggedSpeaking = detector.speaking;
            log("speech=" + loggedSpeaking + " rms=" + measuredRms + " playback=" + playbackActive
                    + " gate=" + playbackGate.blocked + " observation=" + observationOnly);
        }
        boolean targetReady = foreground && screen.target != null && !screen.recording && !targetCovered();
        boolean uiEnded = foreground && screen.at > verificationStarted && !screen.recording
                && screen.target != null;
        // Leaving Doubao confirms no further automation there, but never claims a send.
        if (screen.foreground == DoubaoWindowInspector.Foreground.OTHER && hold.state == HoldController.State.VERIFYING)
            uiEnded = true;
        hold.update(time, tracking && detector.speaking && !observationOnly && !playbackGate.blocked,
                playbackGate.allowsGesture(observationOnly, targetReady),
                tracking && playbackGate.allowsGesture(observationOnly, continueAllowed), uiEnded);
        if (hold.state == HoldController.State.HOLDING && time - gestureStarted > 1600
                && foreground && screen.target != null && !screen.recording) {
            stopTracking("按压未进入录音，请检查豆包界面"); return;
        }
        if (tracking && hold.state == HoldController.State.FAILED) { stopTracking(message); return; }
        if (tracking && hold.state == HoldController.State.IDLE) {
            if (!unlocked()) message = "锁屏暂停";
            else if (!foreground) message = "等待豆包前台";
            else if (observationOnly) message = detector.speaking ? "检测到说话（不点击）" : "仅检测，未触发说话";
            else if (playbackGate.blocked) message = playbackActive ? "播放保护中，暂停按压" : "等待播放尾音结束";
            else if (screen.recording) message = "等待当前录音结束";
            else if (screen.target == null) message = "未找到按住说话按钮";
            else if (targetCovered()) message = "悬浮窗遮挡了说话按钮";
            else if (detector.awaitingQuiet) message = "已达30秒，等待停顿";
            else message = playbackActive ? "外放插话检测中（实验）" : "监听中";
        } else if (tracking && hold.state == HoldController.State.HOLDING) {
            long remaining = detector.remaining(time);
            message = remaining < 0 ? (screen.recording ? "录音中" : "按压中，待确认")
                    : String.format(Locale.CHINA, "停顿 %.2f秒后松手", remaining / 1000f);
        }
        if (time - lastDisplay >= 100) { lastDisplay = time; render(); }
        if (tracking && time - lastLog >= 1000) {
            lastLog = time;
            log(String.format(Locale.US, "state=%s foreground=%s rms=%.5f vad=%s speaking=%s start=%.5f end=%.5f recording=%s micMuted=%s playback=%s gate=%s observation=%s",
                    hold.state, screen.foreground, measuredRms, lastVad, detector.speaking, detector.onset, detector.ending,
                    screen.recording, micSilenced, playbackActive, playbackGate.blocked, observationOnly));
        }
    }

    private boolean allowsAudio() {
        return unlocked() && screen.foreground == DoubaoWindowInspector.Foreground.DOUBAO
                && now() - screen.at < 600;
    }
    private void updatePlayback() {
        long time = now();
        if (time >= nextPlaybackPoll) {
            nextPlaybackPoll = time + 50;
            boolean active;
            try {
                AudioManager manager = getSystemService(AudioManager.class);
                // Global media is only a conservative guard, never proof of Doubao's identity.
                active = manager == null || manager.isMusicActive();
            } catch (RuntimeException e) { active = true; }
            active |= screen.foreground == DoubaoWindowInspector.Foreground.DOUBAO
                    && time - screen.at < 600 && screen.playing;
            if (active != playbackActive) log("playback=" + active);
            playbackActive = active;
        }
        playbackGate.update(time, playbackActive, allowInterruption, echoStatus != null && echoStatus.ready());
    }
    private boolean unlocked() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private boolean targetCovered() {
        if (overlay == null || screen.target == null) return false;
        int[] location = new int[2];
        overlay.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + overlay.getWidth(),
                location[1] + overlay.getHeight()).contains(screen.target.centerX(), screen.target.centerY());
    }

    private void scanWindow() {
        if (scanning || destroyed) return;
        scanning = true;
        lastScan = now();
        long revision = windowRevision;
        windowWorker.execute(() -> {
            long queryStarted = now();
            DoubaoWindowInspector.Snapshot result;
            try { result = new DoubaoWindowInspector().read(this); }
            catch (RuntimeException e) { result = DoubaoWindowInspector.Snapshot.unknown(); }
            DoubaoWindowInspector.Snapshot snapshot = new DoubaoWindowInspector.Snapshot(queryStarted,
                    result.foreground, result.target, result.recording, result.playing);
            main.post(() -> {
                scanning = false;
                if (destroyed || revision != windowRevision) return;
                screen = snapshot;
                if (snapshot.foreground == DoubaoWindowInspector.Foreground.DOUBAO) lastKnownForeground = snapshot.at;
            });
        });
    }

    private void dispatchStroke(long token, boolean first, boolean finish) {
        if (first) {
            updatePlayback();
            if (!playbackGate.allowsGesture(observationOnly, allowsAudio())
                    || screen.target == null || screen.recording || targetCovered()) {
                hold.rejected(token, now(), true); return;
            }
            holdX = screen.target.centerX();
            holdY = screen.target.centerY();
            gestureStarted = now();
        }
        try {
            if (first == finish) throw new IllegalArgumentException("Only press or release is allowed");
            if (first) pressGesture = new PressGesture();
            GestureDescription gesture = first ? pressGesture.press(holdX, holdY) : pressGesture.release();
            log("gesture request token=" + token + " action=" + (first ? "DOWN" : "UP"));
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    log("gesture completed token=" + token);
                    hold.result(token, true, now());
                }
                @Override public void onCancelled(GestureDescription description) {
                    log("gesture cancelled token=" + token);
                    hold.result(token, false, now());
                }
            }, main);
            if (!accepted) {
                log("gesture rejected token=" + token);
                hold.rejected(token, now(), first);
            }
        } catch (RuntimeException e) {
            log("手势异常 " + e.getClass().getSimpleName());
            hold.rejected(token, now(), first);
        }
    }

    private void render() {
        if (status == null) return;
        status.setText(message + (tracking ? String.format(Locale.CHINA,
                "\n音量 %.2f%%  起 %.2f%% / 止 %.2f%%\n%s", measuredRms * 100,
                detector.onset * 100, detector.ending * 100,
                echoStatus == null ? "回声消除初始化中" : echoStatus.label()) : ""));
        startButton.setEnabled(!tracking && (hold == null || !hold.busy()));
        stopButton.setEnabled(tracking || hold != null && hold.busy());
    }
    private void log(String value) {
        diagnostics.addLast(now() + " " + value);
        while (diagnostics.size() > 300) diagnostics.removeFirst();
    }
    private long now() { return SystemClock.elapsedRealtime(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setMinWidth(0); button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(background(color));
        return button;
    }
    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(8));
        return drawable;
    }
}
