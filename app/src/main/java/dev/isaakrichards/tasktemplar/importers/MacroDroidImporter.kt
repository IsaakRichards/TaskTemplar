package dev.isaakrichards.tasktemplar.importers

import dev.isaakrichards.tasktemplar.importers.Imp.cond
import dev.isaakrichards.tasktemplar.importers.Imp.s
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.Step
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Converts MacroDroid exports (.macro / .mdr / .category JSON) into TaskTemplar macros.
 * Field names follow MacroDroid's export format: every trigger/action/constraint is an object
 * whose "m_classType" names its kind, with "m_"-prefixed settings.
 */
object MacroDroidImporter {

    private const val APP = "MacroDroid"

    fun looksLike(text: String): Boolean {
        val t = text.trimStart()
        return (t.startsWith("{") || t.startsWith("[")) &&
            ("m_classType" in text || "macroList" in text || "m_triggerList" in text)
    }

    fun parse(text: String): ImportResult {
        val notes = mutableListOf<String>()
        val trimmed = text.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val geofences = (root as? JSONObject)?.let { geofenceMap(it) } ?: emptyMap()
        val raw: List<JSONObject> = when {
            root is JSONArray -> objects(root)
            (root as JSONObject).has("macroList") -> objects(root.optJSONArray("macroList"))
            root.has("m_triggerList") || root.has("m_actionList") -> listOf(root)
            root.has("macro") -> listOfNotNull(root.optJSONObject("macro"))
            else -> emptyList()
        }
        if (raw.isEmpty()) notes += "No MacroDroid macros found in that file."
        val macros = raw.map { convertMacro(F(it), geofences, notes) }
        return ImportResult(APP, macros, notes)
    }

    // ---------- field access tolerant of key spelling ("m_SSIDList" vs "ssid_list") ----------

    /** Wraps a MacroDroid object; looks up fields ignoring the "m_" prefix, case and underscores. */
    private class F(val o: JSONObject) {
        private val keys: Map<String, String> = o.keys().asSequence().associateBy { norm(it) }

        fun raw(name: String): Any? = keys[norm(name)]?.let { o.opt(it) }?.takeIf { it != JSONObject.NULL }
        fun str(name: String, def: String = ""): String = raw(name)?.toString() ?: def
        fun int(name: String, def: Int = 0): Int = when (val v = raw(name)) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: def
            else -> def
        }
        fun bool(name: String, def: Boolean = false): Boolean = when (val v = raw(name)) {
            is Boolean -> v
            is String -> v.equals("true", true)
            is Number -> v.toInt() != 0
            else -> def
        }
        fun arr(name: String): JSONArray? = raw(name) as? JSONArray
        fun obj(name: String): F? = (raw(name) as? JSONObject)?.let { F(it) }
        fun bools(name: String): List<Boolean> = arr(name)?.let { a -> (0 until a.length()).map { a.optBoolean(it) } } ?: emptyList()
        fun strings(name: String): List<String> = arr(name)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun objects(name: String): List<F> = MacroDroidImporter.objects(arr(name)).map { F(it) }
        val cls: String get() = str("classType").substringAfterLast('.')

        companion object {
            fun norm(k: String) = k.removePrefix("m_").replace("_", "").lowercase()
        }
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun geofenceMap(root: JSONObject): Map<String, Triple<Double, Double, Double>> {
        val map = root.optJSONObject("geofenceData")?.optJSONObject("geofenceMap") ?: return emptyMap()
        return map.keys().asSequence().mapNotNull { id ->
            val g = F(map.optJSONObject(id) ?: return@mapNotNull null)
            val lat = g.raw("latitude") as? Number ?: return@mapNotNull null
            val lng = g.raw("longitude") as? Number ?: return@mapNotNull null
            id to Triple(lat.toDouble(), lng.toDouble(), (g.raw("radius") as? Number)?.toDouble() ?: 150.0)
        }.toMap()
    }

    // ---------- magic text: [v=name], [battery], [sms_message] … → {v=name}, {battery}, {text} ----------

    private val textCodes = mapOf(
        "battery" to "{battery}", "sms_number" to "{number}", "sms_name" to "{number}", "call_number" to "{number}",
        "call_name" to "{number}", "sms_message" to "{text}", "notification" to "{text}", "notification_title" to "{title}",
        "not_title" to "{title}", "app_name" to "{app}", "app_package" to "{app}", "ssid" to "{ssid}",
        "hour" to "{hour}", "minute" to "{minute}", "hour_0" to "{hour}", "minute_0" to "{minute}",
        "day_of_month" to "{day}", "month_digit" to "{month}", "year" to "{year}", "dayofweek" to "{weekday}",
        "date" to "{date}", "time" to "{time}", "macro_name" to "{macro}", "trigger_name" to "{trigger}",
        "clipboard" to "{clip}", "battery_temp" to "{battery_temp}",
    )
    private val codeRx = Regex("""\[(v|lv)=([^\]]+)\]|\[([a-z_0-9]+)\]""")

