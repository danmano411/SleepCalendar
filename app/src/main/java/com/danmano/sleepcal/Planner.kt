package com.danmano.sleepcal

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.MINUTES
import java.util.Locale

// Tuning knobs. Local times are in the zone of `now`.
val MERGE_GAP: Duration = Duration.ofMinutes(60)
val NIGHT_END: LocalTime = LocalTime.of(6, 0) // the night window is [00:00, 06:00)
val PLACEHOLDER_CUTOFF: LocalTime = LocalTime.of(15, 0)
const val HORIZON_DAYS = 3L
const val MEDIAN_NIGHTS = 7
const val MIN_NIGHTS_FOR_MEDIAN = 3
val FALLBACK_BED: LocalTime = LocalTime.of(23, 30)
val FALLBACK_WAKE: LocalTime = LocalTime.of(7, 30)

/** Sessions merged across short gaps. [gaps] are the awake stretches between merged sessions. */
data class Block(
    val start: Instant,
    val end: Instant,
    val sessions: List<Session>,
    val gaps: List<Pair<Instant, Instant>>,
)

fun merge(sessions: List<Session>): List<Block> {
    val blocks = mutableListOf<Block>()
    for (s in sessions.sortedBy { it.start }) {
        val last = blocks.lastOrNull()
        if (last != null && Duration.between(last.end, s.start) < MERGE_GAP) {
            val gap = if (s.start > last.end) listOf(last.end to s.start) else emptyList()
            blocks[blocks.lastIndex] =
                last.copy(end = maxOf(last.end, s.end), sessions = last.sessions + s, gaps = last.gaps + gap)
        } else {
            blocks += Block(s.start, s.end, listOf(s), emptyList())
        }
    }
    return blocks
}

/** The day D whose [D 00:00, D 06:00) window [b] overlaps: its start date, else the next day. */
fun nightDay(b: Block, zone: ZoneId): LocalDate? {
    val first = b.start.atZone(zone).toLocalDate()
    return listOf(first, first.plusDays(1)).firstOrNull { d ->
        b.start < d.atTime(NIGHT_END).atZone(zone).toInstant() && b.end > d.atStartOfDay(zone).toInstant()
    }
}

/** Night blocks by wake day (longest candidate wins), and every other block as a nap. */
fun classify(blocks: List<Block>, zone: ZoneId): Pair<Map<LocalDate, Block>, List<Block>> {
    val nights = blocks.mapNotNull { b -> nightDay(b, zone)?.let { it to b } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, candidates) -> candidates.maxBy { Duration.between(it.start, it.end) } }
    val naps = blocks.filter { b -> nights.values.none { it === b } }
    return nights to naps
}

private fun total(spans: List<Pair<Instant, Instant>>): Duration =
    spans.fold(Duration.ZERO) { acc, (s, e) -> acc + Duration.between(s, e) }

fun stageTotal(b: Block, stage: Stage): Duration =
    total(b.sessions.flatMap { it.stages }.filter { it.stage == stage }.map { it.start to it.end })

/** In-bed time minus awake stages and merge gaps (with no stages: the summed session time). */
fun asleep(b: Block): Duration = Duration.between(b.start, b.end) - stageTotal(b, Stage.AWAKE) - total(b.gaps)

private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

