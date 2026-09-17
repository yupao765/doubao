package com.codex.doubaomictracker;

import android.media.AudioDeviceInfo;
import android.media.AudioRecordingConfiguration;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;

/** Owned and released by the capture thread, before its AudioRecord is released. */
final class EchoProcessor implements AutoCloseable {
    private final boolean requested;
    private final boolean available;
    private AcousticEchoCanceler effect;
    private String setup = "";

    EchoProcessor(boolean requested) {
        this.requested = requested;
        boolean supported;
        try { supported = AcousticEchoCanceler.isAvailable(); }
        catch (RuntimeException e) { supported = false; setup = e.getClass().getSimpleName(); }
        available = supported;
    }

    // Keep the same source for on/off comparison; only change this session's AEC setting.
    boolean useCommunicationSource() { return available; }

    void attach(int session) {
        if (!available) return;
        try {
            effect = AcousticEchoCanceler.create(session);
            if (effect == null) { setup = "create=null"; return; }
            int result = effect.setEnabled(requested);
            setup = "setEnabled=" + result + " engine=" + effect.getDescriptor().name;
        } catch (RuntimeException e) { setup = "attach=" + e.getClass().getSimpleName(); }
    }

    EchoStatus inspect(AudioRecordingConfiguration config) {
        boolean enabled = false;
        boolean control = false;
        boolean stream = false;
        String detail = setup;
        try {
            if (effect != null) {
                enabled = effect.getEnabled();
                control = effect.hasControl();
            }
            if (config != null) {
                for (AudioEffect.Descriptor descriptor : config.getEffects()) {
                    if (AudioEffect.EFFECT_TYPE_AEC.equals(descriptor.type)) stream = true;
                }
                AudioDeviceInfo device = config.getAudioDevice();
                detail += " session=" + config.getClientAudioSessionId()
                        + " requestedSource=" + config.getClientAudioSource()
                        + " actualSource=" + config.getAudioSource()
                        + " inputType=" + (device == null ? -1 : device.getType())
                        + " muted=" + config.isClientSilenced();
            } else detail += " config=unavailable";
        } catch (RuntimeException e) {
            stream = false;
            detail += " inspect=" + e.getClass().getSimpleName();
        }
        return new EchoStatus(requested, available, enabled, control, stream, detail);
    }

    @Override public void close() {
        if (effect != null) {
            try { effect.release(); } catch (RuntimeException ignored) { }
            finally { effect = null; }
        }
    }
}
