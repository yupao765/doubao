package com.codex.doubaomictracker;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

final class DoubaoWindowInspector {
    static final String PACKAGE = "com.larus.nova";
    enum Foreground { DOUBAO, OTHER, UNKNOWN }
    static final class Snapshot {
        final long at;
        final Foreground foreground;
        final Rect target;
        final boolean recording;
        final boolean playing;
        Snapshot(Foreground foreground, Rect target, boolean recording, boolean playing) {
            this(SystemClock.elapsedRealtime(), foreground, target, recording, playing);
        }
        Snapshot(long at, Foreground foreground, Rect target, boolean recording, boolean playing) {
            this.at = at;
            this.foreground = foreground;
            this.target = target;
            this.recording = recording;
            this.playing = playing;
        }
        static Snapshot unknown() { return new Snapshot(Foreground.UNKNOWN, null, false, false); }
    }
    private Rect target;
    private boolean recording;
    private boolean playing;
    private int visited;

    Snapshot read(AccessibilityService service) {
        target = null;
        recording = false;
        playing = false;
        visited = 0;
        List<AccessibilityWindowInfo> windows = service.getWindows();
        AccessibilityWindowInfo top = null;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                        && (window.isActive() || window.isFocused()))
                    return new Snapshot(Foreground.OTHER, null, false, false);
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && (top == null || window.getLayer() > top.getLayer())) top = window;
            }
            AccessibilityNodeInfo root = top != null ? top.getRoot() : service.getRootInActiveWindow();
            if (root == null) return Snapshot.unknown();
            try {
                String owner = text(root.getPackageName());
                if (!PACKAGE.equals(owner)) {
                    return new Snapshot(owner.isEmpty() || owner.equals(service.getPackageName())
                            ? Foreground.UNKNOWN : Foreground.OTHER, null, false, false);
                }
                Rect rootBounds = new Rect();
                root.getBoundsInScreen(rootBounds);
                scan(root, rootBounds, 0);
                return new Snapshot(Foreground.DOUBAO, target, recording, playing);
            } finally { root.recycle(); }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private void scan(AccessibilityNodeInfo node, Rect window, int depth) {
        if (++visited > 1200 || depth > 45) return;
        String value = (text(node.getText()) + text(node.getContentDescription())).replace(" ", "");
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && !bounds.isEmpty()) {
            if ((value.contains("松手发送") || value.contains("松开发送")
                    || value.contains("上移取消")) && bounds.centerY() > window.centerY()) recording = true;
            if (node.isClickable() && (playbackLabel(text(node.getText()))
                    || playbackLabel(text(node.getContentDescription())))) playing = true;
            if (node.isEnabled() && (value.equals("按住说话") || value.equals("按住说话按住说话"))
                    && bounds.centerY() > window.centerY()
                    && (target == null || area(bounds) < area(target))) target = new Rect(bounds);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { scan(child, window, depth + 1); } finally { child.recycle(); }
            }
        }
    }
    private long area(Rect bounds) { return (long) bounds.width() * bounds.height(); }
    private boolean playbackLabel(String text) {
        return text.equals("停止播放") || text.equals("暂停播放") || text.equals("停止朗读")
                || text.equals("暂停朗读") || text.equals("停止播报") || text.equals("暂停");
    }
    private String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }
}
