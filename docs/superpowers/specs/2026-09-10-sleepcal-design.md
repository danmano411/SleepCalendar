# SleepCal — Design Spec

**Date:** 2026-09-10 · **Revised:** 2026-09-11 · **Status:** Working on device · **Owner:** Dan Mano

> **Revision 2026-09-11 — data source switched to the Samsung Health Data SDK.** On the phone, Samsung
> Health held the sleep (with stages) but never wrote anything to Health Connect, even with write
> permission, "Access allowed" and the health-data consent on; Health Connect's access log showed no
> Samsung Health writes at all. SleepCal now reads Samsung Health directly through the SDK (Developer
> Mode, read-only), which also brings back the sleep score. Only `SleepSource.kt`, the setup screen,
> the manifest and the build changed; the planner rules are untouched apart from the score line.

## Goal

Every morning, the sleep my Galaxy Watch logged in Samsung Health appears automatically as an
event on my Google Calendar (and therefore in Notion Calendar, which mirrors Google). The event
must be accurate to the recorded data, easy to adjust by hand, and something sensible must appear
when no sleep was logged.

## Decisions

| Topic | Decision |
|---|---|
| Data source | **Samsung Health Data SDK 1.1.0** (read-only, Samsung Health Developer Mode): sleep records with sessions, stages and sleep score. Originally Health Connect — see the revision note. |
| Calendar write path | **Android `CalendarContract`** on the phone → Google's own sync pushes to Google Calendar → Notion Calendar. No Google Cloud project, no OAuth. |
| Target calendar | Dedicated **"Sleep"** secondary calendar, created once by hand at calendar.google.com. |
| Missing night | **Placeholder** "❔ Sleep (not logged)" at the median bed/wake time of the last 7 logged nights. |
| Cutoff | Placeholder written once it is past **3:00 PM** on the wake day with no data. |
| Ownership | **Your edits win.** The app updates an event only while it is exactly as the app last wrote it. Edited → locked forever. Deleted → never recreated. |
| Split sleep / naps | Sessions < 60 min apart merge into one block. The block overlapping midnight–6 AM is the night; everything else is a nap with its own event. |
| Event look | One block bed→wake, title `😴 Sleep · 7h 23m`, score, stages and wake gaps in the description. |
| Build | Small Kotlin Android app, sideloaded onto a RedMagic 10 Pro (NX789J), Android 15. |

## Architecture

```
Galaxy Watch ─BT─▶ Samsung Health
                          │ Samsung Health Data SDK, background read every 30 min
                          ▼
                   SleepCal app (WorkManager)
                                          │ CalendarContract insert/update
                                          ▼
                    phone's Google calendar ─sync─▶ Google Calendar ─▶ Notion Calendar
```

One Android app, package `com.danmano.sleepcal`, minSdk 34, compileSdk/targetSdk 36. Seven source files in `app/src/main/java/com/danmano/sleepcal/`:

| File | Responsibility | Depends on |
|---|---|---|
| `Model.kt` | Shared data types (below) + the description marker format. The contract between files. | nothing |
| `Planner.kt` | **Pure Kotlin** (java.time only). All rules: merging, night/nap classification, placeholder + median, event text, edit-ownership decisions. Emits actions. | `Model.kt` |
| `SleepSource.kt` | Reads `DataTypes.SLEEP` records through the Samsung Health Data SDK and maps each record's sessions (with stages and the record's score) to `Session`; requests the read permission; turns SDK errors into user-facing messages (`explain`). The only file that knows where sleep comes from. | Samsung Health Data SDK |
| `CalendarStore.kt` | Lists writable Google calendars, reads SleepCal-tagged events in a window, inserts/updates events. | `CalendarContract` |
| `State.kt` | SharedPreferences + `org.json`: per-key memory (one set per calendar), chosen calendar id, last-run status. | Android |
| `SyncWorker.kt` | `CoroutineWorker`: read → plan → apply → save; error notification. Scheduling helpers. | all of the above |
| `MainActivity.kt` | One Compose setup screen. | all of the above |

