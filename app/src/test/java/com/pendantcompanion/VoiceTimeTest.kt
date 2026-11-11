package com.pendantcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTimeTest {
    private val now = 100L * VoiceTime.DAY_MS
    private val sessions = listOf(
        SessionVoices(now - 2 * VoiceTime.DAY_MS, List(40) { 2 } + List(10) { 3 } + listOf(0, 0)),
        SessionVoices(now - 30 * VoiceTime.DAY_MS, List(20) { 2 }),
        SessionVoices(now - 40 * VoiceTime.DAY_MS, List(20) { 7 }),
        SessionVoices(now - 50 * VoiceTime.DAY_MS, List(20) { 2 }),
    )

    @Test fun weekTotalsAndAliases() {
        val st = VoiceTime.summarise(sessions, { if (it == 7) 2 else it }, now)
        assertEquals(60.0, st[2]!!.secondsWeek, 0.001)
        assertEquals(15.0, st[3]!!.secondsWeek, 0.001)
        assertEquals(4, st[2]!!.sessions)
        assertEquals((40 + 20 + 20 + 20) * 1.5, st[2]!!.secondsTotal, 0.001)
        assertTrue(VoiceTime.catchUp(st, setOf(2), now).isEmpty())
    }

    @Test fun catchUpNeedsHistoryAndQuietPeriod() {
        val st = VoiceTime.summarise(sessions.drop(1), { if (it == 7) 2 else it }, now)
        assertEquals(listOf(2), VoiceTime.catchUp(st, setOf(2), now))
        assertTrue(VoiceTime.catchUp(st, setOf(3), now).isEmpty())
    }
}
