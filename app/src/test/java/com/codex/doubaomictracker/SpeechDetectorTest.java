package com.codex.doubaomictracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechDetectorTest {
    private final SpeechDetector detector = new SpeechDetector();
    private long time;
    private void frames(int count, float rms, boolean vad, int delay) {
        for (int i = 0; i < count; i++) {
            time += 20;
            detector.accept(time, rms, vad, 0.0034f, 2.04f, 0.006f, delay, true);
        }
    }
    @Test public void quietVoiceStartsWithoutOldHighThreshold() {
        frames(4, 0.005f, true, 400);
        assertTrue(detector.speaking);
    }
    @Test public void singleImpulseDoesNotStart() {
        frames(1, 0.05f, true, 400);
        frames(10, 0.0005f, false, 400);
        assertFalse(detector.speaking);
    }
    @Test public void noiseAboveOldEndingThresholdStillEnds() {
        frames(10, 0.03f, true, 400);
        frames(21, 0.007f, false, 400);
        assertFalse(detector.speaking);
        assertTrue(detector.noise > 0.0005f);
    }
    @Test public void quietVoiceTailCancelsSilenceCountdown() {
        frames(10, 0.02f, true, 400);
        frames(15, 0.0005f, false, 400);
        frames(4, 0.005f, true, 400);
        assertTrue(detector.speaking);
        assertEquals(-1, detector.remaining(time));
    }
    @Test public void releaseDurationIsRespected() {
        frames(10, 0.02f, true, 1200);
        frames(40, 0.0005f, false, 1200);
        assertTrue(detector.speaking);
        frames(21, 0.0005f, false, 1200);
        assertFalse(detector.speaking);
    }
    @Test public void timerCanFinishWithoutAnotherVoiceFrame() {
        frames(10, 0.02f, true, 200);
        frames(1, 0, false, 200);
        detector.tick(time + 200);
        assertFalse(detector.speaking);
    }
    @Test public void invertedUserSettingsAreClamped() {
        detector.accept(20, 0.01f, true, 0.002f, 2, 0.03f, 200, false);
        assertTrue(detector.ending < detector.onset);
        assertEquals(0.0016f, detector.ending, 0.00001f);
    }
    @Test public void maximumHoldRequiresQuietBeforeRestart() {
        frames(1520, 0.03f, true, 400);
        assertFalse(detector.speaking);
        assertTrue(detector.awaitingQuiet);
        frames(50, 0.03f, true, 400);
        assertFalse(detector.speaking);
        frames(11, 0.0005f, false, 400);
        frames(4, 0.03f, true, 400);
        assertTrue(detector.speaking);
    }
    @Test public void oldFramesDoNotRestartOrCancelSpeech() {
        frames(4, 0.02f, true, 400);
        detector.accept(20, 0, false, 0.0034f, 2, 0.006f, 400, true);
        assertTrue(detector.speaking);
        assertEquals(-1, detector.quietSince);
    }
    @Test public void brokenStreamDoesNotJoinUnrelatedSpeech() {
        frames(4, 0.02f, true, 400);
        time += 500;
        frames(1, 0.02f, true, 400);
        assertFalse(detector.speaking);
    }
}
