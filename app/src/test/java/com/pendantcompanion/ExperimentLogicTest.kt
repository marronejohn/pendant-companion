package com.pendantcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentLogicTest {
    private val draft = ExperimentDraft("Ask before advising", "someone describes a problem", "ask what outcome they want first", "I ask a question before proposing")

    @Test fun startsAndRejectsBlank() {
        val e = ExperimentLogic.start(draft, 1, "2026-11-10")!!
        assertEquals("2026-11-17", e.reviewDate)
        assertEquals("active", e.status)
        assertNull(ExperimentLogic.start(ExperimentDraft("", "x", "", ""), 2, "2026-11-10"))
    }

    @Test fun checksAreIdempotentAndValidated() {
        var e = ExperimentLogic.start(draft, 1, "2026-11-10")!!
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-10", "Attempted", "asked two questions", "session-4"))
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-11", "not attempted", "jumped to a fix", "session-9"))
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-12", "no_opportunity", "", ""))
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-12", "maybe", "bad value", ""))
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-09", "attempted", "before start", ""))
        assertEquals(3, e.checks.size)
        e = ExperimentLogic.addCheck(e, ExperimentCheck("2026-11-11", "attempted", "regenerated", "session-9"))
        assertEquals(3, e.checks.size)
        val t = ExperimentLogic.tally(e)
        assertEquals(2, t.attempted)
        assertEquals(2, t.opportunities)
        assertEquals("Tried it on 2 of 2 days the situation came up; it didn't come up on 1 day.", ExperimentLogic.summaryLine(e))
    }

    @Test fun reviewAndFinish() {
        val e = ExperimentLogic.start(draft, 1, "2026-11-10")!!
        assertFalse(ExperimentLogic.reviewDue(e, "2026-11-16"))
        assertTrue(ExperimentLogic.reviewDue(e, "2026-11-17"))
        assertEquals("2026-11-24", ExperimentLogic.extend(e, "2026-11-17").reviewDate)
        assertEquals("completed", ExperimentLogic.finish(e, "worked").status)
        assertEquals("not_working", ExperimentLogic.finish(e, "not_working").outcome)
        assertFalse(ExperimentLogic.reviewDue(ExperimentLogic.finish(e, "worked"), "2027-01-01"))
    }
}
