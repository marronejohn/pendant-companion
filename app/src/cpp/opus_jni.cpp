#include <jni.h>
#include <android/log.h>
#include <opus.h>

#define TAG "PendantOpus"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// The largest one Opus packet can decode to: 120 ms at 48 kHz mono.
#define MAX_FRAME_SAMPLES 5760

extern "C" JNIEXPORT jlong JNICALL
Java_com_pendantcompanion_protocol_OpusNative_nativeCreate(JNIEnv *, jobject,
                                                           jint sampleRate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create((opus_int32)sampleRate, channels, &err);
    if (err != OPUS_OK || !dec) {
        LOGI("opus_decoder_create failed: %d", err);
        return 0;
    }
    return reinterpret_cast<jlong>(dec);
}

// Returns the decoded PCM as a short array, or null when the packet is
// rejected by the decoder (caller counts it as a skipped packet).
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_pendantcompanion_protocol_OpusNative_nativeDecode(JNIEnv *env, jobject,
                                                           jlong handle, jbyteArray packet) {
    OpusDecoder *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (!dec) return nullptr;
    jsize len = env->GetArrayLength(packet);
    if (len <= 0) return nullptr;
    jbyte *data = env->GetByteArrayElements(packet, nullptr);
    opus_int16 pcm[MAX_FRAME_SAMPLES];
    int n = opus_decode(dec, reinterpret_cast<const unsigned char *>(data), len, pcm,
                        MAX_FRAME_SAMPLES, 0);
    env->ReleaseByteArrayElements(packet, data, JNI_ABORT);
    if (n < 0) return nullptr;
    jshortArray out = env->NewShortArray(n);
    env->SetShortArrayRegion(out, 0, n, pcm);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pendantcompanion_protocol_OpusNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    OpusDecoder *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (dec) opus_decoder_destroy(dec);
}
