package com.codex.doubaomictracker;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

/** A single DOWN followed by a single continuation containing UP, with no keepalives. */
final class PressGesture {
    private GestureDescription.StrokeDescription pressedStroke;
    private float x;
    private float y;
    private boolean releaseCreated;

    GestureDescription press(float x, float y) {
        if (pressedStroke != null) throw new IllegalStateException("Press already created");
        this.x = x;
        this.y = y;
        Path path = point();
        pressedStroke = new GestureDescription.StrokeDescription(path, 0, 1, true);
        return new GestureDescription.Builder().addStroke(pressedStroke).build();
    }

    GestureDescription release() {
        if (pressedStroke == null || releaseCreated)
            throw new IllegalStateException("No unreleased press");
        releaseCreated = true;
        return new GestureDescription.Builder()
                .addStroke(pressedStroke.continueStroke(point(), 0, 1, false)).build();
    }

    private Path point() {
        Path path = new Path();
        path.moveTo(x, y);
        return path;
    }
}
