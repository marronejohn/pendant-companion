#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "PendantWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static whisper_context *g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pendantcompanion_protocol_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return JNI_FALSE;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU only, no vendor GPU dependency
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("whisper init %s", g_ctx ? "ok" : "failed");
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pendantcompanion_protocol_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject,
                                                                  jfloatArray samples, jint threads) {
    if (!g_ctx) return nullptr;
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
    bool ok = whisper_full(g_ctx, wparams, data, n) == 0;
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (!ok) return nullptr;
    std::string out;
    int nseg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nseg; i++) {
        int64_t t0 = whisper_full_get_segment_t0(g_ctx, i); // centiseconds
        const char *text = whisper_full_get_segment_text(g_ctx, i);
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
Java_com_pendantcompanion_protocol_WhisperBridge_nativeFree(JNIEnv*, jobject) {
    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }
}
