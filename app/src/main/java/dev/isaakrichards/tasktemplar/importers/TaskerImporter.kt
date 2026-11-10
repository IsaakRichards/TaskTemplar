package dev.isaakrichards.tasktemplar.importers

import dev.isaakrichards.tasktemplar.importers.Imp.cond
import dev.isaakrichards.tasktemplar.importers.Imp.s
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.Step
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Converts Tasker exports (profiles .prf.xml, tasks .tsk.xml, projects .prj.xml or a full backup)
 * into TaskTemplar macros. A profile becomes a macro (its exit task becomes a second macro);
 * tasks no profile uses become manual macros.
 */
object TaskerImporter {

    private const val APP = "Tasker"

    fun looksLike(text: String) = text.trimStart().startsWith("<") && "TaskerData" in text

    // ---------- small DOM helpers ----------

    private fun Element.kids(tag: String? = null): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { tag == null || it.tagName == tag }

    private fun Element.kid(tag: String): Element? = kids(tag).firstOrNull()
    private fun Element.txt(tag: String): String = kid(tag)?.textContent?.trim() ?: ""

    /** An action's / context's arguments by slot: "arg0" → value (Str text, Int value or variable, App package). */
    private class Args(el: Element) {
        val str = HashMap<Int, String>()
        val int = HashMap<Int, String>()
        val app = HashMap<Int, Pair<String, String>>()
        val bundle = HashMap<Int, Element>()

        init {
            for (n in (0 until el.childNodes.length).map { el.childNodes.item(it) }) {
                if (n !is Element) continue
                val slot = n.getAttribute("sr").removePrefix("arg").toIntOrNull() ?: continue
                when (n.tagName) {
                    "Str" -> str[slot] = n.textContent ?: ""
                    "Int" -> int[slot] = n.getAttribute("val").ifBlank {
                        (0 until n.childNodes.length).map { n.childNodes.item(it) }.filterIsInstance<Element>()
                            .firstOrNull { it.tagName == "var" }?.textContent ?: ""
                    }
                    "App" -> {
                        val kids = (0 until n.childNodes.length).map { n.childNodes.item(it) }.filterIsInstance<Element>()
                        app[slot] = (kids.firstOrNull { it.tagName == "appPkg" }?.textContent ?: "") to
                            (kids.firstOrNull { it.tagName == "appClass" }?.textContent ?: "")
                    }
                    "Bundle" -> bundle[slot] = n
                }
            }
        }

        fun s(i: Int) = str[i] ?: ""
        fun i(i: Int, def: Int = 0) = int[i]?.toIntOrNull() ?: def
        fun iRaw(i: Int) = int[i] ?: ""
    }

    // ---------- %variables → magic text ----------

    private val builtins = mapOf(
        "BATT" to "{battery}", "TIME" to "{time}", "DATE" to "{date}", "SMSRF" to "{number}", "SMSRN" to "{number}",
        "SMSRB" to "{text}", "CNUM" to "{number}", "CONUM" to "{number}", "CNAME" to "{number}", "NTITLE" to "{title}",
        "WIFII" to "{ssid}", "TIMES" to "{time}", "MUSIC" to "", "CLIP" to "{clip}",
    )
    private val varRx = Regex("""%([A-Za-z_][A-Za-z0-9_]*)(\((\d+|#)\))?""")

    /** Tasker: names with any uppercase letter are global, all-lowercase are local. */
    private fun isGlobal(name: String) = name.any { it.isUpperCase() }

    fun text(s: String): String = varRx.replace(s) { m ->
        val name = m.groupValues[1]
        val idx = m.groupValues[3]
        builtins[name]?.let { return@replace it }
        val scope = if (isGlobal(name)) "v" else "lv"
        when {
            idx.isEmpty() -> "{$scope=$name}"
            idx == "#" -> "{$scope=$name[#]}"
            else -> "{$scope=$name[${(idx.toInt() - 1).coerceAtLeast(0)}]}"
        }
    }

    private fun varName(s: String) = s.trim().removePrefix("%")
    private fun scopeOf(s: String) = if (isGlobal(varName(s))) "global" else "local"

    // ---------- entry point ----------

