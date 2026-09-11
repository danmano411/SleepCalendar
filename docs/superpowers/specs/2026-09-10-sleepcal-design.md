# SleepCal — Design Spec

**Date:** 2026-09-10 · **Status:** Approved (build stage) · **Owner:** Dan Mano

## Goal

Every morning, the sleep my Galaxy Watch logged in Samsung Health appears automatically as an
event on my Google Calendar (and therefore in Notion Calendar, which mirrors Google). The event
must be accurate to the recorded data, easy to adjust by hand, and something sensible must appear
when no sleep was logged.

## Decisions

| Topic | Decision |
|---|---|
| Data source | **Health Connect** (Samsung Health writes sleep sessions + stages into it). No sleep score. Samsung Health Data SDK kept as a later swap (only `SleepSource.kt` changes). |
| Calendar write path | **Android `CalendarContract`** on the phone → Google's own sync pushes to Google Calendar → Notion Calendar. No Google Cloud project, no OAuth. |
| Target calendar | Dedicated **"Sleep"** secondary calendar, created once by hand at calendar.google.com. |
| Missing night | **Placeholder** "❔ Sleep (not logged)" at the median bed/wake time of the last 7 logged nights. |
| Cutoff | Placeholder written once it is past **3:00 PM** on the wake day with no data. |
| Ownership | **Your edits win.** The app updates an event only while it is exactly as the app last wrote it. Edited → locked forever. Deleted → never recreated. |
| Split sleep / naps | Sessions < 60 min apart merge into one block. The block overlapping midnight–6 AM is the night; everything else is a nap with its own event. |
| Event look | One block bed→wake, title `😴 Sleep · 7h 23m`, stages and wake gaps in the description. |
| Build | Small Kotlin Android app, sideloaded onto a RedMagic 10 Pro (NX789J), Android 15. |

## Architecture

```
Galaxy Watch ─BT─▶ Samsung Health ─▶ Health Connect
                                          │ background read, every 30 min
                                          ▼
                                   SleepCal app (WorkManager)
                                          │ CalendarContract insert/update
                                          ▼
                    phone's Google calendar ─sync─▶ Google Calendar ─▶ Notion Calendar
```

One Android app, package `com.danmano.sleepcal`, minSdk 34 (Health Connect is part of Android 14+),
compileSdk/targetSdk 36. Seven source files in `app/src/main/java/com/danmano/sleepcal/`:

| File | Responsibility | Depends on |
|---|---|---|
| `Model.kt` | Shared data types (below) + the description marker format. The contract between files. | nothing |
| `Planner.kt` | **Pure Kotlin** (java.time only). All rules: merging, night/nap classification, placeholder + median, event text, edit-ownership decisions. Emits actions. | `Model.kt` |
| `SleepSource.kt` | Reads Health Connect `SleepSessionRecord`s (paged) and maps them to `Session`. The only file that knows Health Connect exists. | Health Connect client |
| `CalendarStore.kt` | Lists writable Google calendars, reads SleepCal-tagged events in a window, inserts/updates events. | `CalendarContract` |
| `State.kt` | SharedPreferences + `org.json`: per-key memory, chosen calendar id, last-run status. | Android |
| `SyncWorker.kt` | `CoroutineWorker`: read → plan → apply → save; error notification. Scheduling helpers. | all of the above |
| `MainActivity.kt` | One Compose setup screen + Health Connect privacy-rationale entry point. | all of the above |

Libraries added to the Android Studio template: `androidx.health.connect:connect-client:1.1.0`,
`androidx.work:work-runtime-ktx:2.11.2`. Template extras (navigation, view model, repository,
serialization, instrumented tests) are removed.

## Core types (contract between files)

```kotlin
// Model.kt
enum class Stage { AWAKE, LIGHT, DEEP, REM, SLEEPING, UNKNOWN }
data class StageSpan(val start: Instant, val end: Instant, val stage: Stage)
data class Session(val id: String, val start: Instant, val end: Instant, val stages: List<StageSpan>)

data class EventSpec(
    val title: String, val description: String,
    val start: Instant, val end: Instant,          // truncated to the minute
    val placeholder: Boolean,
)
enum class Status { ACTIVE, LOCKED, TOMBSTONE }
data class Memory(val status: Status, val written: EventSpec?)   // written == null only for adopted LOCKED
data class LiveEvent(val id: Long, val key: String, val title: String, val description: String,
                     val start: Instant, val end: Instant)

sealed interface Action {
    val key: String
    data class Create(override val key: String, val spec: EventSpec) : Action
    data class Update(override val key: String, val eventId: Long, val spec: EventSpec) : Action
    data class Lock(override val key: String) : Action
    data class Tombstone(override val key: String) : Action
}

fun markerKey(description: String): String?   // parses "#sleepcal <key>"

// Planner.kt
fun plan(sessions: List<Session>, memory: Map<String, Memory>,
         live: Map<String, LiveEvent>, now: ZonedDateTime): List<Action>
```

