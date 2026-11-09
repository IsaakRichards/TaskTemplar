# MacroDroid parity checklist

The goal: every trigger, action and constraint MacroDroid offers. `[x]` = built in TaskTemplar.
List compiled from MacroDroid's published component names; it grows as gaps are found.

## Triggers
**Date & time:** [x] Day/time of day · [x] Regular interval · [ ] Day of week/month · [ ] Calendar event · [ ] Stopwatch · [x] Sunrise/sunset · [ ] Timer
**Device:** [x] Battery saver / DND / location-services state constraints · [x] Boot · [x] Screen on/off · [x] Device unlocked · [x] Airplane mode changed · [x] Auto-rotate changed · [ ] Auto-sync changed · [ ] Clipboard changed · [ ] Daydream/screensaver · [x] Dock/car mode · [ ] Failed login · [x] Flip device · [x] GPS enabled · [x] Orientation · [ ] Power button toggle · [x] Proximity · [x] Shake · [x] Silent/vibrate mode changed · [ ] Swipe (edge) · [x] Volume button pressed · [ ] Media button pressed · [ ] Activity recognition · [ ] Light / other sensors
**Battery & power:** [x] Battery level · [x] Power connected/disconnected · [x] Battery temperature
**Connectivity:** [x] Wi-Fi connect/disconnect (SSID) · [x] Bluetooth device · [x] Headphones · [ ] Wi-Fi SSID in range / Wi-Fi state · [ ] Mobile signal on/off · [ ] Data connection change · [x] USB device · [ ] NFC tag · [ ] Hotspot · [ ] VPN state
**Location:** [x] Geofence (no Google services needed) · [ ] Cell tower · [ ] Weather
**Apps & notifications:** [x] Notification received · [x] Screen content (text on screen) · [x] App launched/closed · [x] App installed/removed · [x] Music playing · [x] Notification bar button · [x] Intent received
**Phone & SMS:** [x] Incoming call · [x] Call active · [x] Call ended · [x] Missed call · [x] Outgoing call · [x] SMS received · [ ] SMS sent
**Variables:** [x] Variable changed
**User input:** [x] Shortcut / home-screen launcher · [x] Floating button · [x] Quick-settings tile · [x] Widget button · [x] Webhook (local network, no cloud) · [x] Empty trigger (macro with no triggers = manual) · [x] Text input dialog

## Actions
**Logic & flow:** [x] Wait · [x] If / Else If / Else / End If · [x] Loop / Break / Continue · [x] Set variable (int, bool, string, decimal) · [ ] Array / dictionary variables · [x] Confirm-next / If confirmed · [x] Run another macro · [x] Enable/disable macro · [x] Cancel running actions · [x] Text manipulation · [ ] Action blocks · [x] Log · [ ] Clear log
**Notify:** [x] Notification · [x] Toast · [x] Speak text · [x] Vibrate · [ ] Say time · [x] Message dialog · [x] Option dialog · [x] Clear notifications · [ ] Notification LED · [ ] Notification interaction (press button / reply)
**Volume & sound:** [x] Ringer mode · [x] DND · [x] Set volume · [x] Volume up/down · [x] Speakerphone · [x] Play/stop sound · [ ] Set ringtone / notification sound · [ ] Silent - vibrate off
**Media:** [x] Media control (play/pause/next) · [ ] Record microphone · [ ] Take picture · [ ] Upload photo
**Device:** [x] Brightness · [x] Flashlight · [x] Clipboard · [x] Screen on/off · [x] Keep awake · [x] Screen timeout · [ ] Dim screen · [x] Force rotation / auto-rotate · [x] Set wallpaper · [ ] Keyboard switch · [x] Keyguard / lock · [x] Car mode · [ ] Daydream · [x] Expand/collapse status bar · [x] Press back / home / recents · [x] UI interaction (tap/long-press/type/scroll/swipe via accessibility) · [x] Read screen text · [x] Screenshot · [x] Split screen · [x] Power menu · [x] Set alarm clock · [ ] Stopwatch · [x] Shell script (root / ADB)
**Connectivity:** [x] Wi-Fi on/off · [x] Bluetooth on/off · [x] Mobile data · [x] Airplane mode · [ ] Hotspot · [ ] Location mode / force location update · [x] Share location (Get location + {location_url}) · [ ] Force location update
**Apps:** [x] Launch app · [x] Open website · [x] Kill background app · [ ] Launch activity / shortcut · [x] Launch home screen · [x] Send intent · [ ] Open file · [ ] Voice search
**Phone & messaging:** [x] Make call · [x] Answer / reject call · [x] Send SMS · [x] Send email · [ ] Clear call log · [x] Open call log
**Web & files:** [x] HTTP request · [ ] UDP command · [ ] Write to file · [ ] File operations (copy/move/delete) · [ ] Add calendar entry · [ ] Export macros

## Constraints
[x] Time window · [x] Day of week · [x] Battery level · [x] Charging · [x] Wi-Fi state · [x] Screen state · [x] Day of month · [x] Month · [x] Airplane mode · [x] App running/foreground · [ ] Auto-rotate · [x] Bluetooth state · [x] Call state · [x] Device locked · [x] Device orientation · [x] Dock state · [x] Geofence · [x] Headphones · [ ] Hotspot · [ ] Light level · [x] Macro enabled/running · [x] Mobile data · [x] Music playing · [x] Notification present · [ ] Proximity · [x] Ringer/volume level · [x] Roaming · [ ] Signal · [x] Variable value · [ ] Weekday/weekend · [ ] Logic (AND/OR/NOT groups)

## Platform features
[x] Run log · [x] Export/import · [x] Test-run · [ ] Categories · [x] Local variables (per run) · [x] Global variables screen · [ ] Templates (local library) · [x] Home-screen widgets · [x] Quick-settings tiles · [x] Floating buttons · [ ] Tasker/Locale plugin host · [x] Webhook server · [ ] Geofence manager · [ ] Backups to file / schedule