fun fmt(d: Duration): String {
    val m = d.toMinutes()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun realSpec(key: String, b: Block, zone: ZoneId, isNight: Boolean): EventSpec {
    val stages = listOf(Stage.DEEP to "Deep", Stage.REM to "REM", Stage.LIGHT to "Light", Stage.AWAKE to "Awake")
        .map { (stage, label) -> label to stageTotal(b, stage) }
        .filter { !it.second.isZero }
        .joinToString(" · ") { (label, d) -> "$label ${fmt(d)}" }
    val lines = buildList {
        add("Asleep ${fmt(asleep(b))} · in bed ${fmt(Duration.between(b.start, b.end))}")
        if (stages.isNotEmpty()) add(stages)
        b.gaps.forEach { (s, e) -> add("Woke ${clock.format(s.atZone(zone))}–${clock.format(e.atZone(zone))}") }
        add("Source: Samsung Health via Health Connect")
        add("$MARKER $key")
    }
    val title = (if (isNight) "😴 Sleep · " else "💤 Nap · ") + fmt(asleep(b))
    return EventSpec(title, lines.joinToString("\n"), b.start.truncatedTo(MINUTES), b.end.truncatedTo(MINUTES), placeholder = false)
}

private fun median(xs: List<Long>): Long = xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/**
 * A "not logged" night for [day] at the median bed/wake time of the most recent logged nights.
 * Bedtime is measured from noon the day before, wake from midnight, so times either side of
 * midnight average correctly.
 */
fun placeholderSpec(day: LocalDate, nights: Map<LocalDate, Block>, zone: ZoneId): EventSpec {
    val recent = nights.entries.sortedBy { it.key }.takeLast(MEDIAN_NIGHTS)
    fun noonBefore(d: LocalDate) = d.minusDays(1).atTime(LocalTime.NOON).atZone(zone)
    val (start, end, note) = if (recent.size >= MIN_NIGHTS_FOR_MEDIAN) {
        val bed = median(recent.map { (d, b) -> Duration.between(noonBefore(d).toInstant(), b.start).toMinutes() })
        val wake = median(recent.map { (d, b) -> Duration.between(d.atStartOfDay(zone).toInstant(), b.end).toMinutes() })
        Triple(
            noonBefore(day).plusMinutes(bed),
            day.atStartOfDay(zone).plusMinutes(wake),
            "Typical times from your last ${recent.size} logged nights.",
        )
    } else {
        Triple(
            day.minusDays(1).atTime(FALLBACK_BED).atZone(zone),
            day.atTime(FALLBACK_WAKE).atZone(zone),
            "Default times — not enough history yet.",
        )
    }
    val safeEnd = if (end.isAfter(start)) end else start.plusHours(8)
    val description = listOf(
        "No watch data for this night — drag this block to your real times.",
        note,
        "$MARKER night:$day",
    ).joinToString("\n")
    return EventSpec(
        "❔ Sleep (not logged)", description,
        start.toInstant().truncatedTo(MINUTES), safeEnd.toInstant().truncatedTo(MINUTES), placeholder = true,
    )
}

/** The local date a key refers to, or null if it is malformed. */
fun keyDate(key: String): LocalDate? = runCatching {
    when {
        key.startsWith("night:") -> LocalDate.parse(key.removePrefix("night:"))
        key.startsWith("nap:") -> LocalDateTime.parse(key.removePrefix("nap:")).toLocalDate()
        else -> null
    }
}.getOrNull()

fun plan(
    sessions: List<Session>,
    memory: Map<String, Memory>,
    live: Map<String, LiveEvent>,
    now: ZonedDateTime,
): List<Action> {
    val zone = now.zone
    val today = now.toLocalDate()
    val firstDay = today.minusDays(HORIZON_DAYS - 1)
    val (nights, naps) = classify(merge(sessions), zone)

    val desired = mutableMapOf<String, EventSpec>()
    var d = firstDay
    while (d <= today) {
        val key = "night:$d"
        val block = nights[d]
        if (block != null) {
            desired[key] = realSpec(key, block, zone, isNight = true)
        } else if (!now.isBefore(d.atTime(PLACEHOLDER_CUTOFF).atZone(zone))) {
            desired[key] = placeholderSpec(d, nights, zone)
        }
        d = d.plusDays(1)
    }
    for (b in naps) {
        val key = "nap:" + b.start.atZone(zone).toLocalDateTime().truncatedTo(MINUTES)
        desired[key] = realSpec(key, b, zone, isNight = false)
    }

    return (desired.keys + memory.keys + live.keys)
        .filter { k -> keyDate(k)?.let { it >= firstDay } ?: false }
        .sorted()
        .mapNotNull { k -> decide(k, memory[k], live[k], desired[k]) }
}

/** The ownership table from the spec: your edits and deletions always win. */
fun decide(key: String, mem: Memory?, live: LiveEvent?, want: EventSpec?): Action? = when {
    mem == null -> when {
        live != null -> Action.Lock(key) // already there but not remembered (e.g. after reinstall)
        want != null -> Action.Create(key, want)
        else -> null
    }
    mem.status != Status.ACTIVE -> null
    live == null -> Action.Tombstone(key)
    mem.written == null || !sameAs(live, mem.written) -> Action.Lock(key)
    want != null && want != mem.written -> Action.Update(key, live.id, want)
    else -> null
}

/** Whether [live] is still exactly what SleepCal wrote, ignoring sync's line-ending/whitespace noise. */
fun sameAs(live: LiveEvent, spec: EventSpec): Boolean =
    norm(live.title) == norm(spec.title) &&
        norm(live.description) == norm(spec.description) &&
        live.start.truncatedTo(MINUTES) == spec.start &&
        live.end.truncatedTo(MINUTES) == spec.end

private fun norm(s: String) = s.replace("\r\n", "\n").trim()
