/**
 *  WiZ Dual Zone Group (device driver)
 *
 *  The light device for a "WiZ Dual Zone Group" app instance. Every command is
 *  handed to the app, which sends to all member zones at once.
 */

metadata {
    definition(name: "WiZ Dual Zone Group", namespace: "youzer-name", author: "youzer-name") {
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

        // Keep in sync with SCENES in the group app.
        command "setEffectByName", [[name: "Effect*", type: "ENUM", constraints: [
                "Alarm", "Bedtime", "Candlelight", "Christmas", "Club", "Cool White", "Cozy",
                "Daylight", "Deep Dive", "Dim-to-Warm", "Diwali", "Fall", "Fireplace", "Focus",
                "Forest", "Golden White", "Halloween", "Jungle", "Mojito", "Night Light", "Ocean",
                "Party", "Pastel Colors", "Plant Growth", "Pulse", "Relax", "Romance", "Snowy Sky",
                "Spring", "Steampunk", "Summer", "Sunset", "True Colors", "TV Time", "Wake-up",
                "Warm White", "White"
        ]]]
        command "setEffectSpeed", [[name: "Speed*", type: "NUMBER", description: "10 (slow) to 200 (fast)"]]
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

// Events from the app (the group's own displayed state).
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

void parse(String description) {}

void on() { parent?.groupOn() }

void off() { parent?.groupOff() }

void setLevel(level, duration = null) { parent?.groupSetLevel(level) }

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
    parent?.groupSetLevel(next)
    if ((dir > 0 && next >= 100) || (dir < 0 && next <= 1)) {
        state.levelDir = 0
        return
    }
    runInMillis(600, "levelStep")
}

void setColor(Map color) {
    if (color == null) return
    def hue = color.hue != null ? color.hue : device.currentValue("hue") ?: 0
    def sat = color.saturation != null ? color.saturation : device.currentValue("saturation") ?: 100
    parent?.groupSetColor(hue, sat, color.level)
}

void setHue(hue) { parent?.groupSetColor(hue, device.currentValue("saturation") ?: 100, null) }

void setSaturation(sat) { parent?.groupSetColor(device.currentValue("hue") ?: 0, sat, null) }

void setColorTemperature(kelvin, level = null, transitionTime = null) {
    parent?.groupSetColorTemperature(kelvin, level)
}

void setEffect(effectNumber) { parent?.groupSetEffect(effectNumber as int) }

void setEffectByName(String name) { parent?.groupSetEffectByName(name) }

void setNextEffect() { parent?.groupStepEffect(1) }

void setPreviousEffect() { parent?.groupStepEffect(-1) }

void setEffectSpeed(speed) { parent?.groupSetEffectSpeed(speed) }

void refresh() { parent?.groupRefresh() }
