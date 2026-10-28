# trackelate

Compact Android journaling app with color-coded daily grades, swipable analytics, custom tracked quantities, and CSV-backed storage.

## Features

- Evening reminder you can answer **from the notification**: tap a 0–6 grade
  chip to log the day, then reply with a note inline. Tapping the notification
  opens the app on today. "Remind me in 1 h" snoozes it
- Home-screen widget: this month as a mood grid, your streak, and seven chips
  to grade today without opening the app
- Analytics: streak and best streak, 30-day average vs the previous 30 days,
  best weekday, a 90-day mood trend, a year-in-pixels grid (tap a day to open
  it) and weekday rhythm bars
- Custom tracked quantities, all stored as plain CSV

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## APK

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
