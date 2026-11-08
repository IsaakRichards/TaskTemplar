package dev.isaakrichards.tasktemplar.model

enum class StepKind(val title: String) {
    TRIGGER("Trigger"),
    CONSTRAINT("Constraint"),
    ACTION("Action"),
}

enum class ParamKind { TEXT, NUMBER, TIME, DAYS, CHOICE, APP }

data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val default: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val hint: String = "",
    val optional: Boolean = false,
)

data class StepSpec(
    val type: String,
    val kind: StepKind,
    val category: String,
    val label: String,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
    val summary: (Step) -> String = { label },
) {
    fun defaults(): Map<String, String> = params.associate { it.key to it.default }
}

/**
 * Every trigger, constraint and action the app knows about. Adding a new one means
 * adding a spec here plus a branch in Conditions (triggers/constraints) or Actions.
 */
object Catalog {
    const val ALL_DAYS = "1,2,3,4,5,6,7"
    private const val MAGIC = "Magic text: {time} {date} {battery} {app} {title} {text} {ssid} {device} {http_code} {http_body}"

    private val T = StepKind.TRIGGER
    private val C = StepKind.CONSTRAINT
    private val A = StepKind.ACTION

    private val onOff = listOf("connected" to "Connected", "disconnected" to "Disconnected")

