package com.codex.doubaomictracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class EchoStatusTest {
    @Test public void requiresEveryEngineAndStreamCondition() {
        for (int mask = 0; mask < 32; mask++) {
            EchoStatus status = new EchoStatus((mask & 1) != 0, (mask & 2) != 0,
                    (mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0, "test");
            assertEquals("mask=" + mask, mask == 31, status.ready());
        }
    }

    @Test public void enabledDoesNotClaimSuccessfulCancellation() {
        EchoStatus status = new EchoStatus(true, true, true, true, true, "source=7");
        assertTrue(status.label().contains("效果待实测"));
        assertTrue(status.diagnostic().contains("source=7"));
    }

    @Test public void unconfirmedStreamHasVisibleWarning() {
        EchoStatus status = new EchoStatus(true, true, true, true, false, "");
        assertFalse(status.ready());
        assertTrue(status.label().contains("待系统确认"));
    }
}