    fun text(s: String): String = codeRx.replace(s) { m ->
        if (m.groupValues[1].isNotEmpty()) "{${m.groupValues[1]}=${m.groupValues[2]}}"
        else textCodes[m.groupValues[3]] ?: m.value
    }

    // ---------- macros ----------

    private fun convertMacro(m: F, geo: Map<String, Triple<Double, Double, Double>>, notes: MutableList<String>): Macro {
        val name = m.str("name", "Imported macro").ifBlank { "Imported macro" }
        val local = mutableListOf<String>()
        val triggers = m.objects("triggerList").flatMap { trigger(it, geo, local) }
        val constraints = m.objects("constraintList").mapNotNull { constraint(it, local) }
        val actions = m.objects("actionList").flatMap { action(it, local) }
        local.forEach { notes += "“$name”: $it" }
        return Macro(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = false,
            category = m.str("category").ifBlank { "Imported" },
            triggers = triggers,
            constraints = constraints,
            constraintMode = if (m.bool("isOrCondition")) Macro.MODE_ANY else Macro.MODE_ALL,
            actions = actions,
        )
    }

    // ---------- triggers ----------

    private fun trigger(t: F, geo: Map<String, Triple<Double, Double, Double>>, notes: MutableList<String>): List<Step> {
        fun skip(why: String): List<Step> { notes += "trigger ${t.cls} skipped ($why)"; return emptyList() }
        fun one(step: Step) = listOf(step)
        val pkgs = t.strings("packageNameList").filter { it.isNotBlank() && !it.startsWith("Any") && it != "allApplications" }

        return when (t.cls) {
            "TimerTrigger" -> {
                val days = Imp.daysFromMondayFirst(t.bools("daysOfWeek")).ifEmpty { "1,2,3,4,5,6,7" }
                one(s("time", "time" to Imp.hhmm(t.int("hour"), t.int("minute")), "days" to days))
            }
            "DayTrigger" -> if (t.int("dayOfMonth") > 0) {
                one(s("date", "day" to t.int("dayOfMonth").toString(), "time" to Imp.hhmm(t.int("hour"), t.int("minute"))))
            } else {
                notes += "Day trigger imported as a daily time; check the day"
                one(s("time", "time" to Imp.hhmm(t.int("hour"), t.int("minute"))))
            }
            "RegularIntervalTrigger" -> {
                val secs = t.int("seconds").takeIf { it > 0 } ?: (t.int("minutes") * 60)
                one(s("interval", "minutes" to (secs / 60).coerceAtLeast(1).toString()))
            }
            "BatteryLevelTrigger" -> if (t.int("option") == 0) {
                one(s("battery_level", "direction" to if (t.bool("decreasesTo", true)) "below" else "above", "level" to t.int("batteryLevel", 50).toString()))
            } else skip("“any change” isn't supported")
            "BatteryTemperatureTrigger" ->
                one(s("battery_temp", "direction" to if (t.bool("decreasesTo")) "below" else "above", "temp" to t.int("temperature", 40).toString()))
            "ExternalPowerTrigger" -> one(s("power", "state" to if (t.bool("powerConnected", true)) "connected" else "disconnected"))
            "BootTrigger" -> one(s("boot"))
            "ScreenOnOffTrigger" -> one(s("screen", "state" to if (t.bool("screenOn", true)) "on" else "off"))
            "DeviceUnlockedTrigger" -> one(s("screen", "state" to "unlocked"))
            "HeadphonesTrigger" -> one(s("headphones", "state" to if (t.bool("headphonesConnected", true)) "plugged" else "unplugged"))
            "BluetoothTrigger" -> when (t.int("btState")) {
                2, 3 -> one(
                    s(
                        "bluetooth", "state" to if (t.int("btState") == 2) "connected" else "disconnected",
                        "device" to if (t.bool("anyDevice") || t.str("deviceName").startsWith("Any")) "" else t.str("deviceName"),
                    ),
                )
                else -> skip("Bluetooth on/off isn't a trigger here; use the Bluetooth constraint")
            }
            "WifiConnectionTrigger" -> when (t.int("wifiState")) {
                2, 3 -> one(
                    s(
                        "wifi", "state" to if (t.int("wifiState") == 2) "connected" else "disconnected",
                        "ssid" to (t.strings("ssidList").firstOrNull { !it.startsWith("Any") && !it.startsWith("[") } ?: ""),
                    ),
                )
                else -> skip("Wi-Fi on/off isn't a trigger here")
            }
            "WifiSSIDTrigger" -> t.strings("ssidList").filter { it.isNotBlank() }.map {
                s("wifi_in_range", "ssid" to it, "state" to if (t.bool("inRange", true)) "appears" else "disappears")
            }.ifEmpty { skip("no network names") }
            "IncomingCallTrigger" -> one(s("call", "state" to "incoming")).also { contactsNote(t, notes) }
            "CallActiveTrigger" -> one(s("call", "state" to "answered"))
            "CallEndedTrigger" -> one(s("call", "state" to "ended"))
            "CallMissedTrigger" -> one(s("call", "state" to "missed"))
            "OutgoingCallTrigger" -> one(s("call", "state" to "outgoing"))
            "IncomingSMSTrigger" -> one(s("sms_received", "contains" to t.str("smsContent"))).also { contactsNote(t, notes) }
            "NotificationTrigger" -> (pkgs.ifEmpty { listOf("") }).map {
                s("notification", "app" to it, "contains" to t.str("textContent"))
            }
            "ApplicationLaunchedTrigger" -> (pkgs.ifEmpty { listOf("") }).map {
                s("app_launched", "app" to it, "state" to if (t.bool("launched", true)) "opened" else "closed")
            }
            "ApplicationInstalledRemovedTrigger" -> one(
                s(
                    "app_installed", "app" to (pkgs.firstOrNull() ?: ""),
                    "state" to when {
                        t.bool("updated") -> "updated"
                        t.bool("installed", true) -> "installed"
                        else -> "removed"
                    },
                ),
            )
            "SilentModeTrigger" -> one(s("silent_mode", "state" to if (t.bool("silentEnabled", true)) "silent" else "normal"))
            "AirplaneModeTrigger" -> one(s("airplane_changed", "state" to if (t.bool("airplaneModeEnabled", true)) "on" else "off"))
            "GPSEnabledTrigger" -> one(s("gps_changed", "state" to if (t.bool("gpsModeEnabled", true)) "on" else "off"))
            "DayDreamTrigger" -> one(s("daydream", "state" to if (t.bool("dayDreamEnabled", true)) "started" else "stopped"))
            "DockTrigger" -> one(s("dock", "state" to "docked"))
            "MusicPlayingTrigger" -> one(s("music", "state" to if (t.int("option") == 0) "playing" else "stopped"))
            "ShakeDeviceTrigger" -> one(s("shake"))
            "FlipDeviceTrigger" -> one(s("flip", "state" to if (t.bool("faceDown", true)) "face_down" else "face_up"))
            "ProximityTrigger" -> one(s("proximity", "state" to if (t.bool("near", true)) "near" else "far"))
            "OrientationTrigger" -> one(s("orientation", "state" to if (t.int("option") == 0) "portrait" else "landscape"))
            "PowerButtonToggleTrigger" -> one(s("power_button", "presses" to t.int("numToggles", 3).toString()))
            "FailedLoginTrigger" -> one(s("login_failed", "attempts" to t.int("numFailures", 1).toString()))
            "ClipboardChangeTrigger" -> one(s("clipboard_changed", "contains" to t.str("text")))
            "IntentReceivedTrigger" -> one(s("intent_received", "action" to t.str("action")))
            "NFCTrigger" -> one(s("nfc")).also { notes += "NFC trigger matches any tag; scan your tag and add its id" }
            "StopwatchTrigger" -> one(s("stopwatch", "name" to t.str("stopwatchName", "timer"), "seconds" to t.int("seconds", 60).toString()))
            "CalendarTrigger" -> one(
                s(
                    "calendar", "when" to if (t.bool("eventStart", true)) "start" else "end", "title" to t.str("titleText"),
                    "offset" to (-(t.int("advanceTimeSeconds") / 60)).toString(),
                    "all_day" to if (t.bool("ignoreAllDay")) "exclude" else "include",
                ),
            )
            "SunriseSunsetTrigger" -> one(
                s(
                    "sun", "event" to if (t.int("option") == 0) "sunrise" else "sunset",
                    "offset" to (t.int("timeAdjustSeconds") / 60).toString(),
                ),
            ).also { notes += "Sunrise/sunset needs its place set (tap ★ or Here)" }
            "GeofenceTrigger" -> {
                val g = geo[t.str("geofenceId")]
                if (g == null) {
                    notes += "Geofence location wasn't in the export; set it by hand"
                    one(s("geofence", "state" to if (t.bool("enterArea", true)) "enter" else "exit"))
                } else {
                    one(
                        s(
                            "geofence", "state" to if (t.bool("enterArea", true)) "enter" else "exit",
                            "location" to "%.6f,%.6f".format(java.util.Locale.US, g.first, g.second), "radius" to g.third.toInt().toString(),
                        ),
                    )
                }
            }
            "SignalOnOffTrigger" -> one(s("signal", "state" to if (t.bool("signalOn", true)) "on" else "off"))
            "UsbDeviceConnectionTrigger" -> one(s("usb_device", "state" to if (t.int("option") == 0) "attached" else "detached"))
            "VolumeButtonTrigger" -> one(s("volume_button", "state" to if (t.int("option") % 2 == 0) "up" else "down"))
            "MediaButtonPressedTrigger", "MediaButtonV2Trigger" -> one(s("media_button"))
            "AutoSyncChangeTrigger" -> one(s("autosync", "state" to if (t.int("option") == 0) "on" else "off"))
            "WebHookTrigger" -> one(s("webhook", "name" to t.str("identifier", "hook").ifBlank { "hook" }))
                .also { notes += "MacroDroid's cloud webhook became a local-network webhook (http://PHONE-IP:8765/hook/NAME)" }
            "FloatingButtonTrigger" -> one(s("floating_button"))
            "SwipeTrigger" -> one(s("edge_swipe")).also { notes += "Swipe area converted to a right-edge swipe; adjust if needed" }
            "WeatherTrigger" -> one(s("weather")).also { notes += "Weather trigger imported with default settings; review it" }
            "MacroDroidVariableTrigger", "VariableTrigger" ->
                one(s("variable_changed", "name" to (t.obj("variable")?.str("name") ?: "")))
            "ShortcutTrigger", "EmptyTrigger", "WidgetPressedTrigger" -> {
                notes += "${t.cls} → run it from the ▶ button, a 🏠 home-screen shortcut or the widget"
                emptyList()
            }
            else -> skip("not supported")
        }
    }

