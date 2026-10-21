package com.pendantcompanion.protocol

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Raw Opus packet -> 16-bit mono PCM WAV, using the platform OPUS decoder
 * (MediaCodec in async mode). No native dependencies, no network. Packets are
 * the bare Opus packets carried inside flash-page audio-data fields (TOC 0xb8,
 * CELT wideband, 20 ms). Async mode feeds and drains continuously, which is
 * orders of magnitude faster than one packet per sync round-trip.
 *
 * Resilience: the stream is decoded in chunks, each with its own codec
 * instance. One malformed frame can raise a fatal CodecException; a chunk
 * that dies is skipped and counted, and decoding continues with the next
 * chunk, so a bad stretch costs at most ~10 s of audio instead of the whole
 * export or playback.
 */
object OpusDecoder {
    const val SAMPLE_RATE = 48000
    const val FRAME_MS = 20

    /** Packets per decode chunk (512 x 20 ms = ~10 s of audio). */
    private const val CHUNK = 512

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
     * cooperative cancel check. Returns decoded sample count. [onSkipped]
     * reports each skipped packet range with the codec's failure reason.
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
        FileOutputStream(tmp).use { fos ->
            fos.write(ByteArray(44)) // WAV header patched in place after decoding
            var start = 0
            while (start < packets.size) {
                if (!keepGoing()) break
                val end = minOf(start + CHUNK, packets.size)
                val (bytes, failure) = decodeChunkInto(packets.subList(start, end), fos, keepGoing)
                written += bytes // a failed chunk's partial PCM is real audio up to the failure point
                if (failure == null) {
                    // chunk fully decoded
                } else {
                    skipped += end - start
                    if (firstFailure == null) firstFailure = failure
                    onSkipped(end - start, failure)
                }
                onProgress(end, packets.size)
                start = end
            }
        }
        if (!keepGoing()) throw IllegalStateException("decode cancelled")
        if (written == 0L) {
            throw IllegalStateException(
                "decode failed: ${firstFailure ?: "unknown"} (no decodable audio in ${packets.size} packets)")
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
        return dataSize / 2
    }

    /**
     * Decodes one chunk into [fos] with a dedicated codec instance. Returns
     * the bytes written, plus the failure reason when the chunk died (a
     * CodecException is usually fatal to the codec instance).
     */
    private fun decodeChunkInto(
        chunk: List<ByteArray>,
        fos: FileOutputStream,
        keepGoing: () -> Boolean,
    ): Pair<Long, String?> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val failure = AtomicReference<String?>(null)
        val eosQueued = AtomicBoolean(false)
        val feedingDone = AtomicBoolean(false)
        val queue = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()
        val writtenBytes = AtomicLong(0L)
        val latch = CountDownLatch(1)
        try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                    try {
                        if (failure.get() != null) return
                        val item = queue.poll()
                        if (item == null) {
                            if (feedingDone.get() && eosQueued.compareAndSet(false, true)) {
                                val ib = c.getInputBuffer(index)!!
                                ib.clear()
                                c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            return
                        }
                        val (i, p) = item
                        val ib = c.getInputBuffer(index)!!
                        ib.clear()
                        ib.put(p)
                        c.queueInputBuffer(index, 0, p.size, i.toLong() * 20_000L, 0)
                    } catch (e: Exception) {
                        failure.compareAndSet(null, "input: ${e.message}")
                        latch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val ob = c.getOutputBuffer(index)!!
                        if (info.size > 0) {
                            val out = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(out)
                            synchronized(fos) { fos.write(out) }
                            writtenBytes.addAndGet(info.size.toLong())
                        }
                        c.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) latch.countDown()
                    } catch (e: Exception) {
                        failure.compareAndSet(null, "output: ${e.message}")
                        latch.countDown()
                    }
                }

                override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {}

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                    failure.compareAndSet(null, e.message ?: "codec error")
                    latch.countDown()
                }
            })
            codec.configure(format, null, null, 0)
            codec.start()
            chunk.forEachIndexed { i, p -> queue.add(i to p) }
            feedingDone.set(true)
            // await with cancel + stall watchdog
            var last = -1L
            var stallTicks = 0
            while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                if (!keepGoing()) {
                    failure.compareAndSet(null, "cancelled")
                    latch.countDown()
                    break
                }
                val w = writtenBytes.get()
                stallTicks = if (w == last) stallTicks + 1 else 0
                if (stallTicks > 150) { // ~30 s with no output at all
                    failure.compareAndSet(null, "decoder stalled")
                    latch.countDown()
                    break
                }
                last = w
            }
            val fail = failure.get()
            if (fail != null) return writtenBytes.get() to fail
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        return writtenBytes.get() to null
    }
}
