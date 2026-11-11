package com.pendantcompanion

/** The voice id of every window of one session that was matched to a recurring voice. */
data class SessionVoices(val startMs: Long, val ids: List<Int>)

data class VoiceTimeStat(val id: Int, val secondsWeek: Double, val secondsTotal: Double, val lastHeardMs: Long, val sessions: Int)

/** Pure arithmetic behind "time with each person" (no Android types). */
object VoiceTime {
    /** Each scored window owns one hop (1.5 s) of audio. */
    const val HOP_SEC = 1.5
    const val DAY_MS = 24L * 3600 * 1000

    fun summarise(sessions: List<SessionVoices>, resolve: (Int) -> Int, nowMs: Long): Map<Int, VoiceTimeStat> {
        val out = HashMap<Int, VoiceTimeStat>()
        for (s in sessions) {
            val per = HashMap<Int, Int>()
            for (raw in s.ids) { val id = resolve(raw); if (id != 0) per[id] = (per[id] ?: 0) + 1 }
            for ((id, n) in per) {
                val sec = n * HOP_SEC
                val cur = out[id] ?: VoiceTimeStat(id, 0.0, 0.0, 0L, 0)
                out[id] = VoiceTimeStat(id,
                    cur.secondsWeek + (if (s.startMs >= nowMs - 7 * DAY_MS) sec else 0.0),
                    cur.secondsTotal + sec, maxOf(cur.lastHeardMs, s.startMs), cur.sessions + 1)
            }
        }
        return out
    }

    /** Named voices heard in at least [minSessions] conversations but not for [quietDays]: worth a catch-up. */
    fun catchUp(stats: Map<Int, VoiceTimeStat>, named: Set<Int>, nowMs: Long, quietDays: Int = 21, minSessions: Int = 3): List<Int> =
        named.mapNotNull { stats[it] }
            .filter { it.sessions >= minSessions && nowMs - it.lastHeardMs >= quietDays * DAY_MS }
            .sortedBy { it.lastHeardMs }.map { it.id }
}
