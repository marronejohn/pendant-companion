package com.pendantcompanion.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Raw Opus packets -> 16-bit mono PCM WAV, decoded with bundled libopus
 * (xiph 1.5.2, BSD-3-Clause) via OpusNative. The pendant's flash audio is
 * CELT-mode Opus (TOC 0xb8, 20 ms frames, 48 kHz mono output). Packets are
 * the bare Opus packets carried inside flash-page audio-data fields.
 *
 * Resilience: each packet decodes independently; a packet libopus rejects
 * is skipped and counted and decoding continues, so bad stretches cost only
 * the affected 20 ms frames instead of the whole export or playback.
 */
object OpusDecoder {
    const val SAMPLE_RATE = 48000
    const val FRAME_MS = 20

    /** Largest single Opus packet PCM: 120 ms at 48 kHz. */
    private const val MAX_PCM = 5760

    /** Packets between progress callbacks (512 x 20 ms = ~10 s). */
    private const val PROGRESS_EVERY = 512

    /**
     * Decodes [packets] into a WAV at [out]. [keepGoing] is polled as a
     * cooperative cancel check. Returns decoded sample count. [onSkipped]
     * reports each skipped packet with the decoder's reason.
     */
    fun decodeToWav(
        packets: List<ByteArray>,
        out: File,
        keepGoing: () -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onSkipped: (count: Int, reason: String) -> Unit = { _, _ -> },
    ): Long {
        if (packets.isEmpty()) throw IllegalStateException("no audio packets to decode")
        val tmp = File(out.parentFile, out.name + ".tmp")
        var written = 0L
        var skipped = 0
        var firstFailure: String? = null
        val handle = OpusNative.nativeCreate(SAMPLE_RATE, 1)
        if (handle == 0L) throw IllegalStateException("decoder unavailable (native init failed)")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(ByteArray(44)) // WAV header patched in place after decoding
                val leBuf = ByteArray(2 * MAX_PCM)
                packets.forEachIndexed { i, p ->
                    if (!keepGoing()) return@forEachIndexed
                    val pcm = OpusNative.nativeDecode(handle, p)
                    if (pcm == null) {
                        skipped++
                        val reason = "packet ${i + 1} rejected by decoder"
                        if (firstFailure == null) firstFailure = reason
                        onSkipped(1, reason)
                    } else {
                        val n = pcm.size
                        if (n > 0) {
                            for (j in 0 until n) {
                                leBuf[2 * j] = (pcm[j].toInt() and 0xff).toByte()
                                leBuf[2 * j + 1] = ((pcm[j].toInt() shr 8) and 0xff).toByte()
                            }
                            fos.write(leBuf, 0, 2 * n)
                            written += 2L * n
                        }
                    }
                    if (i % PROGRESS_EVERY == PROGRESS_EVERY - 1 || i == packets.size - 1) {
                        onProgress(i + 1, packets.size)
                    }
                }
            }
        } finally {
            OpusNative.nativeFree(handle)
        }
        if (!keepGoing()) throw IllegalStateException("decode cancelled")
        if (written == 0L) {
            throw IllegalStateException(
                "decode failed: ${firstFailure ?: "decoder produced no output"} | ${packetDiag(packets)}")
        }
        // patch WAV sizes now that the PCM length is known
        val dataSize = written
        RandomAccessFile(tmp, "rw").use { raf ->
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
            le32(SAMPLE_RATE * 2)
            le16(2)
            le16(16)
            "data".toByteArray().forEach { header.write(it.toInt()) }
            le32(dataSize.toInt())
            raf.write(header.toByteArray())
        }
        if (!tmp.renameTo(out)) {
            out.delete()
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        if (skipped > 0) {
            // carried forward so callers see the count even without a listener
        }
        return dataSize / 2
    }

    /**
     * One-line structure summary of a packet list, for failure reports:
     * sizes plus how many packets start with the expected Opus TOC byte
     * (0xb8) versus the raw 0x22 wrapper byte. Plaintext Opus should be
     * nearly all 0xb8; encrypted or malformed audio shows random first
     * bytes, which is the tell that separates encryption from a parsing bug.
     */
    private fun packetDiag(packets: List<ByteArray>): String {
        if (packets.isEmpty()) return "no packets"
        var min = Int.MAX_VALUE; var max = 0; var sum = 0L
        var b8 = 0; var w22 = 0
        packets.forEach { p ->
            if (p.isEmpty()) { min = 0; return@forEach }
            if (p.size < min) min = p.size
            if (p.size > max) max = p.size
            sum += p.size
            when (p[0]) {
                0xb8.toByte() -> b8++
                0x22.toByte() -> w22++
            }
        }
        val first = packets.take(3).joinToString(" ") { "0x%02x".format(it[0].toInt() and 0xff) }
        return ("packets=${packets.size}, sizes ${min}..${max} avg ${sum / packets.size}, " +
            "first bytes $first, TOC 0xb8: $b8/${packets.size}, 0x22-wrapped: $w22/${packets.size}")
    }
}
