package com.codex.doubaomictracker;

import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class HoldControllerTest {
    static class Command {
        long token; boolean first, finish;
        Command(long token, boolean first, boolean finish) {
            this.token = token; this.first = first; this.finish = finish;
        }
    }
    private final ArrayList<Command> commands = new ArrayList<>();
    private final HoldController controller = new HoldController(new HoldController.Driver() {
        public void dispatch(long token, boolean first, boolean finish) { commands.add(new Command(token, first, finish)); }
        public void changed(String message) { }
    });
    private Command last() { return commands.get(commands.size() - 1); }
    private void start() { controller.update(0, true, true, true, false); }

    @Test public void rejectedPressRetriesWhileVoiceRemainsActive() {
        start();
        controller.rejected(last().token, 10, true);
        controller.update(500, true, true, true, false);
        assertEquals(1, commands.size());
        controller.update(610, true, true, true, false);
        assertEquals(2, commands.size());
        assertTrue(last().first);
    }
    @Test public void speechEndClosesOriginalStroke() {
        start();
        controller.update(30, false, true, true, false);
        controller.result(last().token, true, 100);
        assertTrue(last().finish);
        assertFalse(last().first);
        controller.result(last().token, true, 101);
        assertEquals(HoldController.State.VERIFYING, controller.state);
        controller.update(120, false, true, true, true);
        assertEquals(HoldController.State.IDLE, controller.state);
    }
    @Test public void nextUtteranceWaitsForReleaseThenStarts() {
        start();
        controller.update(30, false, true, true, false);
        controller.result(last().token, true, 100);
        long release = last().token;
        controller.update(101, true, true, true, false);
        assertEquals(2, commands.size());
        controller.result(release, true, 102);
        controller.update(110, true, true, true, true);
        controller.update(210, true, true, true, true);
        assertEquals(3, commands.size());
        assertTrue(last().first);
        controller.result(release, true, 220);
        assertEquals(HoldController.State.HOLDING, controller.state);
        assertEquals(3, commands.size());
    }
    @Test public void oldSessionCallbackIsIgnored() {
        start();
        long old = last().token;
        controller.reset();
        controller.update(10, true, true, true, false);
        controller.result(old, false, 20);
        assertEquals(HoldController.State.HOLDING, controller.state);
        assertEquals(2, commands.size());
    }
    @Test public void missingCallbackHasBoundedContinuationRecovery() {
        start();
        controller.update(800, true, true, true, false);
        assertEquals(2, commands.size());
        assertFalse(last().first);
        assertTrue(last().finish);
        controller.update(1600, true, true, true, false);
        assertEquals(HoldController.State.FAILED, controller.state);
        assertEquals(2, commands.size());
    }
    @Test public void repeatedStopCannotKeepPushingTimeoutBack() {
        start();
        for (int at = 10; at <= 1800; at += 10)
            controller.update(at, false, false, false, false);
        assertEquals(HoldController.State.FAILED, controller.state);
        assertEquals(2, commands.size());
    }
    @Test public void failedVerificationStopsNewPresses() {
        start();
        controller.update(20, false, true, true, false);
        controller.result(last().token, true, 100);
        controller.result(last().token, true, 101);
        controller.update(1700, true, true, true, false);
        assertEquals(HoldController.State.FAILED, controller.state);
        assertEquals(2, commands.size());
    }
    @Test public void backgroundNeverStartsAndEndsExistingPress() {
        controller.update(0, true, false, false, false);
        assertTrue(commands.isEmpty());
        start();
        controller.update(20, true, false, false, false);
        controller.result(last().token, true, 100);
        assertTrue(last().finish);
    }
    @Test public void threeRejectedStartsProduceVisibleFailure() {
        for (int i = 0; i < 3; i++) {
            controller.update(i * 1000, true, true, true, false);
            controller.rejected(last().token, i * 1000 + 1, true);
        }
        assertEquals(HoldController.State.FAILED, controller.state);
    }
    @Test public void cancelledGestureMustBeVerifiedBeforeRetry() {
        start();
        controller.result(last().token, false, 100);
        controller.update(120, true, true, true, false);
        assertEquals(1, commands.size());
        assertEquals(HoldController.State.VERIFYING, controller.state);
        controller.update(150, true, true, true, true);
        controller.update(250, true, true, true, true);
        assertEquals(2, commands.size());
    }
}