Libraries added to the Android Studio template: `androidx.work:work-runtime-ktx:2.11.2`, the Samsung
Health Data SDK as a local AAR (`app/libs/samsung-health-data-api-1.1.0.aar` — licensed, not
redistributable, so it is gitignored and downloaded by hand), plus the two runtime libraries the SDK
needs but does not declare: `gson` and `kotlin-parcelize-runtime` (without the latter every read fails
with `NoClassDefFoundError: kotlinx.parcelize.Parceler`). Template extras (navigation, view model,
repository, serialization, instrumented tests) are removed.

## Core types (contract between files)

```kotlin
// Model.kt
enum class Stage { AWAKE, LIGHT, DEEP, REM, UNKNOWN }
data class StageSpan(val start: Instant, val end: Instant, val stage: Stage)
data class Session(val start: Instant, val end: Instant, val stages: List<StageSpan>, val score: Int? = null)

data class EventSpec(
    val title: String, val description: String,
    val start: Instant, val end: Instant,          // truncated to the minute
    val placeholder: Boolean,
)
enum class Status { ACTIVE, LOCKED, TOMBSTONE }
data class Memory(val status: Status, val written: EventSpec?)   // written == null only for adopted LOCKED
data class LiveEvent(val id: Long, val title: String, val description: String,
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
   - Description lines: `Asleep 7h 23m · in bed 7h 36m`; `Score 82` (the score of the block's longest
     session's record; omitted when Samsung has none); stage line with present stages in order
     Deep · REM · Light · Awake (omitted if no stages); one `Woke 3:10 AM–3:35 AM` line per merge gap;
     `Source: Samsung Health`; last line marker `#sleepcal {key}`.
   - Durations format as `7h 23m`, or `42m` under an hour. Times use `h:mm a`, Locale.US.
5. **Placeholder**: for each day D in [today − 2, today] where `now ≥ D 15:00` and D has no night →
   desired `night:D` = placeholder. Times: median of the last `MEDIAN_NIGHTS` real nights (bedtime as
   minutes after noon of D−1, wake as minutes after midnight of D — handles the midnight wrap; wall-clock
   minutes, so DST change days keep the usual times). Fewer
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
   | ACTIVE | equals `written`, desired same or none, or a placeholder where real data was written | nothing |

   *Normalized* compare: title and description with `\r\n → \n` and trimmed; start/end at minute
   precision. Keys outside the horizon are frozen: no actions. Recorded sleep is never downgraded to a
   placeholder (e.g. after a time-zone change re-classifies a logged night as a nap).

## Calendar details

- **Identification:** the description marker `#sleepcal {key}` is the event's identity; `CalendarStore`
  lists events in the chosen calendar with `DTSTART` in `[now − 4 d, now + 1 d]`, `DELETED = 0`, and
  parses the marker (regex `#sleepcal (\S+)`). No event ids are stored.
- **Calendar choice:** calendars with `ACCOUNT_TYPE = "com.google"` and access level ≥ contributor.
  Auto-select the one named "Sleep" (case-insensitive) if present; otherwise the user picks. On select,
  set `SYNC_EVENTS = 1` and `VISIBLE = 1` (writable by normal apps). Memory is kept per calendar, so
  switching calendars starts fresh there (tagged events already in it are adopted, missing ones created)
  and switching back resumes where it left off.
- **Event fields:** `DTSTART/DTEND` (ms), `EVENT_TIMEZONE` = device zone, `AVAILABILITY_FREE`,
  `HAS_ALARM = 0`. Placeholders: best-effort `EVENT_COLOR_KEY` = the Google "graphite" color key from
  `CalendarContract.Colors` (ignored on failure); replacing a placeholder with real data clears the color.
  Other updates leave the color alone, so a color you picked stays.
- The app never deletes events.

## Failure handling