## Rules (per run, every 30 min)

Constants at the top of `Planner.kt`: `MERGE_GAP = 60 min`, `NIGHT_WINDOW = 00:00–06:00`,
`PLACEHOLDER_CUTOFF = 15:00`, `HORIZON_DAYS = 3`, `MEDIAN_NIGHTS = 7`, `MIN_NIGHTS_FOR_MEDIAN = 3`,
`FALLBACK_BED = 23:30`, `FALLBACK_WAKE = 07:30`. All local times use the zone of `now`.

1. **Read** sessions ending in the last 14 days (14 days feeds the median).
2. **Merge**: sort by start; a session starting less than `MERGE_GAP` after the running block's
   end (or overlapping it) joins the block. Positive gaps between merged sessions are recorded.
3. **Classify**: a block overlapping `[D 00:00, D 06:00)` is a night candidate for day D (check D =
   local date of block start and the day after; first match wins). The candidate with the longest
   in-bed time is **the night for D**, key `night:YYYY-MM-DD`. Every other block is a **nap**, key
   `nap:YYYY-MM-DDTHH:MM` (local start).
4. **Desired events** within the horizon (nights with D ≥ today − 2; naps starting on or after
   today − 2):
   - Night: title `😴 Sleep · {asleep}`; nap: `💤 Nap · {asleep}`.
   - `inBed = end − start`; `awake = Σ AWAKE stage spans + Σ merge gaps`; `asleep = inBed − awake`
     (with no stage data this is the summed session time).
   - Description lines: `Asleep 7h 23m · in bed 7h 36m`; stage line with present stages in order
     Deep · REM · Light · Awake (omitted if no stages); one `Woke 3:10 AM–3:35 AM` line per merge gap;
     `Source: Samsung Health via Health Connect`; last line marker `#sleepcal {key}`.
   - Durations format as `7h 23m`, or `42m` under an hour. Times use `h:mm a`, Locale.US.
5. **Placeholder**: for each day D in [today − 2, today] where `now ≥ D 15:00` and D has no night →
   desired `night:D` = placeholder. Times: median of the last `MEDIAN_NIGHTS` real nights (bedtime as
   minutes after noon of D−1, wake as minutes after midnight of D — handles the midnight wrap). Fewer
   than `MIN_NIGHTS_FOR_MEDIAN` nights → fallback 23:30 → 07:30. Title `❔ Sleep (not logged)`,
   description `No watch data for this night — drag this block to your real times.` +
   `Typical times from your last N logged nights.` (or `Default times — not enough history yet.`) +
   marker. `placeholder = true`.
6. **Ownership decision** for every key that is desired or remembered and inside the horizon:

   | memory | live event (by marker) | Action |
   |---|---|---|
   | none | present | `Lock` (adopt — unknown provenance, e.g. after reinstall) |
   | none | absent, desired exists | `Create` |
   | LOCKED / TOMBSTONE | any | nothing |
   | ACTIVE | absent | `Tombstone` (you deleted or moved it) |
   | ACTIVE | differs from `written` (normalized) | `Lock` (you edited it) |
   | ACTIVE | equals `written`, desired differs | `Update` |
   | ACTIVE | equals `written`, desired same or none | nothing |

   *Normalized* compare: title and description with `\r\n → \n` and trimmed; start/end at minute
   precision. Keys outside the horizon are frozen: no actions.

## Calendar details

- **Identification:** the description marker `#sleepcal {key}` is the event's identity; `CalendarStore`
  lists events in the chosen calendar with `DTSTART` in `[now − 4 d, now + 1 d]`, `DELETED = 0`, and
  parses the marker (regex `#sleepcal (\S+)`). No event ids are stored.