    private fun contactsNote(t: F, notes: MutableList<String>) {
        val specific = (t.objects("incomingCallFromList") + t.objects("smsFromList") + t.objects("contactList"))
            .any { it.str("id") !in setOf("", "-2", "-1") }
        if (specific) notes += "a call/SMS trigger was limited to contacts; add the phone number to the trigger"
    }

    // ---------- constraints ----------

    private fun constraint(c: F, notes: MutableList<String>): Step? {
        fun flag(key: String, on: Boolean) = s("state_$key", "state" to if (on) "on" else "off")
        val pkgs = c.strings("packageNameList").filter { it.isNotBlank() && !it.startsWith("Any") && it != "allApplications" }
        return when (c.cls) {
            "BatteryLevelConstraint" -> s("battery", "direction" to if (c.bool("greaterThan")) "above" else "below", "level" to c.int("batteryLevel", 50).toString())
            "BatteryTemperatureConstraint" -> s("battery_temp_state", "direction" to if (c.bool("greaterThan")) "above" else "below", "temp" to c.int("temperature", 40).toString())
            "ExternalPowerConstraint" -> s("charging", "state" to if (c.bool("externalPower", true)) "charging" else "not_charging")
            "TimeOfDayConstraint" -> s(
                "time_window", "from" to Imp.hhmm(c.int("startHour"), c.int("startMinute")),
                "to" to Imp.hhmm(c.int("endHour"), c.int("endMinute")),
            )
            "DayOfWeekConstraint" -> s("days", "days" to Imp.daysFromMondayFirst(c.bools("daysOfWeek")))
            "DayOfMonthConstraint" -> s("day_of_month", "days" to c.bools("daysOfMonth").mapIndexedNotNull { i, b -> if (b) i + 1 else null }.joinToString(","))
            "MonthOfYearConstraint" -> s("month", "months" to c.bools("months").mapIndexedNotNull { i, b -> if (b) i + 1 else null }.joinToString(","))
            "HeadphonesConnectionConstraint" -> flag("headphones", c.bool("connected", true))
            "WifiConstraint" -> when (c.int("wifiState")) {
                0 -> flag("wifi", true)
                1 -> flag("wifi", false)
                else -> s(
                    "wifi_state", "state" to if (c.int("wifiState") == 2) "connected" else "disconnected",
                    "ssid" to (c.strings("ssidList").firstOrNull { !it.startsWith("Any") } ?: ""),
                )
            }
            "BluetoothConstraint" -> {
                if (c.int("btState") >= 2) notes += "Bluetooth “device connected” constraint became “Bluetooth on”"
                flag("bluetooth", c.int("btState") != 1)
            }
            "ScreenOnOffConstraint" -> s("screen_state", "state" to if (c.bool("screenOn", true)) "on" else "off")
            "DeviceLockedConstraint" -> flag("locked", c.bool("locked", true))
            "AirplaneModeConstraint" -> flag("airplane", c.bool("enabled", true))
            "GPSEnabledConstraint" -> flag("gps", c.bool("enabled", true))
            "AutoRotateConstraint" -> flag("auto_rotate", c.bool("enabled", true))
            "AutoSyncConstraint" -> flag("autosync", c.bool("enabled", true))
            "MusicActiveConstraint" -> flag("music", c.bool("musicActive", true))
            "IsRoamingConstraint" -> flag("roaming", c.bool("isRoaming", true))
            "RoamingOnOffConstraint" -> flag("roaming", c.bool("roamingOn", true))
            "DataOnOffConstraint" -> flag("mobile_data", c.bool("dataOn", true))
            "WifiHotSpotConstraint" -> flag("hotspot", c.bool("enabled", true))
            "VpnConstraint" -> flag("vpn", c.int("option") == 0)
            "SignalOnOffConstraint" -> flag("signal", c.int("option") == 0)
            "BatterySaverStateConstraint" -> flag("power_save", c.int("option") == 0)
            "DeviceOrientationConstraint" -> flag("landscape", !c.bool("portrait", true))
            "ProximitySensorConstraint" -> s("proximity_state", "state" to if (c.bool("near", true)) "near" else "far")
            "InCallConstraint" -> s("call_state", "state" to if (c.bool("inCall", true)) "in_call" else "idle")
            "PhoneRingingConstraint" -> s("call_state", "state" to if (c.bool("ringing", true)) "ringing" else "idle")
            "NotificationPresentConstraint" -> s(
                "notification_present", "app" to (pkgs.firstOrNull() ?: ""), "contains" to c.str("textContent"),
                "state" to if (c.bool("excludes")) "absent" else "present",
            )
            "ActiveApplicationConstraint" -> {
                val pkg = pkgs.firstOrNull()
                if (pkg == null) { notes += "app-running constraint had no app"; null }
                else s("app_foreground", "app" to pkg, "state" to if (c.bool("foreground", true)) "foreground" else "not_foreground")
            }
            "MacroEnabledConstraint" -> s(
                "macro_enabled", "macro" to Imp.macroRef(c.strings("macroNames").firstOrNull() ?: ""),
                "state" to if (c.bool("enabled", true)) "enabled" else "disabled",
            )
            "MacroDroidVariableConstraint" -> variableConstraint(c)
            "LogicConstraint" -> {
                val kids = c.objects("constraintList").mapNotNull { constraint(it, notes) }
                val op = when (c.int("option")) {
                    1 -> "any"
                    2 -> "none"
                    else -> "all"
                }
                Step("logic_group", mapOf("op" to op), kids)
            }
            else -> { notes += "constraint ${c.cls} skipped (not supported)"; null }
        }
    }

