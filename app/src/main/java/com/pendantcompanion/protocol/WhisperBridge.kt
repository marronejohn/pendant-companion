package com.pendantcompanion.protocol

/**
 * JNI bridge to whisper.cpp (ggml-org/whisper.cpp v1.7.5, MIT), built for
 * arm64-v8a, CPU only. Audio in: 16 kHz mono float. Model: a ggml .bin the
 * user imports through the file picker (the app has no INTERNET permission,
 * so models never download themselves).
 */
object WhisperBridge {
    init { System.loadLibrary("pendantwhisper") }

    external fun nativeInit(modelPath: String): Boolean
    external fun nativeTranscribe(samples: FloatArray, threads: Int): String?
    external fun nativeFree()
}
