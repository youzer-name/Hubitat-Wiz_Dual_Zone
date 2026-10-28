/**
 *  WiZ Dual Zone Light (child driver)
 *
 *  One zone (main or ring) of a dual-zone WiZ bulb. Created automatically by
 *  the "WiZ Dual Zone Bulb" parent driver; all bulb communication goes
 *  through the parent.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String NAMESPACE = "youzer-name"

metadata {
    definition(name: "WiZ Dual Zone Light", namespace: "youzer-name", author: "youzer-name") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "LightEffects"
        capability "Refresh"

        attribute "effectSpeed", "number"
        attribute "RGBW", "string"
        attribute "healthStatus", "enum", ["online", "offline", "unknown"]

        // Keep in sync with SCENES in the bulb driver.
        command "setEffectByName", [[name: "Effect*", type: "ENUM", constraints: [
                "Alarm",
                "Bedtime",
                "Candlelight",
                "Christmas",
                "Club",
                "Cool White",
                "Cozy",
                "Daylight",
                "Deep Dive",
                "Dim-to-Warm",
                "Diwali",
                "Fall",
                "Fireplace",
                "Focus",
                "Forest",
                "Golden White",
                "Halloween",
                "Jungle",
                "Mojito",
                "Night Light",
                "Ocean",
                "Party",
                "Pastel Colors",
                "Plant Growth",
                "Pulse",
                "Relax",
                "Romance",
                "Snowy Sky",
                "Spring",
                "Steampunk",
                "Summer",
                "Sunset",
                "True Colors",
                "TV Time",
                "Wake-up",
                "Warm White",
                "White"
        ]]]
        command "setEffectSpeed", [[name: "Speed*", type: "NUMBER", description: "10 (slow) to 200 (fast)"]]
        // Used by the WiZ Dual Zone Group app (apps can only call declared commands).
        command "applyGroupState", [[name: "Events JSON*", type: "STRING", description: "Internal - used by WiZ groups"]]
        command "setRGBW", [
                [name: "Red*", type: "NUMBER", description: "0-255"],
                [name: "Green*", type: "NUMBER", description: "0-255"],
                [name: "Blue*", type: "NUMBER", description: "0-255"],
                [name: "Warm white*", type: "NUMBER", description: "0-255"]
        ]
    }

    preferences {
        input name: "levelStep", type: "number", title: "Brightness step for start/stop level change (%)",
              range: "1..50", defaultValue: 10
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() {
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "effectName", value: "none")
}

void updated() {}

private int zone() { (getDataValue("zone") ?: "1") as int }

// Events from the parent bulb device or a WiZ group. Unchanged values are
// skipped to keep hub work (and database writes) down.
void parse(List<Map> events) {
    events.each { Map e ->
        if ("${device.currentValue(e.name)}" == "${e.value}") return
        if (e.descriptionText == null && e.name in ["switch", "level", "colorTemperature", "colorName", "effectName", "colorMode"]) {
            e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
        }
        sendEvent(e)
        if (txtEnable && e.descriptionText) log.info e.descriptionText
    }
}

// Confirmed state from a WiZ Dual Zone Group (JSON list of events).
void applyGroupState(String json) {
    def evts = new JsonSlurper().parseText(json)
    if (evts instanceof List) parse(evts as List<Map>)
}

void parse(String description) {
    log.warn "${device.displayName}: unexpected parse(String): ${description}"
}

// Switch
void on() { parent?.zoneOn(zone()) }

void off() { parent?.zoneOff(zone()) }

// SwitchLevel (duration not supported by WiZ, ignored)
void setLevel(level, duration = null) { parent?.zoneSetLevel(zone(), level) }

// ChangeLevel
void startLevelChange(String direction) {
    state.levelDir = direction == "up" ? 1 : -1
    levelStep()
}

void stopLevelChange() {
    state.levelDir = 0
    unschedule("levelStep")
}

void levelStep() {
    int dir = (state.levelDir ?: 0) as int
    if (dir == 0) return
    int step = (levelStep ?: 10) as int
    int cur = (device.currentValue("level") ?: 50) as int
    if (device.currentValue("switch") != "on") cur = 0
    int next = Math.max(1, Math.min(100, cur + dir * step))
    parent?.zoneSetLevel(zone(), next)
    // Assume it lands so the next step builds on it; the parent reads back real state.
    sendEvent(name: "level", value: next, unit: "%")
    if ((dir > 0 && next >= 100) || (dir < 0 && next <= 1)) {
        state.levelDir = 0
        return
    }
    runInMillis(600, "levelStep")
}

// ColorControl
void setColor(Map color) {
    if (color == null) return
    def hue = color.hue != null ? color.hue : device.currentValue("hue") ?: 0
    def sat = color.saturation != null ? color.saturation : device.currentValue("saturation") ?: 100
    parent?.zoneSetColor(zone(), hue, sat, color.level)
}

void setHue(hue) {
    parent?.zoneSetColor(zone(), hue, device.currentValue("saturation") ?: 100, null)
}

void setSaturation(sat) {
    parent?.zoneSetColor(zone(), device.currentValue("hue") ?: 0, sat, null)
}

void setRGBW(r, g, b, w) {
    parent?.zoneSetRGBW(zone(), r as int, g as int, b as int, w as int)
}

// ColorTemperature (transition time not supported by WiZ, ignored)
void setColorTemperature(kelvin, level = null, transitionTime = null) {
    parent?.zoneSetColorTemperature(zone(), kelvin, level)
}

// LightEffects
void setEffect(effectNumber) {
    parent?.zoneSetEffect(zone(), effectNumber as int)
}

void setEffectByName(String name) {
    Map<Integer, String> fx = parent?.effectMap() ?: [:]
    def match = fx.find { k, v -> v.equalsIgnoreCase(name?.trim()) }
    if (!match) {
        log.warn "${device.displayName}: no effect named '${name}'. Choices: ${fx.values().join(', ')}"
        return
    }
    setEffect(match.key)
}

void setNextEffect() { stepEffect(1) }

void setPreviousEffect() { stepEffect(-1) }

private void stepEffect(int dir) {
    Map<Integer, String> fx = parent?.effectMap() ?: [:]
    List<Integer> ids = fx.keySet().toList()
    if (!ids) return
    String cur = device.currentValue("effectName")
    Integer curId = fx.find { k, v -> v == cur }?.key
    int idx = curId == null ? (dir > 0 ? 0 : ids.size() - 1) : (ids.indexOf(curId) + dir + ids.size()) % ids.size()
    setEffect(ids[idx])
}

void setEffectSpeed(speed) {
    parent?.zoneSetEffectSpeed(zone(), speed)
}

// Refresh
void refresh() { parent?.zoneRefresh(zone()) }
