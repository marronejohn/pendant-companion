package com.pendantcompanion.protocol

/**
 * JNI bridge to libopus 1.5.2 (xiph, BSD-3-Clause), linked into the same
 * native library as whisper. This is the decode engine for pendant audio:
 * the platform MediaCodec OPUS decoder was observed consuming pendant
 * packets and producing no output and no error, while libopus decodes the
 * same bytes cleanly, so audio goes through libopus directly.
 */
object OpusNative {
    init { System.loadLibrary("pendantwhisper") }

    /** Returns a decoder handle, or 0 when creation failed. */
    external fun nativeCreate(sampleRate: Int, channels: Int): Long

    /** Returns decoded mono PCM samples, or null when the packet is rejected. */
    external fun nativeDecode(handle: Long, packet: ByteArray): ShortArray?

    external fun nativeFree(handle: Long)
}
