package com.pendantcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopLogicTest {
    private fun m(w: String, o: String = "you", p: String = "", due: String = "", a: String = "work", c: String = "session-1") =
        LoopMention(w, o, p, due, a, c)

    @Test fun keepsOnlyRealItemsAndMatchesRestatements() {
        var l = LoopLogic.merge(emptyList(), "2026-11-10", listOf(
            m("Send Sam the revised budget", p = "Sam", due = "Friday"),
            m("Vendor to send the signed contract", "them", "Priya"),
            m("Maybe look into something", "unclear"),
            m("x")))
        assertEquals(2, l.size)
        l = LoopLogic.merge(l, "2026-11-11", listOf(m("Send the revised budget to Sam")))
        assertEquals(2, l.size)
        assertEquals("2026-11-11", l[0].lastSeen)
        assertEquals("2026-11-10", l[0].firstSeen)
        assertEquals("Friday", l[0].due)
    }

    @Test fun doneItemsAreNotReopened() {
        var l = LoopLogic.merge(emptyList(), "2026-11-10", listOf(m("Send Sam the revised budget")))
        l = LoopLogic.setStatus(l, 1, "done")
        l = LoopLogic.merge(l, "2026-11-12", listOf(m("Send Sam the revised budget please")))
        assertEquals(1, l.size)
        assertEquals("done", l[0].status)
    }

    @Test fun differentOwnerIsADifferentLoop() {
        val l = LoopLogic.merge(emptyList(), "2026-11-10", listOf(m("Send the revised budget to Sam")))
        assertEquals(2, LoopLogic.merge(l, "2026-11-11", listOf(m("Send the revised budget to Sam", "them"))).size)
    }

    @Test fun capIsEnforced() {
        val many = LoopLogic.merge(emptyList(), "2026-01-01", (1..10).map { m("unique task number $it${'a' + it} alpha$it") }, max = 5)
        assertTrue(many.size == 5)
    }
}
