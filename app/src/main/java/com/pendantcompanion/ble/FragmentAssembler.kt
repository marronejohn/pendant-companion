package com.pendantcompanion.ble

import com.google.protobuf.InvalidProtocolBufferException
import limitless.common.BLEMessageFromPendantToNative

/**
 * Reassembles BLEMessageFromPendantToNative fragments into complete payloads.
 *
 * Pendant-side framing (verified against MAkcanca/pendant-cli, which verified
 * against the pendant firmware): each fragment is a
 * BLEMessageFromPendantToNative { index=1, ble_fragment_seq=2 (0..N-1),
 * num_fragments=3, payload=4 }. Fragments of one message share the same
 * `index`; the message is complete when ble_fragment_seq + 1 == num_fragments.
 * Out-of-order fragments are buffered; malformed input is rejected, never
 * guessed at.
 */
class FragmentAssembler(private val maxPending: Int = 8) {

    sealed interface Result {
        /** A complete payload; all fragments of that message were consumed. */
        data class Complete(val payload: ByteArray) : Result
        /** Waiting on more fragments of this message. */
        object Partial : Result
        /** The fragment is malformed or belongs to an impossible sequence. */
        data class Rejected(val reason: String) : Result
    }

    private class Pending {
        val parts = HashMap<Int, ByteArray>()
        var numFragments = -1
        val isComplete: Boolean get() = numFragments > 0 && parts.size == numFragments
        fun payload(): ByteArray = (0 until numFragments).joinToByteArray(parts)
    }

    private val pending = HashMap<Int, Pending>()

    fun offer(msg: BLEMessageFromPendantToNative): Result {
        val index = msg.index
        val seq = msg.bleFragmentSeq
        val num = msg.numFragments
        if (num < 1) return Result.Rejected("num_fragments=$num must be >= 1")
        if (seq < 0 || seq >= num) return Result.Rejected("ble_fragment_seq=$seq outside 0..${num - 1}")
        val known = pending[index]
        if (known != null && known.numFragments != -1 && known.numFragments != num) {
            return Result.Rejected("num_fragments changed for index $index: ${known.numFragments} -> $num")
        }
        val p = known ?: Pending().also {
            pending[index] = it
            if (pending.size > maxPending) dropOldest()
        }
        if (p.parts.containsKey(seq)) return Result.Rejected("duplicate fragment seq=$seq for index $index")
        p.numFragments = num
        p.parts[seq] = msg.payload.toByteArray()
        return if (p.isComplete) {
            val payload = p.payload()
            pending.remove(index)
            Result.Complete(payload)
        } else Result.Partial
    }

    private fun dropOldest() {
        val oldest = pending.keys.firstOrNull() ?: return
        pending.remove(oldest)
    }

    fun reset() = pending.clear()

    private fun Collection<Int>.joinToByteArray(parts: Map<Int, ByteArray>): ByteArray {
        val buffers = map { parts[it]!! }
        val total = buffers.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (b in buffers) { b.copyInto(out, pos); pos += b.size }
        return out
    }

    /** Decode a reassembled payload as protobuf, for callers that want the typed message. */
    fun decodeComplete(payload: ByteArray): BLEMessageFromPendantToNative? =
        try { BLEMessageFromPendantToNative.parseFrom(payload) } catch (e: InvalidProtocolBufferException) { null }
}
