/**
 *  WiZ Dual Zone Bulb (parent driver)
 *
 *  Version 0.3.3
 *  https://github.com/youzer-name/Hubitat-Wiz_Dual_Zone (MIT License)
 *
 *  Talks to one dual-zone WiZ bulb over local UDP (port 38899) and creates two
 *  child lights: Zone 1 (main/center) and Zone 2 (ring/halo).
 *
 *  Protocol notes (from pywizlight 0.6.5 / ha-wiz-dualzone):
 *   - setPilot picks a zone with {"devices": 1|2}  (1-based)
 *   - getPilot picks a zone with {"devices": 0|1}  (0-based)
 *   - setPilot with no "devices" key hits both zones
 *   - The bulb drops packets when it gets several at once, so every message to a
 *     bulb is sent one at a time: we wait for the reply (or give up after
 *     retries) before sending the next one.
 *
 *  Hubitat cannot listen on the bulb's push port (38900), so state is kept in
 *  sync by reading it back after each command plus optional polling.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String CHILD_DRIVER = "WiZ Dual Zone Light"
@Field static final String NAMESPACE = "youzer-name"
@Field static final int WIZ_PORT = 38899

// Retry schedule for one message (ms to wait before each resend / giving up).
// Normal replies take ~130 ms, so the first resend is quick; later ones back off
// like pywizlight. Sends at 0, 0.3, 1.0, 2.5, 5.5, 8.5, 11.5 s; give up at ~13 s.
@Field static final List<Integer> RETRY_WAITS = [300, 700, 1500, 3000, 3000, 3000, 1500]

// Keys that describe the light's color/mode. A new command with any of these
// replaces the old ones when two queued commands are merged.
@Field static final List<String> COLOR_KEYS = ["r", "g", "b", "c", "w", "temp", "sceneId"]

// Per-bulb send queue and in-flight message. Keyed by device id.
@Field static final ConcurrentHashMap<String, List> QUEUES = new ConcurrentHashMap()
@Field static final ConcurrentHashMap<String, Map> INFLIGHT = new ConcurrentHashMap()
@Field static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap()
@Field static final ConcurrentHashMap<String, Integer> MSG_IDS = new ConcurrentHashMap()

// Built-in WiZ scenes (Rhythm/1000 is app-only and can't be set, so it's left out).
@Field static final Map<Integer, String> SCENES = [
    1: "Ocean", 2: "Romance", 3: "Sunset", 4: "Party", 5: "Fireplace", 6: "Cozy",
    7: "Forest", 8: "Pastel Colors", 9: "Wake-up", 10: "Bedtime", 11: "Warm White",
    12: "Daylight", 13: "Cool White", 14: "Night Light", 15: "Focus", 16: "Relax",
    17: "True Colors", 18: "TV Time", 19: "Plant Growth", 20: "Spring", 21: "Summer",
    22: "Fall", 23: "Deep Dive", 24: "Jungle", 25: "Mojito", 26: "Club",
    27: "Christmas", 28: "Halloween", 29: "Candlelight", 30: "Golden White",
    31: "Pulse", 32: "Steampunk", 33: "Diwali", 34: "White", 35: "Alarm",
    36: "Snowy Sky", 40: "Dim-to-Warm"
]

metadata {
    definition(name: "WiZ Dual Zone Bulb", namespace: "youzer-name", author: "youzer-name",
            importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-Wiz_Dual_Zone/main/WizDualZoneBulb.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"
        capability "HealthCheck"

        attribute "healthStatus", "enum", ["online", "offline", "unknown"]

        command "createZoneDevices"
        // Used by WiZ Dual Zone Discovery when a bulb's address changes.
        command "setIpAddress", [[name: "IP address*", type: "STRING"]]
    }

    preferences {
        input name: "ipAddress", type: "text", title: "Bulb IP address", required: true
        input name: "pollSeconds", type: "enum", title: "Poll bulb state every",
              options: ["0": "Never", "30": "30 seconds", "60": "1 minute", "120": "2 minutes",
                        "300": "5 minutes", "900": "15 minutes"], defaultValue: "60"
        input name: "minDimming", type: "number", title: "Lowest brightness % sent to bulb (WiZ minimum is usually 10)",
              range: "1..100", defaultValue: 10
        input name: "readBack", type: "bool", title: "Re-read zone state from the bulb after each command (extra traffic; usually not needed)",
              defaultValue: false
        input name: "zone1Name", type: "text", title: "Zone 1 name suffix", defaultValue: "Main"
        input name: "zone2Name", type: "text", title: "Zone 2 name suffix", defaultValue: "Ring"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    sendEvent(name: "healthStatus", value: "unknown")
    sendEvent(name: "switch", value: "off")
}

void updated() {
    log.info "${device.displayName}: preferences saved"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    unschedule()
    resetQueue()
    if (!ipAddress) {
        log.warn "${device.displayName}: set the bulb IP address in Preferences"
        return
    }
    device.updateDataValue("ip", "${ipAddress}")
    createZoneDevices()
    enqueue([method: "getSystemConfig", params: [:]], [kind: "sysConfig"])
    enqueue([method: "getModelConfig", params: [:]], [kind: "modelConfig"])
    refresh()
    schedulePoll()
}

void setIpAddress(String ip) {
    ip = ip?.trim()
    if (!ip || ip == ipAddress) return
    log.info "${device.displayName}: IP address changed from ${ipAddress ?: 'none'} to ${ip}"
    device.updateSetting("ipAddress", [value: ip, type: "text"])
    runInMillis(200, "initialize")
}

void uninstalled() {
    resetQueue()
    unschedule()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging disabled"
}

void createZoneDevices() {
    [1, 2].each { int zone ->
        String dni = zoneDni(zone)
        def child = getChildDevice(dni)
        String suffix = zone == 1 ? (zone1Name ?: "Main") : (zone2Name ?: "Ring")
        if (!child) {
            child = addChildDevice(NAMESPACE, CHILD_DRIVER, dni,
                    [name: "WiZ Zone ${zone}", label: "${device.displayName} ${suffix}", isComponent: false])
            log.info "${device.displayName}: created zone ${zone} device ${child.displayName}"
        }
        child.updateDataValue("zone", "${zone}")
        child.updateDataValue("ip", "${ipAddress}")
        sendZoneEvents(zone, [
                [name: "bulbIp", value: "${ipAddress}"],
                [name: "lightEffects", value: JsonOutput.toJson(SCENES.collectEntries { k, v -> [(k.toString()): v] })],
        ])
    }
}

private String zoneDni(int zone) { "${device.id}-zone${zone}" }

private void schedulePoll() {
    int secs = (pollSeconds ?: "60") as int
    if (secs > 0) runIn(secs, "poll")
}

void poll() {
    refresh()
    schedulePoll()
}

// ---------------------------------------------------------------------------
// Whole-bulb commands (both zones together)
// ---------------------------------------------------------------------------

void on() {
    enqueue([method: "setPilot", params: [state: true]], [kind: "set", zone: 0])
}

void off() {
    enqueue([method: "setPilot", params: [state: false]], [kind: "set", zone: 0])
}

void refresh() {
    zoneRefresh(1)
    zoneRefresh(2)
}

void ping() { refresh() }

// ---------------------------------------------------------------------------
// Per-zone commands, called by the child devices
// ---------------------------------------------------------------------------

void zoneOn(int zone) { zoneSet(zone, [state: true]) }

void zoneOff(int zone) { zoneSet(zone, [state: false]) }

void zoneSetLevel(int zone, level) {
    int lvl = Math.round((level ?: 0) as double) as int
    if (lvl <= 0) {
        zoneOff(zone)
        return
    }
    zoneSet(zone, [state: true, dimming: toDimming(lvl)])
}

void zoneSetColorTemperature(int zone, kelvin, level = null) {
    int ct = Math.round(kelvin as double) as int
    ct = Math.max(ctMin(), Math.min(ctMax(), ct))
    Map p = [state: true, temp: ct]
    if (level != null && (level as double) > 0) p.dimming = toDimming(level)
    zoneSet(zone, p)
}

void zoneSetColor(int zone, hue, sat, level = null) {
    Map p = [state: true] + hsToWiz(hue, sat)
    if (level != null && (level as double) > 0) p.dimming = toDimming(level)
    zoneSet(zone, p)
}

void zoneSetRGBW(int zone, int r, int g, int b, int w) {
    zoneSet(zone, [state: true, r: clamp255(r), g: clamp255(g), b: clamp255(b), w: clamp255(w)])
}

void zoneSetEffect(int zone, int sceneId) {
    if (!SCENES.containsKey(sceneId)) {
        log.warn "${device.displayName}: unknown effect ${sceneId}"
        return
    }
    zoneSet(zone, [state: true, sceneId: sceneId])
}

void zoneSetEffectSpeed(int zone, speed) {
    int s = Math.max(10, Math.min(200, Math.round(speed as double) as int))
    // Sent on its own: the bulb ignores speed when "state" is in the same message.
    enqueue([method: "setPilot", params: [devices: zone, speed: s]], [kind: "speed", zone: zone])
}

void zoneRefresh(int zone) {
    // getPilot is 0-based: zone 1 -> devices 0, zone 2 -> devices 1
    enqueue([method: "getPilot", params: [devices: zone - 1]], [kind: "get", zone: zone])
}

List<Integer> effectIds() { SCENES.keySet().toList() }

Map<Integer, String> effectMap() { SCENES }

private void zoneSet(int zone, Map params) {
    Map p = [devices: zone] + params
    enqueue([method: "setPilot", params: p], [kind: "set", zone: zone])
}

// ---------------------------------------------------------------------------
// Send queue: one message in flight per bulb, with retries
// ---------------------------------------------------------------------------

private Object lockObj() {
    String key = device.id.toString()
    LOCKS.putIfAbsent(key, new Object())
    return LOCKS.get(key)
}

private List queue() {
    String key = device.id.toString()
    QUEUES.putIfAbsent(key, [])
    return QUEUES.get(key)
}

private void resetQueue() {
    String key = device.id.toString()
    synchronized (lockObj()) {
        QUEUES.remove(key)
        INFLIGHT.remove(key)
    }
}

private void enqueue(Map msg, Map meta) {
    if (!ipAddress) return
    Map entry = [msg: msg, kind: meta.kind, zone: meta.zone]
    synchronized (lockObj()) {
        List q = queue()
        if (!mergeIntoQueue(q, entry)) q.add(entry)
    }
    pump()
}

// Combine a new command with a queued (not yet sent) one for the same zone, so
// a burst of commands (e.g. dragging a slider) collapses into one message.
private boolean mergeIntoQueue(List q, Map entry) {
    if (entry.kind == "get") {
        return q.any { it.kind == "get" && it.zone == entry.zone }
    }
    if (entry.kind != "set") return false
    // Only merge with the newest queued item, so command order is kept.
    if (q.isEmpty()) return false
    Map last = q[q.size() - 1]
    if (last.kind != "set" || last.zone != entry.zone) return false
    Map oldP = last.msg.params
    Map newP = entry.msg.params
    if (newP.keySet().any { it in COLOR_KEYS }) COLOR_KEYS.each { oldP.remove(it) }
    if (newP.state == false) {
        // Turning off: drop everything else, just turn off.
        last.msg.params = (oldP.devices != null ? [devices: oldP.devices] : [:]) + [state: false]
    } else {
        oldP.putAll(newP)
    }
    return true
}

private void pump() {
    String key = device.id.toString()
    Map toSend = null
    synchronized (lockObj()) {
        if (INFLIGHT.get(key) != null) return
        List q = queue()
        if (q.isEmpty()) return
        Map entry = q.remove(0)
        int id = nextMsgId()
        entry.msg.id = id
        entry.id = id
        entry.attempt = 0
        INFLIGHT.put(key, entry)
        toSend = entry
    }
    transmit(toSend)
}

// Called only while holding lockObj(). Kept in memory (not `state`) so
// concurrent commands to the same bulb never reuse an id.
private int nextMsgId() {
    String key = device.id.toString()
    int id = ((MSG_IDS.get(key) ?: 0) as int) + 1
    if (id > 100000) id = 1
    MSG_IDS.put(key, id)
    return id
}

private void transmit(Map entry) {
    String json = JsonOutput.toJson(entry.msg)
    if (logEnable) log.debug "${device.displayName}: >> ${json} (try ${entry.attempt + 1})"
    // Hubitat's only UDP payload encoding is HEX_STRING, so send the JSON as hex.
    String hex = hubitat.helper.HexUtils.byteArrayToHexString(json.getBytes("UTF-8"))
    def action = new hubitat.device.HubAction(hex, hubitat.device.Protocol.LAN, [
            type              : hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${ipAddress}:${WIZ_PORT}",
            encoding          : hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreResponse    : false,
            parseWarning      : true,
            timeout           : 5,
            callback          : "udpResponse"
    ])
    sendHubCommand(action)
    int wait = RETRY_WAITS[Math.min(entry.attempt as int, RETRY_WAITS.size() - 1)]
    runInMillis(wait, "checkTimeout", [data: [id: entry.id, attempt: entry.attempt], overwrite: false])
}

void checkTimeout(Map data) {
    String key = device.id.toString()
    Map resend = null
    boolean gaveUp = false
    Map entry
    synchronized (lockObj()) {
        entry = INFLIGHT.get(key)
        if (entry == null || entry.id != data.id || entry.attempt != data.attempt) return
        entry.attempt = (entry.attempt as int) + 1
        if (entry.attempt < RETRY_WAITS.size()) {
            resend = entry
        } else {
            INFLIGHT.remove(key)
            gaveUp = true
        }
    }
    if (resend) {
        transmit(resend)
        return
    }
    if (gaveUp) {
        log.warn "${device.displayName}: no reply from ${ipAddress} to ${entry.msg.method} after ${RETRY_WAITS.size()} tries"
        setHealth("offline")
        pump()
    }
}

void udpResponse(String description) {
    def lan = parseLanMessage(description)
    if (!lan?.payload) {
        if (logEnable) log.debug "${device.displayName}: empty UDP callback: ${description}"
        return
    }
    String text
    try {
        text = new String(hubitat.helper.HexUtils.hexStringToByteArray(lan.payload), "UTF-8")
    } catch (e) {
        text = lan.payload
    }
    if (logEnable) log.debug "${device.displayName}: << ${text}"
    Map resp
    try {
        resp = new JsonSlurper().parseText(text) as Map
    } catch (e) {
        log.warn "${device.displayName}: could not parse reply: ${text}"
        return
    }

    String key = device.id.toString()
    Map entry
    synchronized (lockObj()) {
        entry = INFLIGHT.get(key)
        if (entry == null) return
        boolean idMatch = resp.id != null ? (resp.id as int) == (entry.id as int) : resp.method == entry.msg.method
        if (!idMatch) {
            if (logEnable) log.debug "${device.displayName}: ignoring stale reply ${resp.id}"
            return
        }
        INFLIGHT.remove(key)
    }
    setHealth("online")
    try {
        handleReply(entry, resp)
    } catch (e) {
        log.error "${device.displayName}: error handling reply ${text}: ${e}"
    }
    pump()
}

// Some hub firmware delivers UDP replies to parse() instead of the callback.
void parse(String description) { udpResponse(description) }

// ---------------------------------------------------------------------------
// Replies -> device state
// ---------------------------------------------------------------------------

private void handleReply(Map entry, Map resp) {
    if (resp.error) {
        log.warn "${device.displayName}: bulb rejected ${JsonOutput.toJson(entry.msg)}: ${resp.error}"
        if (entry.zone) runInMillis(500, "refreshZone${entry.zone}".toString())
        return
    }
    Map result = (resp.result ?: [:]) as Map
    switch (entry.kind) {
        case "get":
            applyZoneState(entry.zone as int, result)
            break
        case "set":
        case "speed":
            Map p = entry.msg.params
            if (entry.zone == 0) {
                applyZoneState(1, p)
                applyZoneState(2, p)
                if (readBack) {
                    runInMillis(1500, "refreshZone1")
                    runInMillis(1500, "refreshZone2")
                }
            } else {
                applyZoneState(entry.zone as int, p)
                // The bulb's "success" reply already confirms the change; optionally
                // read the real state back a moment later (debounced).
                if (readBack) runInMillis(1500, "refreshZone${entry.zone}".toString())
            }
            break
        case "sysConfig":
            if (result.moduleName) device.updateDataValue("moduleName", "${result.moduleName}")
            if (result.fwVersion) device.updateDataValue("firmware", "${result.fwVersion}")
            if (result.mac) device.updateDataValue("mac", "${result.mac}")
            if (result.moduleName && !"${result.moduleName}".contains("DH")) {
                log.warn "${device.displayName}: module ${result.moduleName} doesn't look like a dual-head bulb"
            }
            break
        case "modelConfig":
            List cct = (result.cctRange ?: result.extRange) as List
            if (cct) {
                state.ctMin = cct.collect { it as int }.min()
                state.ctMax = cct.collect { it as int }.max()
            }
            break
    }
}

void refreshZone1() { zoneRefresh(1) }

void refreshZone2() { zoneRefresh(2) }

private void applyZoneState(int zone, Map p) {
    List evts = []
    if (p.state != null) {
        evts << [name: "switch", value: p.state ? "on" : "off"]
    }
    if (p.dimming != null) {
        evts << [name: "level", value: (p.dimming as int), unit: "%"]
    }
    Integer scene = p.sceneId != null ? (p.sceneId as int) : null
    Integer temp = p.temp != null ? (p.temp as int) : null
    boolean hasRgb = p.r != null && p.g != null && p.b != null

    if (scene != null && scene > 0) {
        evts << [name: "colorMode", value: "EFFECTS"]
        evts << [name: "effectName", value: SCENES[scene] ?: "Scene ${scene}"]
        evts << [name: "colorName", value: SCENES[scene] ?: "Scene ${scene}"]
    } else if (temp != null && temp > 0) {
        evts << [name: "colorMode", value: "CT"]
        evts << [name: "colorTemperature", value: temp, unit: "K"]
        evts << [name: "colorName", value: ctName(temp)]
        evts << [name: "effectName", value: "none"]
    } else if (hasRgb) {
        Map hs = wizToHs(p.r as int, p.g as int, p.b as int, (p.w ?: 0) as int)
        evts << [name: "colorMode", value: "RGB"]
        evts << [name: "hue", value: hs.hue]
        evts << [name: "saturation", value: hs.saturation, unit: "%"]
        evts << [name: "color", value: rgbHex(p.r as int, p.g as int, p.b as int)]
        evts << [name: "colorName", value: hueName(hs.hue as int, hs.saturation as int)]
        evts << [name: "RGBW", value: "${p.r},${p.g},${p.b},${p.w ?: 0}"]
        evts << [name: "effectName", value: "none"]
    }
    if (p.speed != null) {
        evts << [name: "effectSpeed", value: (p.speed as int)]
    }
    if (evts) sendZoneEvents(zone, evts)
}

private void sendZoneEvents(int zone, List evts) {
    def child = getChildDevice(zoneDni(zone))
    if (!child) return
    child.parse(evts)
    if (evts.any { it.name == "switch" }) updateParentSwitch()
}

void updateParentSwitch() {
    boolean anyOn = getChildDevices().any { it.currentValue("switch", true) == "on" }
    String v = anyOn ? "on" : "off"
    if (device.currentValue("switch") != v) {
        sendEvent(name: "switch", value: v, descriptionText: "${device.displayName} is ${v}")
        if (txtEnable) log.info "${device.displayName} is ${v}"
    }
}

private void setHealth(String v) {
    // skipCache: this can run on several threads at once, and a cached value
    // would leave the status stuck (e.g. "offline" after the bulb answers again).
    if (device.currentValue("healthStatus", true) != v) {
        sendEvent(name: "healthStatus", value: v)
        if (v == "offline") log.warn "${device.displayName}: bulb at ${ipAddress} is not responding"
        getChildDevices().each { it.parse([[name: "healthStatus", value: v]]) }
    }
}

// ---------------------------------------------------------------------------
// Color helpers
// ---------------------------------------------------------------------------

int ctMin() { (state.ctMin ?: 2200) as int }

int ctMax() { (state.ctMax ?: 6500) as int }

private int toDimming(level) {
    int lvl = Math.round(level as double) as int
    int min = (minDimming ?: 10) as int
    return Math.max(min, Math.min(100, lvl))
}

private int clamp255(v) { Math.max(0, Math.min(255, v as int)) }

/**
 * Hubitat hue/saturation (0-100) -> WiZ r,g,b,w.
 * Same approach as pywizlight: full-strength hue on the RGB LEDs, and as
 * saturation drops below 100% the warm-white LED is mixed in (up to 128);
 * below 50% the RGB part fades out too.
 */
