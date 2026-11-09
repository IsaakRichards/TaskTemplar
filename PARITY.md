# MacroDroid parity checklist

**Status: 199 / 200.** The one item left, activity recognition, needs Google Play Services, which the de-Googled build deliberately avoids.

The goal: every trigger, action and constraint MacroDroid offers. `[x]` = built in TaskTemplar.
List compiled from MacroDroid's published component names; it grows as gaps are found.

## Triggers
**Date & time:** [x] Day/time of day · [x] Regular interval · [x] Day of week/month · [x] Calendar event · [x] Stopwatch · [x] Sunrise/sunset · [x] Timer (stopwatch / countdown trigger)
**Device:** [x] Battery saver / DND / location-services state constraints · [x] Boot · [x] Screen on/off · [x] Device unlocked · [x] Airplane mode changed · [x] Auto-rotate changed · [x] Auto-sync changed · [x] Clipboard changed (Android 10+ limits this to when the app is open) · [x] Daydream/screensaver · [x] Dock/car mode · [x] Failed login · [x] Flip device · [x] GPS enabled · [x] Orientation · [x] Power button toggle · [x] Proximity · [x] Shake · [x] Silent/vibrate mode changed · [x] Swipe (edge) · [x] Volume button pressed · [x] Media button pressed · [ ] Activity recognition (needs Google Play Services; not planned for the de-Googled build) · [x] Light / other sensors
**Battery & power:** [x] Battery level · [x] Power connected/disconnected · [x] Battery temperature
**Connectivity:** [x] Wi-Fi connect/disconnect (SSID) · [x] Bluetooth device · [x] Headphones · [x] Wi-Fi SSID in range / Wi-Fi state · [x] Mobile signal on/off · [x] Data connection change · [x] USB device · [x] NFC tag · [x] Hotspot · [x] VPN state
**Location:** [x] Geofence (no Google services needed) · [x] Cell tower · [x] Weather
**Apps & notifications:** [x] Notification received · [x] Screen content (text on screen) · [x] App launched/closed · [x] App installed/removed · [x] Music playing · [x] Notification bar button · [x] Intent received
**Phone & SMS:** [x] Incoming call · [x] Call active · [x] Call ended · [x] Missed call · [x] Outgoing call · [x] SMS received · [x] SMS sent
**Variables:** [x] Variable changed
**User input:** [x] Shortcut / home-screen launcher · [x] Floating button · [x] Quick-settings tile · [x] Widget button · [x] Webhook (local network, no cloud) · [x] Empty trigger (macro with no triggers = manual) · [x] Text input dialog

## Actions
**Logic & flow:** [x] Wait · [x] If / Else If / Else / End If · [x] Loop / Break / Continue · [x] Set variable (int, bool, string, decimal) · [x] Array / dictionary variables · [x] Confirm-next / If confirmed · [x] Run another macro · [x] Enable/disable macro · [x] Cancel running actions · [x] Text manipulation · [x] Action blocks · [x] Log · [x] Clear log
**Notify:** [x] Notification · [x] Toast · [x] Speak text · [x] Vibrate · [x] Say time · [x] Message dialog · [x] Option dialog · [x] Clear notifications · [x] Notification LED (phones that still have one) · [x] Notification interaction (press button / reply)
**Volume & sound:** [x] Ringer mode · [x] DND · [x] Set volume · [x] Volume up/down · [x] Speakerphone · [x] Play/stop sound · [x] Set ringtone / notification sound · [x] Silent - vibrate off (vibrate when ringing)
**Media:** [x] Media control (play/pause/next) · [x] Record microphone · [x] Take picture · [x] Upload photo
**Device:** [x] Brightness · [x] Flashlight · [x] Clipboard · [x] Screen on/off · [x] Keep awake · [x] Screen timeout · [x] Dim screen · [x] Force rotation / auto-rotate · [x] Set wallpaper · [x] Keyboard switch · [x] Keyguard / lock · [x] Car mode · [x] Daydream (start screensaver) · [x] Expand/collapse status bar · [x] Press back / home / recents · [x] UI interaction (tap/long-press/type/scroll/swipe via accessibility) · [x] Read screen text · [x] Screenshot · [x] Split screen · [x] Power menu · [x] Set alarm clock · [x] Stopwatch · [x] Shell script (root / ADB)
**Connectivity:** [x] Wi-Fi on/off · [x] Bluetooth on/off · [x] Mobile data · [x] Airplane mode · [x] Hotspot · [x] Location mode / force location update · [x] Share location (Get location + {location_url}) · [x] Force location update
**Apps:** [x] Launch app · [x] Open website · [x] Kill background app · [x] Launch activity / shortcut · [x] Launch home screen · [x] Send intent · [x] Open file · [x] Voice search
**Phone & messaging:** [x] Make call · [x] Answer / reject call · [x] Send SMS · [x] Send email · [x] Clear call log · [x] Open call log
**Web & files:** [x] HTTP request · [x] UDP command · [x] Write to file · [x] File operations (copy/move/delete) · [x] Add calendar entry · [x] Export macros

## Constraints
[x] Time window · [x] Day of week · [x] Battery level · [x] Charging · [x] Wi-Fi state · [x] Screen state · [x] Day of month · [x] Month · [x] Airplane mode · [x] App running/foreground · [x] Auto-rotate · [x] Bluetooth state · [x] Call state · [x] Device locked · [x] Device orientation · [x] Dock state · [x] Geofence · [x] Headphones · [x] Hotspot · [x] Light level · [x] Macro enabled/running · [x] Mobile data · [x] Music playing · [x] Notification present · [x] Proximity · [x] Ringer/volume level · [x] Roaming · [x] Signal · [x] Variable value · [x] Weekday/weekend · [x] Logic (AND/OR/NOT groups)

## Platform features
[x] Run log · [x] Export/import · [x] Test-run · [x] Categories · [x] Local variables (per run) · [x] Global variables screen · [x] Templates (local library) · [x] Home-screen widgets · [x] Quick-settings tiles · [x] Floating buttons · [x] Tasker/Locale plugin host (action plugins) · [x] Webhook server · [x] Geofence manager (saved places ★) · [x] Backups to file / schedule (Back up macros action + template)
