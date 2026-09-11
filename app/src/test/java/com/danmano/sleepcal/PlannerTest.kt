package com.danmano.sleepcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PlannerTest {
    private val zone = ZoneId.of("America/New_York")

    private fun at(date: String, time: String): Instant = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()
    private fun now(date: String, time: String): ZonedDateTime = LocalDateTime.parse("${date}T$time").atZone(zone)
    private fun session(start: Instant, end: Instant, vararg stages: StageSpan) = Session(start, end, stages.toList())
    private fun liveOf(id: Long, spec: EventSpec) = LiveEvent(id, spec.title, spec.description, spec.start, spec.end)

    /** A night ending on [wake]; a bedtime after noon means the evening before. */
    private fun night(wake: String, bed: String, up: String): Session {
        val w = LocalDate.parse(wake)
        val bedDate = if (bed >= "12:00") w.minusDays(1) else w
        return session(at(bedDate.toString(), bed), at(wake, up))
    }

    private val key = "night:2026-09-10"
    private val spec = EventSpec(
        "😴 Sleep · 8h 0m", "Asleep 8h 0m\n#sleepcal night:2026-09-10",
        at("2026-09-09", "23:30"), at("2026-09-10", "07:30"), false,
    )
    private val newer = spec.copy(title = "😴 Sleep · 8h 5m", end = at("2026-09-10", "07:35"))

    // --- merging and classification

    @Test fun `sessions under 60 minutes apart merge and record the gap`() {
        val blocks = merge(listOf(
            session(at("2026-09-09", "23:00"), at("2026-09-10", "03:10")),
            session(at("2026-09-10", "03:35"), at("2026-09-10", "07:00")),
        ))
        assertEquals(1, blocks.size)
        assertEquals(listOf(at("2026-09-10", "03:10") to at("2026-09-10", "03:35")), blocks[0].gaps)
        assertEquals(at("2026-09-10", "07:00"), blocks[0].end)
    }

    @Test fun `sessions 60 minutes or more apart stay separate`() {
        val blocks = merge(listOf(
            session(at("2026-09-09", "23:00"), at("2026-09-10", "03:00")),
            session(at("2026-09-10", "04:00"), at("2026-09-10", "07:00")),
        ))
        assertEquals(2, blocks.size)
    }

    @Test fun `a block overlapping midnight to 6am is the night for its wake day`() {
        val (nights, naps) = classify(merge(listOf(night("2026-09-10", "23:30", "07:30"))), zone)
        assertEquals(setOf(LocalDate.parse("2026-09-10")), nights.keys)
        assertTrue(naps.isEmpty())
    }

    @Test fun `a short night inside the window still counts as the night`() {
        val (nights, _) = classify(merge(listOf(night("2026-09-10", "03:00", "05:30"))), zone)
        assertEquals(setOf(LocalDate.parse("2026-09-10")), nights.keys)
    }

    @Test fun `an afternoon block is a nap`() {
        val (nights, naps) = classify(merge(listOf(session(at("2026-09-10", "13:00"), at("2026-09-10", "16:30")))), zone)
        assertTrue(nights.isEmpty())
        assertEquals(1, naps.size)
    }

    @Test fun `the longest night candidate wins and the other becomes a nap`() {
        val (nights, naps) = classify(merge(listOf(
            session(at("2026-09-09", "22:00"), at("2026-09-10", "01:00")),
            session(at("2026-09-10", "03:00"), at("2026-09-10", "08:00")),
        )), zone)
        assertEquals(at("2026-09-10", "03:00"), nights.getValue(LocalDate.parse("2026-09-10")).start)
        assertEquals(at("2026-09-09", "22:00"), naps.single().start)
    }

    // --- event text

    @Test fun `asleep subtracts awake stages and merge gaps, and the description lists them`() {
        val s1 = session(
            at("2026-09-09", "23:00"), at("2026-09-10", "03:00"),
            StageSpan(at("2026-09-09", "23:00"), at("2026-09-10", "01:00"), Stage.LIGHT),
            StageSpan(at("2026-09-10", "01:00"), at("2026-09-10", "02:00"), Stage.DEEP),
            StageSpan(at("2026-09-10", "02:00"), at("2026-09-10", "02:15"), Stage.AWAKE),
            StageSpan(at("2026-09-10", "02:15"), at("2026-09-10", "03:00"), Stage.REM),
        )
        val s2 = session(at("2026-09-10", "03:20"), at("2026-09-10", "07:00"))
        val spec = realSpec("night:2026-09-10", merge(listOf(s1, s2)).single(), zone, isNight = true)
        assertEquals("😴 Sleep · 7h 25m", spec.title)
        assertEquals(
            """
            Asleep 7h 25m · in bed 8h 0m
            Deep 1h 0m · REM 45m · Light 2h 0m · Awake 15m
            Woke 3:00 AM–3:20 AM
            Source: Samsung Health via Health Connect
            #sleepcal night:2026-09-10
            """.trimIndent(),
            spec.description,
        )
        assertEquals(at("2026-09-09", "23:00"), spec.start)
        assertEquals(at("2026-09-10", "07:00"), spec.end)
    }

    @Test fun `a night without stages has no stage line`() {
        val spec = realSpec("night:2026-09-10", merge(listOf(night("2026-09-10", "23:30", "07:30"))).single(), zone, isNight = true)
        assertEquals("😴 Sleep · 8h 0m", spec.title)
        assertEquals(
            "Asleep 8h 0m · in bed 8h 0m\nSource: Samsung Health via Health Connect\n#sleepcal night:2026-09-10",
            spec.description,
        )
    }

    @Test fun `marker key is parsed from plain and html descriptions`() {
        assertEquals("night:2026-09-10", markerKey("Asleep 8h\n#sleepcal night:2026-09-10"))
        assertEquals("nap:2026-09-10T13:05", markerKey("<p>#sleepcal nap:2026-09-10T13:05</p>"))
        assertNull(markerKey("Dentist"))
    }

    // --- plan: real events and placeholders

    @Test fun `plan creates events for logged nights`() {
        val sessions = listOf("2026-09-08", "2026-09-09", "2026-09-10").map { night(it, "23:30", "07:30") }
        val actions = plan(sessions, emptyMap(), emptyMap(), now("2026-09-10", "09:00"))
        assertEquals(listOf("night:2026-09-08", "night:2026-09-09", "night:2026-09-10"), actions.map { it.key })
        val create = actions.last() as Action.Create
        assertEquals("😴 Sleep · 8h 0m", create.spec.title)
        assertEquals(at("2026-09-09", "23:30"), create.spec.start)
    }

    @Test fun `a nap gets its own event`() {
        val sessions = listOf("2026-09-08", "2026-09-09", "2026-09-10").map { night(it, "23:30", "07:30") } +
            session(at("2026-09-10", "13:00"), at("2026-09-10", "13:42"))
        val nap = plan(sessions, emptyMap(), emptyMap(), now("2026-09-10", "14:00"))
            .single { it.key == "nap:2026-09-10T13:00" } as Action.Create
        assertEquals("💤 Nap · 42m", nap.spec.title)
    }

    @Test fun `no placeholder before 3pm`() {
        val sessions = listOf("2026-09-08", "2026-09-09").map { night(it, "23:30", "07:30") }
        val actions = plan(sessions, emptyMap(), emptyMap(), now("2026-09-10", "14:59"))
        assertTrue(actions.none { it.key == "night:2026-09-10" })
    }

    @Test fun `placeholder after 3pm uses the median of recent nights across midnight`() {
        val beds = listOf("23:00", "23:30", "00:30", "01:00", "23:45", "00:15", "23:15")
        val ups = listOf("07:00", "07:30", "08:30", "09:00", "07:45", "08:15", "07:15")
        val sessions = (3..9).mapIndexed { i, day -> night("2026-09-0$day", beds[i], ups[i]) }
        val create = plan(sessions, emptyMap(), emptyMap(), now("2026-09-10", "15:00"))
            .single { it.key == "night:2026-09-10" } as Action.Create
        assertEquals("❔ Sleep (not logged)", create.spec.title)
        assertTrue(create.spec.placeholder)
        assertEquals(at("2026-09-09", "23:45"), create.spec.start)
        assertEquals(at("2026-09-10", "07:45"), create.spec.end)
        assertEquals(
            "No watch data for this night — drag this block to your real times.\n" +
                "Typical times from your last 7 logged nights.\n#sleepcal night:2026-09-10",
            create.spec.description,
        )
    }

    @Test fun `placeholder falls back to default times with fewer than 3 nights`() {
        val create = plan(listOf(night("2026-09-09", "23:00", "07:00")), emptyMap(), emptyMap(), now("2026-09-10", "16:00"))
            .single { it.key == "night:2026-09-10" } as Action.Create
        assertEquals(at("2026-09-09", "23:30"), create.spec.start)
        assertEquals(at("2026-09-10", "07:30"), create.spec.end)
        assertTrue(create.spec.description.contains("Default times — not enough history yet."))
    }

    @Test fun `placeholder on a DST change day keeps the typical clock times`() {
        val sessions = listOf("2026-10-29", "2026-10-30", "2026-10-31").map { night(it, "23:30", "07:30") }
        val create = plan(sessions, emptyMap(), emptyMap(), now("2026-11-01", "16:00"))
            .single { it.key == "night:2026-11-01" } as Action.Create
        assertEquals(at("2026-10-31", "23:30"), create.spec.start)
        assertEquals(at("2026-11-01", "07:30"), create.spec.end)
    }

    @Test fun `an untouched placeholder is replaced when real data arrives`() {
        val sessions = listOf("2026-09-08", "2026-09-09").map { night(it, "23:30", "07:30") }
        val first = plan(sessions, emptyMap(), emptyMap(), now("2026-09-10", "15:00"))
            .single { it.key == key } as Action.Create
        val memory = mapOf(key to Memory(Status.ACTIVE, first.spec))
        val live = mapOf(key to liveOf(7, first.spec))
        val update = plan(sessions + night("2026-09-10", "00:10", "08:05"), memory, live, now("2026-09-10", "18:00"))
            .single { it.key == key } as Action.Update
        assertEquals(7L, update.eventId)
        assertEquals("😴 Sleep · 7h 55m", update.spec.title)
        assertEquals(false, update.spec.placeholder)
    }

    @Test fun `keys older than the horizon are frozen`() {
        val memory = mapOf("night:2026-09-01" to Memory(Status.ACTIVE, spec))
        val oldNap = session(at("2026-09-07", "13:00"), at("2026-09-07", "14:00"))
        val keys = plan(listOf(oldNap), memory, emptyMap(), now("2026-09-10", "09:00")).map { it.key }
        assertTrue(keys.none { it == "night:2026-09-01" || it == "nap:2026-09-07T13:00" })
    }

    // --- ownership table

    @Test fun `no memory and a tagged event already there is adopted as locked`() =
        assertEquals(Action.Lock(key), decide(key, null, liveOf(1, spec), spec))

    @Test fun `no memory and no event creates it`() =
        assertEquals(Action.Create(key, spec), decide(key, null, null, spec))

    @Test fun `nothing remembered, nothing there, nothing wanted does nothing`() =
        assertNull(decide(key, null, null, null))

    @Test fun `locked and tombstoned keys are never touched`() {
        assertNull(decide(key, Memory(Status.LOCKED, spec), liveOf(1, spec), newer))
        assertNull(decide(key, Memory(Status.TOMBSTONE, spec), null, newer))
    }

    @Test fun `an event you deleted is tombstoned`() =
        assertEquals(Action.Tombstone(key), decide(key, Memory(Status.ACTIVE, spec), null, newer))

    @Test fun `an event you moved is locked`() {
        val moved = liveOf(1, spec).copy(start = at("2026-09-10", "00:00"))
        assertEquals(Action.Lock(key), decide(key, Memory(Status.ACTIVE, spec), moved, newer))
    }

    @Test fun `an untouched event is updated when the data changes`() =
        assertEquals(Action.Update(key, 1, newer), decide(key, Memory(Status.ACTIVE, spec), liveOf(1, spec), newer))

    @Test fun `an untouched real event is never downgraded to a placeholder`() =
        assertNull(decide(key, Memory(Status.ACTIVE, spec), liveOf(1, spec), spec.copy(title = "❔ Sleep (not logged)", placeholder = true)))

    @Test fun `an untouched event with unchanged data is left alone`() =
        assertNull(decide(key, Memory(Status.ACTIVE, spec), liveOf(1, spec), spec))

    @Test fun `line-ending and whitespace differences from sync are not edits`() {
        val synced = liveOf(1, spec).copy(description = spec.description.replace("\n", "\r\n") + "  ")
        assertNull(decide(key, Memory(Status.ACTIVE, spec), synced, spec))
    }
}
