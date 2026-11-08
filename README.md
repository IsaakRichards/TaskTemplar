# TaskTemplar

An ad-free, tracker-free automation app for Android in the spirit of MacroDroid.
Build **macros** out of three parts:

- **Triggers**: *when* something happens (any one starts the macro)
- **Constraints**: *only if* conditions are right (all, or any, must pass)
- **Actions**: *do* these things, top to bottom

No ads, no analytics, no accounts, no network calls except the HTTP actions you create yourself.
Parity progress against MacroDroid is tracked in [PARITY.md](PARITY.md).

Macros live in a JSON file on the phone and can be exported or imported from the ⋮ menu.

## What's in v0.1

| Triggers | Constraints | Actions |
|---|---|---|
| Time of day (with weekdays) | Time window (crosses midnight) | Show notification |
| Regular interval | Day of week | Popup (toast) |
| Device boot | Battery above/below | Speak text (TTS) |
| Screen on / off / unlocked | Charging / not charging | Vibrate |
| Battery level crosses N% | Wi-Fi connected (optionally SSID) | Ringer mode |
| Power connected / disconnected | Screen on / off | Do Not Disturb mode |
| Wi-Fi connected / disconnected (optional SSID) | | Set volume (media/ring/notif/alarm) |
| Bluetooth device connected / disconnected | | Screen brightness |
| Headphones plugged / unplugged | | Flashlight on/off/toggle |
| Notification received (app + text filter) | | Copy to clipboard |
| | | Launch app |
| | | Open website |
| | | HTTP GET/POST (webhooks, home server) |
| | | Wait |

**Magic text**: text fields accept `{time}`, `{date}`, `{battery}`, `{ssid}`, `{macro}`, `{trigger}`;
notification triggers add `{app}`, `{title}`, `{text}`; Bluetooth adds `{device}`; and after an
HTTP action, later actions can use `{http_code}` and `{http_body}`.

Every macro has a ▶ button for a test run, and the run log (☰ icon) shows what fired and what failed.

## Getting the APK

Push this repo to GitHub. The workflow in `.github/workflows/build.yml` builds the APK on every
push to `main`. Download it from the run's **Artifacts**. Push a tag like `v0.1.0` and it also
attaches the APK to a GitHub Release.

Or open the folder in Android Studio and hit Run.

> The release build is signed with the debug key so it installs straight away. Use your own
> keystore if you ever distribute it.

## First run

Open ⚙ **Permissions** and grant what your macros need. The important one is
**Ignore battery optimization**, because without it Android may kill the engine.
On some phones (Samsung, Xiaomi and others) you may also need to set the app to
"Unrestricted" battery use in system settings.

## How it works

- `MacroService`: a foreground service that listens for battery, power, screen, headset,
  Bluetooth and Wi-Fi events (Android no longer delivers most of these to sleeping apps).
- `AlarmScheduler`: exact alarms for time and interval triggers, rescheduled after each fire,
  after boot, and whenever macros change.
- `NotifListener`: the notification-access service for notification triggers.
- `MacroEngine`: matches events to triggers, checks constraints, runs actions in a coroutine.
- `Catalog`: one registry of every trigger, constraint and action, including its settings.
  The editor UI builds itself from it, so a new action only needs a spec there plus a branch in
  `Actions.execute`.

## Stack

Kotlin 2.0 · Jetpack Compose (Material 3) · minSdk 26 (Android 8) · targetSdk 34 · no third-party deps
beyond AndroidX and coroutines.
