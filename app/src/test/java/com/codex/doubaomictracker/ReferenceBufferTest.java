package com.codex.doubaomictracker;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ReferenceBufferTest {
    @Test public void missingOrStaleReferenceReturnsZeros() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] output = new short[320];
        Arrays.fill(output, (short) 99);
        assertFalse(buffer.read(100000000, output));
        assertEquals(0, ReferenceBuffer.rms(output), 0);
        buffer.add(100000000, output);
        assertFalse(buffer.read(200000000, output));
    }
    @Test public void copiesExactFrameWithoutSharingStorage() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] pcm = new short[320];
        Arrays.fill(pcm, (short) 100);
        buffer.add(100000000, pcm);
        pcm[0] = 0;
        short[] result = new short[320];
        assertTrue(buffer.read(100000000, result));
        assertEquals(100, result[0]);
    }
    @Test public void assemblesSampleAlignedFrameAcrossCaptureBoundaries() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] pcm = new short[320];
        for (int i = 0; i < 320; i++) pcm[i] = (short) i;
        buffer.add(100000000, pcm);
        for (int i = 0; i < 320; i++) pcm[i] = (short) (i + 320);
        buffer.add(120000000, pcm);
        assertTrue(buffer.read(110000000, pcm));
        for (int i = 0; i < 320; i++) assertEquals(i + 160, pcm[i]);
    }
    @Test public void missingAdjacentFrameCannotInventAlignment() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] pcm = new short[320];
        Arrays.fill(pcm, (short) 100);
        buffer.add(100000000, pcm);
        assertFalse(buffer.read(90000000, pcm));
        assertEquals(0, ReferenceBuffer.rms(pcm), 0);
        assertFalse(buffer.read(110000000, pcm));
        buffer.add(140000000, pcm);
        assertFalse(buffer.read(130000000, pcm));
    }
    @Test public void overwritesBoundedTimelineAndClearsOnRevocation() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] pcm = new short[320];
        for (int i = 1; i <= 150; i++) buffer.add(i * 20000000L, pcm);
        assertFalse(buffer.read(20000000L, pcm));
        assertTrue(buffer.read(3000000000L, pcm));
        buffer.clear();
        assertFalse(buffer.read(3000000000L, pcm));
    }
    @Test public void oldTimestampCannotOverwriteCurrentReference() {
        ReferenceBuffer buffer = new ReferenceBuffer();
        short[] pcm = new short[320];
        Arrays.fill(pcm, (short) 7);
        buffer.add(100000000, pcm);
        Arrays.fill(pcm, (short) 9);
        buffer.add(90000000, pcm);
        assertTrue(buffer.read(100000000, pcm));
        assertEquals(7, pcm[0]);
    }
}
