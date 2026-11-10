package dev.isaakrichards.tasktemplar.importers

import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportersTest {

    private val macroDroidExport = """
    {
      "macroList": [
        {
          "m_GUID": -123456789,
          "m_name": "Night silence",
          "m_category": "Home",
          "m_enabled": true,
          "m_triggerList": [
            { "m_classType": "TimerTrigger", "m_hour": 22, "m_minute": 30,
              "m_daysOfWeek": [true, true, true, true, true, false, false], "m_useAlarm": false },
            { "m_classType": "BatteryLevelTrigger", "m_batteryLevel": 15, "m_decreasesTo": true, "m_option": 0 }
          ],
          "m_constraintList": [
            { "m_classType": "ExternalPowerConstraint", "m_externalPower": false },
            { "m_classType": "TimeOfDayConstraint", "m_startHour": 22, "m_startMinute": 0, "m_endHour": 7, "m_endMinute": 0 }
          ],
          "m_actionList": [
            { "m_classType": "IfConditionAction", "m_isOrCondition": false, "m_constraintList": [
                { "m_classType": "HeadphonesConnectionConstraint", "m_connected": true } ] },
            { "m_classType": "SpeakTextAction", "m_textToSay": "Battery is [battery] percent, [v=owner]" },
            { "m_classType": "ElseAction" },
            { "m_classType": "NotificationAction", "m_notificationSubject": "Low", "m_notificationText": "SMS from [sms_number]" },
            { "m_classType": "EndIfAction" },
            { "m_classType": "CameraFlashLightAction", "m_state": 2 },
            { "m_classType": "PauseAction", "m_delayInSeconds": 2, "m_delayInMilliSeconds": 500 },
            { "m_classType": "SetVariableAction", "m_variable": { "m_name": "count", "m_type": 1 }, "m_intValueIncrement": true },
            { "m_classType": "ForceMacroRunAction", "m_macroName": "Helper" },
            { "m_classType": "UIInteractionAction", "m_action": 0 }
          ]
        },
        {
          "m_name": "Helper",
          "m_triggerList": [ { "m_classType": "ShakeDeviceTrigger" } ],
          "m_constraintList": [],
          "m_actionList": [ { "m_classType": "ToastAction", "m_messageText": "Hi" } ]
        }
      ]
    }
    """.trimIndent()

    private val taskerExport = """
    <TaskerData sr="" dvi="1" tv="6.3.13">
      <Profile sr="prof3" ve="2">
        <id>3</id><mid0>4</mid0><mid1>5</mid1><nme>Charging at night</nme>
        <Time sr="con0"><fh>22</fh><fm>0</fm><th>6</th><tm>30</tm></Time>
        <Day sr="con1"><wday0>2</wday0><wday1>3</wday1></Day>
        <State sr="con2" ve="2"><code>10</code><Int sr="arg0" val="0"/></State>
      </Profile>
      <Profile sr="prof7" ve="2">
        <id>7</id><mid0>8</mid0>
        <Event sr="con0" ve="2"><code>7</code><pri>0</pri><Int sr="arg0" val="0"/><Str sr="arg1" ve="3">*555*</Str><Str sr="arg2" ve="3">*where*</Str></Event>
      </Profile>
      <Task sr="task4">
        <id>4</id><nme>Night on</nme>
        <Action sr="act0" ve="7"><code>548</code><Str sr="arg0" ve="3">Battery %BATT</Str><Int sr="arg1" val="0"/></Action>
        <Action sr="act1" ve="7"><code>547</code><Str sr="arg0" ve="3">%count</Str><Str sr="arg1" ve="3">%count + 1</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="1"/></Action>
        <Action sr="act2" ve="7"><code>37</code><ConditionList sr="if"><bool0>Or</bool0>
          <Condition sr="c0" ve="3"><lhs>%count</lhs><op>7</op><rhs>3</rhs></Condition>
          <Condition sr="c1" ve="3"><lhs>%Mode</lhs><op>0</op><rhs>night</rhs></Condition></ConditionList></Action>
        <Action sr="act3" ve="7"><code>511</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3"/></Action>
        <Action sr="act4" ve="7"><code>43</code></Action>
        <Action sr="act5" ve="7"><code>30</code><Int sr="arg0" val="500"/><Int sr="arg1" val="1"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/></Action>
        <Action sr="act6" ve="7"><code>38</code></Action>
        <Action sr="act7" ve="7"><code>20</code><App sr="arg0"><appClass>c</appClass><appPkg>com.spotify.music</appPkg><label>Spotify</label></App></Action>
        <Action sr="act8" ve="7"><code>559</code><Str sr="arg0" ve="3">Hi %name</Str>
          <ConditionList sr="if"><Condition sr="c0" ve="3"><lhs>%name</lhs><op>12</op><rhs></rhs></Condition></ConditionList></Action>
        <Action sr="act9" ve="7"><code>130</code><Str sr="arg0" ve="3">Helper task</Str></Action>
        <Action sr="act10" ve="7"><code>9999</code></Action>
      </Task>
      <Task sr="task5"><id>5</id><nme>Night off</nme>
        <Action sr="act0" ve="7"><code>425</code><Int sr="arg0" val="1"/></Action></Task>
      <Task sr="task8"><id>8</id>
        <Action sr="act0" ve="7"><code>41</code><Str sr="arg0" ve="3">%SMSRF</Str><Str sr="arg1" ve="3">Driving</Str></Action></Task>
      <Task sr="task9"><id>9</id><nme>Helper task</nme>
        <Action sr="act0" ve="7"><code>39</code><Str sr="arg0" ve="3">%item</Str><Str sr="arg1" ve="3">a,b,c</Str></Action>
        <Action sr="act1" ve="7"><code>548</code><Str sr="arg0" ve="3">%item</Str></Action>
        <Action sr="act2" ve="7"><code>40</code></Action>
        <Action sr="act3" ve="7"><code>1452528931</code><label>Lamp ON</label>
          <Bundle sr="arg0"><Vals sr="val">
            <com.twofortyfouram.locale.intent.extra.BLURB>Device: Lamp</com.twofortyfouram.locale.intent.extra.BLURB>
            <com.twofortyfouram.locale.intent.extra.BLURB-type>java.lang.String</com.twofortyfouram.locale.intent.extra.BLURB-type>
            <com.yeelight.ACTION>ON</com.yeelight.ACTION><com.yeelight.ACTION-type>java.lang.String</com.yeelight.ACTION-type>
            <com.yeelight.LEVEL>80</com.yeelight.LEVEL><com.yeelight.LEVEL-type>java.lang.Integer</com.yeelight.LEVEL-type>
          </Vals></Bundle>
          <Str sr="arg1" ve="3">com.yeelight.tasker</Str><Str sr="arg2" ve="3">com.yeelight.tasker.EditActivity</Str><Int sr="arg3" val="0"/></Action>
      </Task>
    </TaskerData>
    """.trimIndent()

    private fun allTypesKnown(m: Macro) {
        fun check(s: Step) {
            assertTrue("unknown step type ${s.type} in ${m.name}", Catalog.spec(s.type) != null)
            s.conditions.forEach { check(it) }
        }
        (m.triggers + m.constraints + m.actions).forEach { check(it) }
    }

    private fun balanced(m: Macro) {
        var ifs = 0
        var loops = 0
        m.actions.forEach {
            when (it.type) {
                "if" -> ifs++
                "end_if" -> ifs--
                "loop" -> loops++
                "end_loop" -> loops--
            }
        }
        assertEquals("If/End If balance in ${m.name}", 0, ifs)
        assertEquals("Loop/End Loop balance in ${m.name}", 0, loops)
    }

    @Test fun macroDroidDetectsAndConverts() {
        assertTrue(MacroDroidImporter.looksLike(macroDroidExport))
        assertFalse(TaskerImporter.looksLike(macroDroidExport))
        val r = MacroDroidImporter.parse(macroDroidExport)
        assertEquals(2, r.macros.size)
        val m = r.macros[0]
        assertEquals("Night silence", m.name)
        assertEquals("Home", m.category)
        assertFalse("imported macros start disabled", m.enabled)
        r.macros.forEach { allTypesKnown(it); balanced(it) }

        val time = m.triggers[0]
        assertEquals("time", time.type)
        assertEquals("22:30", time.params["time"])
        assertEquals("1,2,3,4,5", time.params["days"])
        assertEquals("battery_level", m.triggers[1].type)
        assertEquals("below", m.triggers[1].params["direction"])

        assertEquals(listOf("charging", "time_window"), m.constraints.map { it.type })
        assertEquals("not_charging", m.constraints[0].params["state"])
        assertEquals("07:00", m.constraints[1].params["to"])

        val types = m.actions.map { it.type }
        assertEquals("if", types[0])
        assertEquals("state_headphones", m.actions[0].conditions.single().type)
        assertEquals("Battery is {battery} percent, {v=owner}", m.actions[1].params["text"])
        assertEquals("SMS from {number}", m.actions[3].params["text"])
        assertEquals("toggle", m.actions[5].params["state"])
        assertEquals("2.5", m.actions[6].params["seconds"])
        assertEquals("increment", m.actions[7].params["op"])
        assertEquals("name:Helper", m.actions[8].params["macro"])
        assertEquals("log", m.actions[9].type)
        assertTrue(r.notes.any { "UIInteractionAction" in it })
    }

    @Test fun macroRefsResolveByName() {
        val r = MacroDroidImporter.parse(macroDroidExport)
        val notes = r.notes.toMutableList()
        val resolved = ImportCommon.resolve(r.macros, emptyList(), notes)
        val helperId = resolved.first { it.name == "Helper" }.id
        assertEquals(helperId, resolved[0].actions.first { it.type == "run_macro" }.params["macro"])
    }

    @Test fun singleMacroFileWorks() {
        val single = """{"m_name":"Solo","m_triggerList":[{"m_classType":"BootTrigger"}],"m_actionList":[{"m_classType":"LaunchHomeScreenAction"}]}"""
        val r = MacroDroidImporter.parse(single)
        assertEquals("boot", r.macros.single().triggers.single().type)
        assertEquals("launch_home", r.macros.single().actions.single().type)
    }

    @Test fun taskerProfilesBecomeMacros() {
        assertTrue(TaskerImporter.looksLike(taskerExport))
        val r = TaskerImporter.parse(taskerExport)
        val names = r.macros.map { it.name }
        assertEquals(listOf("Charging at night", "Charging at night (exit)", "Received Text profile", "Helper task").size, r.macros.size)
        assertTrue("Charging at night" in names)
        assertTrue("Charging at night (exit)" in names)
        assertTrue("Helper task" in names)
        r.macros.forEach { allTypesKnown(it); balanced(it); assertFalse(it.enabled) }

        val night = r.macros.first { it.name == "Charging at night" }
        assertEquals("time", night.triggers.single().type)
        assertEquals("22:00", night.triggers.single().params["time"])
        assertEquals("1,2", night.triggers.single().params["days"]) // Tasker Monday(2), Tuesday(3)
        assertEquals("charging", night.constraints.single().type)

        val exit = r.macros.first { it.name == "Charging at night (exit)" }
        assertEquals("06:30", exit.triggers.single().params["time"])
        assertEquals("wifi_set", exit.actions.single().type)
        assertEquals("on", exit.actions.single().params["state"])

        val a = night.actions
        assertEquals("toast", a[0].type)
        assertEquals("Battery {battery}", a[0].params["text"])
        assertEquals("set_variable", a[1].type)
        assertEquals("local", a[1].params["scope"])
        assertEquals("{lv=count} + 1", a[1].params["value"])
        assertEquals("if", a[2].type)
        assertEquals("any", a[2].conditionMode)
        assertEquals(listOf("gt", "eq"), a[2].conditions.map { it.params["op"] })
        assertEquals("global", a[2].conditions[1].params["scope"])
        assertEquals("flashlight", a[3].type)
        assertEquals("else", a[4].type)
        assertEquals("1.5", a[5].params["seconds"])
        assertEquals("end_if", a[6].type)
        assertEquals("com.spotify.music", a[7].params["app"])
        // Conditional "Say" is wrapped in If / End If
        assertEquals(listOf("if", "speak", "end_if"), a.subList(8, 11).map { it.type })
        assertEquals("not_empty", a[8].conditions.single().params["op"])
        assertEquals("name:Helper task", a[11].params["macro"])
        assertEquals("log", a.last().type)

        val sms = r.macros.first { it.triggers.firstOrNull()?.type == "sms_received" }
        assertEquals("555", sms.triggers.single().params["number"])
        assertEquals("where", sms.triggers.single().params["contains"])
        assertEquals("{number}", sms.actions.single().params["number"])
    }

    @Test fun taskerForLoopAndPlugin() {
        val r = TaskerImporter.parse(taskerExport)
        val helper = r.macros.first { it.name == "Helper task" }
        val types = helper.actions.map { it.type }
        assertEquals(listOf("list_op", "loop", "set_variable", "set_variable", "toast", "end_loop", "locale_plugin"), types)
        assertEquals("{lv=item}", helper.actions[4].params["text"])
        val cfg = org.json.JSONObject(helper.actions.last().params["config"]!!)
        assertEquals("com.yeelight.tasker", cfg.getString("pkg"))
        assertEquals("Device: Lamp", cfg.getString("blurb"))
        val bundle = cfg.getJSONObject("bundle")
        assertEquals("ON", bundle.getJSONObject("com.yeelight.ACTION").getString("v"))
        assertEquals(80, bundle.getJSONObject("com.yeelight.LEVEL").optInt("v"))
    }

    @Test fun taskerInvertedStateAndUnknownsAreReported() {
        val xml = """
        <TaskerData sr="" dvi="1" tv="6.3.13">
          <Profile sr="prof1" ve="2"><id>1</id><mid0>2</mid0><nme>Unplugged</nme>
            <State sr="con0" ve="2"><code>10</code><pin>true</pin><Int sr="arg0" val="0"/></State>
            <Event sr="con1" ve="2"><code>4242</code></Event>
          </Profile>
          <Task sr="task2"><id>2</id><Action sr="act0" ve="7"><code>548</code><Str sr="arg0" ve="3">off charger</Str></Action></Task>
        </TaskerData>
        """.trimIndent()
        val r = TaskerImporter.parse(xml)
        val m = r.macros.single()
        assertEquals("disconnected", m.triggers.single().params["state"])
        assertTrue(r.notes.any { "4242" in it })
    }

    @Test fun magicTextConversions() {
        assertEquals("{v=Home} {lv=x} {battery} {lv=arr[0]} {v=LIST[#]}", TaskerImporter.text("%Home %x %BATT %arr(1) %LIST(#)"))
        assertEquals("{v=a} {lv=b} {text} [unknown_code]", MacroDroidImporter.text("[v=a] [lv=b] [sms_message] [unknown_code]"))
    }

    @Test fun randomVariablesImport() {
        val md = MacroDroidImporter.parse(
            """
            { "macroList": [ { "m_name": "Dice", "m_triggerList": [ { "m_classType": "ShakeDeviceTrigger" } ], "m_constraintList": [],
              "m_actionList": [ { "m_classType": "SetVariableAction", "m_variable": { "m_name": "roll", "m_type": 1 },
                "m_intRandom": true, "m_intRandomMin": 1, "m_intRandomMax": 6 } ] } ] }
            """.trimIndent(),
        )
        val a = md.macros.single().actions.single()
        assertEquals("random", a.params["op"])
        assertEquals("1", a.params["min"])
        assertEquals("6", a.params["max"])

        val tk = TaskerImporter.parse(
            """
            <TaskerData sr="" dvi="1" tv="6.3.13">
              <Task sr="task9"><id>9</id><nme>Dice</nme>
                <Action sr="act0" ve="7"><code>545</code><Str sr="arg0" ve="3">%roll</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="6"/></Action>
              </Task>
            </TaskerData>
            """.trimIndent(),
        )
        val t = tk.macros.single().actions.single()
        assertEquals("set_variable", t.type)
        assertEquals(listOf("random", "1", "6", "roll"), listOf(t.params["op"], t.params["min"], t.params["max"], t.params["name"]))
    }

    @Test fun randomRangeStaysInBounds() {
        val rng = kotlin.random.Random(42)
        val ints = (1..2000).map { dev.isaakrichards.tasktemplar.engine.RandomRange.pick(1.0, 6.0, "integer", rng).toInt() }
        assertEquals((1..6).toSet(), ints.toSet())
        repeat(2000) {
            val d = dev.isaakrichards.tasktemplar.engine.RandomRange.pick(-2.5, 2.5, "decimal", rng).toDouble()
            assertTrue(d in -2.5..2.5)
        }
        assertEquals("7", dev.isaakrichards.tasktemplar.engine.RandomRange.pick(7.0, 7.0, "integer", rng))
    }

    @Test fun variableWaitImportsAndConverts() {
        val tk = TaskerImporter.parse(
            """
            <TaskerData sr="" dvi="1" tv="6.3.13">
              <Task sr="task9"><id>9</id><nme>Pause</nme>
                <Action sr="act0" ve="7"><code>30</code><Int sr="arg0" val="0"/><Int sr="arg1" val="30"/><Int sr="arg2"><var>%delay</var></Int><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/></Action>
              </Task>
            </TaskerData>
            """.trimIndent(),
        )
        assertEquals("{lv=delay} * 60 + 30", tk.macros.single().actions.single().params["seconds"])

        val w = dev.isaakrichards.tasktemplar.engine.WaitTime
        assertEquals(90.0, w.seconds(1.5, "min"), 1e-9)
        assertEquals(0.25, w.seconds(250.0, "ms"), 1e-9)
        assertEquals(7200.0, w.seconds(2.0, "h"), 1e-9)
        assertEquals(5.0, w.seconds(5.0, ""), 1e-9)
        assertEquals(0.0, w.seconds(-3.0, "s"), 1e-9)
        assertEquals(w.MAX_SECONDS, w.seconds(48.0, "h"), 1e-9)
    }
}