    val triggers: List<StepSpec> = listOf(
        StepSpec(
            "time", T, "Date & time", "Time of day", "Fires at a set time on chosen days.",
            listOf(
                ParamSpec("time", "Time", ParamKind.TIME, "08:00"),
                ParamSpec("days", "Days", ParamKind.DAYS, ALL_DAYS),
            ),
        ) { "At ${it.p("time", "08:00")} · ${daysLabel(it.p("days", ALL_DAYS))}" },
        StepSpec(
            "interval", T, "Date & time", "Regular interval", "Fires every N minutes.",
            listOf(ParamSpec("minutes", "Every (minutes)", ParamKind.NUMBER, "15")),
        ) { "Every ${it.p("minutes", "15")} min" },
        StepSpec("boot", T, "Device", "Device boot", "Fires when the phone finishes starting up.") { "On boot" },
        StepSpec(
            "screen", T, "Device", "Screen on / off / unlocked", "Fires when the screen changes state.",
            listOf(
                ParamSpec(
                    "state", "When", ParamKind.CHOICE, "on",
                    listOf("on" to "Screen on", "off" to "Screen off", "unlocked" to "Device unlocked"),
                ),
            ),
        ) { "Screen ${it.p("state", "on")}" },
        StepSpec(
            "battery_level", T, "Battery & power", "Battery level", "Fires when the level crosses a threshold.",
            listOf(
                ParamSpec("direction", "Fire when the level",ParamKind.CHOICE, "below", listOf("below" to "Drops to or below", "above" to "Rises to or above")),
                ParamSpec("level", "Level (%)", ParamKind.NUMBER, "20"),
            ),
        ) { "Battery ${it.p("direction", "below")} ${it.p("level", "20")}%" },
        StepSpec(
            "power", T, "Battery & power", "Power connected / disconnected", "Fires when the charger is plugged or unplugged.",
            listOf(ParamSpec("state", "When", ParamKind.CHOICE, "connected", onOff)),
        ) { "Power ${it.p("state", "connected")}" },
        StepSpec(
            "wifi", T, "Connectivity", "Wi-Fi connected / disconnected", "Fires on Wi-Fi changes, optionally for one network.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "connected", onOff),
                ParamSpec("ssid", "Network name", ParamKind.TEXT, optional = true, hint = "Any network"),
            ),
        ) { "Wi-Fi ${it.p("state", "connected")}" + it.p("ssid").let { s -> if (s.isBlank()) "" else " ($s)" } },
        StepSpec(
            "bluetooth", T, "Connectivity", "Bluetooth device", "Fires when a Bluetooth device connects or disconnects.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "connected", onOff),
                ParamSpec("device", "Device name contains", ParamKind.TEXT, optional = true, hint = "Any device"),
            ),
        ) { "Bluetooth ${it.p("state", "connected")}" + it.p("device").let { s -> if (s.isBlank()) "" else " ($s)" } },
        StepSpec(
            "headphones", T, "Connectivity", "Headphones plugged / unplugged", "Fires when wired headphones are inserted or removed.",
            listOf(ParamSpec("state", "When", ParamKind.CHOICE, "plugged", listOf("plugged" to "Plugged in", "unplugged" to "Unplugged"))),
        ) { "Headphones ${it.p("state", "plugged")}" },
        StepSpec(
            "notification", T, "Apps", "Notification received", "Fires when an app posts a notification. Needs notification access.",
            listOf(
                ParamSpec("app", "From app", ParamKind.APP, optional = true, hint = "Any app"),
                ParamSpec("contains", "Title or text contains", ParamKind.TEXT, optional = true),
            ),
        ) { s ->
            "Notification" + s.p("app").let { if (it.isBlank()) "" else " from $it" } +
                s.p("contains").let { if (it.isBlank()) "" else " containing \"$it\"" }
        },
    )

    val constraints: List<StepSpec> = listOf(
        StepSpec(
            "time_window", C, "Date & time", "Time window", "Only between two times (can cross midnight).",
            listOf(
                ParamSpec("from", "From", ParamKind.TIME, "22:00"),
                ParamSpec("to", "To", ParamKind.TIME, "07:00"),
            ),
        ) { "Between ${it.p("from", "22:00")} and ${it.p("to", "07:00")}" },
        StepSpec(
            "days", C, "Date & time", "Day of week", "Only on chosen days.",
            listOf(ParamSpec("days", "Days", ParamKind.DAYS, "1,2,3,4,5")),
        ) { "On ${daysLabel(it.p("days", ALL_DAYS))}" },
        StepSpec(
            "battery", C, "Battery & power", "Battery level", "Only when battery is above/below a level.",
            listOf(
                ParamSpec("direction", "Battery is", ParamKind.CHOICE, "below", listOf("below" to "Below", "above" to "Above")),
                ParamSpec("level", "Level (%)", ParamKind.NUMBER, "30"),
            ),
        ) { "Battery ${it.p("direction", "below")} ${it.p("level", "30")}%" },
        StepSpec(
            "charging", C, "Battery & power", "Charging state", "Only when charging / not charging.",
            listOf(ParamSpec("state", "Phone is", ParamKind.CHOICE, "charging", listOf("charging" to "Charging", "not_charging" to "Not charging"))),
        ) { if (it.p("state", "charging") == "charging") "Charging" else "Not charging" },
        StepSpec(
            "wifi_state", C, "Connectivity", "Wi-Fi state", "Only when on (or off) Wi-Fi, optionally a specific network.",
            listOf(
                ParamSpec("state", "Wi-Fi is", ParamKind.CHOICE, "connected", onOff),
                ParamSpec("ssid", "Network name", ParamKind.TEXT, optional = true, hint = "Any network"),
            ),
        ) { "Wi-Fi ${it.p("state", "connected")}" + it.p("ssid").let { s -> if (s.isBlank()) "" else " ($s)" } },
        StepSpec(
            "screen_state", C, "Device", "Screen state", "Only when the screen is on / off.",
            listOf(ParamSpec("state", "Screen is", ParamKind.CHOICE, "on", listOf("on" to "On", "off" to "Off"))),
        ) { "Screen ${it.p("state", "on")}" },
    )

    val actions: List<StepSpec> = listOf(
        StepSpec(
            "notify", A, "Notify", "Show notification", "Posts a notification.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, "TaskTemplar", hint = MAGIC),
                ParamSpec("text", "Text", ParamKind.TEXT, "Macro fired at {time}", hint = MAGIC),
            ),
        ) { "\"${it.p("title")}\"" },
        StepSpec(
            "toast", A, "Notify", "Popup message", "Shows a short toast message.",
            listOf(ParamSpec("text", "Message", ParamKind.TEXT, "Hello from TaskTemplar", hint = MAGIC)),
        ) { "\"${it.p("text")}\"" },
        StepSpec(
            "speak", A, "Notify", "Speak text", "Reads text aloud with text-to-speech.",
            listOf(ParamSpec("text", "Text", ParamKind.TEXT, "Battery is at {battery} percent", hint = MAGIC)),
        ) { "Say \"${it.p("text")}\"" },
        StepSpec(
            "vibrate", A, "Notify", "Vibrate", "Vibrates for a set time.",
            listOf(ParamSpec("ms", "Duration (ms)", ParamKind.NUMBER, "500")),
        ) { "${it.p("ms", "500")} ms" },
        StepSpec(
            "ringer", A, "Volume & sound", "Ringer mode", "Sets normal / vibrate / silent. Silent needs Do Not Disturb access.",
            listOf(ParamSpec("mode", "Mode", ParamKind.CHOICE, "vibrate", listOf("normal" to "Normal", "vibrate" to "Vibrate", "silent" to "Silent"))),
        ) { it.p("mode", "vibrate").replaceFirstChar { c -> c.uppercase() } },
        StepSpec(
            "dnd", A, "Volume & sound", "Do Not Disturb", "Sets the Do Not Disturb mode. Needs DND access.",
            listOf(
                ParamSpec(
                    "mode", "Mode", ParamKind.CHOICE, "priority",
                    listOf("off" to "Off", "priority" to "Priority only", "alarms" to "Alarms only", "none" to "Total silence"),
                ),
            ),
        ) { "DND ${it.p("mode", "priority")}" },
        StepSpec(
            "volume", A, "Volume & sound", "Set volume", "Sets a volume stream to a percentage.",
            listOf(
                ParamSpec(
                    "stream", "Stream", ParamKind.CHOICE, "media",
                    listOf("media" to "Media", "ring" to "Ringtone", "notification" to "Notification", "alarm" to "Alarm"),
                ),
                ParamSpec("percent", "Volume (%)", ParamKind.NUMBER, "50"),
            ),
        ) { "${it.p("stream", "media")} → ${it.p("percent", "50")}%" },
        StepSpec(
            "brightness", A, "Device", "Screen brightness", "Sets brightness (turns off auto). Needs Modify system settings.",
            listOf(ParamSpec("percent", "Brightness (%)", ParamKind.NUMBER, "40")),
        ) { "${it.p("percent", "40")}%" },
        StepSpec(
            "flashlight", A, "Device", "Flashlight", "Turns the torch on, off or toggles it.",
            listOf(ParamSpec("state", "Set to", ParamKind.CHOICE, "toggle", listOf("on" to "On", "off" to "Off", "toggle" to "Toggle"))),
        ) { "Torch ${it.p("state", "toggle")}" },
        StepSpec(
            "clipboard", A, "Device", "Copy to clipboard", "Puts text on the clipboard.",
            listOf(ParamSpec("text", "Text", ParamKind.TEXT, hint = MAGIC)),
        ) { "\"${it.p("text")}\"" },
        StepSpec(
            "launch_app", A, "Apps", "Launch app", "Opens an app. From the background this needs 'Display over other apps'.",
            listOf(ParamSpec("app", "App", ParamKind.APP)),
        ) { it.p("app", "(no app chosen)") },
        StepSpec(
            "open_url", A, "Apps", "Open website", "Opens a URL in the browser.",
            listOf(ParamSpec("url", "URL", ParamKind.TEXT, "https://", hint = MAGIC)),
        ) { it.p("url") },
        StepSpec(
            "http", A, "Web", "HTTP request", "Sends a GET/POST request (great for webhooks & home servers). Sets {http_code} and {http_body}.",
            listOf(
                ParamSpec("method", "Method", ParamKind.CHOICE, "GET", listOf("GET" to "GET", "POST" to "POST")),
                ParamSpec("url", "URL", ParamKind.TEXT, "https://", hint = MAGIC),
                ParamSpec("body", "Body (POST)", ParamKind.TEXT, optional = true, hint = MAGIC),
            ),
        ) { "${it.p("method", "GET")} ${it.p("url")}" },
        StepSpec(
            "wait", A, "Flow", "Wait", "Pauses before the next action.",
            listOf(ParamSpec("seconds", "Seconds", ParamKind.NUMBER, "5")),
        ) { "${it.p("seconds", "5")} s" },
    )

    private val byType: Map<String, StepSpec> = (triggers + constraints + actions).associateBy { it.type }

    fun spec(type: String): StepSpec? = byType[type]

    fun forKind(kind: StepKind): List<StepSpec> = when (kind) {
        StepKind.TRIGGER -> triggers
        StepKind.CONSTRAINT -> constraints
        StepKind.ACTION -> actions
    }

    fun summarize(step: Step): String = spec(step.type)?.summary?.invoke(step) ?: step.type

    val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun parseDays(s: String): Set<Int> =
        s.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    fun daysLabel(s: String): String {
        val d = parseDays(s)
        return when {
            d.size == 7 -> "every day"
            d.isEmpty() -> "no days"
            d == setOf(1, 2, 3, 4, 5) -> "weekdays"
            d == setOf(6, 7) -> "weekends"
            else -> d.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }
    }
}
