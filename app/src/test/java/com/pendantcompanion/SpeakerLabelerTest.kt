package com.pendantcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerLabelerTest {
    private val thr = 0.75

    @Test fun strongScoreIsYouEvenWhenAClusteredWindowOverlaps() {
        // A tiny overlap with a clustered window must never flip a YOU line.
        assertEquals(SpeakerLabeler.YOU, SpeakerLabeler.labelFor(0.85, thr, 3, 0.4))
    }

    @Test fun bandScoresAreUnsure() {
        assertEquals(SpeakerLabeler.UNSURE, SpeakerLabeler.labelFor(thr - 0.05, thr, 0, 0.0))
        assertEquals(SpeakerLabeler.UNSURE, SpeakerLabeler.labelFor(thr - 0.08, thr, 0, 0.0))
    }

    @Test fun belowBandWithDominantKnownVoiceIsNamedOther() {
        assertEquals(SpeakerLabeler.OTHER + "*", SpeakerLabeler.labelFor(0.55, thr, 3, 2.0))
    }

    @Test fun belowBandWithSmallVoiceOverlapIsPlainOther() {
        assertEquals(SpeakerLabeler.OTHER, SpeakerLabeler.labelFor(0.55, thr, 3, 0.4))
    }

    @Test fun belowBandWithoutAVoiceIsOther() {
        assertEquals(SpeakerLabeler.OTHER, SpeakerLabeler.labelFor(0.55, thr, 0, 0.0))
    }
}
