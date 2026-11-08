# MacroDroid parity checklist

The goal: every trigger, action and constraint MacroDroid offers. `[x]` = built in TaskTemplar.
List compiled from MacroDroid's published component names; it grows as gaps are found.

## Triggers
**Date & time:** [x] Day/time of day · [x] Regular interval · [ ] Day of week/month · [ ] Calendar event · [ ] Stopwatch · [ ] Sunrise/sunset · [ ] Timer
**Device:** [x] Boot · [x] Screen on/off · [x] Device unlocked · [ ] Airplane mode changed · [ ] Auto-rotate changed · [ ] Auto-sync changed · [ ] Clipboard changed · [ ] Daydream/screensaver · [ ] Dock/car mode · [ ] Failed login · [ ] Flip device · [ ] GPS enabled · [ ] Orientation · [ ] Power button toggle · [ ] Proximity · [ ] Shake · [ ] Silent/vibrate mode changed · [ ] Swipe (edge) · [ ] Volume button pressed · [ ] Media button pressed · [ ] Activity recognition · [ ] Light / other sensors
**Battery & power:** [x] Battery level · [x] Power connected/disconnected · [ ] Battery temperature
**Connectivity:** [x] Wi-Fi connect/disconnect (SSID) · [x] Bluetooth device · [x] Headphones · [ ] Wi-Fi SSID in range / Wi-Fi state · [ ] Mobile signal on/off · [ ] Data connection change · [ ] USB device · [ ] NFC tag · [ ] Hotspot · [ ] VPN state
**Location:** [ ] Geofence · [ ] Cell tower · [ ] Weather
**Apps & notifications:** [x] Notification received · [ ] App launched/closed · [ ] App installed/removed · [ ] Music playing · [ ] Notification bar button · [ ] Intent received
**Phone & SMS:** [ ] Incoming call · [ ] Call active · [ ] Call ended · [ ] Missed call · [ ] Outgoing call · [ ] SMS received · [ ] SMS sent
**User input:** [ ] Shortcut / home-screen launcher · [ ] Floating button · [ ] Quick-settings tile · [ ] Widget button · [ ] Webhook (URL) · [ ] Empty trigger

## Actions
**Logic & flow:** [x] Wait · [ ] If / Else If / Else / End If · [ ] Loop / Break / Continue · [ ] Set variable (int, bool, string, decimal, array, dictionary) · [ ] Confirm-next / If confirmed · [ ] Run another macro · [ ] Enable/disable macro · [ ] Cancel running actions · [ ] Text manipulation · [ ] Action blocks · [ ] Log / clear log
**Notify:** [x] Notification · [x] Toast · [x] Speak text · [x] Vibrate · [ ] Say time · [ ] Message dialog · [ ] Option dialog · [ ] Clear notifications · [ ] Notification LED · [ ] Notification interaction (press button / reply)
**Volume & sound:** [x] Ringer mode · [x] DND · [x] Set volume · [ ] Volume up/down · [ ] Speakerphone · [ ] Play/stop sound · [ ] Set ringtone / notification sound · [ ] Silent - vibrate off
**Media:** [ ] Media control (play/pause/next) · [ ] Record microphone · [ ] Take picture · [ ] Upload photo
**Device:** [x] Brightness · [x] Flashlight · [x] Clipboard · [ ] Screen on/off · [ ] Keep awake · [ ] Screen timeout · [ ] Dim screen · [ ] Force rotation / auto-rotate · [ ] Set wallpaper · [ ] Keyboard switch · [ ] Keyguard / lock · [ ] Car mode · [ ] Daydream · [ ] Expand/collapse status bar · [ ] Press back / home / recents · [ ] UI interaction (tap/scroll/type via accessibility) · [ ] Set alarm clock · [ ] Stopwatch · [ ] Shell script (root / ADB)
**Connectivity:** [ ] Wi-Fi on/off · [ ] Bluetooth on/off · [ ] Mobile data · [ ] Airplane mode · [ ] Hotspot · [ ] Location mode / force location update · [ ] Share location
**Apps:** [x] Launch app · [x] Open website · [ ] Kill background app · [ ] Launch activity / shortcut · [ ] Launch home screen · [ ] Send intent · [ ] Open file · [ ] Voice search
**Phone & messaging:** [ ] Make call · [ ] Answer / reject call · [ ] Send SMS · [ ] Send email · [ ] Clear call log · [ ] Open call log
**Web & files:** [x] HTTP request · [ ] UDP command · [ ] Write to file · [ ] File operations (copy/move/delete) · [ ] Add calendar entry · [ ] Export macros

## Constraints
[x] Time window · [x] Day of week · [x] Battery level · [x] Charging · [x] Wi-Fi state · [x] Screen state · [ ] Day of month · [ ] Month · [ ] Airplane mode · [ ] App running/foreground · [ ] Auto-rotate · [ ] Bluetooth state · [ ] Call state · [ ] Device locked · [ ] Device orientation · [ ] Dock state · [ ] Geofence · [ ] Headphones · [ ] Hotspot · [ ] Light level · [ ] Macro enabled/running · [ ] Mobile data · [ ] Music playing · [ ] Notification present · [ ] Proximity · [ ] Ringer/volume level · [ ] Roaming · [ ] Signal · [ ] Variable value · [ ] Weekday/weekend · [ ] Logic (AND/OR/NOT groups)

## Platform features
[x] Run log · [x] Export/import · [x] Test-run · [ ] Categories · [ ] Local variables per macro · [ ] Global variables screen · [ ] Templates (local library) · [ ] Home-screen widgets · [ ] Quick-settings tiles · [ ] Floating buttons · [ ] Tasker/Locale plugin host · [ ] Webhook server · [ ] Geofence manager · [ ] Backups to file / schedule