    fun parse(xml: String): ImportResult {
        val notes = mutableListOf<String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement
        val tasks = root.kids("Task").associateBy { it.txt("id") }
        val profiles = root.kids("Profile")
        val macros = mutableListOf<Macro>()
        val usedTasks = mutableSetOf<String>()

        fun taskName(id: String) = tasks[id]?.txt("nme")?.ifBlank { null } ?: "Task $id"

        for (p in profiles) {
            val entryId = p.txt("mid0")
            val exitId = p.txt("mid1")
            usedTasks += listOf(entryId, exitId).filter { it.isNotBlank() }
            val name = p.txt("nme").ifBlank { tasks[entryId]?.txt("nme") ?: "Tasker profile ${p.txt("id")}" }
            val local = mutableListOf<String>()
            val ctx = contexts(p, local)
            if (tasks[entryId] != null) {
                macros += macro(name, ctx.entry, ctx.constraints, convertTask(tasks.getValue(entryId), local))
            }
            if (tasks[exitId] != null) {
                if (ctx.exit.isEmpty()) local += "exit task “${taskName(exitId)}” has no matching end trigger here; imported as a manual macro"
                macros += macro("$name (exit)", ctx.exit, ctx.exitConstraints, convertTask(tasks.getValue(exitId), local))
            }
            local.forEach { notes += "“$name”: $it" }
        }

        // Named tasks that no profile runs become manual macros (usable with Run macro / Perform Task).
        for ((id, t) in tasks) {
            if (id in usedTasks || t.txt("nme").isBlank()) continue
            val local = mutableListOf<String>()
            macros += macro(t.txt("nme"), emptyList(), emptyList(), convertTask(t, local))
            local.forEach { notes += "“${t.txt("nme")}”: $it" }
        }
        if (macros.isEmpty()) notes += "No Tasker profiles or named tasks found in that file."
        return ImportResult(APP, macros, notes)
    }

    private fun macro(name: String, triggers: List<Step>, constraints: List<Step>, actions: List<Step>) = Macro(
        id = UUID.randomUUID().toString(), name = name, enabled = false, category = "Imported",
        triggers = triggers, constraints = constraints, actions = actions,
    )

    // ---------- contexts → triggers / constraints ----------

    private class Ctx(val entry: List<Step>, val exit: List<Step>, val constraints: List<Step>, val exitConstraints: List<Step>)

    /** One context as: trigger when it starts, trigger when it ends, and an "is true now" constraint. */
    private class Piece(val start: Step?, val end: Step?, val constraint: Step?)

