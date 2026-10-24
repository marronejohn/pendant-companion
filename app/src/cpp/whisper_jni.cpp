#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "PendantWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Handle-based contexts: nativeInit returns its own whisper_context, so the
// batch can run several model instances in parallel (one per worker thread).
// 0 means the model file failed to load.
extern "C" JNIEXPORT jlong JNICALL
Java_com_pendantcompanion_protocol_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU only, no vendor GPU dependency
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("whisper init %s", ctx ? "ok" : "failed");
    return (jlong) (intptr_t) ctx;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pendantcompanion_protocol_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject,
                                                                  jlong handle, jfloatArray samples, jint threads) {
    whisper_context *ctx = (whisper_context *) (intptr_t) handle;
    if (!ctx) return nullptr;
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = threads > 0 ? threads : 4;
    wparams.language = "en";
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.no_context = true;
    wparams.single_segment = false;
    bool ok = whisper_full(ctx, wparams, data, n) == 0;
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (!ok) return nullptr;
    std::string out;
    int nseg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nseg; i++) {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i); // centiseconds
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (!text) continue;
        long sec = (long)(t0 / 100);
        char stamp[16];
        snprintf(stamp, sizeof(stamp), "[%02ld:%02ld] ", sec / 60, sec % 60);
        out += stamp;
        out += text;
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pendantcompanion_protocol_WhisperBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    whisper_context *ctx = (whisper_context *) (intptr_t) handle;
    if (ctx) whisper_free(ctx);
}
