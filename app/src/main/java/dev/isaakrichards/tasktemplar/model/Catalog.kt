package dev.isaakrichards.tasktemplar.model

enum class StepKind(val title: String) {
    TRIGGER("Trigger"),
    CONSTRAINT("Constraint"),
    ACTION("Action"),
}

enum class ParamKind { TEXT, NUMBER, TIME, DAYS, CHOICE, APP, MACRO }

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
            "set_variable", A, "Variables", "Set variable", "Creates or changes a variable. Number values accept math like {v=count} * 2 + 1.",
            listOf(
                scopeChoice,
                ParamSpec("name", "Variable name", ParamKind.TEXT),
                ParamSpec(
                    "type", "Type", ParamKind.CHOICE, "integer",
                    listOf("integer" to "Integer", "decimal" to "Decimal", "boolean" to "True / false", "string" to "Text"),
                ),
                ParamSpec(
                    "op", "Operation", ParamKind.CHOICE, "set",
                    listOf("set" to "Set to value", "increment" to "Add value (default 1)", "decrement" to "Subtract value (default 1)", "toggle" to "Toggle true/false", "append" to "Append text"),
                ),
                ParamSpec("value", "Value", ParamKind.TEXT, optional = true, hint = "Magic text and math allowed"),
            ),
        ) { s ->
            when (s.p("op", "set")) {
                "increment" -> "${varRef(s)} += ${s.p("value", "1")}"
                "decrement" -> "${varRef(s)} -= ${s.p("value", "1")}"
                "toggle" -> "Toggle ${varRef(s)}"
                "append" -> "${varRef(s)} += \"${s.p("value")}\""
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
            ),
        ) { "Run “${macroName(it.p("macro"))}”" },
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

    val triggers: List<StepSpec> = baseTriggers + moreTriggers
    val constraints: List<StepSpec> = baseConstraints + logicConstraints
    val actions: List<StepSpec> = baseActions + screenActions + logicActions

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
