package com.pendantcompanion.protocol

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Raw Opus packet -> 16-bit mono PCM WAV, using the platform OPUS decoder
 * (MediaCodec). No native dependencies, no network. Packets are the bare
 * Opus packets carried inside flash-page audio-data fields (TOC 0xb8,
 * CELT wideband, 20 ms).
 */
object OpusDecoder {
    const val SAMPLE_RATE = 48000
    const val FRAME_MS = 20

    /** Standard OpusHead identification header, 19 bytes, mono 48 kHz. */
    private fun opusHead(): ByteArray {
        val b = ByteArray(19)
        "OpusHead".toByteArray().copyInto(b, 0)
        b[8] = 1 // version
        b[9] = 1 // channel count
        // pre-skip = 0 (LE16)
        b[10] = 0; b[11] = 0
        // input sample rate 48000 (LE32)
        b[12] = 0x50.toByte(); b[13] = 0xBB.toByte(); b[14] = 0x00.toByte(); b[15] = 0x00.toByte()
        // output gain 0 (LE16), mapping family 0
        b[16] = 0; b[17] = 0; b[18] = 0
        return b
    }

    /**
     * Decodes [packets] into a WAV at [out]. [keepGoing] is polled as a
     * cooperative cancel check. Returns decoded sample count.
     */
    fun decodeToWav(
        packets: List<ByteArray>,
        out: File,
        keepGoing: () -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Long {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()
        val tmp = File(out.parentFile, out.name + ".tmp")
        var totalPcm = 0L
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(ByteArray(44)) // WAV header placeholder, patched below
                val info = MediaCodec.BufferInfo()
                var src = 0
                var eosQueued = false
                var sawEos = false
                while (!sawEos) {
                    if (keepGoing()) {
                        if (src < packets.size) {
                            val inIdx = codec.dequeueInputBuffer(5_000)
                            if (inIdx >= 0) {
                                val ib = codec.getInputBuffer(inIdx)!!
                                ib.clear()
                                val p = packets[src++]
                                ib.put(p)
                                codec.queueInputBuffer(inIdx, 0, p.size, src * 20_000L, 0)
                                if (src % 500 == 0) onProgress(src, packets.size)
                            }
                        } else if (!eosQueued) {
                            val inIdx = codec.dequeueInputBuffer(5_000)
                            if (inIdx >= 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            }
                        }
                    }
                    var outIdx = codec.dequeueOutputBuffer(info, 5_000)
                    while (outIdx >= 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(chunk)
                            fos.write(chunk)
                            totalPcm += info.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                        codec.releaseOutputBuffer(outIdx, false)
                        outIdx = codec.dequeueOutputBuffer(info, 0)
                    }
                    // -1 (no buffer yet) or INFO_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED: keep looping
                }
            }
            // patch WAV sizes now that the PCM length is known
            RandomAccessFile(tmp, "rw").use { raf ->
                val dataSize = totalPcm
                val byteRate = SAMPLE_RATE * 2 // mono, 16-bit
                raf.seek(0)
                val header = java.io.ByteArrayOutputStream()
                fun le16(v: Int) { header.write(v and 0xff); header.write((v shr 8) and 0xff) }
                fun le32(v: Int) { le16(v and 0xffff); le16((v shr 16) and 0xffff) }
                "RIFF".toByteArray().forEach { header.write(it.toInt()) }
                le32((36 + dataSize).toInt())
                "WAVE".toByteArray().forEach { header.write(it.toInt()) }
                "fmt ".toByteArray().forEach { header.write(it.toInt()) }
                le32(16)
                le16(1) // PCM
                le16(1) // mono
                le32(SAMPLE_RATE)
                le32(byteRate)
                le16(2) // block align
                le16(16) // bits per sample
                "data".toByteArray().forEach { header.write(it.toInt()) }
                le32(dataSize.toInt())
                raf.write(header.toByteArray())
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        return totalPcm / 2
    }
}
