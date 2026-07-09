# Call Logger

An Android app (like a focused MacroDroid) that records your phone calls and lets
you export them to Excel.

## What it does

1. **First launch → email setup.** You enter an email; it's stored on the device
   and setup is complete. (You won't be asked again.)
2. **Automatic call recording.** Every incoming, outgoing, missed and rejected
   call is saved into a local database — read from the system call log when the
   app opens and refreshed right after each call ends. The data stays in the app
   even if you clear your phone's call history.
3. **Filter by duration.** Chips at the top: **Today, Yesterday, Day before
   yesterday, This week, This month, All.**
4. **Download as Excel.** Tap **Excel** to generate a real `.xlsx` file (number,
   name, type, date, time, duration) for the selected range. It's saved to your
   **Downloads** folder and a share sheet opens so you can email/save it.

## Tech

- Kotlin + Jetpack Compose (Material 3)
- Room (local database)
- A tiny built-in `.xlsx` writer (no Apache POI dependency)
- MediaStore (Android 10+) / legacy file API (Android 9-) for saving to Downloads

## How to build / run

1. Open the project folder in **Android Studio** (Hedgehog or newer). It will
   download the Gradle wrapper and sync automatically.
2. Connect a phone (or use an emulator with call-log data), then press **Run**.

> From the command line you can instead run `gradle wrapper` once (if you have
> Gradle installed) to generate `gradlew`, then `./gradlew installDebug`.

## Permissions

- `READ_CALL_LOG` — read call history (the core feature).
- `READ_PHONE_STATE` — detect when a call ends for near real-time capture.
- `WRITE_EXTERNAL_STORAGE` (Android 9 and below only) — write the Excel file to
  Downloads. On Android 10+ this isn't needed.

The app requests these at runtime on the main screen.

## Note about Google Play

`READ_CALL_LOG` is a *restricted* permission. The app works perfectly when
installed directly (sideloaded) or distributed privately. Publishing on the
Play Store with this permission requires a policy declaration and an approved
use case — fine for personal use, something to plan for if you publish publicly.

## Project structure

```
app/src/main/java/com/mydream/calllogger/
├─ MainActivity.kt            # entry; shows onboarding or home
├─ App.kt
├─ data/                      # Room entity, DAO, database, repository, type labels
├─ prefs/SettingsManager.kt   # stores the onboarding email
├─ calllog/PhoneStateReceiver.kt  # syncs after each call ends
├─ export/                    # DateRange filters, formatters, XlsxWriter, Exporter
└─ ui/                        # Compose screens, ViewModel, theme
```
