package dev.isaakrichards.tasktemplar.model

enum class StepKind(val title: String) {
    TRIGGER("Trigger"),
    CONSTRAINT("Constraint"),
    ACTION("Action"),
}

enum class ParamKind { TEXT, NUMBER, TIME, DAYS, CHOICE, APP, MACRO, LOCATION, SOUND, PLUGIN }

data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val default: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val hint: String = "",
    val optional: Boolean = false,
    /** TEXT fields only: shows a button that fills the field with a number variable's name. */
    val varPicker: Boolean = false,
)

data class StepSpec(
    val type: String,
    val kind: StepKind,
    val category: String,
    val label: String,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
    /** True for logic steps that hold a list of constraint conditions (If, Else If, Loop). */
    val conditions: Boolean = false,
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

    private val baseTriggers: List<StepSpec> = listOf(
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

    // ---- appended triggers ----
    private val moreTriggers: List<StepSpec> = listOf(
        StepSpec(
            "variable_changed", T, "Variables", "Variable changed", "Fires when a global variable is set to a new value.",
            listOf(ParamSpec("name", "Variable name", ParamKind.TEXT, optional = true, hint = "Any variable")),
        ) { "Variable ${it.p("name", "(any)")} changed" },
        StepSpec(
            "app_launched", T, "Apps", "App opened / closed", "Fires when an app comes to the front or leaves it. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "opened", listOf("opened" to "App opened", "closed" to "App closed")),
                ParamSpec("app", "App", ParamKind.APP, optional = true, hint = "Any app"),
            ),
        ) { s -> "${s.p("app").ifBlank { "Any app" }} ${s.p("state", "opened")}" },
        StepSpec(
            "app_installed", T, "Apps", "App installed / removed / updated", "Fires when apps are installed, uninstalled or updated.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "installed", listOf("installed" to "Installed", "removed" to "Removed", "updated" to "Updated")),
                ParamSpec("app", "Package name", ParamKind.APP, optional = true, hint = "Any app"),
            ),
        ) { s -> "${s.p("app").ifBlank { "An app" }} ${s.p("state", "installed")}" },
        StepSpec(
            "screen_text", T, "Apps", "Screen content", "Fires when text appears on screen. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec("text", "Text on screen contains", ParamKind.TEXT),
                ParamSpec("app", "Only in app", ParamKind.APP, optional = true, hint = "Any app"),
            ),
        ) { s -> "“${s.p("text")}” on screen" + s.p("app").let { if (it.isBlank()) "" else " in $it" } },
    )

    private val baseConstraints: List<StepSpec> = listOf(
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

    private val baseActions: List<StepSpec> = listOf(
        StepSpec(
            "notify", A, "Notify", "Show notification", "Posts a notification.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, "TaskTemplar", hint = MAGIC),
                ParamSpec("text", "Text", ParamKind.TEXT, "Macro fired at {time}", hint = MAGIC),
                ParamSpec("led", "Notification light", ParamKind.CHOICE, "no", listOf("no" to "Normal", "yes" to "Blink the LED (phones that have one)")),
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
            "wait", A, "Flow", "Wait", "Pauses before the next action. The length can be a number, a variable's name (pick one with the list button), or math like {v=delay} * 2. Up to 24 hours.",
            listOf(
                ParamSpec("seconds", "Length", ParamKind.TEXT, "5", hint = "Number, variable name, or math", varPicker = true),
                ParamSpec(
                    "unit", "Unit", ParamKind.CHOICE, "s",
                    listOf("ms" to "Milliseconds", "s" to "Seconds", "min" to "Minutes", "h" to "Hours"),
                ),
            ),
        ) { "${it.p("seconds", "5")} ${it.p("unit", "s")}" },
    )

    // ---- Stage 1: variables, logic and macro control ----

    private val scopeChoice = ParamSpec(
        "scope", "Scope", ParamKind.CHOICE, "global",
        listOf("global" to "Global (shared by all macros)", "local" to "Local (this run only)"),
    )

    private val compareOps = listOf(
        "eq" to "equals", "ne" to "does not equal",
        "lt" to "less than", "le" to "less than or equal", "gt" to "greater than", "ge" to "greater than or equal",
        "contains" to "contains", "not_contains" to "does not contain",
        "empty" to "is empty", "not_empty" to "is not empty",
        "is_true" to "is true", "is_false" to "is false",
    )

    private fun opLabel(op: String) = compareOps.firstOrNull { it.first == op }?.second ?: op

    /** Turns a macro id into its name for summaries; replaced at app start with a real lookup. */
    var macroName: (String) -> String = { it }

    fun conditionSummary(step: Step): String =
        if (step.conditions.isEmpty()) "(no conditions — always true)"
        else step.conditions.joinToString(if (step.conditionMode == Macro.MODE_ANY) " or " else " and ") { summarize(it) }

    private fun varRef(step: Step) =
        (if (step.p("scope", "global") == "local") "local " else "") + step.p("name", "?")

    private val logicConstraints: List<StepSpec> = listOf(
        StepSpec(
            "variable", C, "Variables", "Variable value", "Compares a variable with a value (numbers compare as numbers).",
            listOf(
                scopeChoice,
                ParamSpec("name", "Variable name", ParamKind.TEXT),
                ParamSpec("op", "Is", ParamKind.CHOICE, "eq", compareOps),
                ParamSpec("value", "Value", ParamKind.TEXT, optional = true, hint = "Magic text allowed, e.g. {v=other}"),
            ),
        ) { s ->
            val op = s.p("op", "eq")
            val tail = if (op in setOf("empty", "not_empty", "is_true", "is_false")) "" else " ${s.p("value")}"
            "${varRef(s)} ${opLabel(op)}$tail"
        },
        StepSpec(
            "macro_enabled", C, "Macros", "Macro enabled / disabled", "Checks whether another macro is switched on.",
            listOf(
                ParamSpec("macro", "Macro", ParamKind.MACRO),
                ParamSpec("state", "Macro is", ParamKind.CHOICE, "enabled", listOf("enabled" to "Enabled", "disabled" to "Disabled")),
            ),
        ) { "“${macroName(it.p("macro"))}” ${it.p("state", "enabled")}" },
        StepSpec(
            "app_foreground", C, "Apps", "App in foreground", "Checks which app is in front. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec("app", "App", ParamKind.APP),
                ParamSpec("state", "App is", ParamKind.CHOICE, "foreground", listOf("foreground" to "In front", "not_foreground" to "Not in front")),
            ),
        ) { s -> "${s.p("app", "?")} ${if (s.p("state", "foreground") == "foreground") "in front" else "not in front"}" },
        StepSpec(
            "notification_present", C, "Apps", "Notification present", "Checks the notifications currently showing. Needs notification access.",
            listOf(
                ParamSpec("state", "Notification is", ParamKind.CHOICE, "present", listOf("present" to "Showing", "absent" to "Not showing")),
                ParamSpec("app", "From app", ParamKind.APP, optional = true, hint = "Any app"),
                ParamSpec("contains", "Title or text contains", ParamKind.TEXT, optional = true),
            ),
        ) { s ->
            "Notification ${if (s.p("state", "present") == "present") "showing" else "not showing"}" +
                s.p("app").let { if (it.isBlank()) "" else " from $it" } +
                s.p("contains").let { if (it.isBlank()) "" else " with “$it”" }
        },
    )

    private val logicActions: List<StepSpec> = listOf(
        StepSpec("if", A, "Logic", "If", "Runs the following actions only when the conditions pass. Adds a matching End If.", conditions = true) {
            "If ${conditionSummary(it)}"
        },
        StepSpec("else_if", A, "Logic", "Else If", "Checked when the If above (and earlier Else Ifs) did not pass.", conditions = true) {
            "Else if ${conditionSummary(it)}"
        },
        StepSpec("else", A, "Logic", "Else", "Runs when no If / Else If above passed.") { "Else" },
        StepSpec("end_if", A, "Logic", "End If", "Closes an If block.") { "End if" },
        StepSpec(
            "loop", A, "Logic", "Loop", "Repeats the actions up to End Loop. The current pass number is {loop_index}.",
            listOf(
                ParamSpec(
                    "mode", "Repeat", ParamKind.CHOICE, "count",
                    listOf("count" to "A number of times", "while" to "While conditions pass", "forever" to "Forever (until Break / Stop)"),
                ),
                ParamSpec("count", "Times (for “a number of times”)", ParamKind.TEXT, "3", hint = "Number or magic text"),
            ),
            conditions = true,
        ) { s ->
            when (s.p("mode", "count")) {
                "while" -> "Loop while ${conditionSummary(s)}"
                "forever" -> "Loop forever"
                else -> "Loop ${s.p("count", "3")} times"
            }
        },
        StepSpec("end_loop", A, "Logic", "End Loop", "Closes a Loop block.") { "End loop" },
        StepSpec("break", A, "Logic", "Break", "Leaves the innermost loop.") { "Break out of loop" },
        StepSpec("continue", A, "Logic", "Continue", "Skips to the next pass of the innermost loop.") { "Next loop pass" },
        StepSpec("stop", A, "Logic", "Stop this macro", "Ends the macro here.") { "Stop" },

        StepSpec(
            "set_variable", A, "Variables", "Set variable", "Creates or changes a variable. Number values accept math like {v=count} * 2 + 1. “Random in range” picks a number between Min and Max (inclusive for integers; decimals get 2 places; true/false flips a coin).",
            listOf(
                scopeChoice,
                ParamSpec("name", "Variable name", ParamKind.TEXT),
                ParamSpec(
                    "type", "Type", ParamKind.CHOICE, "integer",
                    listOf("integer" to "Integer", "decimal" to "Decimal", "boolean" to "True / false", "string" to "Text"),
                ),
                ParamSpec(
                    "op", "Operation", ParamKind.CHOICE, "set",
                    listOf("set" to "Set to value", "increment" to "Add value (default 1)", "decrement" to "Subtract value (default 1)", "toggle" to "Toggle true/false", "append" to "Append text", "random" to "Random in range"),
                ),
                ParamSpec("value", "Value", ParamKind.TEXT, optional = true, hint = "Magic text and math allowed"),
                ParamSpec("min", "Min (for random)", ParamKind.TEXT, "1", hint = "Lowest value · magic text and math allowed", optional = true),
                ParamSpec("max", "Max (for random)", ParamKind.TEXT, "100", hint = "Highest value · magic text and math allowed", optional = true),
            ),
        ) { s ->
            when (s.p("op", "set")) {
                "increment" -> "${varRef(s)} += ${s.p("value", "1")}"
                "decrement" -> "${varRef(s)} -= ${s.p("value", "1")}"
                "toggle" -> "Toggle ${varRef(s)}"
                "append" -> "${varRef(s)} += \"${s.p("value")}\""
                "random" -> if (s.p("type", "integer") == "boolean") "${varRef(s)} = random true/false"
                else "${varRef(s)} = random ${s.p("min", "1")}…${s.p("max", "100")}"
                else -> "${varRef(s)} = ${s.p("value")}"
            }
        },
        StepSpec(
            "text_op", A, "Variables", "Text manipulation", "Transforms text and saves the result into a variable.",
            listOf(
                ParamSpec(
                    "op", "Operation", ParamKind.CHOICE, "upper",
                    listOf(
                        "upper" to "UPPERCASE", "lower" to "lowercase", "trim" to "Trim spaces", "length" to "Length",
                        "replace" to "Replace A with B", "substring" to "Substring (start A, length B)",
                        "regex" to "Regex extract (pattern A, group B)", "url_encode" to "URL-encode",
                    ),
                ),
                ParamSpec("input", "Input text", ParamKind.TEXT, hint = "Magic text allowed"),
                ParamSpec("a", "A", ParamKind.TEXT, optional = true),
                ParamSpec("b", "B", ParamKind.TEXT, optional = true),
                scopeChoice.copy(default = "local"),
                ParamSpec("name", "Save into variable", ParamKind.TEXT, "result"),
            ),
        ) { "${it.p("op", "upper")} → ${varRef(it)}" },
        StepSpec(
            "log", A, "Variables", "Write to log", "Adds a line to the run log — handy for debugging.",
            listOf(ParamSpec("text", "Text", ParamKind.TEXT, hint = "Magic text allowed")),
        ) { "\"${it.p("text")}\"" },

        StepSpec(
            "run_macro", A, "Macros", "Run macro", "Starts another macro's actions.",
            listOf(
                ParamSpec("macro", "Macro", ParamKind.MACRO),
                ParamSpec("check", "Constraints", ParamKind.CHOICE, "ignore", listOf("ignore" to "Ignore its constraints", "check" to "Only run if its constraints pass")),
                ParamSpec(
                    "mode", "How", ParamKind.CHOICE, "separate",
                    listOf("separate" to "Start it separately", "inline" to "Run here as an action block (wait, share local variables)"),
                ),
            ),
        ) { (if (it.p("mode", "separate") == "inline") "Block “" else "Run “") + "${macroName(it.p("macro"))}”" },
        StepSpec(
            "macro_enable", A, "Macros", "Enable / disable macro", "Switches a macro on or off.",
            listOf(
                ParamSpec("macro", "Macro", ParamKind.MACRO),
                ParamSpec("state", "Set to", ParamKind.CHOICE, "toggle", listOf("enable" to "Enabled", "disable" to "Disabled", "toggle" to "Toggle")),
            ),
        ) { "${it.p("state", "toggle").replaceFirstChar { c -> c.uppercase() }} “${macroName(it.p("macro"))}”" },
        StepSpec(
            "cancel_macro", A, "Macros", "Cancel macro actions", "Stops running copies of a macro (blank = other runs of this macro).",
            listOf(ParamSpec("macro", "Macro", ParamKind.MACRO, optional = true, hint = "This macro")),
        ) { s -> s.p("macro").let { if (it.isBlank()) "Cancel other runs of this macro" else "Cancel “${macroName(it)}”" } },
    )

    private val screenActions: List<StepSpec> = listOf(
        StepSpec(
            "ui_click", A, "Screen control", "Tap / long-press", "Presses a button or element in the app on screen. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec(
                    "target", "Find it by", ParamKind.CHOICE, "text",
                    listOf("text" to "Text on it", "id" to "View id", "coords" to "Screen coordinates"),
                ),
                ParamSpec("text", "Text", ParamKind.TEXT, optional = true, hint = "For “Text on it”"),
                ParamSpec("match", "Text match", ParamKind.CHOICE, "contains", listOf("contains" to "Contains", "exact" to "Exact")),
                ParamSpec("id", "View id", ParamKind.TEXT, optional = true, hint = "e.g. send_button or com.app:id/send"),
                ParamSpec("x", "X", ParamKind.TEXT, optional = true, hint = "For coordinates"),
                ParamSpec("y", "Y", ParamKind.TEXT, optional = true, hint = "For coordinates"),
                ParamSpec("press", "Press", ParamKind.CHOICE, "click", listOf("click" to "Tap", "long" to "Long-press")),
            ),
        ) { s ->
            val verb = if (s.p("press", "click") == "long") "Long-press" else "Tap"
            when (s.p("target", "text")) {
                "id" -> "$verb id ${s.p("id")}"
                "coords" -> "$verb at ${s.p("x")},${s.p("y")}"
                else -> "$verb “${s.p("text")}”"
            }
        },
        StepSpec(
            "ui_type", A, "Screen control", "Type text", "Fills the focused text field. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec("text", "Text", ParamKind.TEXT, hint = "Magic text allowed"),
                ParamSpec("mode", "Mode", ParamKind.CHOICE, "replace", listOf("replace" to "Replace field text", "append" to "Add to the end")),
            ),
        ) { "Type “${it.p("text")}”" },
        StepSpec(
            "ui_scroll", A, "Screen control", "Scroll", "Scrolls the first scrollable list on screen. Needs Accessibility (Permissions screen).",
            listOf(ParamSpec("direction", "Direction", ParamKind.CHOICE, "forward", listOf("forward" to "Down / forward", "backward" to "Up / back"))),
        ) { "Scroll ${it.p("direction", "forward")}" },
        StepSpec(
            "ui_swipe", A, "Screen control", "Swipe gesture", "Draws a swipe between two points. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec("x1", "Start X", ParamKind.TEXT, "540"),
                ParamSpec("y1", "Start Y", ParamKind.TEXT, "1600"),
                ParamSpec("x2", "End X", ParamKind.TEXT, "540"),
                ParamSpec("y2", "End Y", ParamKind.TEXT, "600"),
                ParamSpec("ms", "Duration (ms)", ParamKind.TEXT, "300"),
            ),
        ) { "Swipe ${it.p("x1")},${it.p("y1")} → ${it.p("x2")},${it.p("y2")}" },
        StepSpec(
            "global_action", A, "Screen control", "Press system button", "Back, Home, Recents, notification shade, lock, screenshot and more. Needs Accessibility (Permissions screen).",
            listOf(
                ParamSpec(
                    "button", "Button", ParamKind.CHOICE, "back",
                    listOf(
                        "back" to "Back", "home" to "Home", "recents" to "Recent apps",
                        "notifications" to "Open notifications", "quick_settings" to "Open quick settings",
                        "power_dialog" to "Power menu", "lock" to "Lock screen", "screenshot" to "Take screenshot",
                        "split_screen" to "Toggle split screen",
                    ),
                ),
            ),
        ) { "Press ${it.p("button", "back").replace('_', ' ')}" },
        StepSpec(
            "read_screen", A, "Screen control", "Read screen text", "Saves all visible text into a variable. Needs Accessibility (Permissions screen).",
            listOf(
                scopeChoice.copy(default = "local"),
                ParamSpec("name", "Save into variable", ParamKind.TEXT, "screen"),
            ),
        ) { "Screen text → ${varRef(it)}" },
        StepSpec("launch_home", A, "Apps", "Go to home screen", "Opens the launcher.") { "Home screen" },
        StepSpec(
            "kill_app", A, "Apps", "Kill background app", "Asks Android to close an app's background processes (can't close the app in front).",
            listOf(ParamSpec("app", "App", ParamKind.APP)),
        ) { "Kill ${it.p("app", "?")}" },
        StepSpec(
            "clear_notifications", A, "Notify", "Clear notifications", "Dismisses notifications. Needs notification access.",
            listOf(ParamSpec("app", "Only from app", ParamKind.APP, optional = true, hint = "All apps")),
        ) { s -> "Clear " + s.p("app").let { if (it.isBlank()) "all" else it } },
    )

    // ---- Stage 3: phone, SMS, location ----

    private val callStates = listOf(
        "incoming" to "Incoming (ringing)", "answered" to "Answered", "outgoing" to "Outgoing call started",
        "ended" to "Call ended", "missed" to "Missed call",
    )

    private val locationParam = ParamSpec("location", "Place (lat,lng)", ParamKind.LOCATION, hint = "e.g. 25.8690,-97.5027")

    private val phoneTriggers: List<StepSpec> = listOf(
        StepSpec(
            "call", T, "Phone & SMS", "Phone call", "Fires on incoming, answered, outgoing, ended or missed calls. Number is {number}.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "incoming", callStates),
                ParamSpec("number", "Number", ParamKind.TEXT, optional = true, hint = "Any number (outgoing numbers aren't visible)"),
            ),
        ) { s -> (callStates.firstOrNull { it.first == s.p("state", "incoming") }?.second ?: "Call") + s.p("number").let { if (it.isBlank()) "" else " · $it" } },
        StepSpec(
            "sms_received", T, "Phone & SMS", "SMS received", "Fires when a text arrives. Sender is {number}, message is {text}.",
            listOf(
                ParamSpec("number", "From number", ParamKind.TEXT, optional = true, hint = "Anyone"),
                ParamSpec("contains", "Message contains", ParamKind.TEXT, optional = true),
            ),
        ) { s -> "SMS" + s.p("number").let { if (it.isBlank()) "" else " from $it" } + s.p("contains").let { if (it.isBlank()) "" else " containing “$it”" } },
        StepSpec(
            "geofence", T, "Location", "Location (geofence)", "Fires when you enter or leave an area. Works without Google services. Needs location “all the time”.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "enter", listOf("enter" to "Entering the area", "exit" to "Leaving the area")),
                locationParam,
                ParamSpec("radius", "Radius (metres)", ParamKind.NUMBER, "150"),
            ),
        ) { s -> "${if (s.p("state", "enter") == "enter") "Enter" else "Leave"} ${s.p("radius", "150")} m around ${s.p("location", "?")}" },
        StepSpec(
            "sun", T, "Date & time", "Sunrise / sunset", "Fires at sunrise or sunset for a place, with an optional offset.",
            listOf(
                ParamSpec("event", "Event", ParamKind.CHOICE, "sunset", listOf("sunrise" to "Sunrise", "sunset" to "Sunset")),
                ParamSpec("offset", "Offset (minutes, negative = before)", ParamKind.NUMBER, "0"),
                locationParam,
            ),
        ) { s ->
            val off = s.p("offset", "0").toIntOrNull() ?: 0
            val tail = when {
                off > 0 -> " +$off min"
                off < 0 -> " $off min"
                else -> ""
            }
            "At ${s.p("event", "sunset")}$tail"
        },
    )

    private val phoneConstraints: List<StepSpec> = listOf(
        StepSpec(
            "call_state", C, "Phone & SMS", "Call state", "Only when the phone is idle, ringing or in a call.",
            listOf(ParamSpec("state", "Phone is", ParamKind.CHOICE, "idle", listOf("idle" to "Idle", "ringing" to "Ringing", "in_call" to "In a call"))),
        ) { "Phone ${it.p("state", "idle").replace('_', ' ')}" },
        StepSpec(
            "location", C, "Location", "Location", "Only when inside / outside an area (uses the last known location).",
            listOf(
                ParamSpec("state", "You are", ParamKind.CHOICE, "inside", listOf("inside" to "Inside", "outside" to "Outside")),
                locationParam,
                ParamSpec("radius", "Radius (metres)", ParamKind.NUMBER, "150"),
            ),
        ) { s -> "${s.p("state", "inside").replaceFirstChar { it.uppercase() }} ${s.p("radius", "150")} m of ${s.p("location", "?")}" },
        StepSpec(
            "day_of_month", C, "Date & time", "Day of month", "Only on certain dates, e.g. 1, 15, 28-31.",
            listOf(ParamSpec("days", "Days of month", ParamKind.TEXT, "1", hint = "Commas and ranges: 1, 15, 28-31")),
        ) { "On day ${it.p("days", "1")}" },
        StepSpec(
            "month", C, "Date & time", "Month", "Only in certain months (1 = January), e.g. 6-8 or 12, 1, 2.",
            listOf(ParamSpec("months", "Months", ParamKind.TEXT, "12", hint = "Commas and ranges: 6-8, 12")),
        ) { "In month ${it.p("months", "12")}" },
    )

    private val phoneActions: List<StepSpec> = listOf(
        StepSpec(
            "make_call", A, "Phone & SMS", "Make call", "Dials a number straight away.",
            listOf(ParamSpec("number", "Number", ParamKind.TEXT, hint = "Magic text allowed, e.g. {number}")),
        ) { "Call ${it.p("number")}" },
        StepSpec("answer_call", A, "Phone & SMS", "Answer call", "Picks up a ringing call.") { "Answer call" },
        StepSpec("end_call", A, "Phone & SMS", "End / reject call", "Hangs up or rejects the current call (Android 9+).") { "End call" },
        StepSpec(
            "speakerphone", A, "Phone & SMS", "Speakerphone", "Turns the call speaker on or off.",
            listOf(ParamSpec("state", "Set to", ParamKind.CHOICE, "toggle", listOf("on" to "On", "off" to "Off", "toggle" to "Toggle"))),
        ) { "Speaker ${it.p("state", "toggle")}" },
        StepSpec(
            "send_sms", A, "Phone & SMS", "Send SMS", "Sends a text message in the background.",
            listOf(
                ParamSpec("number", "To number", ParamKind.TEXT, hint = "Reply with {number}"),
                ParamSpec("text", "Message", ParamKind.TEXT, hint = "Magic text allowed, e.g. I'm here: {location_url}"),
            ),
        ) { "SMS ${it.p("number")}: “${it.p("text")}”" },
        StepSpec(
            "send_email", A, "Phone & SMS", "Compose email", "Opens your email app with the message filled in.",
            listOf(
                ParamSpec("to", "To", ParamKind.TEXT),
                ParamSpec("subject", "Subject", ParamKind.TEXT, optional = true),
                ParamSpec("body", "Body", ParamKind.TEXT, optional = true, hint = "Magic text allowed"),
            ),
        ) { "Email ${it.p("to")}" },
        StepSpec("open_call_log", A, "Phone & SMS", "Open call log", "Shows the call history.") { "Call log" },
        StepSpec(
            "get_location", A, "Location", "Get current location", "Fixes your position and sets {lat}, {lng}, {accuracy} and {location_url} (OpenStreetMap link).",
        ) { "Get location" },
    )

    // ---- Stage 4: sensors, device state, toggles, media ----

    /** Triggers that just compare the event's "state" with the chosen one (value = default state). */
    val simpleStateTriggers: Map<String, String> = mapOf(
        "flip" to "face_down", "proximity" to "near", "orientation" to "landscape", "music" to "playing",
        "silent_mode" to "silent", "airplane_changed" to "on", "gps_changed" to "on", "dock" to "docked",
        "usb_device" to "attached", "volume_button" to "up",
        "daydream" to "started", "vpn" to "on", "hotspot" to "on", "autosync" to "on", "signal" to "on",
    )

    private fun stateTrigger(type: String, cat: String, label: String, desc: String, vararg states: Pair<String, String>) =
        StepSpec(type, T, cat, label, desc, listOf(ParamSpec("state", "When", ParamKind.CHOICE, states.first().first, states.toList()))) { s ->
            "$label: " + (states.firstOrNull { it.first == s.p("state", states.first().first) }?.second ?: "")
        }

    private val onOffStates = arrayOf("on" to "Turned on", "off" to "Turned off")

    private val deviceTriggers: List<StepSpec> = listOf(
        StepSpec(
            "shake", T, "Sensors", "Shake device", "Fires when you shake the phone.",
            listOf(ParamSpec("sensitivity", "Sensitivity", ParamKind.CHOICE, "medium", listOf("low" to "Low (hard shake)", "medium" to "Medium", "high" to "High (gentle shake)"))),
        ) { "Shake (${it.p("sensitivity", "medium")})" },
        stateTrigger("flip", "Sensors", "Flip device", "Fires when the phone is turned face down or face up.", "face_down" to "Face down", "face_up" to "Face up"),
        stateTrigger("proximity", "Sensors", "Proximity sensor", "Fires when something covers or uncovers the top of the screen.", "near" to "Near (covered)", "far" to "Far (uncovered)"),
        stateTrigger("orientation", "Sensors", "Orientation", "Fires when the phone turns to landscape or portrait.", "landscape" to "Landscape", "portrait" to "Portrait"),
        stateTrigger("music", "Volume & sound", "Music playing", "Fires when any app starts or stops playing audio.", "playing" to "Started playing", "stopped" to "Stopped playing"),
        stateTrigger("silent_mode", "Volume & sound", "Ringer mode changed", "Fires when the phone switches ringer mode.", "silent" to "Silent", "vibrate" to "Vibrate", "normal" to "Normal"),
        stateTrigger("volume_button", "Volume & sound", "Volume button pressed", "Fires on volume key presses (screen on). Needs Accessibility.", "up" to "Volume up", "down" to "Volume down"),
        stateTrigger("airplane_changed", "Connectivity", "Airplane mode changed", "Fires when airplane mode is switched.", *onOffStates),
        stateTrigger("gps_changed", "Location", "Location services changed", "Fires when location (GPS) is switched on or off.", *onOffStates),
        stateTrigger("dock", "Device", "Dock / car mode", "Fires when docked or undocked (desk or car dock).", "docked" to "Docked", "undocked" to "Undocked"),
        stateTrigger("usb_device", "Connectivity", "USB device", "Fires when a USB device (keyboard, drive, DAC…) is attached or removed.", "attached" to "Attached", "detached" to "Removed"),
        StepSpec(
            "battery_temp", T, "Battery & power", "Battery temperature", "Fires when battery temperature crosses a threshold (°C).",
            listOf(
                ParamSpec("direction", "Fire when it", ParamKind.CHOICE, "above", listOf("above" to "Rises to or above", "below" to "Drops to or below")),
                ParamSpec("temp", "Temperature (°C)", ParamKind.NUMBER, "40"),
            ),
        ) { "Battery temp ${it.p("direction", "above")} ${it.p("temp", "40")}°C" },
    )

    private fun stateConstraint(key: String, cat: String, label: String, on: String, off: String) =
        StepSpec(
            "state_$key", C, cat, label, "Checks whether: $label.",
            listOf(ParamSpec("state", "State", ParamKind.CHOICE, "on", listOf("on" to on, "off" to off))),
        ) { s -> if (s.p("state", "on") == "on") on else off }

    private val deviceConstraints: List<StepSpec> = listOf(
        stateConstraint("headphones", "Connectivity", "Headphones connected", "Headphones connected", "No headphones"),
        stateConstraint("bluetooth", "Connectivity", "Bluetooth on", "Bluetooth on", "Bluetooth off"),
        stateConstraint("wifi", "Connectivity", "Wi-Fi on", "Wi-Fi switched on", "Wi-Fi switched off"),
        stateConstraint("airplane", "Connectivity", "Airplane mode", "Airplane mode on", "Airplane mode off"),
        stateConstraint("mobile_data", "Connectivity", "Mobile data", "Mobile data on", "Mobile data off"),
        stateConstraint("roaming", "Connectivity", "Roaming", "Roaming", "Not roaming"),
        stateConstraint("gps", "Location", "Location services", "Location on", "Location off"),
        stateConstraint("auto_rotate", "Device", "Auto-rotate", "Auto-rotate on", "Auto-rotate off"),
        stateConstraint("locked", "Device", "Device locked", "Device locked", "Device unlocked"),
        stateConstraint("landscape", "Device", "Orientation", "Landscape", "Portrait"),
        stateConstraint("docked", "Device", "Docked", "Docked", "Not docked"),
        stateConstraint("power_save", "Battery & power", "Battery saver", "Battery saver on", "Battery saver off"),
        stateConstraint("music", "Volume & sound", "Music playing", "Audio playing", "Nothing playing"),
        stateConstraint("dnd", "Volume & sound", "Do Not Disturb", "DND on", "DND off"),
        StepSpec(
            "ringer_state", C, "Volume & sound", "Ringer mode", "Only in a given ringer mode.",
            listOf(ParamSpec("state", "Ringer is", ParamKind.CHOICE, "normal", listOf("normal" to "Normal", "vibrate" to "Vibrate", "silent" to "Silent"))),
        ) { "Ringer ${it.p("state", "normal")}" },
        StepSpec(
            "battery_temp_state", C, "Battery & power", "Battery temperature", "Only when battery is above/below a temperature.",
            listOf(
                ParamSpec("direction", "Battery is", ParamKind.CHOICE, "above", listOf("above" to "Hotter than", "below" to "Cooler than")),
                ParamSpec("temp", "Temperature (°C)", ParamKind.NUMBER, "40"),
            ),
        ) { "Battery ${it.p("direction", "above")} ${it.p("temp", "40")}°C" },
    )

    private val toggleStates = listOf("on" to "On", "off" to "Off", "toggle" to "Toggle")

    private fun toggleAction(type: String, cat: String, label: String, desc: String) =
        StepSpec(type, A, cat, label, desc, listOf(ParamSpec("state", "Set to", ParamKind.CHOICE, "toggle", toggleStates))) {
            "$label ${it.p("state", "toggle")}"
        }

    private val deviceActions: List<StepSpec> = listOf(
        toggleAction("wifi_set", "Connectivity", "Wi-Fi", "Switches Wi-Fi. Android 10+ needs root, otherwise it opens the Wi-Fi panel."),
        toggleAction("bluetooth_set", "Connectivity", "Bluetooth", "Switches Bluetooth. Android 13+ needs root, otherwise it opens settings."),
        toggleAction("mobile_data_set", "Connectivity", "Mobile data", "Switches mobile data. Needs root."),
        toggleAction("airplane_set", "Connectivity", "Airplane mode", "Switches airplane mode. Needs root."),
        toggleAction("auto_rotate", "Device", "Auto-rotate", "Switches screen auto-rotation. Needs Modify system settings."),
        toggleAction("car_mode", "Device", "Car mode", "Switches Android's car mode."),
        StepSpec(
            "keep_awake", A, "Device", "Keep screen awake", "Stops the screen turning off (optionally for N minutes).",
            listOf(
                ParamSpec("state", "Set to", ParamKind.CHOICE, "on", toggleStates),
                ParamSpec("minutes", "For minutes (0 = until turned off)", ParamKind.NUMBER, "0"),
            ),
        ) { "Keep awake ${it.p("state", "on")}" },
        StepSpec("screen_on", A, "Device", "Turn screen on", "Wakes the screen.") { "Screen on" },
        StepSpec(
            "screen_timeout", A, "Device", "Screen timeout", "Sets how long before the screen turns off. Needs Modify system settings.",
            listOf(ParamSpec("seconds", "Seconds", ParamKind.NUMBER, "60")),
        ) { "Timeout ${it.p("seconds", "60")}s" },
        StepSpec(
            "set_wallpaper", A, "Device", "Set wallpaper", "Sets the wallpaper from an image file path or URL.",
            listOf(
                ParamSpec("source", "Image path or URL", ParamKind.TEXT, hint = "/sdcard/Pictures/bg.jpg or https://…"),
                ParamSpec("target", "Apply to", ParamKind.CHOICE, "both", listOf("home" to "Home screen", "lock" to "Lock screen", "both" to "Both")),
            ),
        ) { "Wallpaper (${it.p("target", "both")})" },
        StepSpec(
            "set_alarm", A, "Date & time", "Set alarm clock", "Adds an alarm to your clock app.",
            listOf(
                ParamSpec("time", "Time", ParamKind.TIME, "07:00"),
                ParamSpec("label", "Label", ParamKind.TEXT, "TaskTemplar"),
            ),
        ) { "Alarm at ${it.p("time", "07:00")}" },
        StepSpec(
            "media_control", A, "Media", "Media control", "Sends play / pause / next / previous to the active player.",
            listOf(
                ParamSpec(
                    "button", "Button", ParamKind.CHOICE, "play_pause",
                    listOf("play_pause" to "Play / pause", "play" to "Play", "pause" to "Pause", "next" to "Next", "previous" to "Previous", "stop" to "Stop"),
                ),
            ),
        ) { "Media ${it.p("button", "play_pause").replace('_', '/')}" },
        StepSpec(
            "volume_step", A, "Volume & sound", "Volume up / down", "Nudges a volume stream one step.",
            listOf(
                ParamSpec("direction", "Direction", ParamKind.CHOICE, "up", listOf("up" to "Up", "down" to "Down")),
                ParamSpec("stream", "Stream", ParamKind.CHOICE, "media", listOf("media" to "Media", "ring" to "Ringtone", "notification" to "Notification", "alarm" to "Alarm")),
                ParamSpec("show", "Show volume bar", ParamKind.CHOICE, "yes", listOf("yes" to "Yes", "no" to "No")),
            ),
        ) { "${it.p("stream", "media")} volume ${it.p("direction", "up")}" },
        StepSpec(
            "play_sound", A, "Volume & sound", "Play sound", "Plays the default notification / ringtone / alarm sound, or a file or URL.",
            listOf(
                ParamSpec(
                    "source", "Sound", ParamKind.CHOICE, "notification",
                    listOf("notification" to "Default notification", "ringtone" to "Default ringtone", "alarm" to "Default alarm", "file" to "File path or URL"),
                ),
                ParamSpec("path", "File path or URL", ParamKind.TEXT, optional = true),
                ParamSpec("stream", "Play as", ParamKind.CHOICE, "notification", listOf("notification" to "Notification", "media" to "Media", "alarm" to "Alarm (ignores silent)")),
                ParamSpec("loop", "Loop", ParamKind.CHOICE, "no", listOf("no" to "Once", "yes" to "Loop until Stop sound")),
            ),
        ) { "Play ${it.p("source", "notification")}" },
        StepSpec("stop_sound", A, "Volume & sound", "Stop sound", "Stops a sound started by Play sound.") { "Stop sound" },
        StepSpec(
            "shell", A, "Advanced", "Shell command", "Runs a shell command (as root if you choose). Output goes to {shell_output}, exit code to {shell_exit}.",
            listOf(
                ParamSpec("command", "Command", ParamKind.TEXT, hint = "Magic text allowed"),
                ParamSpec("root", "Run as root", ParamKind.CHOICE, "no", listOf("no" to "No", "yes" to "Yes (rooted phones)")),
                ParamSpec("timeout", "Timeout (seconds)", ParamKind.NUMBER, "30"),
                ParamSpec("name", "Also save output to global variable", ParamKind.TEXT, optional = true),
            ),
        ) { (if (it.p("root", "no") == "yes") "# " else "$ ") + it.p("command") },
    )

    // ---- Stage 5: launchers and dialogs ----

    private val launcherTriggers: List<StepSpec> = listOf(
        StepSpec(
            "qs_tile", T, "Launchers", "Quick-settings tile", "Adds a tile to the pull-down quick settings (edit your tiles and drag in “TaskTemplar 1/2/3”).",
            listOf(ParamSpec("slot", "Tile slot", ParamKind.CHOICE, "1", listOf("1" to "TaskTemplar 1", "2" to "TaskTemplar 2", "3" to "TaskTemplar 3"))),
        ) { "Quick-settings tile ${it.p("slot", "1")}" },
        StepSpec(
            "floating_button", T, "Launchers", "Floating button", "A draggable bubble over all apps. Needs “Display over other apps”.",
            listOf(
                ParamSpec("label", "Label (1–3 letters)", ParamKind.TEXT, optional = true, hint = "Defaults to the macro name"),
                ParamSpec("color", "Colour (hex)", ParamKind.TEXT, "#B69CFF"),
                ParamSpec("size", "Size (dp)", ParamKind.NUMBER, "52"),
                ParamSpec("opacity", "Opacity (%)", ParamKind.NUMBER, "90"),
            ),
        ) { "Floating button ${it.p("label")}".trim() },
        StepSpec(
            "notif_button", T, "Launchers", "Notification button", "Adds a button to TaskTemplar's status notification (expand it to see; up to 3).",
            listOf(ParamSpec("label", "Button label", ParamKind.TEXT, optional = true, hint = "Defaults to the macro name")),
        ) { "Notification button ${it.p("label")}".trim() },
        StepSpec(
            "intent_received", T, "Launchers", "Intent received", "Fires when another app (Tasker, scripts, adb) sends a broadcast with this action. Extras become magic text.",
            listOf(ParamSpec("action", "Intent action", ParamKind.TEXT, "dev.isaakrichards.tasktemplar.RUN")),
        ) { "Intent ${it.p("action")}" },
        StepSpec(
            "webhook", T, "Launchers", "Webhook (local network)",
            "Fires when http://PHONE-IP:PORT/hook/NAME is opened from your network. Query values and {body} become magic text.",
            listOf(
                ParamSpec("name", "Hook name", ParamKind.TEXT, "lights"),
                ParamSpec("port", "Port (shared by all hooks)", ParamKind.NUMBER, "8765"),
                ParamSpec("token", "Secret token (?token=…)", ParamKind.TEXT, optional = true, hint = "Recommended"),
            ),
        ) { "Webhook /hook/${it.p("name")}" },
    )

    private val dialogActions: List<StepSpec> = listOf(
        StepSpec(
            "dialog_message", A, "Dialogs", "Message dialog", "Shows a pop-up message and waits for OK. Needs “Display over other apps” when the app isn't open.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, optional = true),
                ParamSpec("message", "Message", ParamKind.TEXT, hint = "Magic text allowed"),
                ParamSpec("timeout", "Give up after (seconds)", ParamKind.NUMBER, "120"),
            ),
        ) { "Show “${it.p("title").ifBlank { it.p("message") }}”" },
        StepSpec(
            "dialog_options", A, "Dialogs", "Option dialog", "Lets you pick from a list. Choice goes to {option} and {option_index}.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, optional = true),
                ParamSpec("message", "Message", ParamKind.TEXT, optional = true),
                ParamSpec("options", "Options (comma separated)", ParamKind.TEXT, "Yes, No, Later"),
                ParamSpec("on_cancel", "If dismissed", ParamKind.CHOICE, "continue", listOf("continue" to "Carry on", "stop" to "Stop the macro")),
                ParamSpec("timeout", "Give up after (seconds)", ParamKind.NUMBER, "120"),
            ),
        ) { "Choose: ${it.p("options")}" },
        StepSpec(
            "confirm", A, "Dialogs", "Confirm (yes / no)", "Asks before carrying on. Answer goes to {confirmed}.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, "Continue?"),
                ParamSpec("message", "Message", ParamKind.TEXT, optional = true),
                ParamSpec("on_no", "If No", ParamKind.CHOICE, "stop", listOf("stop" to "Stop the macro", "continue" to "Carry on (check {confirmed})")),
                ParamSpec("timeout", "Give up after (seconds)", ParamKind.NUMBER, "120"),
            ),
        ) { "Confirm “${it.p("title", "Continue?")}”" },
        StepSpec(
            "input_dialog", A, "Dialogs", "Text input dialog", "Asks you to type something and saves it into a variable.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, optional = true),
                ParamSpec("message", "Prompt", ParamKind.TEXT, optional = true),
                scopeChoice.copy(default = "local"),
                ParamSpec("name", "Save into variable", ParamKind.TEXT, "input"),
                ParamSpec("on_cancel", "If cancelled", ParamKind.CHOICE, "continue", listOf("continue" to "Carry on", "stop" to "Stop the macro")),
                ParamSpec("timeout", "Give up after (seconds)", ParamKind.NUMBER, "120"),
            ),
        ) { "Ask → ${varRef(it)}" },
        StepSpec(
            "send_intent", A, "Advanced", "Send intent", "Sends an Android intent: talk to Tasker, Home Assistant, Termux and other apps.",
            listOf(
                ParamSpec("target", "Send as", ParamKind.CHOICE, "activity", listOf("activity" to "Start activity", "broadcast" to "Broadcast", "service" to "Start service")),
                ParamSpec("action", "Action", ParamKind.TEXT, optional = true),
                ParamSpec("data", "Data URI", ParamKind.TEXT, optional = true),
                ParamSpec("package", "Package", ParamKind.TEXT, optional = true),
                ParamSpec("extras", "Extras (key=value; key2=value2)", ParamKind.TEXT, optional = true),
            ),
        ) { "Intent ${it.p("action").ifBlank { it.p("data") }}" },
    )

    // ---- Stage 6: more triggers ----

    private val moreTriggers6: List<StepSpec> = listOf(
        StepSpec(
            "date", T, "Date & time", "Day of month", "Fires on a given day of the month (or the last day) at a set time.",
            listOf(
                ParamSpec("day", "Day (1–31 or “last”)", ParamKind.TEXT, "1"),
                ParamSpec("time", "Time", ParamKind.TIME, "09:00"),
                ParamSpec("months", "Months", ParamKind.TEXT, "1-12", hint = "e.g. 1-12 or 3, 6, 9, 12"),
            ),
        ) { "Day ${it.p("day", "1")} at ${it.p("time", "09:00")}" },
        StepSpec(
            "calendar", T, "Date & time", "Calendar event", "Fires at the start or end of events in your phone's calendars. Title is {event_title}.",
            listOf(
                ParamSpec("when", "At event", ParamKind.CHOICE, "start", listOf("start" to "Start", "end" to "End")),
                ParamSpec("title", "Title contains", ParamKind.TEXT, optional = true, hint = "Any event"),
                ParamSpec("offset", "Offset (minutes, negative = before)", ParamKind.NUMBER, "0"),
                ParamSpec("all_day", "All-day events", ParamKind.CHOICE, "include", listOf("include" to "Include", "exclude" to "Ignore")),
            ),
        ) { s -> "Calendar ${s.p("when", "start")}" + s.p("title").let { if (it.isBlank()) "" else " “$it”" } },
        StepSpec(
            "stopwatch", T, "Date & time", "Stopwatch / countdown", "Fires when a named stopwatch (started by the Stopwatch action) reaches a time.",
            listOf(
                ParamSpec("name", "Stopwatch name", ParamKind.TEXT, "timer"),
                ParamSpec("seconds", "Reaches (seconds)", ParamKind.NUMBER, "60"),
            ),
        ) { "Stopwatch ${it.p("name", "timer")} hits ${it.p("seconds", "60")}s" },
        StepSpec(
            "weather", T, "Location", "Weather", "Checks the weather (Open-Meteo, free, no account) and fires when a condition becomes true.",
            listOf(
                ParamSpec(
                    "condition", "When", ParamKind.CHOICE, "rain",
                    listOf(
                        "rain" to "It starts raining", "snow" to "It starts snowing", "clear" to "It clears up",
                        "temp_above" to "Temperature above", "temp_below" to "Temperature below",
                        "wind_above" to "Wind above", "humidity_above" to "Humidity above (%)",
                    ),
                ),
                ParamSpec("value", "Value (for above / below)", ParamKind.NUMBER, "90"),
                ParamSpec("unit", "Units", ParamKind.CHOICE, "fahrenheit", listOf("fahrenheit" to "°F, mph", "celsius" to "°C, km/h")),
                ParamSpec("location", "Place (blank = last known)", ParamKind.LOCATION, optional = true),
                ParamSpec("every", "Check every (minutes)", ParamKind.NUMBER, "30"),
            ),
        ) { "Weather: ${it.p("condition", "rain").replace('_', ' ')}" },
        StepSpec(
            "power_button", T, "Device", "Power button presses", "Fires when the power button is pressed several times quickly.",
            listOf(
                ParamSpec("presses", "Presses", ParamKind.NUMBER, "3"),
                ParamSpec("window", "Within (seconds)", ParamKind.NUMBER, "3"),
            ),
        ) { "Power ×${it.p("presses", "3")}" },
        StepSpec(
            "media_button", T, "Volume & sound", "Media / headset button", "Fires on headset or media keys while the screen is on. Needs Accessibility.",
            listOf(
                ParamSpec(
                    "button", "Button", ParamKind.CHOICE, "any",
                    listOf("any" to "Any", "play_pause" to "Play / pause / headset", "next" to "Next", "previous" to "Previous"),
                ),
            ),
        ) { "Media button ${it.p("button", "any").replace('_', '/')}" },
        StepSpec(
            "light", T, "Sensors", "Light level", "Fires when ambient light crosses a level (lux).",
            listOf(
                ParamSpec("direction", "Fire when it", ParamKind.CHOICE, "below", listOf("below" to "Gets darker than", "above" to "Gets brighter than")),
                ParamSpec("lux", "Lux", ParamKind.NUMBER, "10"),
            ),
        ) { "Light ${it.p("direction", "below")} ${it.p("lux", "10")} lux" },
        StepSpec(
            "edge_swipe", T, "Launchers", "Edge swipe", "Swipe in from a screen edge. Adds a thin invisible strip. Needs “Display over other apps”.",
            listOf(
                ParamSpec("edge", "Edge", ParamKind.CHOICE, "right", listOf("left" to "Left", "right" to "Right")),
                ParamSpec("position", "Strip position", ParamKind.CHOICE, "middle", listOf("top" to "Upper", "middle" to "Middle", "bottom" to "Lower")),
            ),
        ) { "Swipe in from ${it.p("edge", "right")}" },
        StepSpec(
            "nfc", T, "Connectivity", "NFC tag", "Fires when an NFC tag is scanned (screen unlocked). Tag id is {tag}.",
            listOf(ParamSpec("tag", "Tag id", ParamKind.TEXT, optional = true, hint = "Any tag. Scan once, copy the id from the log")),
        ) { s -> "NFC tag" + s.p("tag").let { if (it.isBlank()) "" else " $it" } },
        StepSpec(
            "login_failed", T, "Device", "Failed unlock", "Fires after wrong PIN / pattern attempts. Needs Device admin (Permissions screen).",
            listOf(ParamSpec("attempts", "After attempts", ParamKind.NUMBER, "1")),
        ) { "Failed unlock ×${it.p("attempts", "1")}" },
        StepSpec(
            "connection", T, "Connectivity", "Data connection changed", "Fires when the active connection switches (Wi-Fi, mobile, ethernet, none).",
            listOf(
                ParamSpec(
                    "type", "To", ParamKind.CHOICE, "any",
                    listOf("any" to "Any change", "wifi" to "Wi-Fi", "mobile" to "Mobile data", "ethernet" to "Ethernet", "none" to "No connection"),
                ),
            ),
        ) { "Connection → ${it.p("type", "any")}" },
        StepSpec(
            "wifi_in_range", T, "Connectivity", "Wi-Fi network in range", "Fires when a network shows up (or disappears) in Wi-Fi scans. Needs location.",
            listOf(
                ParamSpec("ssid", "Network name", ParamKind.TEXT),
                ParamSpec("state", "When", ParamKind.CHOICE, "appears", listOf("appears" to "Comes in range", "disappears" to "Goes out of range")),
            ),
        ) { "“${it.p("ssid")}” ${it.p("state", "appears")}" },
        StepSpec(
            "sms_sent", T, "Phone & SMS", "SMS sent", "Fires after you send a text. Number is {number}, message {text}. Needs SMS permission.",
            listOf(ParamSpec("number", "To number", ParamKind.TEXT, optional = true, hint = "Anyone")),
        ) { s -> "SMS sent" + s.p("number").let { if (it.isBlank()) "" else " to $it" } },
        StepSpec(
            "clipboard_changed", T, "Device", "Clipboard changed", "Fires when the clipboard changes. Android 10+ only reports this while TaskTemplar is open, or if it's your keyboard.",
            listOf(ParamSpec("contains", "Text contains", ParamKind.TEXT, optional = true)),
        ) { "Clipboard changed" },
        stateTrigger("daydream", "Device", "Screensaver", "Fires when the screensaver (daydream) starts or stops.", "started" to "Started", "stopped" to "Stopped"),
        stateTrigger("vpn", "Connectivity", "VPN", "Fires when a VPN connects or disconnects.", "on" to "Connected", "off" to "Disconnected"),
        stateTrigger("hotspot", "Connectivity", "Hotspot", "Fires when the Wi-Fi hotspot is switched.", *onOffStates),
        stateTrigger("autosync", "Device", "Auto-sync", "Fires when account auto-sync is switched.", *onOffStates),
    )

    private val moreConstraints6: List<StepSpec> = listOf(
        StepSpec(
            "logic_group", C, "Logic", "Logic group (AND / OR / NOT)", "Combines its own conditions: all, any, none, or not all.",
            listOf(
                ParamSpec(
                    "op", "Passes when", ParamKind.CHOICE, "all",
                    listOf("all" to "ALL pass (AND)", "any" to "ANY passes (OR)", "none" to "NONE pass (NOT OR)", "not_all" to "NOT all pass (NAND)"),
                ),
            ),
            conditions = true,
        ) { s -> "${s.p("op", "all").uppercase().replace('_', ' ')} of (${s.conditions.joinToString("; ") { summarize(it) }})" },
        StepSpec(
            "light_level", C, "Sensors", "Light level", "Compares ambient light (lux).",
            listOf(
                ParamSpec("direction", "Light is", ParamKind.CHOICE, "below", listOf("below" to "Darker than", "above" to "Brighter than")),
                ParamSpec("lux", "Lux", ParamKind.NUMBER, "10"),
            ),
        ) { "Light ${it.p("direction", "below")} ${it.p("lux", "10")} lux" },
        StepSpec(
            "proximity_state", C, "Sensors", "Proximity", "Checks if the proximity sensor is covered.",
            listOf(ParamSpec("state", "Sensor is", ParamKind.CHOICE, "near", listOf("near" to "Covered", "far" to "Uncovered"))),
        ) { "Proximity ${it.p("state", "near")}" },
        StepSpec(
            "stopwatch_state", C, "Date & time", "Stopwatch value", "Compares a named stopwatch's seconds.",
            listOf(
                ParamSpec("name", "Stopwatch name", ParamKind.TEXT, "timer"),
                ParamSpec("direction", "Is", ParamKind.CHOICE, "above", listOf("above" to "More than", "below" to "Less than")),
                ParamSpec("seconds", "Seconds", ParamKind.NUMBER, "60"),
            ),
        ) { "Stopwatch ${it.p("name", "timer")} ${it.p("direction", "above")} ${it.p("seconds", "60")}s" },
        stateConstraint("hotspot", "Connectivity", "Hotspot", "Hotspot on", "Hotspot off"),
        stateConstraint("vpn", "Connectivity", "VPN", "VPN connected", "No VPN"),
        stateConstraint("autosync", "Device", "Auto-sync", "Auto-sync on", "Auto-sync off"),
    )

    private val moreActions6: List<StepSpec> = listOf(
        StepSpec(
            "stopwatch_op", A, "Date & time", "Stopwatch", "Starts, pauses or resets a named stopwatch. Read it with {sw=name} (seconds).",
            listOf(
                ParamSpec("name", "Stopwatch name", ParamKind.TEXT, "timer"),
                ParamSpec(
                    "op", "Do", ParamKind.CHOICE, "start",
                    listOf("start" to "Start / resume", "pause" to "Pause", "reset" to "Reset to 0", "restart" to "Restart from 0", "stop" to "Stop and clear"),
                ),
            ),
        ) { "Stopwatch ${it.p("name", "timer")}: ${it.p("op", "start")}" },
        StepSpec(
            "get_weather", A, "Location", "Get weather", "Looks up current weather (Open-Meteo). Sets {temp}, {humidity}, {wind}, {precip}, {weather}.",
            listOf(
                ParamSpec("location", "Place (blank = current location)", ParamKind.LOCATION, optional = true),
                ParamSpec("unit", "Units", ParamKind.CHOICE, "fahrenheit", listOf("fahrenheit" to "°F, mph", "celsius" to "°C, km/h")),
            ),
        ) { "Get weather" },
    )

    // ---- Stage 7 ----

    private val fileHint = "Relative = Download/TaskTemplar/…, or a full /sdcard/… path"

    private val moreTriggers7: List<StepSpec> = listOf(
        stateTrigger("signal", "Phone & SMS", "Mobile signal", "Fires when the phone gains or loses mobile service.", "on" to "Signal found", "off" to "Signal lost"),
        StepSpec(
            "cell_tower", T, "Location", "Cell tower", "Fires when you connect to (or leave) listed cell towers: battery-friendly location. Ids appear in the log while used. Needs location.",
            listOf(
                ParamSpec("state", "When", ParamKind.CHOICE, "enter", listOf("enter" to "Connecting to one of them", "exit" to "Leaving them all")),
                ParamSpec("ids", "Cell ids (comma separated)", ParamKind.TEXT),
            ),
        ) { "Cell ${it.p("state", "enter")}: ${it.p("ids")}" },
    )

    private val moreConstraints7: List<StepSpec> = listOf(
        stateConstraint("signal", "Phone & SMS", "Mobile signal", "Has signal", "No signal"),
    )

    private val moreActions7: List<StepSpec> = listOf(
        StepSpec(
            "list_op", A, "Variables", "Array variable", "Adds, removes, sorts or splits items. Read with {v=name[0]}, count with {v=name[#]}.",
            listOf(
                scopeChoice,
                ParamSpec("name", "Array name", ParamKind.TEXT),
                ParamSpec(
                    "op", "Operation", ParamKind.CHOICE, "add",
                    listOf(
                        "add" to "Add to end", "insert" to "Insert at index", "set" to "Set item at index", "remove_at" to "Remove at index",
                        "remove_value" to "Remove value", "clear" to "Clear", "sort" to "Sort", "reverse" to "Reverse",
                        "split" to "Fill by splitting text", "join" to "Join into {joined}",
                    ),
                ),
                ParamSpec("value", "Value / text", ParamKind.TEXT, optional = true),
                ParamSpec("index", "Index (from 0)", ParamKind.TEXT, optional = true),
                ParamSpec("separator", "Separator (split / join)", ParamKind.TEXT, ","),
            ),
        ) { "${varRef(it)}: ${it.p("op", "add")}" },
        StepSpec(
            "dict_op", A, "Variables", "Dictionary variable", "Key → value pairs. Read with {v=name[key]}, keys with {v=name[keys]}.",
            listOf(
                scopeChoice,
                ParamSpec("name", "Dictionary name", ParamKind.TEXT),
                ParamSpec("op", "Operation", ParamKind.CHOICE, "put", listOf("put" to "Set key", "remove" to "Remove key", "clear" to "Clear", "from_json" to "Merge JSON object (e.g. {http_body})")),
                ParamSpec("key", "Key", ParamKind.TEXT, optional = true),
                ParamSpec("value", "Value", ParamKind.TEXT, optional = true),
            ),
        ) { "${varRef(it)}[${it.p("key")}] ${it.p("op", "put")}" },
        StepSpec("clear_log", A, "Variables", "Clear run log", "Empties the run log.") { "Clear log" },
        StepSpec(
            "say_time", A, "Notify", "Say the time", "Speaks the current time.",
            listOf(ParamSpec("format", "Clock", ParamKind.CHOICE, "12", listOf("12" to "12-hour", "24" to "24-hour"))),
        ) { "Say time" },
        StepSpec(
            "notification_interact", A, "Notify", "Notification interaction", "Opens, dismisses, presses a button on, or replies to another app's notification. Needs notification access.",
            listOf(
                ParamSpec("op", "Do", ParamKind.CHOICE, "tap", listOf("tap" to "Open it", "button" to "Press a button", "reply" to "Reply", "dismiss" to "Dismiss")),
                ParamSpec("app", "From app", ParamKind.APP, optional = true, hint = "Newest from any app"),
                ParamSpec("contains", "Title or text contains", ParamKind.TEXT, optional = true),
                ParamSpec("button", "Button label", ParamKind.TEXT, optional = true, hint = "For “Press a button”"),
                ParamSpec("text", "Reply text", ParamKind.TEXT, optional = true, hint = "Magic text allowed"),
            ),
        ) { "Notification: ${it.p("op", "tap")}" + it.p("app").let { a -> if (a.isBlank()) "" else " ($a)" } },
        StepSpec(
            "set_ringtone", A, "Volume & sound", "Set ringtone / notification sound", "Changes a default sound. Needs Modify system settings.",
            listOf(
                ParamSpec("which", "Which sound", ParamKind.CHOICE, "ringtone", listOf("ringtone" to "Ringtone", "notification" to "Notification sound", "alarm" to "Alarm sound")),
                ParamSpec("sound", "Sound", ParamKind.SOUND),
            ),
        ) { "Set ${it.p("which", "ringtone")}" },
        StepSpec(
            "vibrate_ringing", A, "Volume & sound", "Vibrate when ringing", "Turns vibrate-for-calls on or off. Needs Modify system settings.",
            listOf(ParamSpec("state", "Set to", ParamKind.CHOICE, "off", listOf("on" to "On", "off" to "Off"))),
        ) { "Vibrate when ringing ${it.p("state", "off")}" },
        StepSpec(
            "dim_screen", A, "Device", "Dim screen (overlay)", "Darkens the screen below minimum brightness. Needs “Display over other apps”.",
            listOf(
                ParamSpec("state", "Set to", ParamKind.CHOICE, "on", listOf("on" to "On", "off" to "Off")),
                ParamSpec("level", "Darkness (%)", ParamKind.NUMBER, "50"),
            ),
        ) { "Dim ${it.p("state", "on")}" },
        StepSpec("keyboard_picker", A, "Device", "Choose keyboard", "Shows the keyboard switcher.") { "Keyboard picker" },
        StepSpec("voice_search", A, "Apps", "Voice command", "Opens your voice assistant.") { "Voice command" },
        StepSpec("screensaver", A, "Device", "Start screensaver", "Starts the daydream / screensaver.") { "Screensaver" },
        StepSpec("hotspot_settings", A, "Connectivity", "Hotspot settings", "Android doesn't let apps switch the hotspot, so this opens its settings page.") { "Hotspot settings" },
        StepSpec(
            "launch_activity", A, "Apps", "Launch activity", "Opens a specific screen inside an app (package + activity class).",
            listOf(
                ParamSpec("package", "Package", ParamKind.APP),
                ParamSpec("class", "Activity class", ParamKind.TEXT, hint = "e.g. .SettingsActivity or com.app.SettingsActivity"),
            ),
        ) { "${it.p("package")}/${it.p("class")}" },
        StepSpec(
            "open_file", A, "Files", "Open file", "Opens a file in a matching app.",
            listOf(ParamSpec("path", "File", ParamKind.TEXT, hint = fileHint)),
        ) { "Open ${it.p("path")}" },
        StepSpec(
            "write_file", A, "Files", "Write to file", "Appends or writes text to a file (great for logs and CSVs).",
            listOf(
                ParamSpec("path", "File", ParamKind.TEXT, "notes.txt", hint = fileHint),
                ParamSpec("text", "Text", ParamKind.TEXT, hint = "Magic text allowed, e.g. {date} {time},{battery}"),
                ParamSpec("mode", "Mode", ParamKind.CHOICE, "append", listOf("append" to "Append", "overwrite" to "Overwrite")),
                ParamSpec("newline", "Add new line", ParamKind.CHOICE, "yes", listOf("yes" to "Yes", "no" to "No")),
            ),
        ) { "${it.p("mode", "append").replaceFirstChar { c -> c.uppercase() }} → ${it.p("path", "notes.txt")}" },
        StepSpec(
            "read_file", A, "Files", "Read file", "Loads a text file into a variable.",
            listOf(
                ParamSpec("path", "File", ParamKind.TEXT, hint = fileHint),
                scopeChoice.copy(default = "local"),
                ParamSpec("name", "Save into variable", ParamKind.TEXT, "file"),
            ),
        ) { "Read ${it.p("path")} → ${varRef(it)}" },
        StepSpec(
            "file_op", A, "Files", "File operation", "Copy, move, delete or create folders.",
            listOf(
                ParamSpec("op", "Operation", ParamKind.CHOICE, "copy", listOf("copy" to "Copy", "move" to "Move / rename", "delete" to "Delete", "mkdir" to "Create folder")),
                ParamSpec("path", "File / folder", ParamKind.TEXT, hint = fileHint),
                ParamSpec("dest", "Destination (copy / move)", ParamKind.TEXT, optional = true),
            ),
        ) { "${it.p("op", "copy")} ${it.p("path")}" },
        StepSpec(
            "upload_file", A, "Web", "Upload file / photo", "POSTs a file as multipart/form-data, e.g. to your home server or a Nextcloud / Immich endpoint.",
            listOf(
                ParamSpec("path", "File", ParamKind.TEXT, hint = fileHint),
                ParamSpec("url", "Upload URL", ParamKind.TEXT, "http://"),
                ParamSpec("field", "Form field name", ParamKind.TEXT, "file"),
            ),
        ) { "Upload ${it.p("path")}" },
        StepSpec(
            "udp_send", A, "Web", "UDP command", "Sends a UDP packet: wake-on-LAN helpers, smart lights, home automation.",
            listOf(
                ParamSpec("host", "Host / IP", ParamKind.TEXT, "192.168.1.255"),
                ParamSpec("port", "Port", ParamKind.NUMBER, "9"),
                ParamSpec("message", "Message", ParamKind.TEXT, hint = "Magic text allowed"),
            ),
        ) { "UDP ${it.p("host")}:${it.p("port", "9")}" },
        StepSpec(
            "clear_call_log", A, "Phone & SMS", "Clear call log", "Deletes call history (optionally only for a number).",
            listOf(ParamSpec("number", "Only number", ParamKind.TEXT, optional = true, hint = "All calls")),
        ) { "Clear call log" },
        StepSpec(
            "calendar_add", A, "Date & time", "Add calendar event", "Adds an event to your main calendar.",
            listOf(
                ParamSpec("title", "Title", ParamKind.TEXT, "TaskTemplar"),
                ParamSpec("in_minutes", "Starts in (minutes)", ParamKind.NUMBER, "60"),
                ParamSpec("duration", "Length (minutes)", ParamKind.NUMBER, "60"),
                ParamSpec("notes", "Notes", ParamKind.TEXT, optional = true),
            ),
        ) { "Event “${it.p("title", "TaskTemplar")}”" },
        StepSpec(
            "export_macros", A, "Files", "Back up macros", "Saves all macros as JSON (import them from the ⋮ menu). Pair with a Day/time trigger for scheduled backups.",
            listOf(ParamSpec("path", "File", ParamKind.TEXT, "backup-{date}.json", hint = fileHint)),
        ) { "Backup → ${it.p("path", "backup-{date}.json")}" },
    )

    // ---- Stage 8 ----

    /** Turns a plugin config into a summary; replaced at app start. */
    var pluginSummary: (String) -> String = { "Plugin" }

    private val moreActions8: List<StepSpec> = listOf(
        StepSpec(
            "take_photo", A, "Media", "Take photo", "Silently takes a photo into Pictures/TaskTemplar. Path goes to {photo_path}. Needs Camera (and “Display over other apps” in the background).",
            listOf(ParamSpec("camera", "Camera", ParamKind.CHOICE, "back", listOf("back" to "Back", "front" to "Front (selfie)"))),
        ) { "Photo (${it.p("camera", "back")})" },
        StepSpec(
            "record_audio", A, "Media", "Record microphone", "Records audio into Music/TaskTemplar and waits. Path goes to {audio_path}. Needs Microphone.",
            listOf(ParamSpec("seconds", "Seconds", ParamKind.NUMBER, "10")),
        ) { "Record ${it.p("seconds", "10")}s" },
        StepSpec(
            "locale_plugin", A, "Advanced", "Tasker / Locale plugin", "Runs an installed Locale-compatible plugin (AutoTools, AutoInput, Join…). Text values accept magic text.",
            listOf(ParamSpec("config", "Plugin", ParamKind.PLUGIN)),
        ) { pluginSummary(it.p("config")) },
    )

    val triggers: List<StepSpec> = baseTriggers + moreTriggers + phoneTriggers + deviceTriggers + launcherTriggers + moreTriggers6 + moreTriggers7
    val constraints: List<StepSpec> = baseConstraints + logicConstraints + phoneConstraints + deviceConstraints + moreConstraints6 + moreConstraints7
    val actions: List<StepSpec> = baseActions + dialogActions + screenActions + phoneActions + deviceActions + moreActions6 + moreActions7 + moreActions8 + logicActions

    /** Logic steps whose block needs a closing partner, added automatically. */
    val blockPartner = mapOf("if" to "end_if", "loop" to "end_loop")

    /** Steps run by the flow engine itself rather than [dev.isaakrichards.tasktemplar.engine.Actions]. */
    val flowTypes = setOf("if", "else_if", "else", "end_if", "loop", "end_loop", "break", "continue", "stop")

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
