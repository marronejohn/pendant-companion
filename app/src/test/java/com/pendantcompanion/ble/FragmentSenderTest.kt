package com.pendantcompanion.ble

import com.google.protobuf.ByteString
import limitless.common.Common
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentSenderTest {

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun budgetFollowsTheVerifiedRule() {
        assertEquals(470, FragmentSender(498).payloadBudget())
        assertEquals(20, FragmentSender(23).payloadBudget())
        assertTrue(FragmentSender(100).payloadBudget() in 21..85)
    }

    @Test
    fun fragmentsReassembleToOriginalPayload() {
        val sender = FragmentSender(498)
        val data = payload(1200)
        val frags = sender.fragments(3, data)
        assertEquals(3, frags.size)
        frags.forEachIndexed { i, f ->
            assertEquals(3, f.index)
            assertEquals(i, f.bleFragmentSeq)
            assertEquals(3, f.numFragments)
        }
        val asm = FragmentAssembler()
        var complete: ByteArray? = null
        for (f in frags) {
            val r = asm.offer(f.toInbound())
            if (r is FragmentAssembler.Result.Complete) complete = r.payload
        }
        assertEquals(data.toList(), complete!!.toList())
    }

    @Test
    fun smallPayloadStaysSingleFragment() {
        val frags = FragmentSender(498).fragments(1, byteArrayOf(1, 2, 3))
        assertEquals(1, frags.size)
        assertEquals(3, frags[0].payload.size())
    }

    // FragmentSender emits BLEMessageFromNativeToPendant; the assembler consumes
    // the pendant-to-native twin. They share the same wire shape, so simulate it.
    private fun Common.BLEMessageFromNativeToPendant.toInbound() =
        Common.BLEMessageFromPendantToNative.newBuilder()
            .setIndex(index).setBleFragmentSeq(bleFragmentSeq).setNumFragments(numFragments)
            .setPayload(ByteString.copyFrom(payload.toByteArray()))
            .build()
}
