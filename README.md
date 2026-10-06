# RepDayrise

A habit tracker for Android, with optional accountability partners.

It is a personal project, modeled after the Dayrise app and rebuilt from scratch in Kotlin and Jetpack Compose. The idea is simple: finish your habits and the sun rises. The app works fully offline, and a small Cloudflare backend lets you share your habit list with the people who keep you on track.

## What it does

- **Three kinds of habit.** Simple check-offs, counts toward a goal, and timers.
- **Flexible schedules.** Daily (on the weekdays you choose), weekly, or monthly with a number of times per period.
- **Streaks and history.** Current and best streaks, a month calendar, and a year-long contribution graph for each habit.
- **Health Connect.** A habit can read steps, distance, water, sleep, exercise, or active calories and log itself.
- **Reminders.** Per-habit notifications at the times you pick.
- **Widgets.** Four home-screen widgets: Sunrise, Today's habits, Dashboard, and Rhythm.
- **Accountability partners.** Publish a habit list, hand out an invite code, and let a partner follow your progress. No accounts are needed.
- **Theming.** System, light, or dark, with a few app icons to choose from.

## Screenshots

Coming soon.

## Built with

- Kotlin, Jetpack Compose, Material 3, Navigation 3
- Room and DataStore for local storage
- Glance for widgets
- Health Connect for health data
- Cloudflare Workers and D1 (TypeScript) for the sharing backend

The app follows an MVVM structure. Screens and view models live under `ui`, storage and the sharing client under `data`, and the streak and scheduling rules under `domain`, which keeps them easy to unit test.

## Getting started

You will need a recent Android Studio and a device or emulator running Android 12 (API 31) or newer.

1. Clone the repository and open it in Android Studio.
2. Let Gradle sync. It will fetch the JDK toolchain it needs.
3. Run the `app` configuration.

Health Connect features need Health Connect to be available on the device.

## Sharing with partners

Everything above works without a server. To follow or be followed, the app needs the address of a sharing backend. You can either:

- open **Partners**, then **Server**, and paste the address, or
- add `dayrise.sharingUrl=https://your-address` to `local.properties` before building.

The backend is a single Cloudflare Worker with a D1 database. Setup, the API, and the limits are described in [`backend/README.md`](backend/README.md).

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

The unit tests cover streaks, schedules, progress, and what gets shared with partners. A client-to-server check also runs when a test server is provided, and the backend has an end-to-end smoke script, `npm run smoke`. Both are described in the backend README.

## Project layout

```
app/       Android application
backend/   Cloudflare Worker for partner sharing
```

## Status

A work in progress that I use and keep improving. Screenshots and a short walkthrough are on the way.

## Acknowledgements

Inspired by the Dayrise habit app. This project is an independent reimplementation made for learning, and is not affiliated with it.
