# SleepCal

Turns the sleep your Galaxy Watch logs in Samsung Health into events on a Google "Sleep" calendar
(and therefore Notion Calendar), every morning, automatically. Missing nights get a placeholder you
can drag into place; anything you edit or delete is never touched again.

How it works and why: [design spec](docs/superpowers/specs/2026-09-10-sleepcal-design.md).

## One-time setup

1. **Health Connect:** Settings → Health Connect → Data and access → Sleep — check that Samsung Health
   entries are there. If not: Samsung Health → Settings → Health Connect → allow Sleep, and turn on
   *Consent to processing of health and wellness data*.
2. **Google Calendar:** at calendar.google.com, Other calendars → + → Create new calendar → "Sleep".
   Pick a color and set its default notifications to none.
3. **Battery:** set Unrestricted (and allow auto-start) for SleepCal, Samsung Health, Galaxy Wearable
   and the Galaxy Watch plugin.
4. **Install:** enable Developer options → USB debugging on the phone, plug it in, then:
   ```bash
   export JAVA_HOME="/c/Program Files/Java/jdk-25.0.4.1" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
   ./gradlew installDebug
   ```
5. **Open SleepCal:** grant Health Connect, calendar and notification access, confirm the "Sleep"
   calendar is selected, allow unrestricted battery, tap **Sync now**.
6. **Notion Calendar:** make sure the Sleep calendar is visible.

## Build and test

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.4.1" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew testDebugUnitTest assembleDebug
```

All the rules live in `Planner.kt` (pure Kotlin) and are covered by `PlannerTest.kt`. Tuning knobs —
merge gap, placeholder cutoff, fallback times — are the constants at the top of `Planner.kt`.