- **Calendar choice:** calendars with `ACCOUNT_TYPE = "com.google"` and access level ≥ contributor.
  Auto-select the one named "Sleep" (case-insensitive) if present; otherwise the user picks. On select,
  set `SYNC_EVENTS = 1` and `VISIBLE = 1` (writable by normal apps).
- **Event fields:** `DTSTART/DTEND` (ms), `EVENT_TIMEZONE` = device zone, `AVAILABILITY_FREE`,
  `HAS_ALARM = 0`. Placeholders: best-effort `EVENT_COLOR_KEY` = the Google "graphite" color key from
  `CalendarContract.Colors` (ignored on failure); updating to real data clears the color.
- The app never deletes events.

## Failure handling

| Situation | Behavior |
|---|---|
| Watch not worn / dead | Placeholder after 3 PM (rule 5). |
| Data lands late | Picked up by the next 30-min run; replaces an untouched placeholder. |
| Samsung revises a session | Untouched event updated; touched event left alone. |
| Health Connect unavailable, or `READ_SLEEP` / background-read not granted | Run aborts without writing; notification "SleepCal needs Health Connect access" (max once/day); status shown in app. |
| Calendar permission missing, or chosen calendar gone | Run aborts; notification "SleepCal can't find your Sleep calendar" (max once/day). |
| Any other exception | Recorded as last-run error, notification (max once/day), next period retries. |
| OS kills background work (RedMagic) | Setup requires battery "Unrestricted" for SleepCal, Samsung Health, Galaxy Wearable, Watch plugin. WorkManager catches up when allowed; app shows last successful run. |
| App data cleared / reinstall | Existing tagged events are adopted as LOCKED — never overwritten, never duplicated. |
| Duplicate Health Connect writers | Read filtered to data origin `com.sec.android.app.shealth` (constant in `SleepSource.kt`). |

## One-time setup & credentials

No API keys, developer accounts, Google Cloud project, or OAuth.

1. **Health Connect check:** Settings → Health Connect → Data and access → Sleep → entries from
   Samsung Health exist. If not: Samsung Health → Settings → Health Connect → allow Sleep; enable
   *Consent to processing of health and wellness data*.
2. **Google:** calendar.google.com → Other calendars → + → Create new calendar → "Sleep"; pick a
   color; set its default notifications to none.
3. **Battery:** Unrestricted + allow auto-start for SleepCal, Samsung Health, Galaxy Wearable, Galaxy
   Watch plugin.
4. **Install:** enable Developer options → USB debugging; build & install (`gradlew installDebug`).
5. **In SleepCal:** grant Health Connect (sleep + background), calendar and notification permissions;
   confirm the "Sleep" calendar; tap *Sync now*.
6. **Notion Calendar:** make sure the Sleep calendar is visible.

Permissions (manifest): `health.READ_SLEEP`, `health.READ_HEALTH_DATA_IN_BACKGROUND`,
`READ_CALENDAR`, `WRITE_CALENDAR`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
Manifest also declares the Health Connect rationale intent (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`)
and the Android 14+ `VIEW_PERMISSION_USAGE` / `HEALTH_PERMISSIONS` activity-alias — without them the
permission dialog does not open.

## Testing

- **Unit (JVM, `PlannerTest.kt`):** merge threshold; night vs nap (normal night, 03:00–05:30 short night,
  afternoon block); placeholder only after 15:00; median across midnight; fallback with < 3 nights;
  every row of the ownership table; placeholder → real update; asleep/awake math with stages and gaps;
  description text; horizon freezing.
- **Build gate:** `gradlew testDebugUnitTest assembleDebug` green.
- **On device (later stage, phone connected via adb):** Phase-0 checks below, then *Sync now* → event
  appears in Google Calendar web and Notion Calendar; drag it → next sync leaves it; delete it → not
  recreated; a watch-off night → placeholder at 3 PM.

## Open items to verify on the device

1. Samsung Health on a non-Samsung phone writes sleep (with stages) to Health Connect, and the data
   origin package is `com.sec.android.app.shealth`.
2. `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` is available on this phone's Health Connect module.
3. Placeholder `EVENT_COLOR_KEY` syncs to Google and shows in Notion Calendar (else title alone marks it).
4. Whether naps are written to Health Connect at all.

## Out of scope

Sleep score (needs Samsung Health Data SDK — later swap), stage-level events, cloud components,
multi-user, Play Store distribution, editing sleep back into Samsung Health.
