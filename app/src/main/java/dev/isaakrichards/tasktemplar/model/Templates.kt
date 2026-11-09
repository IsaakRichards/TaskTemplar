package dev.isaakrichards.tasktemplar.model

/** Ready-made macros, kept locally (no template store, no account). */
object Templates {

    private fun s(type: String, vararg p: Pair<String, String>) = Step(type, p.toMap())

    val all: List<Pair<String, Macro>> = listOf(
        "Silence the phone at night, restore in the morning" to Macro(
            name = "Night mode", category = "Templates",
            triggers = listOf(s("time", "time" to "22:30"), s("time", "time" to "07:00")),
            actions = listOf(
                Step("if", conditions = listOf(s("time_window", "from" to "22:00", "to" to "06:59"))),
                s("dnd", "mode" to "priority"), s("brightness", "percent" to "10"),
                s("else"),
                s("dnd", "mode" to "off"), s("brightness", "percent" to "60"),
                s("end_if"),
            ),
        ),
        "Low battery warning with spoken alert" to Macro(
            name = "Low battery", category = "Templates",
            triggers = listOf(s("battery_level", "direction" to "below", "level" to "15")),
            constraints = listOf(s("charging", "state" to "not_charging")),
            actions = listOf(
                s("notify", "title" to "Battery at {battery}%", "text" to "Plug in soon."),
                s("speak", "text" to "Battery is at {battery} percent"),
            ),
        ),
        "Open music app and set volume when headphones go in" to Macro(
            name = "Headphones in", category = "Templates",
            triggers = listOf(s("headphones", "state" to "plugged")),
            actions = listOf(s("volume", "stream" to "media", "percent" to "40"), s("launch_app", "app" to "")),
        ),
        "Auto-reply to texts while driving (Bluetooth car)" to Macro(
            name = "Driving auto-reply", category = "Templates",
            triggers = listOf(s("sms_received")),
            constraints = listOf(s("state_bluetooth", "state" to "on")),
            actions = listOf(s("send_sms", "number" to "{number}", "text" to "Driving right now, I'll text you back soon.")),
        ),
        "Shake to toggle the flashlight" to Macro(
            name = "Shake torch", category = "Templates",
            triggers = listOf(s("shake", "sensitivity" to "medium")),
            constraints = listOf(s("screen_state", "state" to "off")),
            actions = listOf(s("flashlight", "state" to "toggle"), s("vibrate", "ms" to "150")),
        ),
        "Log battery level to a CSV every hour" to Macro(
            name = "Battery logger", category = "Templates",
            triggers = listOf(s("interval", "minutes" to "60")),
            actions = listOf(s("write_file", "path" to "battery.csv", "text" to "{date} {time},{battery}", "mode" to "append")),
        ),
        "Weekly backup of all macros" to Macro(
            name = "Weekly backup", category = "Templates",
            triggers = listOf(s("time", "time" to "03:00", "days" to "7")),
            actions = listOf(s("export_macros", "path" to "backup-{date}.json")),
        ),
        "Count how many times the phone was unlocked today" to Macro(
            name = "Unlock counter", category = "Templates",
            triggers = listOf(s("screen", "state" to "unlocked")),
            actions = listOf(
                s("set_variable", "name" to "unlocks", "type" to "integer", "op" to "increment", "value" to "1"),
                Step("if", conditions = listOf(s("variable", "name" to "unlocks", "op" to "ge", "value" to "100"))),
                s("toast", "text" to "100 unlocks today. Maybe put the phone down?"),
                s("end_if"),
            ),
        ),
        "Reset the unlock counter at midnight" to Macro(
            name = "Reset unlock counter", category = "Templates",
            triggers = listOf(s("time", "time" to "00:00")),
            actions = listOf(s("set_variable", "name" to "unlocks", "type" to "integer", "op" to "set", "value" to "0")),
        ),
        "Webhook from your home server shows a notification" to Macro(
            name = "Server alert", category = "Templates",
            triggers = listOf(s("webhook", "name" to "alert", "port" to "8765")),
            actions = listOf(s("notify", "title" to "Server", "text" to "{msg}")),
        ),
    )
}
