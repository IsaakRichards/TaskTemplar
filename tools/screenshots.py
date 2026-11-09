#!/usr/bin/env python3
"""Seeds sample macros into a debug build on an emulator and captures screenshots of each screen."""
import json, os, re, subprocess, time, uuid
import xml.etree.ElementTree as ET

PKG = "dev.isaakrichards.tasktemplar"
OUT = "shots"
os.makedirs(OUT, exist_ok=True)


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def step(t, **p):
    return {"type": t, "params": p}


def cond_step(t, conds, mode="all", **p):
    return {"type": t, "params": p, "conditions": conds, "conditionMode": mode}


def macro(name, cat, triggers, actions, constraints=(), enabled=True):
    return {"id": str(uuid.uuid4()), "name": name, "category": cat, "enabled": enabled, "constraintMode": "all",
            "triggers": list(triggers), "constraints": list(constraints), "actions": list(actions)}


MACROS = [
    macro("Night mode", "Home", [step("time", time="22:30", days="1,2,3,4,5,6,7"), step("time", time="07:00", days="1,2,3,4,5,6,7")], [
        cond_step("if", [step("time_window", **{"from": "22:00", "to": "06:59"})]),
        step("dnd", mode="priority"), step("brightness", percent="10"), step("ringer", mode="vibrate"),
        step("else"),
        step("dnd", mode="off"), step("brightness", percent="60"), step("ringer", mode="normal"),
        step("end_if"),
        step("notify", title="Night mode", text="Switched at {time}", led="no"),
    ]),
    macro("Server alert", "Home", [step("webhook", name="alert", port="8765", token="")],
          [step("notify", title="Jellyfin server", text="{msg}", led="yes"), step("vibrate", ms="300")]),
    macro("Low battery", "Battery", [step("battery_level", direction="below", level="15")],
          [step("notify", title="Battery at {battery}%", text="Plug in soon."), step("speak", text="Battery is at {battery} percent")],
          [step("charging", state="not_charging")]),
    macro("Battery logger", "Battery", [step("interval", minutes="60")],
          [step("write_file", path="battery.csv", text="{date} {time},{battery}", mode="append", newline="yes")]),
    macro("Driving auto-reply", "Car", [step("sms_received", number="", contains="")],
          [step("send_sms", number="{number}", text="Driving right now, I'll text you back soon.")],
          [step("state_bluetooth", state="on")]),
    macro("Car dash music", "Car", [step("bluetooth", state="connected", device="Trailblazer")], [
        step("volume", stream="media", percent="70"), step("media_control", button="play"),
        cond_step("if", [step("call_state", state="idle")]), step("speak", text="Welcome back"), step("end_if"),
    ]),
    macro("Shake torch", "Fun", [step("shake", sensitivity="medium")],
          [step("flashlight", state="toggle"), step("vibrate", ms="150")], [step("screen_state", state="off")]),
    macro("Unlock counter", "Fun", [step("screen", state="unlocked")], [
        step("set_variable", scope="global", name="unlocks", type="integer", op="increment", value="1"),
        cond_step("if", [step("variable", scope="global", name="unlocks", op="ge", value="100")]),
        step("toast", text="100 unlocks today. Put the phone down, my liege."),
        step("end_if"),
    ]),
    macro("Weekly backup", "", [step("time", time="03:00", days="7")], [step("export_macros", path="backup-{date}.json")], enabled=False),
]

VARS = [{"name": "unlocks", "type": "integer", "value": "42"},
        {"name": "home_ip", "type": "string", "value": "192.168.1.50"},
        {"name": "driving", "type": "boolean", "value": "false"}]


def seed():
    for name, data in (("macros.json", MACROS), ("variables.json", VARS)):
        with open(f"/tmp/{name}", "w") as f:
            json.dump(data, f)
        adb("push", f"/tmp/{name}", f"/data/local/tmp/{name}")
        adb("shell", "chmod", "644", f"/data/local/tmp/{name}")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "run-as", PKG, "mkdir", "-p", "files")
    for name in ("macros.json", "variables.json"):
        adb("shell", "run-as", PKG, "cp", f"/data/local/tmp/{name}", f"files/{name}")
    print(adb("shell", "run-as", PKG, "ls", "-l", "files"))


def nodes():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return list(ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node"))
        time.sleep(1)
    return []


def tap(text=None, desc=None, index=0):
    for _ in range(10):
        ms = [n for n in nodes() if (text and n.get("text") == text) or (desc and n.get("content-desc") == desc)]
        if len(ms) > index:
            b = list(map(int, re.findall(r"\d+", ms[index].get("bounds"))))
            adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
            time.sleep(1.5)
            return True
        time.sleep(1)
    print("NOT FOUND", text, desc, index)
    return False


def shot(name):
    time.sleep(2)
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("captured", name)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)


# Clean status bar
adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
for args in (["-e", "command", "enter"], ["-e", "command", "clock", "-e", "hhmm", "0900"],
             ["-e", "command", "battery", "-e", "level", "86", "-e", "plugged", "false"],
             ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4"],
             ["-e", "command", "notifications", "-e", "visible", "false"]):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *args)

adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
seed()
adb("shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity")
time.sleep(6)
shot("01-macros")

tap(desc="Run now", index=0)  # run Night mode so the log has entries
tap(desc="Run now", index=6)
time.sleep(2)

tap(text="Night mode")
shot("02-editor-logic")
tap(desc="Add", index=2)
shot("03-add-action")
back()
for _ in range(4):
    adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
time.sleep(1)
shot("04-editor-actions")
back()

tap(text="Car dash music")
tap(text="Bluetooth device")
shot("05-step-settings")
back()
back()

tap(desc="Run log")
shot("06-run-log")
back()

tap(desc="More")
tap(text="Global variables")
shot("07-variables")
back()

tap(desc="Permissions")
shot("08-permissions")
back()

tap(desc="More")
tap(text="New from template")
shot("09-templates")
back()
print(os.listdir(OUT))
