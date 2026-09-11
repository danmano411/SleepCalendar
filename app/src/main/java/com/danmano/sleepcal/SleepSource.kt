package com.danmano.sleepcal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/** Samsung Health's package — the only Health Connect sleep writer SleepCal trusts. */
const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

val HC_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
)

/** The only file that knows Health Connect exists; swap this out to read Samsung's SDK instead. */
class SleepSource(context: Context) {
    private val client = HealthConnectClient.getOrCreate(context)

    /** Background read is only required where this phone's Health Connect supports it. */
    suspend fun missingPermissions(): Set<String> {
        val needed = if (backgroundReadSupported()) HC_PERMISSIONS
        else HC_PERMISSIONS - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        return needed - client.permissionController.getGrantedPermissions()
    }

    fun backgroundReadSupported(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun read(from: Instant, to: Instant): List<Session> {
        val out = mutableListOf<Session>()
        var page: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    TimeRangeFilter.between(from, to),
                    dataOriginFilter = setOf(DataOrigin(SAMSUNG_HEALTH)),
                    pageToken = page,
                ),
            )
            response.records.mapTo(out) { r ->
                Session(r.metadata.id, r.startTime, r.endTime, r.stages.map { StageSpan(it.startTime, it.endTime, stageOf(it.stage)) })
            }
            page = response.pageToken
        } while (page != null)
        return out
    }

    companion object {
        fun available(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
}

private fun stageOf(type: Int): Stage = when (type) {
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> Stage.AWAKE
    SleepSessionRecord.STAGE_TYPE_LIGHT -> Stage.LIGHT
    SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
    SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> Stage.SLEEPING
    else -> Stage.UNKNOWN
}
