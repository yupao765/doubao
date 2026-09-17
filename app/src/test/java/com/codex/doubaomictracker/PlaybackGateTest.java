package com.codex.doubaomictracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackGateTest {
    @Test public void quietRoomDoesNotRequireAec() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, false, true, false);
        assertFalse(gate.blocked);
    }

    @Test public void playbackWithoutAecNeverAllowsInterruption() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, false);
        gate.update(60000, true, true, false);
        assertTrue(gate.blocked);
    }

    @Test public void aecAllowsInterruptionAfterStartupSettles() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(349, true, true, true);
        assertTrue(gate.blocked);
        gate.update(350, true, true, true);
        assertFalse(gate.blocked);
    }

    @Test public void safeModeBlocksEvenWithAec() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, false, true);
        gate.update(10000, true, false, true);
        assertTrue(gate.blocked);
    }

    @Test public void lossOfAecImmediatelyBlocksPlayback() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(400, true, true, true);
        assertFalse(gate.blocked);
        gate.update(420, true, true, false);
        assertTrue(gate.blocked);
    }

    @Test public void playbackTailExpiresWithoutFurtherAudioFrames() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(1000, false, true, true);
        gate.update(1799, false, true, true);
        assertTrue(gate.blocked);
        gate.update(1800, false, true, true);
        assertFalse(gate.blocked);
    }

    @Test public void playbackRestartGetsNewSettlingWindow() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(1000, false, true, true);
        gate.update(1100, true, true, true);
        gate.update(1300, true, true, true);
        assertTrue(gate.blocked);
        gate.update(1350, true, true, true);
        assertFalse(gate.blocked);
    }

    @Test public void observationAndBackgroundNeverPermitGestures() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, false, true, true);
        assertFalse(gate.allowsGesture(true, true));
        assertFalse(gate.allowsGesture(false, false));
        assertTrue(gate.allowsGesture(false, true));
        gate.update(200, true, true, false);
        assertFalse(gate.allowsGesture(false, true));
        assertFalse(gate.allowsContinuation(true, true));
        assertFalse(gate.allowsContinuation(false, false));
    }

    @Test public void playbackStoppingOnDownDoesNotReleaseUserSpeech() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(400, true, true, true);
        assertTrue(gate.allowsGesture(false, true));
        gate.update(420, false, true, true);
        assertFalse("New presses wait for the tail", gate.allowsGesture(false, true));
        assertTrue("Existing press continues", gate.allowsContinuation(false, true));
        assertFalse(gate.continuationBlocked);
    }

    @Test public void losingEchoDuringPlaybackBlocksExistingPressToo() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(100, true, true, true);
        gate.update(400, true, true, false);
        assertFalse(gate.allowsContinuation(false, true));
    }

    @Test public void normalModeNeverUnlocksJustBecauseEchoIsEnabled() {
        PlaybackGate gate = new PlaybackGate();
        for (long time = 0; time < 60000; time += 20) {
            gate.update(time, true, false, true);
            assertFalse("No timeout may authorize a press during playback", gate.allowsGesture(false, true));
        }
    }

    @Test public void shortPlaybackGapsCannotArmNewPress() {
        PlaybackGate gate = new PlaybackGate();
        gate.update(0, true, false, true);
        gate.update(1000, false, false, true);
        gate.update(1600, false, false, true);
        assertFalse(gate.allowsGesture(false, true));
        gate.update(1700, true, false, true);
        gate.update(2000, false, false, true);
        gate.update(2799, false, false, true);
        assertFalse(gate.allowsGesture(false, true));
        gate.update(2800, false, false, true);
        assertTrue(gate.allowsGesture(false, true));
    }
}