    private fun contexts(p: Element, notes: MutableList<String>): Ctx {
        val pieces = mutableListOf<Piece>()
        var days: String? = null
        for (c in p.kids()) {
            when (c.tagName) {
                "Time" -> {
                    val fh = c.txt("fh").toIntOrNull()
                    val fm = c.txt("fm").toIntOrNull() ?: 0
                    val th = c.txt("th").toIntOrNull()
                    val tm = c.txt("tm").toIntOrNull() ?: 0
                    val rep = c.txt("rep").toIntOrNull()
                    val repVal = c.txt("repval").toIntOrNull()
                    if (rep != null && repVal != null && repVal > 0) {
                        val minutes = if (rep == 1) repVal * 60 else repVal
                        pieces += Piece(s("interval", "minutes" to minutes.toString()), null, null)
                        notes += "repeating time context became a regular interval (from-time ignored)"
                    } else if (fh != null) {
                        val window = th != null
                        pieces += Piece(
                            s("time", "time" to Imp.hhmm(fh, fm)),
                            if (window) s("time", "time" to Imp.hhmm(th!!, tm)) else null,
                            if (window) s("time_window", "from" to Imp.hhmm(fh, fm), "to" to Imp.hhmm(th!!, tm)) else null,
                        )
                    }
                }
                "Day" -> {
                    val w = c.kids().filter { it.tagName.startsWith("wday") }.mapNotNull { it.textContent.trim().toIntOrNull() }
                    if (w.isNotEmpty()) days = w.map { if (it == 1) 7 else it - 1 }.sorted().joinToString(",")
                    if (c.kids().any { it.tagName.startsWith("mday") }) notes += "day-of-month context not converted"
                }
                "App" -> {
                    val pkgs = c.kids().filter { it.tagName.startsWith("pkg") }.map { it.textContent.trim() }.filter { it.isNotEmpty() }
                    pkgs.forEach { pkg ->
                        pieces += Piece(
                            s("app_launched", "app" to pkg, "state" to "opened"),
                            s("app_launched", "app" to pkg, "state" to "closed"),
                            s("app_foreground", "app" to pkg, "state" to "foreground"),
                        )
                    }
                }
                "Event" -> event(c, notes)?.let { pieces += Piece(it, null, null) }
                "State" -> state(c, notes)?.let { pieces += it }
                "Loc" -> {
                    val lat = (c.txt("lat").ifBlank { c.txt("latitude") }).toDoubleOrNull()
                    val lng = (c.txt("lon").ifBlank { c.txt("lng").ifBlank { c.txt("longitude") } }).toDoubleOrNull()
                    val rad = (c.txt("rad").ifBlank { c.txt("radius") }).toDoubleOrNull()?.toInt() ?: 150
                    val loc = if (lat != null && lng != null) "%.6f,%.6f".format(java.util.Locale.US, lat, lng) else ""
                    if (loc.isEmpty()) notes += "location context had no coordinates; set the place by hand"
                    pieces += Piece(
                        s("geofence", "state" to "enter", "location" to loc, "radius" to rad.toString()),
                        s("geofence", "state" to "exit", "location" to loc, "radius" to rad.toString()),
                        s("location", "state" to "inside", "location" to loc, "radius" to rad.toString()),
                    )
                }
            }
        }

        // Day context: attach to time triggers, otherwise a day-of-week constraint.
        val dayList = days
        fun withDays(st: Step?) = if (st != null && st.type == "time" && dayList != null) st.copy(params = st.params + ("days" to dayList)) else st
        val dayConstraint = if (dayList != null && pieces.none { it.start?.type == "time" }) s("days", "days" to dayList) else null

        if (pieces.isEmpty()) {
            return Ctx(emptyList(), emptyList(), listOfNotNull(dayConstraint), emptyList())
        }
        // Tasker fires when ALL contexts are true: use the first as the trigger and the rest as constraints.
        val first = pieces.first()
        val rest = pieces.drop(1)
        val entry = listOfNotNull(withDays(first.start))
        val exit = listOfNotNull(withDays(first.end))
        val restConstraints = rest.mapNotNull { it.constraint }
        rest.filter { it.constraint == null }.forEach {
            notes += "an extra ${it.start?.type ?: "context"} context can't be a constraint; it was dropped"
        }
        return Ctx(entry, exit, restConstraints + listOfNotNull(dayConstraint), restConstraints + listOfNotNull(dayConstraint))
    }

    private fun event(c: Element, notes: MutableList<String>): Step? {
        val a = Args(c)
        return when (c.txt("code").toIntOrNull()) {
            208 -> s("screen", "state" to "on")
            210 -> s("screen", "state" to "off")
            1000 -> s("screen", "state" to "unlocked")
            411 -> s("boot")
            7 -> s("sms_received", "number" to a.s(1).trim('*'), "contains" to a.s(2).trim('*'))
            2003 -> s("call", "state" to "missed")
            6 -> s("call", "state" to "incoming", "number" to a.s(0).trim('*'))
            2 -> s("call", "state" to "answered")
            4 -> s("call", "state" to "ended")
            461 -> s("notification", "app" to (a.app[0]?.first ?: ""), "contains" to a.s(1).trim('*'))
            450 -> s("app_installed", "state" to "installed")
            451 -> s("app_installed", "state" to "removed")
            453 -> s("app_installed", "state" to "updated")
            599 -> s("intent_received", "action" to a.s(0))
            3001 -> s("shake")
            2076 -> s("nfc")
            205 -> s("battery_level", "direction" to "above", "level" to "100")
            3050 -> s("variable_changed", "name" to varName(a.s(0)))
            2078 -> s("app_launched", "state" to "opened")
            2081 -> s("music", "state" to "playing")
            2080 -> s("bluetooth", "state" to "connected")
            2050 -> s("qs_tile", "slot" to "1").also { notes += "quick-setting event became TaskTemplar tile 1" }
            else -> { notes += "event code ${c.txt("code")} not supported"; null }
        }
    }

