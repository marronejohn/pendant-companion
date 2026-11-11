package com.pendantcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceClustererTest {
    private fun vec(base: Double, noise: Double = 0.0, seed: Int = 1): FloatArray {
        val v = FloatArray(4)
        for (i in v.indices) v[i] = (base + (if (i == seed % 4) noise else 0.0)).toFloat()
        return v
    }

    // A direction orthogonal to the all-ones reference, so its cosine to
    // anything near vec(1.0) is ~0 and it can never match by similarity.
    private fun ortho(base: Double, noise: Double = 0.0, seed: Int = 1): FloatArray {
        val v = FloatArray(4)
        for (i in v.indices) v[i] = ((if (i % 2 == 0) base else -base) +
            (if (i == seed % 4) noise else 0.0)).toFloat()
        return v
    }

    @Test fun knownVoiceMatchesAtThreshold() {
        val known = listOf(VoiceProfile(1, "Sam", emptyList(), 10, emptyList(), 0L, vec(1.0)))
        // window 0 same direction as the known voice, window 1 orthogonal
        val ids = VoiceClusterer.assign(known, listOf(vec(1.0), ortho(1.0)), 0.70f, 2)
        assertEquals(listOf(1, 0), ids)
    }

    @Test fun clusterNeedsMinimumAgreement() {
        // 6 near-identical windows agree into one cluster (id nextId+1 = 2);
        // 3 windows in an unrelated direction stay unassigned (0).
        val big = List(6) { vec(0.5, 0.01, it) }
        val small = List(3) { ortho(0.4, 0.01, it) }
        val ids = VoiceClusterer.assign(emptyList(), big + small, 0.70f, 1)
        assertTrue(ids.take(6).all { it == 2 })
        assertTrue(ids.drop(6).all { it == 0 })
    }

    @Test fun aliasResolutionAndMerge() {
        val a = VoiceClusterer.cosine(vec(1.0), vec(1.0))
        assertEquals(1.0, a, 0.01)
        assertEquals(0.0, VoiceClusterer.cosine(vec(1.0), FloatArray(0)), 0.001)
    }
}
