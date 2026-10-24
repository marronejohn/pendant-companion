package com.pendantcompanion.protocol

/**
 * JNI bridge to whisper.cpp (ggml-org/whisper.cpp v1.7.5, MIT), built for
 * arm64-v8a, CPU only. Audio in: 16 kHz mono float. Model: a ggml .bin the
 * user imports through the file picker (the app has no INTERNET permission,
 * so models never download themselves).
 */
object WhisperBridge {
    init { System.loadLibrary("pendantwhisper") }

    /**
     * Loads a ggml model and returns an independent context handle (0 on
     * failure). Several handles can run Whisper passes concurrently, which
     * is what the parallel batch uses; each handle is freed with [nativeFree].
     */
    external fun nativeInit(modelPath: String): Long
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int): String?
    external fun nativeFree(handle: Long)
}
