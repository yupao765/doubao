package com.codex.doubaomictracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GestureIntegrationTest {
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private GestureProbeActivity activity;
    private GestureProbeService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        shell("settings put secure enabled_accessibility_services com.codex.doubaomictracker/.GestureProbeService");
        shell("settings put secure accessibility_enabled 1");
        long until = SystemClock.elapsedRealtime() + 10000;
        while (GestureProbeService.instance == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
        service = GestureProbeService.instance;
        assertNotNull("Probe accessibility service must connect", service);
        Intent intent = new Intent(instrumentation.getTargetContext(), GestureProbeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (GestureProbeActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(500);
        GestureProbeActivity.actions.clear();
        GestureProbeActivity.times.clear();
    }

    @After public void tearDown() throws Exception {
        // Disconnecting the test service lets Android cancel any outstanding injected pointer.
        shell("settings put secure enabled_accessibility_services null");
        if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
    }

    @Test public void oldStationaryContinuationIsRejectedByAndroid() throws Exception {
        Path path = new Path();
        path.moveTo(200, 400);
        GestureDescription.StrokeDescription down = new GestureDescription.StrokeDescription(path, 0, 100, true);
        assertTrue(dispatch(new GestureDescription.Builder().addStroke(down).build()));
        GestureDescription.StrokeDescription empty = down.continueStroke(path, 0, 100, true);
        assertFalse("Old keepalive has no DOWN, MOVE or UP event", dispatch(
                new GestureDescription.Builder().addStroke(empty).build()));
        // Android has already advanced the stroke id even though it rejected an empty event list.
        assertTrue(dispatch(new GestureDescription.Builder()
                .addStroke(empty.continueStroke(path, 0, 1, false)).build()));
        assertEquals(1, count(MotionEvent.ACTION_DOWN));
        assertEquals(1, count(MotionEvent.ACTION_UP));
    }

    @Test public void productionControllerHoldsWithoutRetriggerAndReleases() throws Exception {
        runConversation(2000);
        assertEquals(1, count(MotionEvent.ACTION_DOWN));
        assertEquals(1, count(MotionEvent.ACTION_UP));
        assertEquals(0, count(MotionEvent.ACTION_CANCEL));
        assertTrue(GestureProbeActivity.times.get(GestureProbeActivity.times.size() - 1)
                - GestureProbeActivity.times.get(0) >= 1800);
    }

    @Test public void productionControllerCanRepeatThreeConversations() throws Exception {
        for (int i = 0; i < 3; i++) runConversation(700);
        assertEquals(3, count(MotionEvent.ACTION_DOWN));
        assertEquals(3, count(MotionEvent.ACTION_UP));
        assertEquals(0, count(MotionEvent.ACTION_CANCEL));
    }

    private void runConversation(long duration) throws Exception {
        PressGesture gesture = new PressGesture();
        HoldController[] controller = new HoldController[1];
        CountDownLatch released = new CountDownLatch(1);
        boolean[] failed = {false};
        instrumentation.runOnMainSync(() -> {
            controller[0] = new HoldController(new HoldController.Driver() {
                public void changed(String message) { }
                public void dispatch(long token, boolean first, boolean finish) {
                    assertTrue("Only DOWN and UP are valid", first != finish);
                    GestureDescription description = first ? gesture.press(200, 400) : gesture.release();
                    boolean accepted = service.dispatchGesture(description, new AccessibilityService.GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription completed) {
                            controller[0].result(token, true, SystemClock.elapsedRealtime());
                            if (finish) released.countDown();
                        }
                        @Override public void onCancelled(GestureDescription cancelled) {
                            failed[0] = true;
                            controller[0].result(token, false, SystemClock.elapsedRealtime());
                            released.countDown();
                        }
                    }, main);
                    if (!accepted) {
                        failed[0] = true;
                        controller[0].rejected(token, SystemClock.elapsedRealtime(), first);
                        released.countDown();
                    }
                }
            });
            controller[0].update(SystemClock.elapsedRealtime(), true, true, true, false);
        });
        long until = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < until) {
            instrumentation.runOnMainSync(() -> controller[0].update(SystemClock.elapsedRealtime(), true, true, true, false));
            SystemClock.sleep(40);
        }
        instrumentation.runOnMainSync(() -> controller[0].update(SystemClock.elapsedRealtime(), false, true, true, false));
        assertTrue("UP callback must arrive", released.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        assertFalse("No rejected or cancelled gesture", failed[0]);
        instrumentation.runOnMainSync(() -> controller[0].update(SystemClock.elapsedRealtime(), false, true, true, true));
        assertEquals(HoldController.State.IDLE, controller[0].state);
    }

    private boolean dispatch(GestureDescription gesture) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] result = {false};
        instrumentation.runOnMainSync(() -> {
            boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) { result[0] = true; done.countDown(); }
                @Override public void onCancelled(GestureDescription description) { done.countDown(); }
            }, main);
            if (!accepted) done.countDown();
        });
        assertTrue("Gesture callback must arrive", done.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        return result[0];
    }
    private long count(int action) {
        return GestureProbeActivity.actions.stream().filter(value -> value == action).count();
    }
    private void shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }
}
