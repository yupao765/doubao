package com.codex.doubaomictracker;

final class SoftwareEcho implements AutoCloseable {
    static { System.loadLibrary("doubao_echo"); }
    private long handle = nativeCreate();
    SoftwareEcho() {
        if (handle == 0) throw new IllegalStateException("回声处理器初始化失败");
    }
    synchronized void process(short[] microphone, short[] reference, short[] output) {
        if (handle == 0) throw new IllegalStateException("回声处理器已关闭");
        if (microphone.length != 320 || reference.length != 320 || output.length != 320)
            throw new IllegalArgumentException("Expected 20ms at 16kHz");
        nativeProcess(handle, microphone, reference, output);
    }
    @Override public synchronized void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    private static native long nativeCreate();
    private static native void nativeProcess(long handle, short[] microphone, short[] reference, short[] output);
    private static native void nativeDestroy(long handle);
}
