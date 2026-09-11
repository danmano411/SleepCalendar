package com.danmano.sleepcal

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType.SleepType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Instant

private val SLEEP_READ = setOf(Permission.of(DataTypes.SLEEP, AccessType.READ))

/** The only file that knows where sleep comes from: Samsung Health, via Samsung's Health Data SDK. */
class SleepSource(context: Context) {
    private val store = HealthDataService.getStore(context.applicationContext)

    suspend fun hasPermission(): Boolean = store.getGrantedPermissions(SLEEP_READ).containsAll(SLEEP_READ)

    /** Opens Samsung Health's permission screen, or its install/update/terms screen when that comes first. */
    suspend fun requestPermission(activity: Activity) {
        try {
            store.requestPermissions(SLEEP_READ, activity)
        } catch (e: ResolvablePlatformException) {
            if (e.hasResolution) e.resolve(activity) else throw e
        }
    }

    suspend fun read(from: Instant, to: Instant): List<Session> {
        val request = DataTypes.SLEEP.readDataRequestBuilder
            .setInstantTimeFilter(InstantTimeFilter.of(from, to))
            .setOrdering(Ordering.ASC)
            .build()
        return store.readData(request).dataList.flatMap { point ->
            // One record per night; its score covers every session in it.
            val score = point.getValue(SleepType.SLEEP_SCORE)
            val sessions = point.getValue(SleepType.SESSIONS)
            if (sessions.isNullOrEmpty()) {
                listOfNotNull(point.endTime?.let { Session(point.startTime, it, emptyList(), score) })
            } else {
                sessions.map { s ->
                    val stages = s.stages.orEmpty().map { StageSpan(it.startTime, it.endTime, stageOf(it.stage)) }
                    Session(s.startTime, s.endTime, stages, score)
                }
            }
        }
    }
}

/** What to tell the user about a Samsung Health SDK failure, or null if [e] isn't one. */
fun explain(e: Throwable): String? = when {
    e !is HealthDataException -> null
    e.errorCode == ErrorCode.ERR_ACCESS_CONTROL ->
        "Turn on Developer Mode for Data Read in Samsung Health (Settings → About Samsung Health → tap the version 10 times)"
    e.errorCode == ErrorCode.ERR_NO_USER_PERMISSION -> "SleepCal needs Samsung Health access"
    e is ResolvablePlatformException -> "Samsung Health needs attention — open SleepCal and tap Grant"
    else -> "Samsung Health error ${e.errorCode}: ${e.errorMessage ?: e.message}"
}

private fun stageOf(type: SleepType.StageType): Stage = when (type) {
    SleepType.StageType.AWAKE -> Stage.AWAKE
    SleepType.StageType.LIGHT -> Stage.LIGHT
    SleepType.StageType.DEEP -> Stage.DEEP
    SleepType.StageType.REM -> Stage.REM
    else -> Stage.UNKNOWN
}
