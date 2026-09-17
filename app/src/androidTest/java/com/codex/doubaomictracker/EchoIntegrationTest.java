package com.codex.doubaomictracker;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Checks lifecycle/API compatibility only; an emulator cannot test speaker echo quality. */
@RunWith(AndroidJUnit4.class)
public class EchoIntegrationTest {
    @Test public void disabledProcessorIsNeverReady() {
        try (EchoProcessor processor = new EchoProcessor(false)) {
            assertFalse(processor.inspect(null).ready());
        }
    }

    @Test public void canAttachInspectAndReleaseOnRealRecordingSession() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        Activity activity = instrumentation.startActivitySync(new Intent(context, GestureProbeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            for (int round = 0; round < 2; round++) {
                AudioRecord recorder = null;
                EchoProcessor processor = new EchoProcessor(round == 0);
                try {
                    int buffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    AudioRecord.Builder builder = new AudioRecord.Builder()
                            .setAudioSource(processor.useCommunicationSource() && Build.VERSION.SDK_INT >= 30
                                    ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(5120, buffer));
                    if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
                    recorder = builder.build();
                    assertEquals(AudioRecord.STATE_INITIALIZED, recorder.getState());
                    processor.attach(recorder.getAudioSessionId());
                    recorder.startRecording();
                    assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.getRecordingState());
                    EchoStatus status = processor.inspect(recorder.getActiveRecordingConfiguration());
                    assertNotNull(status.diagnostic());
                    if (round == 1) assertFalse(status.ready());
                    assertFalse("No capture configuration must never authorize interruption", processor.inspect(null).ready());
                    recorder.stop();
                } finally {
                    processor.close();
                    processor.close();
                    if (recorder != null) recorder.release();
                }
            }
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
