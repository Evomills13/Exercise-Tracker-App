# Exercise Routine Tracker

Android app for a home calisthenics + running plan: weekly sessions, set and run logging,
animated form guides, plan length (24/36/52 weeks), targets, and progress check-ins.

## Getting the app
Every push to `main` builds a signed APK automatically (Actions tab).
The newest one is on the **Releases** page as `ExerciseRoutineTracker.apk`.

## Installing on your phone
1. Open the latest release on your phone (signed in to GitHub) and download `ExerciseRoutineTracker.apk`.
2. Tap the download. Android will ask to allow installing apps from your browser or Files app: allow it for that app.
3. Tap **Install**. Updates install over the old version and keep your logs.

## Your data
Logs are stored on the phone, inside the app. Use **Progress → Backup & restore** to save a copy
(e.g. to Google Drive) or to bring logs over from the web version.

## Project layout
- `app/src/main/assets/index.html`: the whole app (HTML/CSS/JS), shown in a WebView.
- `app/src/main/java/.../MainActivity.java`: the WebView shell, on-phone storage and backup file handling.
- `app/release.keystore`: personal signing key so updates install over each other. Keep this repository private.
