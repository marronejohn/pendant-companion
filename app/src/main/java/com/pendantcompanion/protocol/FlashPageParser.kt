package com.pendantcompanion.protocol

import java.io.ByteArrayOutputStream

/**
 * Flash-page extraction, mirroring the verified wire codec
 * (official app LimitlessProtocol.kt, unit-tested against its golden fixtures).
 *
 * A flash page payload = optional field-1 varint (pendant-clock timestamp_ms),
 * then 0x1a audio wrappers each containing 0x12 audio-data fields; each
 * audio-data content is a nested protobuf whose field 4 (tag 0x22) is the bare
 * Opus packet (verified against hardware flash: TOC uniformly 0xb8, CELT WB).
 * Audio pages that yield zero frames are treated as parse failures upstream; here
 * we surface counts so the UI can show what happened without ever acknowledging
 * (acknowledging is what frees/deletes pages, and phase 3 never acknowledges).
 */
object FlashPageParser {

    data class FlashPage(
        val ingestType: Int,
        val session: Int,
        val run: Int,
        val seq: Long,
        val index: Int,
        val timestampMs: Long,
        val opusFrames: List<ByteArray>,
        val rawPageBytes: ByteArray,
    )

    private class Reader(private val d: ByteArray) {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (pos < d.size) {
                val b = d[pos].toInt() and 0xFF; pos++
                result = result or ((b.toLong() and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            val out = d.copyOfRange(pos, (pos + len).coerceAtMost(d.size)); pos += len
            return out
        }
    }

    private fun varintAt(d: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var p = pos
        while (p < d.size) {
            val b = d[p].toInt() and 0xFF; p++
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }

    /** Pendant-clock timestamp: field 1 (0x08) varint at the page start. */
    fun timestampMs(page: ByteArray): Long =
        if (page.isNotEmpty() && page[0].toInt() == 0x08) varintAt(page, 1).first else 0L

    fun hasAudioSubfields(page: ByteArray): Boolean {
        // any 0x1a wrapper in the body counts as an audio-bearing page
        var p = 0
        if (p < page.size && page[p].toInt() == 0x08) { p++; p = varintAt(page, p).second }
        if (p < page.size && page[p].toInt() == 0x10) { p++; p = varintAt(page, p).second }
        while (p < page.size - 2) {
            if (page[p].toInt() == 0x1a) return true
            p++
        }
        return false
    }

    /** Walk 0x1a audio wrappers (each containing 0x12 audio-data fields) collecting Opus frames. */
    fun extractOpusFrames(page: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var p = 0
        if (p < page.size && page[p].toInt() == 0x08) { p++; p = varintAt(page, p).second }
        if (p < page.size && page[p].toInt() == 0x10) { p++; p = varintAt(page, p).second }

        while (p < page.size - 2) {
            if (page[p].toInt() != 0x1a) { p++; continue }
            p++
            val (wrapperLen, afterLen) = varintAt(page, p); p = afterLen
            val wrapperEnd = (p + wrapperLen.toInt()).coerceAtMost(page.size)
            // inside the wrapper: repeated field 2 (0x12) = audio data
            while (p + 1 < wrapperEnd) {
                val tag = page[p].toInt(); p++
                if (tag == 0x12) {
                    val (len, after) = varintAt(page, p); p = after
                    if (len > 0 && p + len.toInt() <= wrapperEnd) {
                        frames.add(unwrapOpus(page.copyOfRange(p, p + len.toInt())))
                    }
                    p += len.toInt()
                } else if (tag == 0x1a) {
                    // nested wrapper (recursive), skip past its length to continue at same level
                    val (l2, a2) = varintAt(page, p); p = a2
                    // treat nested wrapper contents as same-level audio-data walk
                } else if (tag and 0x07 == 2) {
                    val (l3, a3) = varintAt(page, p); p = a3 + l3.toInt()
                } else if (tag and 0x07 == 0) {
                    val (_, a4) = varintAt(page, p); p = a4
                } else {
                    break
                }
            }
            p = wrapperEnd
        }
        return frames
    }

    private fun unwrapOpus(content: ByteArray): ByteArray {
        if (content.size >= 2 && content[0].toInt() == 0x22) {
            val (l, q) = varintAt(content, 1)
            if (q + l.toInt() == content.size) return content.copyOfRange(q, q + l.toInt())
        }
        return content
    }

    fun fromStorageBuffer(ingestType: Int, session: Int, run: Int, seq: Long,
                          index: Int, page: ByteArray): FlashPage = FlashPage(
        ingestType = ingestType, session = session, run = run, seq = seq, index = index,
        timestampMs = timestampMs(page), opusFrames = extractOpusFrames(page), rawPageBytes = page,
    )
}
