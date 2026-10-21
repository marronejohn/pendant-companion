package com.pendantcompanion.ble

import limitless.common.Common
import org.junit.Assert.*
import org.junit.Test

class FragmentAssemblerTest {

    private fun frag(index: Int, seq: Int, num: Int, payload: ByteArray) =
        Common.BLEMessageFromPendantToNative.newBuilder()
            .setIndex(index).setBleFragmentSeq(seq).setNumFragments(num)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
            .build()

    @Test
    fun `single fragment message completes immediately`() {
        val asm = FragmentAssembler()
        val r = asm.offer(frag(1, 0, 1, byteArrayOf(1, 2, 3)))
        assertTrue(r is FragmentAssembler.Result.Complete)
        assertEquals(listOf<Byte>(1, 2, 3), (r as FragmentAssembler.Result.Complete).payload.toList())
    }

    @Test
    fun `out-of-order fragments reassemble in seq order`() {
        val asm = FragmentAssembler()
        val data = arrayOf(byteArrayOf(9), byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertTrue(asm.offer(frag(7, 2, 3, data[2])) is FragmentAssembler.Result.Partial)
        assertTrue(asm.offer(frag(7, 0, 3, data[0])) is FragmentAssembler.Result.Partial)
        val r = asm.offer(frag(7, 1, 3, data[1]))
        assertTrue(r is FragmentAssembler.Result.Complete)
        assertEquals((data[0] + data[1] + data[2]).toList(), (r as FragmentAssembler.Result.Complete).payload.toList())
    }

    @Test
    fun `duplicate fragment is rejected`() {
        val asm = FragmentAssembler()
        asm.offer(frag(1, 0, 2, byteArrayOf(1)))
        val r = asm.offer(frag(1, 0, 2, byteArrayOf(1)))
        assertTrue(r is FragmentAssembler.Result.Rejected)
    }

    @Test
    fun `seq outside range is rejected`() {
        val asm = FragmentAssembler()
        assertTrue(asm.offer(frag(1, 3, 2, byteArrayOf(1))) is FragmentAssembler.Result.Rejected)
        assertTrue(asm.offer(frag(1, 0, 0, byteArrayOf(1))) is FragmentAssembler.Result.Rejected)
    }

    @Test
    fun `num_fragments change for same index is rejected`() {
        val asm = FragmentAssembler()
        asm.offer(frag(1, 0, 3, byteArrayOf(1)))
        assertTrue(asm.offer(frag(1, 1, 2, byteArrayOf(1))) is FragmentAssembler.Result.Rejected)
    }

    @Test
    fun `interleaved messages do not cross-contaminate`() {
        val asm = FragmentAssembler()
        assertTrue(asm.offer(frag(1, 0, 2, byteArrayOf(1))) is FragmentAssembler.Result.Partial)
        assertTrue(asm.offer(frag(2, 0, 1, byteArrayOf(7))) is FragmentAssembler.Result.Complete)
        val r = asm.offer(frag(1, 1, 2, byteArrayOf(2)))
        assertTrue(r is FragmentAssembler.Result.Complete)
        assertEquals(listOf<Byte>(1, 2), (r as FragmentAssembler.Result.Complete).payload.toList())
    }
}
