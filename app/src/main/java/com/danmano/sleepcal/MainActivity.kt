package com.danmano.sleepcal

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.work.WorkManager

private val ANDROID_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.POST_NOTIFICATIONS,
)

private data class SetupStatus(
    val healthAvailable: Boolean,
    val healthGranted: Boolean,
    val backgroundSupported: Boolean,
    val calendarGranted: Boolean,
    val calendars: List<CalendarInfo>,
    val calendarId: Long,
    val batteryUnrestricted: Boolean,
    val lastRun: String,
)

/** The setup screen. Health Connect also opens it to show SleepCal's privacy rationale. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    private val askHealth =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
    private val askAndroid =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schedule(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Setup() } } }
    }

    override fun onResume() {
        super.onResume()
        refresh++ // pick up changes made in system settings screens
    }

    @Composable
    private fun Setup() {
        val state = remember { State(this) }
        val store = remember { CalendarStore(this) }
        var status by remember { mutableStateOf<SetupStatus?>(null) }
        val syncRun by remember { WorkManager.getInstance(this).getWorkInfosForUniqueWorkFlow(SYNC_NOW) }
            .collectAsState(emptyList())
        LaunchedEffect(refresh, syncRun.firstOrNull()?.state) { status = load(state, store) }
        val s = status ?: return

        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SleepCal", style = MaterialTheme.typography.headlineMedium)

            when {
                !s.healthAvailable -> Text("⚠️ Health Connect isn't available on this phone.")
                s.healthGranted -> Text("✅ Health Connect: sleep + background access")
                else -> Button(onClick = { askHealth.launch(HC_PERMISSIONS) }) { Text("Grant Health Connect access") }
            }
            if (s.healthAvailable && !s.backgroundSupported) {
                Text("⚠️ This phone's Health Connect can't read in the background, so syncs only work while SleepCal is open.")
            }

            if (s.calendarGranted) {
                Text("✅ Calendar access")
            } else {
                Button(onClick = { askAndroid.launch(ANDROID_PERMISSIONS) }) { Text("Grant calendar & notification access") }
            }

            if (s.calendarGranted) {
                Text("Sleep calendar", style = MaterialTheme.typography.titleMedium)
                if (s.calendars.isEmpty()) {
                    Text("No Google calendars found. Create a \"Sleep\" calendar at calendar.google.com, then reopen SleepCal.")
                }
                s.calendars.forEach { cal ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            state.calendarId = cal.id
                            store.enable(cal.id)
                            refresh++
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = cal.id == s.calendarId, onClick = null)
                        Text("${cal.name}  (${cal.account})")
                    }
                }
            }

            if (s.batteryUnrestricted) {
                Text("✅ Battery: unrestricted")
            } else {
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }) { Text("Allow unrestricted battery") }
            }

            Button(
                onClick = { syncNow(this@MainActivity) },
                enabled = s.healthGranted && s.calendarGranted && s.calendarId >= 0,
            ) { Text("Sync now") }
            Text("Last run: ${s.lastRun}")

            Text(
                "Privacy: SleepCal reads your sleep sessions from Health Connect and writes them to the Google " +
                    "calendar you choose. It runs only on this phone and sends nothing anywhere else.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    private suspend fun load(state: State, store: CalendarStore): SetupStatus {
        val healthAvailable = SleepSource.available(this)
        val source = if (healthAvailable) SleepSource(this) else null
        val calendarGranted = hasCalendarPermission(this)
        val calendars = if (calendarGranted) store.writableCalendars() else emptyList()
        if (state.calendarId < 0) {
            calendars.firstOrNull { it.name.equals("Sleep", ignoreCase = true) }?.let {
                state.calendarId = it.id
                store.enable(it.id)
            }
        }
        return SetupStatus(
            healthAvailable = healthAvailable,
            healthGranted = source?.missingPermissions()?.isEmpty() == true,
            backgroundSupported = source?.backgroundReadSupported() == true,
            calendarGranted = calendarGranted,
            calendars = calendars,
            calendarId = state.calendarId,
            batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            lastRun = state.lastRun,
        )
    }
}