    private fun state(c: Element, notes: MutableList<String>): Piece? {
        val a = Args(c)
        val inverted = c.txt("pin") == "true"
        fun flip(st: Step?, key: String, on: String, off: String) =
            st?.let { it.copy(params = it.params + (key to (if (it.params[key] == on) off else on))) }

        val base: Piece = when (c.txt("code").toIntOrNull()) {
            10, 150 -> Piece(s("power", "state" to "connected"), s("power", "state" to "disconnected"), s("charging", "state" to "charging"))
            140 -> {
                val from = a.i(0)
                val to = a.i(1, 100)
                if (from <= 0) {
                    Piece(s("battery_level", "direction" to "below", "level" to to.toString()), s("battery_level", "direction" to "above", "level" to (to + 1).toString()), s("battery", "direction" to "below", "level" to (to + 1).toString()))
                } else {
                    Piece(s("battery_level", "direction" to "above", "level" to from.toString()), s("battery_level", "direction" to "below", "level" to (from - 1).toString()), s("battery", "direction" to "above", "level" to (from - 1).toString()))
                }
            }
            160 -> {
                val ssid = a.s(0).trim('*').substringBefore('/')
                Piece(s("wifi", "state" to "connected", "ssid" to ssid), s("wifi", "state" to "disconnected", "ssid" to ssid), s("wifi_state", "state" to "connected", "ssid" to ssid))
            }
            3 -> Piece(s("bluetooth", "state" to "connected", "device" to a.s(0).trim('*')), s("bluetooth", "state" to "disconnected", "device" to a.s(0).trim('*')), s("state_bluetooth", "state" to "on"))
            2 -> Piece(null, null, s("state_bluetooth", "state" to "on"))
            30 -> Piece(s("headphones", "state" to "plugged"), s("headphones", "state" to "unplugged"), s("state_headphones", "state" to "on"))
            123 -> {
                val on = a.i(0) != 0
                Piece(s("screen", "state" to if (on) "on" else "off"), s("screen", "state" to if (on) "off" else "on"), s("screen_state", "state" to if (on) "on" else "off"))
            }
            100 -> Piece(s("airplane_changed", "state" to "on"), s("airplane_changed", "state" to "off"), s("state_airplane", "state" to "on"))
            120, 122 -> Piece(s("orientation", "state" to "landscape"), s("orientation", "state" to "portrait"), s("state_landscape", "state" to "on"))
            125 -> Piece(s("proximity", "state" to "near"), s("proximity", "state" to "far"), s("proximity_state", "state" to "near"))
            136 -> Piece(s("vpn", "state" to "on"), s("vpn", "state" to "off"), s("state_vpn", "state" to "on"))
            135 -> Piece(s("autosync", "state" to "on"), s("autosync", "state" to "off"), s("state_autosync", "state" to "on"))
            175 -> Piece(s("daydream", "state" to "started"), s("daydream", "state" to "stopped"), null)
            80 -> Piece(s("dock", "state" to "docked"), s("dock", "state" to "undocked"), s("state_docked", "state" to "on"))
            14 -> Piece(null, null, s("state_power_save", "state" to "on"))
            40 -> Piece(s("call", "state" to "answered"), s("call", "state" to "ended"), s("call_state", "state" to "in_call"))
            103 -> Piece(s("light", "direction" to "above", "lux" to a.i(0).toString()), s("light", "direction" to "below", "lux" to a.i(0).toString()), s("light_level", "direction" to "above", "lux" to a.i(0).toString()))
            165 -> {
                val name = varName(a.s(0))
                Piece(s("variable_changed", "name" to name), null, s("variable", "scope" to scopeOf(a.s(0)), "name" to name, "op" to "eq", "value" to text(a.s(2))))
                    .also { notes += "variable-value context became “variable changed” + a constraint; check the comparison" }
            }
            105 -> Piece(s("media_button"), null, null)
            else -> { notes += "state code ${c.txt("code")} not supported"; return null }
        }
        if (!inverted) return base
        // Inverted state: swap start/end and negate the constraint where we can.
        val neg = base.constraint?.let { con ->
            when {
                con.type.startsWith("state_") -> flip(con, "state", "on", "off")
                con.type == "charging" -> flip(con, "state", "charging", "not_charging")
                con.type == "screen_state" -> flip(con, "state", "on", "off")
                con.type == "wifi_state" -> flip(con, "state", "connected", "disconnected")
                con.type == "call_state" -> con.copy(params = con.params + ("state" to "idle"))
                else -> Step("logic_group", mapOf("op" to "none"), listOf(con))
            }
        }
        return Piece(base.end, base.start, neg)
    }

    // ---------- tasks → actions ----------

