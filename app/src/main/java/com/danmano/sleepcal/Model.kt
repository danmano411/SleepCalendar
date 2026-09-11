package com.danmano.sleepcal

import java.time.Instant

enum class Stage { AWAKE, LIGHT, DEEP, REM, SLEEPING, UNKNOWN }

data class StageSpan(val start: Instant, val end: Instant, val stage: Stage)

/** One sleep session as recorded by the watch. */
data class Session(val id: String, val start: Instant, val end: Instant, val stages: List<StageSpan>)

/** What SleepCal wants an event to look like. Times are truncated to the minute. */
data class EventSpec(
    val title: String,
    val description: String,
    val start: Instant,
    val end: Instant,
    val placeholder: Boolean,
)

enum class Status { ACTIVE, LOCKED, TOMBSTONE }

/** What SleepCal remembers about one key. [written] is null only for adopted LOCKED events. */
data class Memory(val status: Status, val written: EventSpec?)

/** An event in the Sleep calendar that carries a SleepCal marker. */
data class LiveEvent(
    val id: Long,
    val key: String,
    val title: String,
    val description: String,
    val start: Instant,
    val end: Instant,
)

sealed interface Action {
    val key: String

    data class Create(override val key: String, val spec: EventSpec) : Action
    data class Update(override val key: String, val eventId: Long, val spec: EventSpec) : Action
    data class Lock(override val key: String) : Action
    data class Tombstone(override val key: String) : Action
}

/** Last line of every SleepCal event description: `#sleepcal night:2026-09-10`. */
const val MARKER = "#sleepcal"

private val markerRegex = Regex("""#sleepcal ([a-z]+:[0-9T:\-]+)""")

/** The key in a SleepCal marker, or null if [description] has none. */
fun markerKey(description: String): String? = markerRegex.find(description)?.groupValues?.get(1)
