package com.codex.doubaomictracker;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class DoubaoAccessibilityService extends AccessibilityService {
    private static final String CHANNEL_ID = "doubao_mic_tracker";
    private static final int NOTIFICATION_ID = 2301;
    private static final long HOLD_CHUNK_MS = 300L;
    private static final long PLAYBACK_GUARD_MS = 900L;
    private static final String DOUBAO_PACKAGE = "com.larus.nova";
    private static DoubaoAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable forceReleaseRunnable = this::forceReleaseIfStillHeld;
    private AudioManager audioManager;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private View overlayView;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private VoiceTracker voiceTracker;
    private boolean tracking;
    private volatile String foregroundPackage = "";
    private volatile boolean doubaoForeground;
    private final Runnable foregroundRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshForegroundState();
            if (tracking) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };
    private long lastPlaybackAt;
    private long lastVolumeStatusAt;
    private GestureDescription.StrokeDescription activeStroke;
    private boolean holding;
    private boolean holdWanted;
    private boolean gestureInFlight;
    private float holdX;
    private float holdY;

    public static boolean isRunning() {
        return instance != null;
    }

    public static DoubaoAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        startBasicForeground();
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
        }
        if (tracking) {
            refreshForegroundState();
            if (!isDoubaoForeground()) {
                releaseHold();
                setWaitingForDoubaoStatus();
            }
        }
    }

    @Override
    public void onInterrupt() {
        releaseHold();
    }

    @Override
    public void onDestroy() {
        stopTracking(false);
        removeOverlay();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    public void showOverlay() {
        mainHandler.post(() -> {
            if (overlayView != null) {
                return;
            }
            installOverlay();
        });
    }

    public void releaseHold() {
        mainHandler.post(() -> {
            holdWanted = false;
            if (holding) {
                setStatus("正在松开发送");
                if (!gestureInFlight) {
                    dispatchReleaseStroke();
                }
            }
            mainHandler.removeCallbacks(forceReleaseRunnable);
            mainHandler.postDelayed(forceReleaseRunnable, 1500L);
        });
    }

    private void installOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(8), dp(8), dp(8), dp(8));
        container.setBackground(makeBackground(0xEE111827, dp(10)));

        statusView = new TextView(this);
        statusView.setText("待启动 · " + formatTrackerSettings());
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(4), 0, dp(4), dp(6));
        container.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        startButton = makeOverlayButton("启用麦克风跟踪", true);
        stopButton = makeOverlayButton("结束跟踪", false);
        stopButton.setEnabled(false);
        startButton.setOnClickListener(v -> startTracking());
        stopButton.setOnClickListener(v -> stopTracking(true));

        row.addView(startButton);
        row.addView(stopButton);
        container.addView(row);
        container.setOnTouchListener(new DragTouchListener());

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(12);
        overlayParams.y = dp(110);

        overlayView = container;
        windowManager.addView(overlayView, overlayParams);
    }

    private void removeOverlay() {
        if (overlayView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(overlayView);
        } catch (IllegalArgumentException ignored) {
        }
        overlayView = null;
    }

    private Button makeOverlayButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(primary ? Color.WHITE : 0xFF111827);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(makeBackground(primary ? 0xFF176BFF : 0xFFE5E7EB, dp(8)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(42));
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void startTracking() {
        if (tracking) {
            return;
        }
        if (!hasAudioPermission()) {
            Toast.makeText(this, "缺少麦克风权限，请回到应用里授权", Toast.LENGTH_LONG).show();
            return;
        }
        if (!promoteForegroundForMicrophone()) {
            return;
        }

        voiceTracker = new VoiceTracker(this, new VoiceTracker.Listener() {
            @Override
            public void onVoiceStarted() {
                mainHandler.post(() -> {
                    setStatus("检测到人声，按住中 · " + formatTrackerSettings());
                    beginHoldOnMain();
                });
            }

            @Override
            public void onSilence() {
                releaseHold();
            }

            @Override
            public void onSuppressedByPlayback() {
                if (isDoubaoForeground()) {
                    setStatus("豆包播放中，暂停触发");
                } else {
                    setWaitingForDoubaoStatus();
                }
            }

            @Override
            public void onEndingCountdown(float rms, float endingThreshold, long remainingMs) {
                if (!isDoubaoForeground()) {
                    return;
                }
                setStatus(
                        "检测说完中 · 音量 " + formatPercent(rms)
                                + " < " + formatPercent(endingThreshold)
                                + " · " + formatSeconds(remainingMs) + "后松手"
                );
            }

            @Override
            public void onVolumeLevel(float rms, float threshold, boolean speaking, boolean suppressed) {
                long now = System.currentTimeMillis();
                if (now - lastVolumeStatusAt < 350L) {
                    return;
                }
                lastVolumeStatusAt = now;
                if (!isDoubaoForeground()) {
                    setWaitingForDoubaoStatus();
                } else if (suppressed) {
                    setStatus("播放中暂停 · 音量 " + formatPercent(rms));
                } else if (speaking) {
                    setStatus("人声 " + formatPercent(rms) + "，按住中 · " + formatEndingSettings());
                } else {
                    setStatus("音量 " + formatPercent(rms) + " / 触发 " + formatPercent(threshold)
                            + " · " + formatEndingSettings());
                }
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    Toast.makeText(DoubaoAccessibilityService.this, message, Toast.LENGTH_LONG).show();
                    stopTracking(true);
                });
            }
        }, this::shouldSuppressVoice);
        voiceTracker.start();
        tracking = true;
        updateButtons();
        refreshForegroundState();
        mainHandler.removeCallbacks(foregroundRefreshRunnable);
        mainHandler.postDelayed(foregroundRefreshRunnable, 500L);
        if (isDoubaoForeground()) {
            setListeningStatus();
        } else {
            setWaitingForDoubaoStatus();
        }
        Toast.makeText(this, "已开始监听人声", Toast.LENGTH_SHORT).show();
    }

    private void stopTracking(boolean keepBasicForeground) {
        tracking = false;
        mainHandler.removeCallbacks(foregroundRefreshRunnable);
        if (voiceTracker != null) {
            voiceTracker.stop();
            voiceTracker = null;
        }
        releaseHold();
        if (keepBasicForeground) {
            startBasicForeground();
        }
        updateButtons();
        setStatus("已停止");
    }

    private void beginHoldOnMain() {
        refreshForegroundState();
        if (!isDoubaoForeground()) {
            holdWanted = false;
            setWaitingForDoubaoStatus();
            return;
        }
        holdWanted = true;
        if (holding) {
            return;
        }

        TargetPoint target = findDoubaoPressTarget();
        holdX = target.x;
        holdY = target.y;
        holding = true;
        activeStroke = null;
        dispatchHoldTick();
    }

    private void dispatchHoldTick() {
        if (!holding || !holdWanted || gestureInFlight) {
            return;
        }

        Path path = new Path();
        path.moveTo(holdX, holdY);

        GestureDescription.StrokeDescription stroke;
        if (activeStroke == null) {
            stroke = new GestureDescription.StrokeDescription(path, 0, HOLD_CHUNK_MS, true);
        } else {
            stroke = activeStroke.continueStroke(path, 0, HOLD_CHUNK_MS, true);
        }
        activeStroke = stroke;

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        gestureInFlight = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                mainHandler.post(() -> {
                    gestureInFlight = false;
                    if (!holding) {
                        return;
                    }
                    if (holdWanted) {
                        dispatchHoldTick();
                    } else {
                        dispatchReleaseStroke();
                    }
                });
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                mainHandler.post(() -> {
                    gestureInFlight = false;
                    resetHoldState();
                });
            }
        }, mainHandler);

        if (!accepted) {
            resetHoldState();
            return;
        }
    }

    private void dispatchReleaseStroke() {
        if (!holding || holdWanted || gestureInFlight) {
            return;
        }

        if (activeStroke == null) {
            resetHoldState();
            return;
        }

        Path path = new Path();
        path.moveTo(holdX, holdY);
        GestureDescription.StrokeDescription stroke = activeStroke.continueStroke(path, 0, 80L, false);
        activeStroke = stroke;

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        gestureInFlight = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                mainHandler.post(DoubaoAccessibilityService.this::resetHoldState);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                mainHandler.post(() -> {
                    gestureInFlight = false;
                    forceReleaseIfStillHeld();
                });
            }
        }, mainHandler);

        if (!accepted) {
            forceReleaseIfStillHeld();
            return;
        }
        mainHandler.postDelayed(forceReleaseRunnable, 900L);
    }

    private void resetHoldState() {
        mainHandler.removeCallbacks(forceReleaseRunnable);
        boolean wasHolding = holding;
        holding = false;
        holdWanted = false;
        gestureInFlight = false;
        activeStroke = null;
        if (tracking) {
            if (wasHolding && isDoubaoForeground()) {
                setStatus("已松开发送");
                mainHandler.postDelayed(() -> {
                    if (tracking && !holding && isDoubaoForeground()) {
                        setListeningStatus();
                    }
                }, 450L);
            } else if (isDoubaoForeground()) {
                setListeningStatus();
            } else {
                setWaitingForDoubaoStatus();
            }
        }
    }

    private void forceReleaseIfStillHeld() {
        if (!holding || holdWanted) {
            return;
        }
        gestureInFlight = false;
        Path path = new Path();
        path.moveTo(holdX, holdY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40L, false))
                .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                mainHandler.post(DoubaoAccessibilityService.this::resetHoldState);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                mainHandler.post(DoubaoAccessibilityService.this::resetHoldState);
            }
        }, mainHandler);
        if (!accepted) {
            resetHoldState();
        }
    }

    private boolean shouldSuppressVoice() {
        long now = System.currentTimeMillis();
        if (!isDoubaoForeground()) {
            lastPlaybackAt = 0L;
            return true;
        }
        if (audioManager != null && audioManager.isMusicActive()) {
            lastPlaybackAt = now;
            return true;
        }
        return now - lastPlaybackAt < PLAYBACK_GUARD_MS;
    }

    private TargetPoint findDoubaoPressTarget() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        ScreenSize screen = getScreenSize();
        Candidate best = null;
        if (root != null) {
            best = findBestCandidate(root, screen, null);
        }
        if (best == null) {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo windowRoot = window.getRoot();
                best = findBestCandidate(windowRoot, screen, best);
                if (best != null) {
                    break;
                }
            }
        }
        if (best != null) {
            return new TargetPoint(best.bounds.centerX(), best.bounds.centerY());
        }
        return new TargetPoint(screen.width / 2f, screen.height - dp(78));
    }

    private Candidate findBestCandidate(AccessibilityNodeInfo node, ScreenSize screen, Candidate best) {
        if (node == null) {
            return best;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String text = safeText(node.getText()) + " " + safeText(node.getContentDescription());
        String compact = text.replace(" ", "");

        int score = 0;
        if (compact.contains("按住说话")) {
            score += 120;
        }
        if (compact.contains("按住") && compact.contains("说话")) {
            score += 100;
        }
        if (compact.contains("说话") && bounds.centerY() > screen.height * 0.65f) {
            score += 55;
        }
        if (node.isClickable() || node.isLongClickable()) {
            score += 12;
        }
        if (bounds.centerY() > screen.height * 0.75f) {
            score += 24;
        }
        if (bounds.width() > screen.width * 0.45f) {
            score += 18;
        }
        if (!node.isVisibleToUser() || bounds.isEmpty()) {
            score = 0;
        }

        if (score >= 80 && (best == null || score > best.score)) {
            best = new Candidate(score, new Rect(bounds));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            best = findBestCandidate(node.getChild(i), screen, best);
        }
        return best;
    }

    private boolean promoteForegroundForMicrophone() {
        Notification notification = buildNotification("正在监听人声，检测到说话会按住豆包按钮");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Toast.makeText(this, "系统阻止后台麦克风启动，请打开本应用后再点启用", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void startBasicForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("悬浮按钮已开启"));
        } catch (SecurityException | IllegalStateException ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("豆包语音跟随")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "豆包语音跟随",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void updateButtons() {
        mainHandler.post(() -> {
            if (startButton != null) {
                startButton.setEnabled(!tracking);
            }
            if (stopButton != null) {
                stopButton.setEnabled(tracking);
            }
        });
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            if (statusView != null) {
                statusView.setText(text);
            }
        });
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private GradientDrawable makeBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private String safeText(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean isDoubaoForeground() {
        return doubaoForeground;
    }

    private void refreshForegroundState() {
        boolean found = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            foregroundPackage = root.getPackageName().toString();
            found = isDoubaoRoot(root);
        }
        if (!found) {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (!window.isActive() && !window.isFocused()) {
                    continue;
                }
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) {
                    continue;
                }
                if (windowRoot.getPackageName() != null && window.isActive()) {
                    foregroundPackage = windowRoot.getPackageName().toString();
                }
                if (isDoubaoRoot(windowRoot)) {
                    if (windowRoot.getPackageName() != null) {
                        foregroundPackage = windowRoot.getPackageName().toString();
                    }
                    found = true;
                    break;
                }
            }
        }
        doubaoForeground = found;
    }

    private boolean isDoubaoRoot(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        String packageName = safeText(root.getPackageName());
        if (DOUBAO_PACKAGE.equals(packageName)) {
            return true;
        }
        DoubaoMarker marker = scanDoubaoMarker(root, new DoubaoMarker(), 0);
        return marker.hasPressToTalk && marker.hasAiGeneratedHint;
    }

    private DoubaoMarker scanDoubaoMarker(AccessibilityNodeInfo node, DoubaoMarker marker, int depth) {
        if (node == null || depth > 80 || (marker.hasPressToTalk && marker.hasAiGeneratedHint)) {
            return marker;
        }
        String compact = (safeText(node.getText()) + safeText(node.getContentDescription())).replace(" ", "");
        if (compact.contains("按住说话") || (compact.contains("按住") && compact.contains("说话"))) {
            marker.hasPressToTalk = true;
        }
        if (compact.contains("内容由AI生成")
                || compact.contains("AI生成")
                || compact.contains("豆包")
                || compact.contains("豆包P")
                || compact.contains("视频通话")
                || compact.contains("打电话")) {
            marker.hasAiGeneratedHint = true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            scanDoubaoMarker(node.getChild(i), marker, depth + 1);
        }
        return marker;
    }

    private void setWaitingForDoubaoStatus() {
        String suffix = foregroundPackage == null || foregroundPackage.isEmpty()
                ? ""
                : " " + foregroundPackage;
        setStatus("等待豆包前台" + suffix);
    }

    private void setListeningStatus() {
        setStatus("监听中 · " + formatTrackerSettings());
    }

    private String formatPercent(float value) {
        float percent = Math.max(0f, Math.min(99f, value * 100f));
        return String.format(Locale.CHINA, "%.1f%%", percent);
    }

    private String formatSeconds(long milliseconds) {
        return String.format(Locale.CHINA, "%.1f秒", Math.max(0L, milliseconds) / 1000f);
    }

    private String formatTrackerSettings() {
        return "灵敏度 " + TrackerSettings.getSensitivity(this) + " · " + formatEndingSettings();
    }

    private String formatEndingSettings() {
        float endingPercent = TrackerSettings.getEndingVolumeTenthsPercent(this) / 10f;
        float releaseSeconds = TrackerSettings.getReleaseDelayMs(this) / 1000f;
        String mode = TrackerSettings.isAutomaticEndingEnabled(this) ? "自动结束≥" : "结束<";
        return String.format(Locale.CHINA, "%s%.1f%% 持续%.1f秒", mode, endingPercent, releaseSeconds);
    }

    private ScreenSize getScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager manager = getSystemService(WindowManager.class);
            if (manager != null) {
                Rect bounds = manager.getCurrentWindowMetrics().getBounds();
                return new ScreenSize(bounds.width(), bounds.height());
            }
        }
        return new ScreenSize(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float downX;
        private float downY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (overlayParams == null || windowManager == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = overlayParams.x;
                    initialY = overlayParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = initialX - Math.round(event.getRawX() - downX);
                    overlayParams.y = initialY + Math.round(event.getRawY() - downY);
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    return true;
                default:
                    return false;
            }
        }
    }

    private static final class TargetPoint {
        final float x;
        final float y;

        TargetPoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class ScreenSize {
        final int width;
        final int height;

        ScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class Candidate {
        final int score;
        final Rect bounds;

        Candidate(int score, Rect bounds) {
            this.score = score;
            this.bounds = bounds;
        }
    }

    private static final class DoubaoMarker {
        boolean hasPressToTalk;
        boolean hasAiGeneratedHint;
    }
}