| Situation | Behavior |
|---|---|
| Watch not worn / dead | Placeholder after 3 PM (rule 5). |
| Data lands late | Picked up by the next 30-min run; replaces an untouched placeholder. |
| Samsung revises a session | Untouched event updated; touched event left alone. |
| Samsung Health Developer Mode off (SDK error 2003) | Run aborts without writing; "Turn on Developer Mode for Data Read in Samsung Health…" shown in the app and as a notification (max once/day). |
| Sleep read permission not granted (2000), or Samsung Health missing / outdated / terms not agreed (3000–3003) | Run aborts; message in app + notification; *Grant Samsung Health access* opens Samsung Health's permission, install, update or terms screen. |
| Calendar permission missing, or chosen calendar gone | Run aborts; notification "SleepCal can't find your Sleep calendar" (max once/day). |
| Any other failure (including `Error`s such as a missing SDK class) | Recorded as last-run error, notification (max once/day), next period retries. The worker catches `Throwable`: this phone ships with logcat silenced (`log.tag=S`), so "Last run" is the only place a failure shows. |
| OS kills background work (RedMagic) | Setup requires battery "Unrestricted" for SleepCal, Samsung Health, Galaxy Wearable, Watch plugin. WorkManager catches up when allowed; app shows last successful run. |
| App data cleared / reinstall | Existing tagged events are adopted as LOCKED — never overwritten, never duplicated. App backup is off (`allowBackup="false"`), so a reinstall never restores another phone's calendar id. |
| Reinstalling SleepCal | The phone resets its unrestricted-battery setting; the setup screen shows the button again. |

## One-time setup & credentials

No API keys, Google Cloud project, OAuth, or Samsung partnership. Credentials needed: a **Samsung
account** (already signed in to Samsung Health) — used once on developer.samsung.com to download the SDK
and accept its license — and Samsung Health **Developer Mode** on the phone.

1. **SDK:** sign in at developer.samsung.com/health/data → download *Samsung Health Data SDK v1.1.0* →
   copy `libs/samsung-health-data-api-1.1.0.aar` into `app/libs/` (gitignored — the license forbids
   redistribution).
2. **Samsung Health:** ⋮ → Settings → About Samsung Health → tap the version ~10× → *Developer mode
   (Samsung Health Data SDK)* → agree → turn on the developer-mode toggle (called *Developer Mode for Data
   Read* in Samsung's docs; Samsung Health 7.x shows a single toggle). Leave package name / access code
   empty — those are only for writing data.
3. **Google:** calendar.google.com → Other calendars → + → Create new calendar → "Sleep"; pick a
   color; set its default notifications to none.
4. **Battery:** Unrestricted + allow auto-start for SleepCal, Samsung Health, Galaxy Wearable, Galaxy
   Watch plugin.
5. **Install:** enable Developer options → USB debugging; build & install (`gradlew installDebug`).
6. **In SleepCal:** *Grant Samsung Health access* (allow Sleep), calendar and notification permissions;
   confirm the "Sleep" calendar; tap *Sync now*.
7. **Notion Calendar:** make sure the Sleep calendar is visible.

Permissions (manifest): `READ_CALENDAR`, `WRITE_CALENDAR`, `POST_NOTIFICATIONS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The SDK's own manifest merges in `INTERNET` and a `<queries>`
entry for Samsung Health. Sleep access itself is granted inside Samsung Health, not as an Android
permission.

## Testing

- **Unit (JVM, `PlannerTest.kt`):** merge threshold; night vs nap (normal night, 03:00–05:30 short night,
  afternoon block); placeholder only after 15:00; median across midnight; fallback with < 3 nights;
  every row of the ownership table; placeholder → real update; asleep/awake math with stages and gaps;
  description text incl. the score line; horizon freezing.
- **Build gate:** `gradlew testDebugUnitTest assembleDebug` green.
- **On device:** *Sync now* → event appears in Google Calendar web and Notion Calendar; drag it → next
  sync leaves it; delete it → not recreated; a watch-off night → placeholder at 3 PM.

## Verified on the device (2026-09-11, RedMagic 10 Pro, Android 15, Samsung Health 7.00.6)

- Install, setup screen, calendar auto-select, battery exemption, 30-minute worker firing on its own.
- Placeholders written for nights with no data; the graphite colour key `8` is accepted by the provider.
- Samsung Health Data SDK reads on a non-Samsung phone in Developer Mode (Samsung logs "Bypassing
  checking signature … verified"). First real sync: `1 created, 2 updated` — both untouched placeholders
  replaced by real nights, colour cleared, score and stages in the notes; all three events reached
  Google Calendar.
- Health Connect route abandoned: Samsung Health never wrote to Health Connect on this phone.

## Open items

1. Background reads through the SDK while SleepCal is not in the foreground (the 30-minute run).
2. Whether Developer Mode survives Samsung Health updates (undocumented; the app says so if it doesn't).
3. Whether naps come through as separate sleep records.

## Out of scope

Stage-level events, cloud components, multi-user, Play Store distribution (would need Samsung
partnership), editing sleep back into Samsung Health.