private Map hsToWiz(hue, sat) {
    double s = Math.max(0d, Math.min(100d, sat as double)) / 100.0d
    List rgb = hubitat.helper.ColorUtils.hsvToRGB([hue as double, 100, 100])
    double rgbScale = 1.0
    double cw
    if (s >= 0.5) {
        cw = 1.0 - ((s - 0.5) * 2)
    } else {
        cw = 1.0
        rgbScale = s * 2
    }
    return [
            r: clamp255(Math.round((rgb[0] as double) * rgbScale)),
            g: clamp255(Math.round((rgb[1] as double) * rgbScale)),
            b: clamp255(Math.round((rgb[2] as double) * rgbScale)),
            w: clamp255(Math.round(cw * 128))
    ]
}

/** WiZ r,g,b,w -> Hubitat hue/saturation (inverse of hsToWiz). */
private Map wizToHs(int r, int g, int b, int w) {
    List hsv = hubitat.helper.ColorUtils.rgbToHSV([r, g, b])
    int hue = Math.round(hsv[0] as double) as int
    double cw = Math.min(w, 128) / 128.0d
    double sat
    if (cw >= 0.999) {
        sat = (Math.max(r, Math.max(g, b)) / 255.0) * 0.5
    } else {
        sat = 1.0 - (cw / 2.0)
    }
    return [hue: hue, saturation: Math.round(sat * 100) as int]
}

private String rgbHex(int r, int g, int b) {
    String.format("#%02X%02X%02X", r, g, b)
}

private String ctName(int k) {
    if (k < 2500) return "Warm White"
    if (k < 3500) return "Soft White"
    if (k < 4500) return "Moonlight"
    if (k < 5500) return "Daylight"
    return "Cool White"
}

private String hueName(int hue, int sat) {
    if (sat < 15) return "White"
    int deg = Math.round(hue * 3.6d) as int
    if (deg < 15) return "Red"
    if (deg < 45) return "Orange"
    if (deg < 70) return "Yellow"
    if (deg < 150) return "Green"
    if (deg < 190) return "Cyan"
    if (deg < 260) return "Blue"
    if (deg < 290) return "Purple"
    if (deg < 335) return "Pink"
    return "Red"
}
