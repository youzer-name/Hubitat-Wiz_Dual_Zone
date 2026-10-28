/**
 *  WiZ Dual Zone Group (app)
 *
 *  Version 1.0.1
 *  https://github.com/youzer-name/Hubitat-Wiz_Dual_Zone (MIT License)
 *
 *  Groups any number of "WiZ Dual Zone Light" zone devices behind one light
 *  device. A command on the group device sends one UDP packet per bulb in a
 *  single tight loop, so all bulbs change at (nearly) the same moment instead
 *  of one after another.
 *
 *  - If both zones of a bulb are in the group, one packet without a zone
 *    number is sent (the bulb applies it to both zones).
 *  - Each packet is resent until the bulb confirms it (or ~13 s pass).
 *  - Confirmed changes are pushed to the member zone devices so their state
 *    stays correct.
 *  - The group device follows its members: on if any member is on; brightness
 *    is the average of the members that are on; color, color temperature and
 *    effect update when all members that are on agree (otherwise the group
 *    keeps its last value).
 *
 *  Install one instance of this app per group.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final int WIZ_PORT = 38899
@Field static final List<Integer> RETRY_WAITS = [300, 700, 1500, 3000, 3000, 3000, 1500]
@Field static final int CT_MIN = 2200
@Field static final int CT_MAX = 6500

// Packets waiting for a reply. Key: "<appId>|<bulb ip>".
@Field static final ConcurrentHashMap<String, Map> PENDING = new ConcurrentHashMap()
// Cached membership per app: ip -> [zones: [1,2], ids: [deviceId, ...]]
@Field static final ConcurrentHashMap<String, Map> MEMBERS = new ConcurrentHashMap()
@Field static final ConcurrentHashMap<String, Integer> MSG_IDS = new ConcurrentHashMap()

// Member attributes the group shows when every member that's on agrees.
// (Brightness is handled separately: the average of the members that are on.)
@Field static final List<String> FOLLOW_ATTRS = ["colorMode", "colorTemperature", "hue", "saturation", "color",
                                                 "colorName", "effectName", "effectSpeed"]
@Field static final Map<String, String> UNITS = [level: "%", colorTemperature: "K", saturation: "%"]

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

definition(
        name: "WiZ Dual Zone Group",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-Wiz_Dual_Zone/main/WizDualZoneGroupApp.groovy",
        namespace: "youzer-name",
        author: "youzer-name",
        description: "Control many WiZ dual-zone lights at once through one group device",
        category: "Lighting",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "WiZ Dual Zone Group", install: true, uninstall: true) {
        section {
            input name: "groupName", type: "text", title: "Group name (also the group device's name)", required: true,
                  submitOnChange: true
            input name: "zones", type: "device.WiZDualZoneLight", title: "Zone lights in this group",
                  multiple: true, required: true
        }
        section("Options") {
            input name: "minDimming", type: "number", title: "Lowest brightness % sent to bulbs", range: "1..100", defaultValue: 10
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void updated() {
    unsubscribe()
    initialize()
}

void uninstalled() {
    clearPending()
    MEMBERS.remove(appKey())
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    if (groupName) app.updateLabel(groupName)
    def dev = groupDevice()
    if (!dev) {
        dev = addChildDevice("youzer-name", "WiZ Dual Zone Group", "wizgrp-${app.id}",
                [name: "WiZ Dual Zone Group", label: groupName ?: "WiZ Group ${app.id}", isComponent: false])
        log.info "${app.label}: created group device ${dev.displayName}"
    } else if (groupName && dev.label != groupName) {
        dev.setLabel(groupName)
    }
    dev.parse([[name: "lightEffects", value: JsonOutput.toJson(SCENES.collectEntries { k, v -> [(k.toString()): v] })]])
    buildMembers()
    subscribe(zones, "switch", "memberSwitchHandler")
    subscribe(zones, "bulbIp", "memberIpHandler")
    (["level"] + FOLLOW_ATTRS).each { subscribe(zones, it, "memberStateHandler") }
    memberSwitchHandler(null)
    if (logEnable) runIn(1800, "logsOff")
}

void logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

private String appKey() { app.id.toString() }

private def groupDevice() { getChildDevice("wizgrp-${app.id}") }

// Group membership by bulb IP, cached in memory so commands don't re-read it.
private Map buildMembers() {
    Map byIp = [:]
    (zones ?: []).each { d ->
        String ip = d.getDataValue("ip")
        String z = d.getDataValue("zone")
        if (!ip || !z) {
            log.warn "${app.label}: ${d.displayName} has no IP/zone yet - run Initialize on its bulb device"
            return
        }
        Map m = byIp[ip] ?: [zones: [], ids: []]
        m.zones << (z as int)
        m.ids << d.id.toString()
        byIp[ip] = m
    }
    MEMBERS.put(appKey(), byIp)
    return byIp
}

// A member bulb moved to a new IP address: rebuild the membership cache.
void memberIpHandler(evt) {
    log.info "${app.label}: ${evt.displayName} is now at ${evt.value}"
    buildMembers()
}

private Map members() { MEMBERS.get(appKey()) ?: buildMembers() }

// ---------------------------------------------------------------------------
// Commands from the group device
// ---------------------------------------------------------------------------

void groupOn() { sendAll([state: true]) }

void groupOff() { sendAll([state: false]) }

void groupSetLevel(level) {
    int lvl = Math.round((level ?: 0) as double) as int
    if (lvl <= 0) {
        groupOff()
        return
    }
    sendAll([state: true, dimming: toDimming(lvl)])
}

void groupSetColor(hue, sat, level = null) {
    Map p = [state: true] + hsToWiz(hue, sat)
    if (level != null && (level as double) > 0) p.dimming = toDimming(level)
    sendAll(p)
}

void groupSetColorTemperature(kelvin, level = null) {
    int ct = Math.max(CT_MIN, Math.min(CT_MAX, Math.round(kelvin as double) as int))
    Map p = [state: true, temp: ct]
    if (level != null && (level as double) > 0) p.dimming = toDimming(level)
    sendAll(p)
}

void groupSetEffect(int sceneId) {
    if (!SCENES.containsKey(sceneId)) {
        log.warn "${app.label}: unknown effect ${sceneId}"
        return
    }
    sendAll([state: true, sceneId: sceneId])
}

void groupSetEffectByName(String name) {
    def match = SCENES.find { k, v -> v.equalsIgnoreCase(name?.trim()) }
    if (match) groupSetEffect(match.key)
    else log.warn "${app.label}: no effect named '${name}'"
}

void groupStepEffect(int dir) {
    List<Integer> ids = SCENES.keySet().toList()
    String cur = groupDevice()?.currentValue("effectName")
    Integer curId = SCENES.find { k, v -> v == cur }?.key
    int idx = curId == null ? (dir > 0 ? 0 : ids.size() - 1) : (ids.indexOf(curId) + dir + ids.size()) % ids.size()
    groupSetEffect(ids[idx])
}

void groupSetEffectSpeed(speed) {
    int s = Math.max(10, Math.min(200, Math.round(speed as double) as int))
    // Sent without "state": the bulb ignores speed when state is included.
    sendAll([speed: s])
}

void groupRefresh() {
    buildMembers()
    zones?.each { it.refresh() }
}

// ---------------------------------------------------------------------------
// Sending
// ---------------------------------------------------------------------------

private void sendAll(Map params) {
    Map byIp = members()
    long t = now()
    // 1) Fire every packet first, with as little work per bulb as possible.
    byIp.each { String ip, Map m ->
        Map p = new LinkedHashMap(params)
        // Both zones in the group -> one packet with no zone number hits both.
        if (m.zones.size() == 1) p.devices = m.zones[0]
        int id = nextMsgId()
        Map msg = [method: "setPilot", params: p, id: id]
        PENDING.put("${appKey()}|${ip}".toString(), [ip: ip, msg: msg, id: id, attempt: 0, sentAt: t, ids: m.ids])
        transmit(ip, msg)
    }
    runInMillis(RETRY_WAITS[0], "retryPending", [overwrite: true])
    // 2) Then update the group device's own state.
    groupDevice()?.parse(eventsFor(params))
    if (logEnable) log.debug "${app.label}: sent ${params} to ${byIp.size()} bulbs in ${now() - t} ms"
}

private int nextMsgId() {
    String key = appKey()
    synchronized (MSG_IDS) {
        int id = ((MSG_IDS.get(key) ?: 0) as int) + 1
        if (id > 100000) id = 1
        MSG_IDS.put(key, id)
        return id
    }
}

private void transmit(String ip, Map msg) {
    String json = JsonOutput.toJson(msg)
    String hex = hubitat.helper.HexUtils.byteArrayToHexString(json.getBytes("UTF-8"))
    sendHubCommand(new hubitat.device.HubAction(hex, hubitat.device.Protocol.LAN, [
            type              : hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${ip}:${WIZ_PORT}",
            encoding          : hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreResponse    : false,
            parseWarning      : true,
            timeout           : 5,
            callback          : "udpReply"
    ]))
}

void retryPending() {
    String prefix = "${appKey()}|"
    long t = now()
    long nextDue = Long.MAX_VALUE
    PENDING.findAll { k, v -> k.startsWith(prefix) }.each { String k, Map e ->
        int wait = RETRY_WAITS[Math.min(e.attempt as int, RETRY_WAITS.size() - 1)]
        long due = (e.sentAt as long) + wait
        if (t + 20 >= due) {
            e.attempt = (e.attempt as int) + 1
            if (e.attempt >= RETRY_WAITS.size()) {
                PENDING.remove(k, e)
                log.warn "${app.label}: no reply from ${e.ip} after ${RETRY_WAITS.size()} tries"
                return
            }
            e.sentAt = t
            if (logEnable) log.debug "${app.label}: resend to ${e.ip} (try ${e.attempt + 1})"
            transmit(e.ip as String, e.msg as Map)
            due = t + RETRY_WAITS[Math.min(e.attempt as int, RETRY_WAITS.size() - 1)]
        }
        nextDue = Math.min(nextDue, due)
    }
    if (nextDue != Long.MAX_VALUE) {
        runInMillis(Math.max(50L, nextDue - now()), "retryPending", [overwrite: true])
    }
}

void udpReply(response) {
    String description = response instanceof String ? response : response?.description
    def lan = description ? parseLanMessage(description) : null
    if (!lan?.payload) return
    Map resp
    try {
        resp = new JsonSlurper().parseText(new String(hubitat.helper.HexUtils.hexStringToByteArray(lan.payload), "UTF-8")) as Map
    } catch (e) {
        return
    }
    if (resp?.id == null) return
    String prefix = "${appKey()}|"
    def found = PENDING.find { k, v -> k.startsWith(prefix) && (v.id as int) == (resp.id as int) }
    if (!found) return
    if (!PENDING.remove(found.key, found.value)) return
    Map e = found.value
    if (resp.error) {
        log.warn "${app.label}: ${e.ip} rejected ${JsonOutput.toJson(e.msg)}: ${resp.error}"
        return
    }
    if (logEnable) log.debug "${app.label}: ${e.ip} confirmed in ${now() - (e.sentAt as long)} ms"
    // Push the confirmed change to the member zone devices on that bulb.
    List evts = eventsFor(e.msg.params as Map)
    if (!evts) return
    String json = JsonOutput.toJson(evts)
    zones?.findAll { it.id.toString() in e.ids }?.each { it.applyGroupState(json) }
}

private void clearPending() {
    String prefix = "${appKey()}|"
    PENDING.keySet().findAll { it.startsWith(prefix) }.each { PENDING.remove(it) }
}

// Keep the group's on/off in step with its members (on if any member is on).
void memberSwitchHandler(evt) {
    def dev = groupDevice()
    if (!dev) return
    boolean anyOn = zones?.any { it.currentValue("switch") == "on" }
    dev.parse([[name: "switch", value: anyOn ? "on" : "off"]])
    memberStateHandler(evt)
}

// Members' changes come in bursts (one per bulb), so summarize once they settle.
void memberStateHandler(evt) {
    runInMillis(400, "summarizeMembers", [overwrite: true])
}

void summarizeMembers() {
    def dev = groupDevice()
    if (!dev) return
    List on = (zones ?: []).findAll { it.currentValue("switch") == "on" }
    if (!on) return
    List evts = []
    List levels = on.collect { it.currentValue("level") }.findAll { it != null }
    if (levels) {
        BigDecimal total = levels.sum { it as BigDecimal } as BigDecimal
        int avg = (total / levels.size()).setScale(0, java.math.RoundingMode.HALF_UP) as int
        evts << [name: "level", value: avg, unit: "%"]
    }
    FOLLOW_ATTRS.each { String a ->
        def first = on[0].currentValue(a)
        if (first == null) return
        if (on.every { "${it.currentValue(a)}" == "${first}" }) {
            Map e = [name: a, value: first]
            if (UNITS[a]) e.unit = UNITS[a]
            evts << e
        }
    }
    if (evts) dev.parse(evts)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private List eventsFor(Map p) {
    List evts = []
    if (p.state != null) evts << [name: "switch", value: p.state ? "on" : "off"]
    if (p.dimming != null) evts << [name: "level", value: (p.dimming as int), unit: "%"]
    if (p.sceneId != null && (p.sceneId as int) > 0) {
        String n = SCENES[p.sceneId as int] ?: "Scene ${p.sceneId}"
        evts << [name: "colorMode", value: "EFFECTS"]
        evts << [name: "effectName", value: n]
        evts << [name: "colorName", value: n]
    } else if (p.temp != null) {
        int k = p.temp as int
        evts << [name: "colorMode", value: "CT"]
        evts << [name: "colorTemperature", value: k, unit: "K"]
        evts << [name: "colorName", value: ctName(k)]
        evts << [name: "effectName", value: "none"]
    } else if (p.r != null) {
        Map hs = wizToHs(p.r as int, p.g as int, p.b as int, (p.w ?: 0) as int)
        evts << [name: "colorMode", value: "RGB"]
        evts << [name: "hue", value: hs.hue]
        evts << [name: "saturation", value: hs.saturation, unit: "%"]
        evts << [name: "color", value: String.format("#%02X%02X%02X", p.r as int, p.g as int, p.b as int)]
        evts << [name: "colorName", value: hueName(hs.hue as int, hs.saturation as int)]
        evts << [name: "effectName", value: "none"]
    }
    if (p.speed != null) evts << [name: "effectSpeed", value: (p.speed as int)]
    return evts
}

private int toDimming(level) {
    int lvl = Math.round(level as double) as int
    return Math.max((minDimming ?: 10) as int, Math.min(100, lvl))
}

private int clamp255(v) { Math.max(0, Math.min(255, v as int)) }

// Same conversion as the bulb driver (see comments there).
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

private Map wizToHs(int r, int g, int b, int w) {
    List hsv = hubitat.helper.ColorUtils.rgbToHSV([r, g, b])
    int hue = Math.round(hsv[0] as double) as int
    double cw = Math.min(w, 128) / 128.0d
    double sat = cw >= 0.999 ? (Math.max(r, Math.max(g, b)) / 255.0) * 0.5 : 1.0 - (cw / 2.0)
    return [hue: hue, saturation: Math.round(sat * 100) as int]
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
