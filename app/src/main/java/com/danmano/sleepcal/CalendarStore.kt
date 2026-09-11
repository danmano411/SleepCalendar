package com.danmano.sleepcal

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.ZoneId

data class CalendarInfo(val id: Long, val name: String, val account: String)

fun hasCalendarPermission(context: Context): Boolean =
    listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Google's "Graphite" event colour key. ponytail: best guess at the key; unverified until the device test, title marks placeholders regardless. */
private const val GRAPHITE = "8"

/** Reads and writes events through Android's calendar provider; Google's sync adapter uploads them. */
class CalendarStore(context: Context) {
    private val resolver = context.contentResolver

    /** Google calendars you can write to. */
    fun writableCalendars(): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf("com.google", Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }
        return out
    }

    /** Make sure the chosen calendar is synced to this phone and visible. */
    fun enable(calendarId: Long) {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), values, null, null)
    }

    fun exists(calendarId: Long): Boolean = writableCalendars().any { it.id == calendarId }

    /** SleepCal-tagged events in [calendarId] starting within [from, to), keyed by their marker. */
    fun tagged(calendarId: Long, from: Instant, to: Instant): Map<String, LiveEvent> {
        val out = mutableMapOf<String, LiveEvent>()
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events.DTEND),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.DTSTART} >= ? AND ${Events.DTSTART} < ?",
            arrayOf(calendarId.toString(), from.toEpochMilli().toString(), to.toEpochMilli().toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val description = c.getString(2) ?: ""
                val key = markerKey(description) ?: continue
                out[key] = LiveEvent(
                    c.getLong(0), c.getString(1) ?: "", description,
                    Instant.ofEpochMilli(c.getLong(3)), Instant.ofEpochMilli(c.getLong(4)),
                )
            }
        }
        return out
    }

    fun insert(calendarId: Long, spec: EventSpec) = withColorFallback(spec) { values ->
        values.put(Events.CALENDAR_ID, calendarId)
        values.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        values.put(Events.HAS_ALARM, 0)
        // A null result means nothing was written; throwing keeps the memory from claiming otherwise.
        checkNotNull(resolver.insert(Events.CONTENT_URI, values)) { "Calendar insert failed" }
    }

    fun update(eventId: Long, spec: EventSpec) = withColorFallback(spec) { values ->
        val rows = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
        check(rows == 1) { "Calendar update touched $rows events" }
    }

    /** Placeholders get the graphite colour if the account has it; otherwise write without colour. */
    private fun withColorFallback(spec: EventSpec, write: (ContentValues) -> Unit) {
        try {
            write(values(spec, withColor = true))
        } catch (e: IllegalArgumentException) {
            write(values(spec, withColor = false))
        }
    }

    private fun values(spec: EventSpec, withColor: Boolean) = ContentValues().apply {
        put(Events.TITLE, spec.title)
        put(Events.DESCRIPTION, spec.description)
        put(Events.DTSTART, spec.start.toEpochMilli())
        put(Events.DTEND, spec.end.toEpochMilli())
        put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        if (withColor) {
            if (spec.placeholder) {
                put(Events.EVENT_COLOR_KEY, GRAPHITE)
            } else {
                putNull(Events.EVENT_COLOR_KEY)
                putNull(Events.EVENT_COLOR)
            }
        }
    }
}
