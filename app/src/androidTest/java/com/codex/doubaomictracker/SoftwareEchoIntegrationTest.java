package com.codex.doubaomictracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SoftwareEchoIntegrationTest {
    @Test public void cancelsSyntheticEchoAndRetainsIndependentNearEndEnergy() {
        Random random = new Random(42);
        short[] history = new short[1024];
        short[] far = new short[320], mic = new short[320], clean = new short[320];
        int sample = 0;
        double echoInput = 0, echoOutput = 0, nearOutput = 0, nearInput = 0;
        try (SoftwareEcho echo = new SoftwareEcho()) {
            for (int frame = 0; frame < 650; frame++) {
                for (int i = 0; i < 320; i++, sample++) {
                    far[i] = (short) (random.nextInt(16001) - 8000);
                    history[sample % history.length] = far[i];
                    double delayed = sample >= 64 ? history[(sample - 64) % history.length] * .55 : 0;
                    double near = frame >= 500 ? 4500 * Math.sin(sample * 2 * Math.PI * 181 / 16000)
                            + 2000 * Math.sin(sample * 2 * Math.PI * 362 / 16000) : 0;
                    mic[i] = (short) (delayed + near);
                    if (frame >= 550) nearInput += near * near;
                }
                echo.process(mic, far, clean);
                for (int i = 0; i < 320; i++) {
                    if (frame >= 400 && frame < 500) { echoInput += (double) mic[i] * mic[i]; echoOutput += (double) clean[i] * clean[i]; }
                    if (frame >= 550) nearOutput += (double) clean[i] * clean[i];
                }
            }
        }
        assertTrue("Synthetic echo power must drop: " + echoOutput / echoInput, echoOutput < echoInput * .2);
        assertTrue("Independent near-end energy must remain: " + nearOutput / nearInput, nearOutput > nearInput * .08);
    }
    @Test public void closedProcessorRejectsCallsAndCanCloseTwice() {
        SoftwareEcho echo = new SoftwareEcho();
        echo.close();
        echo.close();
        try { echo.process(new short[320], new short[320], new short[320]); fail("closed processor"); }
        catch (IllegalStateException expected) { }
    }
}