    private fun convertTask(task: Element, notes: MutableList<String>): List<Step> {
        val out = mutableListOf<Step>()
        val actions = task.kids("Action").sortedBy { it.getAttribute("sr").removePrefix("act").toIntOrNull() ?: 0 }
        for (act in actions) {
            if (act.txt("on") == "false") { notes += "a disabled action was skipped"; continue }
            val code = act.txt("code").toIntOrNull() ?: continue
            val conds = act.kid("ConditionList")
            val converted = action(code, act, notes)
            // A condition on an ordinary action means "only do this if…": wrap it in If / End If.
            if (conds != null && code !in setOf(37, 43)) {
                val (cs, any) = conditions(conds, notes)
                out += Imp.guarded(cs, any, converted)
            } else {
                out += converted
            }
        }
        return out
    }

    private fun conditions(list: Element, notes: MutableList<String>): Pair<List<Step>, Boolean> {
        val any = list.kids().any { it.tagName.startsWith("bool") && it.textContent.trim().equals("Or", true) }
        val steps = list.kids("Condition").mapNotNull { c ->
            val lhs = c.txt("lhs")
            val rhs = c.txt("rhs")
            val op = c.txt("op").toIntOrNull() ?: 0
            val isVar = lhs.startsWith("%")
            val name = varName(lhs)
            fun v(o: String, value: String = text(rhs)) =
                if (isVar) s("variable", "scope" to scopeOf(lhs), "name" to name, "op" to o, "value" to value)
                else null.also { notes += "condition on “$lhs” (not a variable) not converted" }
            when (op) {
                0, 8 -> v("eq")
                1, 9 -> v("ne")
                2 -> if (rhs.startsWith("*") && rhs.endsWith("*") && rhs.length > 2) v("contains", text(rhs.trim('*'))) else v("eq", text(rhs.trim('*')))
                3 -> when {
                    rhs.startsWith("%") || rhs.startsWith("\\%") -> v("not_empty", "")
                    rhs.startsWith("*") && rhs.endsWith("*") -> v("not_contains", text(rhs.trim('*')))
                    else -> v("ne")
                }
                6 -> v("lt")
                7 -> v("gt")
                12 -> v("not_empty", "")
                13 -> v("empty", "")
                else -> { notes += "condition operator $op on $lhs (regex / even / odd) not converted"; null }
            }
        }
        return steps to any
    }

    private fun onOff(v: Int) = when (v) {
        0 -> "off"
        1 -> "on"
        else -> "toggle"
    }

