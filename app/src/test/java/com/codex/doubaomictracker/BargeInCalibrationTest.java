package com.codex.doubaomictracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class BargeInCalibrationTest {
    @Test public void silenceNeverAuthorizesBargeIn() {
        BargeInCalibration calibration = new BargeInCalibration();
        for (int i = 0; i < 1000; i++) assertFalse(calibration.update(i * 20L, true, 0, .02f, .001f));
    }
    @Test public void unalignedFramesNeverAuthorizeBargeIn() {
        BargeInCalibration calibration = new BargeInCalibration();
        for (int i = 0; i < 1000; i++) assertFalse(calibration.update(i * 20L, false, .2f, .02f, .001f));
    }
    @Test public void unattenuatedPlaybackNeverAuthorizesBargeIn() {
        BargeInCalibration calibration = new BargeInCalibration();
        for (int i = 0; i < 1000; i++) assertFalse(calibration.update(i * 20L, true, .2f, .02f, .019f));
    }
    @Test public void requiresWarmupAndMeasuredReduction() {
        BargeInCalibration calibration = new BargeInCalibration();
        for (int i = 0; i < 49; i++) assertFalse(calibration.update(i * 20L, true, .2f, .02f, .001f));
        assertTrue(calibration.update(980, true, .2f, .02f, .001f));
        assertTrue("Near-end speech must not undo learned echo", calibration.update(1000, true, .2f, .05f, .04f));
    }
    @Test public void captureLossRevokesReadinessAndRequiresNewWarmup() {
        BargeInCalibration calibration = new BargeInCalibration();
        for (int i = 0; i < 60; i++) calibration.update(i * 20L, true, .2f, .02f, .001f);
        assertFalse(calibration.update(1500, false, 0, .02f, .02f));
        assertFalse(calibration.update(1520, true, .2f, .02f, .001f));
    }
}
