# SleepCal

Turns the sleep your Galaxy Watch logs in Samsung Health into events on a Google "Sleep" calendar
(and therefore Notion Calendar), every morning, automatically. Missing nights get a placeholder you
can drag into place; anything you edit or delete is never touched again.

How it works and why: [design spec](docs/superpowers/specs/2026-09-10-sleepcal-design.md).

## One-time setup

1. **Samsung Health Data SDK:** sign in with your Samsung account at
   [developer.samsung.com/health/data](https://developer.samsung.com/health/data/overview.html#SDK-download),
   download *Samsung Health Data SDK v1.1.0*, accept the license, and copy
   `libs/samsung-health-data-api-1.1.0.aar` from the zip into `app/libs/`. It is gitignored on purpose:
   the license doesn't allow redistributing it.
2. **Samsung Health Developer Mode:** Samsung Health → ⋮ → Settings → About Samsung Health → tap the
   version number ~10 times → *Developer mode (Samsung Health Data SDK)* → agree → turn the toggle on.
   Leave the package name and access code empty (they're only for apps that write data).
3. **Google Calendar:** at calendar.google.com, Other calendars → + → Create new calendar → "Sleep".
   Pick a color and set its default notifications to none.
4. **Battery:** set Unrestricted (and allow auto-start) for SleepCal, Samsung Health, Galaxy Wearable
   and the Galaxy Watch plugin.
5. **Install:** enable Developer options → USB debugging on the phone, plug it in, then:
   ```bash
   export JAVA_HOME="/c/Program Files/Java/jdk-25.0.4.1" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
   ./gradlew installDebug
   ```
6. **Open SleepCal:** tap *Grant Samsung Health access* (allow Sleep), grant calendar and notification
   access, confirm the "Sleep" calendar is selected, allow unrestricted battery, tap **Sync now**.
7. **Notion Calendar:** make sure the Sleep calendar is visible.

If something's wrong, SleepCal says so on its screen and in "Last run" (e.g. Developer Mode switched
off after a Samsung Health update).

## Build and test

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.4.1" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew testDebugUnitTest assembleDebug
```

All the rules live in `Planner.kt` (pure Kotlin) and are covered by `PlannerTest.kt`. Tuning knobs —
merge gap, placeholder cutoff, fallback times — are the constants at the top of `Planner.kt`.