    private fun action(code: Int, act: Element, notes: MutableList<String>): List<Step> {
        val a = Args(act)
        fun one(step: Step) = listOf(step)
        fun unsupported(what: String): List<Step> { notes += "action $what not converted"; return listOf(Imp.unsupported(APP, what)) }

        return when (code) {
            37 -> { val (c, any) = conditions(act.kid("ConditionList") ?: return one(s("if")), notes); one(cond("if", c, any)) }
            43 -> {
                val cl = act.kid("ConditionList")
                if (cl == null) one(s("else")) else { val (c, any) = conditions(cl, notes); one(cond("else_if", c, any)) }
            }
            38 -> one(s("end_if"))
            39 -> {
                // For %var in items: split into a local array, loop over it, set %var each pass.
                val v = varName(a.s(0)).ifBlank { "item" }
                listOf(
                    s("list_op", "scope" to "local", "name" to "_for_$v", "op" to "split", "value" to text(a.s(1)), "separator" to ","),
                    s("loop", "mode" to "count", "count" to "{lv=_for_$v[#]}"),
                    s("set_variable", "scope" to "local", "name" to "_i_$v", "type" to "integer", "op" to "set", "value" to "{loop_index} - 1"),
                    s("set_variable", "scope" to "local", "name" to v, "type" to "string", "op" to "set", "value" to "{lv=_for_$v[{lv=_i_$v}]}"),
                )
            }
            40 -> one(s("end_loop"))
            137 -> one(s("stop"))
            30 -> {
                val secs = a.i(0) / 1000.0 + a.i(1) + a.i(2) * 60 + a.i(3) * 3600 + a.i(4) * 86400
                one(s("wait", "seconds" to VarFmt.num(secs)))
            }
            547 -> one(
                s(
                    "set_variable", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)),
                    "type" to if (a.i(3) == 1) "decimal" else "string", "op" to "set", "value" to text(a.s(1)),
                ),
            )
            888, 890 -> one(
                s(
                    "set_variable", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)), "type" to "integer",
                    "op" to if (code == 888) "increment" else "decrement", "value" to (a.iRaw(1).ifBlank { "1" }).let { text(it) },
                ),
            )
            545 -> one(
                s(
                    "set_variable", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)), "type" to "integer", "op" to "random",
                    "min" to text(a.iRaw(1).ifBlank { a.s(1) }.ifBlank { "0" }), "max" to text(a.iRaw(2).ifBlank { a.s(2) }.ifBlank { "100" }),
                ),
            )
            549 -> one(s("set_variable", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)), "type" to "string", "op" to "set", "value" to ""))
            590 -> one(s("list_op", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)), "op" to "split", "value" to text(a.s(0)), "separator" to a.s(1).ifEmpty { " " }))
                .also { notes += "Variable Split now fills an array variable; read items with {lv=name[0]}" }
            355 -> one(s("list_op", "scope" to scopeOf(a.s(0)), "name" to varName(a.s(0)), "op" to "add", "value" to text(a.s(2))))
            548 -> one(s("toast", "text" to text(a.s(0))))
            523 -> one(s("notify", "title" to text(a.s(0)), "text" to text(a.s(1))))
            550 -> one(s("dialog_message", "title" to text(a.s(0)), "message" to text(a.s(1))))
            559 -> one(s("speak", "text" to text(a.s(0))))
            61 -> one(s("vibrate", "ms" to a.i(0, 500).toString()))
            62 -> one(s("vibrate", "ms" to "600"))
            171 -> one(s("play_sound", "source" to "notification"))
            192 -> one(s("play_sound", "source" to "ringtone"))
            20 -> one(s("launch_app", "app" to (a.app[0]?.first ?: "")))
            18 -> one(s("kill_app", "app" to (a.app[0]?.first ?: "")))
            25 -> one(s("launch_home"))
            104 -> one(s("open_url", "url" to text(a.s(0))))
            105 -> one(s("clipboard", "text" to text(a.s(0))))
            41 -> one(s("send_sms", "number" to text(a.s(0)), "text" to text(a.s(1))))
            90 -> one(s("make_call", "number" to text(a.s(0))))
            731 -> one(s("answer_call"))
            733 -> one(s("end_call"))
            254 -> one(s("speakerphone", "state" to onOff(a.i(0, 2))))
            425 -> one(s("wifi_set", "state" to onOff(a.i(0, 2))))
            294 -> one(s("bluetooth_set", "state" to onOff(a.i(0, 2))))
            333 -> one(s("airplane_set", "state" to onOff(a.i(0, 2))))
            433 -> one(s("mobile_data_set", "state" to onOff(a.i(0, 2))))
            511 -> one(s("flashlight", "state" to onOff(a.i(0, 2))))
            307 -> one(s("volume", "stream" to "media", "percent" to volumePct(a, 15)))
            304 -> one(s("volume", "stream" to "ring", "percent" to volumePct(a, 7)))
            303 -> one(s("volume", "stream" to "alarm", "percent" to volumePct(a, 7)))
            305 -> one(s("volume", "stream" to "notification", "percent" to volumePct(a, 7)))
            810 -> one(s("brightness", "percent" to (a.i(0, 128) * 100 / 255).toString()))
            812 -> one(s("screen_timeout", "seconds" to (a.i(0) + a.i(1) * 60 + a.i(2) * 3600).coerceAtLeast(5).toString()))
            313 -> one(s("ringer", "mode" to listOf("normal", "vibrate", "silent").getOrElse(a.i(0)) { "normal" }))
            312 -> one(s("dnd", "mode" to "priority")).also { notes += "Do Not Disturb mode set to “priority”; check it" }
            443 -> one(s("media_control", "button" to listOf("next", "pause", "previous", "play_pause", "stop", "play").getOrElse(a.i(0)) { "play_pause" }))
            123 -> one(s("shell", "command" to text(a.s(0)), "timeout" to a.i(1, 30).toString(), "root" to if (a.i(2) == 1) "yes" else "no"))
            130 -> one(s("run_macro", "macro" to Imp.macroRef(a.s(0)), "check" to "ignore", "mode" to "inline"))
            159 -> one(s("macro_enable", "macro" to Imp.macroRef(a.s(0)), "state" to onOff(a.i(1, 2)).let { if (it == "on") "enable" else if (it == "off") "disable" else "toggle" }))
            877 -> one(
                s(
                    "send_intent", "action" to a.s(0), "data" to a.s(3), "package" to a.s(7),
                    "extras" to listOf(a.s(4), a.s(5), a.s(6)).filter { ':' in it }.joinToString("; ") { it.replaceFirst(':', '=') },
                    "target" to when (a.i(9)) {
                        1 -> "broadcast"
                        2 -> "service"
                        else -> "activity"
                    },
                ),
            )
            410 -> one(s("write_file", "path" to a.s(0), "text" to text(a.s(1)), "mode" to if (a.i(2) == 1) "append" else "overwrite", "newline" to "no"))
            417 -> one(s("read_file", "path" to a.s(0), "scope" to scopeOf(a.s(1)), "name" to varName(a.s(1)).ifBlank { "file" }))
            406 -> one(s("file_op", "op" to "delete", "path" to a.s(0)))
            404 -> one(s("file_op", "op" to "copy", "path" to a.s(0), "dest" to a.s(1)))
            400 -> one(s("file_op", "op" to "move", "path" to a.s(0), "dest" to a.s(1)))
            118, 116 -> one(s("http", "method" to if (code == 116) "POST" else "GET", "url" to text(joinUrl(a.s(0), a.s(1))), "body" to text(a.s(2))))
            339 -> {
                val url = a.str.values.firstOrNull { it.startsWith("http") } ?: ""
                one(s("http", "method" to if (a.i(1) == 1) "POST" else "GET", "url" to text(url)))
                    .also { notes += "HTTP Request converted to a basic GET/POST; check headers and body" }
            }
            15, 16, 248 -> one(s("global_action", "button" to "lock"))
            902, 366 -> one(s("get_location"))
            360 -> one(s("input_dialog", "title" to text(a.s(0)), "scope" to "local", "name" to "input"))
                .also { notes += "Input Dialog result is now {lv=input}" }
            378 -> one(s("dialog_options", "title" to text(a.s(0)), "options" to text(a.s(1)).replace('/', ',')))
                .also { notes += "List Dialog result is now {option}" }
            43, 38 -> emptyList()
            else -> plugin(code, act, a, notes) ?: unsupported("code $code")
        }
    }

    private fun volumePct(a: Args, max: Int) = (a.i(0) * 100 / max).coerceIn(0, 100).toString()

    private fun joinUrl(server: String, path: String): String {
        val base = if (server.startsWith("http")) server else "http://$server"
        return if (path.isBlank()) base else base.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** Locale-API plugin actions: bundle in arg0, plugin package in arg1, edit activity in arg2. */
    private fun plugin(code: Int, act: Element, a: Args, notes: MutableList<String>): List<Step>? {
        val bundle = a.bundle[0] ?: return null
        val pkg = a.s(1).ifBlank { return null }
        val vals = bundle.kids("Vals").firstOrNull() ?: return null
        val entries = vals.kids()
        val types = entries.filter { it.tagName.endsWith("-type") }.associate { it.tagName.removeSuffix("-type") to it.textContent.trim() }
        val json = StringBuilder("{")
        var first = true
        var blurb = ""
        for (e in entries) {
            if (e.tagName.endsWith("-type") || e.tagName.startsWith("net.dinglisch.android.tasker")) continue
            val value = e.textContent ?: ""
            if (e.tagName == "com.twofortyfouram.locale.intent.extra.BLURB") blurb = value
            val (t, v) = when (types[e.tagName]) {
                "java.lang.Integer" -> "i" to (value.toIntOrNull()?.toString() ?: continue)
                "java.lang.Long" -> "l" to (value.toLongOrNull()?.toString() ?: continue)
                "java.lang.Boolean" -> "b" to value.trim().lowercase()
                "java.lang.Double", "java.lang.Float" -> "d" to (value.toDoubleOrNull()?.toString() ?: continue)
                else -> "s" to quote(if (value == "<null>") "" else text(value))
            }
            if (!first) json.append(',')
            first = false
            json.append(quote(e.tagName)).append(":{\"t\":\"").append(t).append("\",\"v\":").append(v).append('}')
        }
        json.append('}')
        val label = act.txt("label").ifBlank { pkg.substringAfterLast('.') }
        val config = "{\"pkg\":${quote(pkg)},\"activity\":${quote(a.s(2))},\"label\":${quote(label)},\"blurb\":${quote(blurb)},\"bundle\":$json}"
        notes += "plugin action ($label) carried over; the plugin app must be installed"
        return listOf(s("locale_plugin", "config" to config))
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
