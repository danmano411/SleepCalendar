package com.danmano.sleepcal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

const val SYNC_NOW = "sync-now"

fun schedule(context: Context) {
    val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
}

fun syncNow(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
}

// The periodic job and "Sync now" can overlap; both would see an empty memory and insert twice.
private val syncLock = Mutex()

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = State(applicationContext)
        val stamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US))
        try {
            state.lastRun = "$stamp — OK, ${syncLock.withLock { syncOnce(applicationContext, state) }}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            state.lastRun = "$stamp — $message"
            notifyOncePerDay(applicationContext, state, message)
        }
        return Result.success() // the next period retries
    }
}

/** One pass: read sleep, plan, write the calendar, remember what was written. */
private suspend fun syncOnce(context: Context, state: State): String {
    val now = ZonedDateTime.now()
    check(SleepSource.available(context)) { "Health Connect isn't available" }
    val source = SleepSource(context)
    check(source.missingPermissions().isEmpty()) { "SleepCal needs Health Connect access" }
    check(hasCalendarPermission(context)) { "SleepCal needs calendar access" }
    val store = CalendarStore(context)
    val calendarId = state.calendarId
    check(calendarId >= 0 && store.exists(calendarId)) { "SleepCal can't find your Sleep calendar" }

    val sessions = source.read(now.minusDays(14).toInstant(), now.toInstant())
    val live = store.tagged(calendarId, now.minusDays(4).toInstant(), now.plusDays(1).toInstant())
    val memory = state.memory().toMutableMap()
    val actions = plan(sessions, memory, live, now)
    try {
        for (a in actions) when (a) {
            is Action.Create -> {
                store.insert(calendarId, a.spec)
                memory[a.key] = Memory(Status.ACTIVE, a.spec)
            }
            is Action.Update -> {
                store.update(a.eventId, a.spec, recolor = memory[a.key]?.written?.placeholder != a.spec.placeholder)
                memory[a.key] = Memory(Status.ACTIVE, a.spec)
            }
            is Action.Lock -> memory[a.key] = Memory(Status.LOCKED, memory[a.key]?.written)
            is Action.Tombstone -> memory[a.key] = Memory(Status.TOMBSTONE, memory[a.key]?.written)
        }
    } finally {
        // Save whatever succeeded, and forget keys a month old.
        val oldest = now.toLocalDate().minusDays(30)
        state.saveMemory(memory.filterKeys { k -> keyDate(k)?.let { it >= oldest } ?: false })
    }
    return "${actions.count { it is Action.Create }} created, ${actions.count { it is Action.Update }} updated"
}

private fun notifyOncePerDay(context: Context, state: State, text: String) {
    val today = LocalDate.now().toString()
    if (state.lastNotified == today) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("sync", "Sync problems", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, "sync")
        .setShowWhen(true)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("SleepCal")
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    manager.notify(1, notification)
    state.lastNotified = today
}
