#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <speex/speex_echo.h>
#include <speex/speex_preprocess.h>

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *preprocess;
} Processor;

JNIEXPORT jlong JNICALL Java_com_codex_doubaomictracker_SoftwareEcho_nativeCreate(JNIEnv *env, jclass cls) {
    Processor *p = calloc(1, sizeof(*p));
    if (!p) return 0;
    p->echo = speex_echo_state_init(320, 8000);
    p->preprocess = speex_preprocess_state_init(320, 16000);
    if (!p->echo || !p->preprocess) {
        if (p->echo) speex_echo_state_destroy(p->echo);
        if (p->preprocess) speex_preprocess_state_destroy(p->preprocess);
        free(p);
        return 0;
    }
    int sample_rate = 16000, disabled = 0, suppression = -40, active_suppression = -15;
    speex_echo_ctl(p->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &sample_rate);
    speex_preprocess_ctl(p->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, p->echo);
    speex_preprocess_ctl(p->preprocess, SPEEX_PREPROCESS_SET_AGC, &disabled);
    speex_preprocess_ctl(p->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &disabled);
    speex_preprocess_ctl(p->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &suppression);
    speex_preprocess_ctl(p->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &active_suppression);
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL Java_com_codex_doubaomictracker_SoftwareEcho_nativeProcess(
        JNIEnv *env, jclass cls, jlong handle, jshortArray microphone, jshortArray reference, jshortArray output) {
    Processor *p = (Processor *)(intptr_t)handle;
    spx_int16_t mic[320], far[320], result[320];
    (*env)->GetShortArrayRegion(env, microphone, 0, 320, mic);
    (*env)->GetShortArrayRegion(env, reference, 0, 320, far);
    if ((*env)->ExceptionCheck(env)) return;
    speex_echo_cancellation(p->echo, mic, far, result);
    speex_preprocess_run(p->preprocess, result);
    (*env)->SetShortArrayRegion(env, output, 0, 320, result);
}

JNIEXPORT void JNICALL Java_com_codex_doubaomictracker_SoftwareEcho_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    Processor *p = (Processor *)(intptr_t)handle;
    speex_preprocess_state_destroy(p->preprocess);
    speex_echo_state_destroy(p->echo);
    free(p);
}