    private fun variableConstraint(c: F): Step {
        val v = c.obj("variable")
        val name = v?.str("name") ?: ""
        val type = v?.int("type") ?: 1
        val op = when {
            c.bool("intGreaterThan") -> "gt"
            c.bool("intLessThan") -> "lt"
            c.bool("intNotEqual") -> "ne"
            else -> "eq"
        }
        val value = when (type) {
            0 -> c.bool("booleanValue").toString()
            2 -> text(c.str("stringValue"))
            3 -> c.str("decimalValue", c.str("doubleValue"))
            else -> c.str("intValue")
        }
        return s("variable", "scope" to "global", "name" to name, "op" to op, "value" to value)
    }

    // ---------- actions ----------

    private fun action(a: F, notes: MutableList<String>): List<Step> {
        fun one(step: Step) = listOf(step)
        fun unsupported(): List<Step> { notes += "action ${a.cls} not converted"; return listOf(Imp.unsupported(APP, a.cls)) }
        fun conds(): List<Step> = a.objects("constraintList").mapNotNull { constraint(it, notes) }
        val pkgs = a.strings("packageNameList").filter { it.isNotBlank() }

        return when (a.cls) {
            "IfConditionAction" -> one(cond("if", conds(), a.bool("isOrCondition")))
            "ElseIfConditionAction" -> one(cond("else_if", conds(), a.bool("isOrCondition")))
            "ElseAction" -> one(s("else"))
            "EndIfAction" -> one(s("end_if"))
            "IfConfirmedThenAction" -> listOf(
                s("confirm", "title" to text(a.str("title", "Continue?")), "message" to text(a.str("message")), "on_no" to "continue"),
                cond("if", listOf(s("variable", "scope" to "local", "name" to "confirmed", "op" to "is_true"))),
            )
            "LoopAction" -> {
                val c = conds()
                val iterations = a.int("iterations", a.int("numIterations", 0))
                one(
                    when {
                        c.isNotEmpty() -> cond("loop", c, a.bool("isOrCondition"), "mode" to "while")
                        iterations > 0 -> s("loop", "mode" to "count", "count" to iterations.toString())
                        else -> s("loop", "mode" to "forever").also { notes += "a loop's repeat setting wasn't recognised; set to forever, check it" }
                    },
                )
            }
            "EndLoopAction" -> one(s("end_loop"))
            "BreakFromLoopAction" -> one(s("break"))
            "ContinueLoopAction" -> one(s("continue"))
            "PauseAction" -> {
                val secs = a.int("delayInSeconds") + a.int("delayInMinutes") * 60 + a.int("delayInHours") * 3600 + a.int("delayInMilliSeconds") / 1000.0
                one(s("wait", "seconds" to VarFmt.num(secs)))
            }
            "NotificationAction" -> one(s("notify", "title" to text(a.str("notificationSubject", "MacroDroid")), "text" to text(a.str("notificationText"))))
            "ToastAction" -> one(s("toast", "text" to text(a.str("messageText"))))
            "SpeakTextAction" -> one(s("speak", "text" to text(a.str("textToSay"))))
            "SayTimeAction" -> one(s("say_time"))
            "VibrateAction" -> one(s("vibrate", "ms" to listOf(80, 300, 900, 600, 900, 1000, 1500, 1000, 1200, 1200, 1500, 60, 30).getOrElse(a.int("vibratePattern")) { 500 }.toString()))
            "CameraFlashLightAction" -> one(s("flashlight", "state" to Imp.onOffToggle(a.int("state", 2))))
            "SetBrightnessAction" -> one(s("brightness", "percent" to a.int("brightnessPercent", 50).toString()))
            "SetVolumeAction" -> volume(a, notes)
            "VolumeIncrementDecrementAction" -> one(s("volume_step", "direction" to if (a.bool("volumeUp", true)) "up" else "down"))
            "SetVibrateAction", "SilentModeVibrateOffAction", "VolumeAction" -> {
                val opt = a.str("option").lowercase()
                one(
                    s(
                        "ringer", "mode" to when {
                            "silent" in opt -> "silent"
                            "vibrate" in opt -> "vibrate"
                            else -> "normal"
                        },
                    ),
                )
            }
            "LaunchActivityAction" -> one(s("launch_app", "app" to a.str("packageToLaunch", a.str("launchByPackageName"))))
            "LaunchHomeScreenAction" -> one(s("launch_home"))
            "PressBackAction" -> one(s("global_action", "button" to "back"))
            "KillBackgroundAppAction" -> pkgs.map { s("kill_app", "app" to it) }.ifEmpty { unsupported() }
            "OpenWebPageAction" -> {
                val url = text(a.str("urlToOpen", a.str("url")))
                one(if (a.bool("httpGet")) s("http", "method" to "GET", "url" to url) else s("open_url", "url" to url))
            }
            "HttpRequestAction" -> {
                val cfg = a.obj("httpRequestConfig") ?: a
                val method = cfg.str("requestType", "GET").uppercase().let { if (it.contains("POST")) "POST" else "GET" }
                one(s("http", "method" to method, "url" to text(cfg.str("urlToOpen", cfg.str("url"))), "body" to text(cfg.str("contentBodyText", cfg.str("body")))))
            }
            "SendSMSAction" -> one(s("send_sms", "number" to text(a.str("number")), "text" to text(a.str("messageContent"))))
            "MakeCallAction" -> one(s("make_call", "number" to text(a.str("number"))))
            "AnswerCallAction" -> one(s("answer_call"))
            "RejectCallAction" -> one(s("end_call"))
            "SpeakerPhoneAction" -> one(s("speakerphone", "state" to Imp.onOffToggle(a.int("state"))))
            "SendEmailAction" -> one(s("send_email", "to" to a.str("emailAddress"), "subject" to text(a.str("subject")), "body" to text(a.str("body"))))
            "ClipboardAction" -> one(s("clipboard", "text" to text(a.str("clipboardText"))))
            "SendIntentAction" -> one(
                s(
                    "send_intent", "action" to a.str("action"), "data" to a.str("data"), "package" to a.str("packageName"),
                    "target" to when (a.str("target").lowercase()) {
                        "broadcast" -> "broadcast"
                        "service" -> "service"
                        else -> "activity"
                    },
                    "extras" to (1..4).mapNotNull { i ->
                        a.str("extra${i}Name").takeIf { it.isNotBlank() }?.let { "$it=${text(a.str("extra${i}Value"))}" }
                    }.joinToString("; "),
                ),
            )
            "SetWifiAction" -> if (a.int("state") <= 2) one(s("wifi_set", "state" to Imp.onOffToggle(a.int("state")))) else unsupported()
            "SetBluetoothAction" -> if (a.int("state") <= 2) one(s("bluetooth_set", "state" to Imp.onOffToggle(a.int("state")))) else unsupported()
            "SetAirplaneModeAction" -> one(s("airplane_set", "state" to Imp.onOffToggle(a.int("state"))))
            "SetAutoRotateAction" -> one(s("auto_rotate", "state" to Imp.onOffToggle(a.int("state"))))
            "SetScreenTimeoutAction" -> one(s("screen_timeout", "seconds" to a.int("timeoutDelay", 60).toString()))
            "KeepAwakeAction" -> one(s("keep_awake", "state" to if (a.bool("enabled", true)) "on" else "off", "minutes" to (a.int("secondsToStayAwakeFor") / 60).toString()))
            "ScreenOnAction" -> one(if (a.bool("screenOff")) s("global_action", "button" to "lock") else s("screen_on"))
            "DimScreenAction" -> one(s("dim_screen", "state" to if (a.bool("dimScreenOn", true)) "on" else "off", "level" to a.int("percent", 50).toString()))
            "StopWatchAction" -> one(s("stopwatch_op", "name" to a.str("stopwatchName", "timer"), "op" to listOf("start", "pause", "reset", "restart").getOrElse(a.int("option")) { "start" }))
            "SetAlarmClockAction" -> one(s("set_alarm", "time" to Imp.hhmm(a.int("hour"), a.int("minute")), "label" to a.str("label", "Alarm")))
            "AddCalendarEntryAction" -> one(s("calendar_add", "title" to text(a.str("title")), "notes" to text(a.str("detail"))))
            "LogAction" -> one(s("log", "text" to text(a.str("logText"))))
            "ClearLogAction" -> one(s("clear_log"))
            "SetVariableAction" -> one(setVariable(a))
            "ForceMacroRunAction" -> one(s("run_macro", "macro" to Imp.macroRef(a.str("macroName")), "check" to "ignore", "mode" to "separate"))
            "DisableMacroAction" -> one(s("macro_enable", "macro" to Imp.macroRef(a.str("macroName")), "state" to listOf("enable", "disable", "toggle").getOrElse(a.int("state")) { "toggle" }))
            "CancelActiveMacroAction" -> one(s("cancel_macro", "macro" to a.str("macroName").let { if (it.isBlank()) "" else Imp.macroRef(it) }))
            "ConfirmNextAction" -> one(s("confirm", "title" to text(a.str("title", "Continue?")), "message" to text(a.str("message")), "on_no" to "stop"))
            "MessageDialogAction" -> one(s("dialog_message", "title" to text(a.str("dialogTitle", a.str("title"))), "message" to text(a.str("dialogText", a.str("message", a.str("notificationText"))))))
            "ControlMediaAction" -> {
                val o = a.str("option").lowercase()
                one(
                    s(
                        "media_control", "button" to when {
                            "next" in o -> "next"
                            "prev" in o -> "previous"
                            "stop" in o -> "stop"
                            "pause" in o && "play" in o -> "play_pause"
                            "pause" in o -> "pause"
                            "play" in o -> "play"
                            else -> "play_pause"
                        },
                    ),
                )
            }
            "PlaySoundAction" -> one(
                if (a.str("filePath").isNotBlank()) s("play_sound", "source" to "file", "path" to a.str("filePath")) else s("play_sound", "source" to "notification"),
            )
            "RecordMicrophoneAction" -> one(s("record_audio", "seconds" to a.int("secondsToRecordFor", 10).coerceAtLeast(1).toString()))
            "TakePictureAction" -> one(s("take_photo", "camera" to if (a.bool("useFrontCamera")) "front" else "back"))
            "TakeScreenshotAction" -> one(s("global_action", "button" to "screenshot"))
            "ExpandCollapseStatusBarAction" -> one(s("global_action", "button" to if (a.int("option") == 1) "quick_settings" else "notifications"))
            "UDPCommandAction" -> one(s("udp_send", "host" to a.str("destination"), "port" to a.int("port", 1024).toString(), "message" to text(a.str("message"))))
            "WriteToFileAction" -> one(
                s(
                    "write_file", "path" to a.str("filePath").trimEnd('/') + "/" + a.str("filename", "macrodroid.txt"),
                    "text" to text(a.str("fileContent", a.str("text", a.str("textToWrite")))),
                    "mode" to if (a.int("option") == 1) "overwrite" else "append",
                ),
            )
            "ExportMacrosAction" -> one(s("export_macros", "path" to (a.str("filePath").ifBlank { "backup-{date}.json" })))
            "ClearNotificationsAction" -> (pkgs.ifEmpty { listOf("") }).map { s("clear_notifications", "app" to it) }
            "ShellScriptAction" -> one(s("shell", "command" to text(a.str("script", a.str("scriptText"))), "root" to if (a.bool("useRoot")) "yes" else "no"))
            "SetRingtoneAction" -> one(s("set_ringtone", "which" to "ringtone", "sound" to a.str("ringtoneUri")))
            "SetNotificationSoundAction" -> one(s("set_ringtone", "which" to "notification", "sound" to a.str("ringtoneUri")))
            "VoiceSearchAction" -> one(s("voice_search"))
            "ChangeKeyboardAction" -> one(s("keyboard_picker"))
            "DayDreamAction" -> one(s("screensaver"))
            "SetHotspotAction" -> one(s("hotspot_settings"))
            "ForceLocationUpdateAction", "ShareLocationAction" -> one(s("get_location"))
            "OpenFileAction" -> one(s("open_file", "path" to a.str("filePath")))
            "CarModeAction" -> one(s("car_mode", "state" to if (a.int("option") == 0) "on" else "off"))
            "ClearCallLogAction" -> one(s("clear_call_log"))
            "OpenCallLogAction" -> one(s("open_call_log"))
            else -> unsupported()
        }
    }

