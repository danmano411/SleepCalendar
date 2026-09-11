package com.danmano.sleepcal

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** SleepCal's on-device memory, in SharedPreferences. Memory is written with commit() so a killed worker can't lose it. */
class State(context: Context) {
    private val prefs = context.getSharedPreferences("sleepcal", Context.MODE_PRIVATE)

    var calendarId: Long
        get() = prefs.getLong("calendarId", -1)
        set(v) = prefs.edit().putLong("calendarId", v).apply()

    var lastRun: String
        get() = prefs.getString("lastRun", null) ?: "never"
        set(v) = prefs.edit().putString("lastRun", v).apply()

    var lastNotified: String
        get() = prefs.getString("lastNotified", null) ?: ""
        set(v) = prefs.edit().putString("lastNotified", v).apply()

    fun memory(): Map<String, Memory> {
        val root = JSONObject(prefs.getString("memory", null) ?: "{}")
        return root.keys().asSequence().associateWith { decode(root.getJSONObject(it)) }
    }

    fun saveMemory(memory: Map<String, Memory>) {
        val root = JSONObject()
        memory.forEach { (key, m) -> root.put(key, encode(m)) }
        prefs.edit().putString("memory", root.toString()).commit()
    }
}

private fun encode(m: Memory) = JSONObject().apply {
    put("status", m.status.name)
    m.written?.let {
        put("title", it.title)
        put("description", it.description)
        put("start", it.start.toEpochMilli())
        put("end", it.end.toEpochMilli())
        put("placeholder", it.placeholder)
    }
}

private fun decode(o: JSONObject) = Memory(
    Status.valueOf(o.getString("status")),
    if (!o.has("title")) null else EventSpec(
        o.getString("title"),
        o.getString("description"),
        Instant.ofEpochMilli(o.getLong("start")),
        Instant.ofEpochMilli(o.getLong("end")),
        o.getBoolean("placeholder"),
    ),
)
