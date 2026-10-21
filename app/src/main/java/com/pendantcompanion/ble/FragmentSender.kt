package com.pendantcompanion.ble

import com.google.protobuf.ByteString
import limitless.common.Common

/**
 * Splits an outgoing payload into BLEMessageFromNativeToPendant fragments.
 *
 * Sizing follows the verified reference: at MTU 498 the official app sends
 * ~470-byte payloads, i.e. ATT overhead (3 bytes) plus a few wrapper bytes.
 * We use the same conservative budget and fall back gracefully at small MTUs.
 */
class FragmentSender(private val negotiatedMtu: Int) {

    fun payloadBudget(): Int = (negotiatedMtu - 3 - 12).coerceAtLeast(20).coerceAtMost(470)

    fun fragments(index: Int, payload: ByteArray): List<Common.BLEMessageFromNativeToPendant> {
        if (payload.isEmpty()) throw IllegalArgumentException("empty payload")
        val budget = payloadBudget()
        val chunks = payload.asList().chunked(budget).map { it.toByteArray() }
        val num = chunks.size
        return chunks.mapIndexed { seq, chunk ->
            Common.BLEMessageFromNativeToPendant.newBuilder()
                .setIndex(index)
                .setBleFragmentSeq(seq)
                .setNumFragments(num)
                .setPayload(ByteString.copyFrom(chunk))
                .build()
        }
    }
}