    private fun volume(a: F, notes: MutableList<String>): List<Step> {
        // MacroDroid stream order: alarm, media, notification, ringer, system, voice call.
        val names = listOf("alarm", "media", "notification", "ring")
        val on = a.bools("streamIndexArray")
        val levels = a.arr("streamVolumeArray")
        val steps = on.mapIndexedNotNull { i, b ->
            if (!b || i >= names.size) null else s("volume", "stream" to names[i], "percent" to (levels?.optInt(i, 50) ?: 50).toString())
        }
        if (steps.isEmpty()) notes += "a volume action had no recognisable streams"
        return steps
    }

    private fun setVariable(a: F): Step {
        val v = a.obj("variable")
        val name = v?.str("name") ?: "var"
        val scope = if (v?.bool("isLocal") == true || a.bool("localVar")) "local" else "global"
        val type = when (v?.int("type") ?: 1) {
            0 -> "boolean"
            2 -> "string"
            3 -> "decimal"
            else -> "integer"
        }
        return when {
            a.bool("intValueIncrement") -> s("set_variable", "scope" to scope, "name" to name, "type" to type, "op" to "increment", "value" to "1")
            a.bool("intValueDecrement") -> s("set_variable", "scope" to scope, "name" to name, "type" to type, "op" to "decrement", "value" to "1")
            a.bool("intRandom") || a.bool("doubleRandom") -> {
                val dec = type == "decimal"
                s(
                    "set_variable", "scope" to scope, "name" to name, "type" to type, "op" to "random",
                    "min" to a.str(if (dec) "doubleRandomMin" else "intRandomMin", "0"),
                    "max" to a.str(if (dec) "doubleRandomMax" else "intRandomMax", "100"),
                )
            }
            a.str("expression").isNotBlank() -> s("set_variable", "scope" to scope, "name" to name, "type" to type, "op" to "set", "value" to text(a.str("expression")))
            else -> {
                val value = when (type) {
                    "boolean" -> a.bool("newBooleanValue").toString()
                    "string" -> text(a.str("newStringValue"))
                    "decimal" -> a.str("newDoubleValue", "0")
                    else -> a.str("newIntValue", "0")
                }
                s("set_variable", "scope" to scope, "name" to name, "type" to type, "op" to "set", "value" to value)
            }
        }
    }
}

/** Number formatting without trailing ".0". */
internal object VarFmt {
    fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
