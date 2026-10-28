/**
 *  WiZ Dual Zone Discovery (app)
 *
 *  Version 0.3.5
 *  https://github.com/youzer-name/Hubitat-Wiz_Dual_Zone (MIT License)
 *
 *  Finds WiZ dual-zone bulbs on the local network and creates a
 *  "WiZ Dual Zone Bulb" device for each one you pick.
 *
 *  - Search: asks every address on the hub's subnet (x.x.x.1-254) for its
 *    WiZ system config, which includes the MAC address and model. (A UDP
 *    broadcast would be quicker, but Hubitat only passes back the first reply.)
 *    Hubitat holds each request open until it's answered or times out, so the
 *    scan is paced to keep only a few waiting at once (about 40 s in all).
 *  - Bulbs are remembered by MAC address. When a known bulb shows up at a
 *    new IP address, its device is pointed at the new address.
 *  - Bulb devices created by hand (not by this app) can be selected so they
 *    get the same IP updates.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String NAMESPACE = "youzer-name"
@Field static final String BULB_DRIVER = "WiZ Dual Zone Bulb"
@Field static final int WIZ_PORT = 38899
// Scan pacing: BATCH addresses every BATCH_MS, each waiting up to 1 s for a reply.
@Field static final int BATCH = 8
@Field static final int BATCH_MS = 1200

// Bulbs heard during the current search. Key: MAC (lowercase, no colons).
@Field static final ConcurrentHashMap<String, Map> FOUND = new ConcurrentHashMap()
// Last address checked so far in the current search (for the progress line).
@Field static final ConcurrentHashMap<String, Integer> PROGRESS = new ConcurrentHashMap()

definition(
        name: "WiZ Dual Zone Discovery",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-Wiz_Dual_Zone/main/WizDualZoneDiscovery.groovy",
        namespace: "youzer-name",
        author: "youzer-name",
        description: "Find WiZ dual-zone bulbs on your network and create their devices",
        category: "Lighting",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    boolean searching = state.searching && now() < ((state.searchEnds ?: 0) as long) + 10000
    dynamicPage(name: "mainPage", title: "WiZ Dual Zone Discovery", install: true, uninstall: true,
            refreshInterval: searching ? 2 : 0) {
        section {
            paragraph "The hub must be on the same network as the bulbs. Give each bulb a fixed IP " +
                    "address (a DHCP reservation in your router) if you can; if one changes, run a " +
                    "search again and its device is updated."
            if (app.getInstallationState() != "COMPLETE") {
                // Hubitat doesn't run an app's scheduled jobs until it's installed,
                // and the search depends on them.
                paragraph "<b>Click Done to finish installing this app, then open it again to search.</b>"
            } else if (searching) {
                paragraph "<b>Searching...</b> checked ${PROGRESS.get('n') ?: 0} of 254 addresses; " +
                        "${FOUND.size()} WiZ device(s) found so far. This takes about 40 seconds."
            } else {
                input name: "btnSearch", type: "button", title: "Search for bulbs"
            }
        }
        if (searching) return

        Map found = (state.found ?: [:]) as Map
        Map known = knownBulbs()
        List newOnes = []
        List setUp = []
        List other = []
        found.each { String mac, Map b ->
            if (!isDualZone(b.model as String)) other << [mac: mac, b: b]
            else if (known[mac]) setUp << [mac: mac, b: b, dev: known[mac]]
            else newOnes << [mac: mac, b: b]
        }

        if (state.ipChanges) {
            section("IP address changes from the last search") {
                state.ipChanges.each { paragraph "${it}" }
            }
        }

        if (state.lastSearch) {
            section("New dual-zone bulbs (${newOnes.size()})") {
                if (!newOnes) {
                    paragraph "None found. Every dual-zone bulb that answered already has a device."
                } else {
                    paragraph "Name each bulb (its zones become \"&lt;name&gt; Main\" and \"&lt;name&gt; Ring\"). " +
                            "Use <b>Flash</b> to blink a bulb so you can tell which one it is."
                    newOnes.sort { ipSortKey(it.b.ip as String) }.each { Map n ->
                        String mac = n.mac
                        input name: "add_${mac}", type: "bool", title: "Create a device for ${n.b.ip} (${prettyMac(mac)})",
                              defaultValue: true, width: 6
                        input name: "flash_${mac}", type: "button", title: "Flash ${n.b.ip}", width: 6
                        input name: "label_${mac}", type: "text", title: "Name", defaultValue: defaultLabel(mac), width: 12
                    }
                    input name: "btnCreate", type: "button", title: "Create selected devices"
                }
            }
            section("Already set up (${setUp.size()})", hideable: true, hidden: true) {
                if (!setUp) paragraph "None."
                setUp.sort { ipSortKey(it.b.ip as String) }.each { Map s ->
                    paragraph "${s.dev.displayName}: ${s.b.ip} (${prettyMac(s.mac)})"
                }
            }
            if (other) {
                section("Other WiZ devices (not dual-zone color bulbs, ignored)", hideable: true, hidden: true) {
                    other.sort { ipSortKey(it.b.ip as String) }.each { Map o ->
                        paragraph "${o.b.ip} (${prettyMac(o.mac)}): ${o.b.model ?: 'model unknown - it did not answer'}"
                    }
                }
            }
            section {
                paragraph "<small>Last search: ${new Date((state.lastSearch) as long).format('yyyy-MM-dd HH:mm', location.timeZone)}, " +
                        "${found.size()} WiZ device(s) answered.</small>"
            }
        }

        section("Bulb devices you created by hand (optional)", hideable: true, hidden: !manualBulbs) {
            paragraph "Pick bulb devices that weren't created by this app so searches also keep their IP addresses up to date."
            input name: "manualBulbs", type: "device.WiZDualZoneBulb", title: "Bulb devices", multiple: true, required: false,
                  submitOnChange: true
        }
        section("Options") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void updated() { initialize() }

void initialize() {
    if (logEnable) runIn(1800, "logsOff")
}

void uninstalled() {
    // Bulb devices are this app's children, so Hubitat removes them with the app.
}

void logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

void appButtonHandler(String btn) {
    if (btn == "btnSearch") startSearch()
    else if (btn == "btnCreate") createSelected()
    else if (btn.startsWith("flash_")) flash(btn.substring(6))
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

void startSearch() {
    if (app.getInstallationState() != "COMPLETE") return
    if (state.searching && now() < ((state.searchEnds ?: 0) as long)) return
    FOUND.clear()
    PROGRESS.put("n", 0)
    state.searching = true
    state.searchEnds = now() + (Math.ceil(254 / BATCH) as long) * BATCH_MS + 2000L
    state.remove("ipChanges")
    String hubIp = location.hub.localIP
    state.scanPrefix = hubIp.substring(0, hubIp.lastIndexOf(".") + 1)
    log.info "WiZ discovery: searching ${state.scanPrefix}1-254"
    scanChunk([start: 1])
}

// Ask the next few addresses on the hub's /24 for their system config.
void scanChunk(Map data) {
    int start = data.start as int
    int end = Math.min(254, start + BATCH - 1)
    String prefix = state.scanPrefix
    (start..end).each { int i ->
        String ip = "${prefix}${i}"
        if (ip != location.hub.localIP) send(ip, [method: "getSystemConfig", params: [:]], 1)
    }
    PROGRESS.put("n", end)
    if (end < 254) runInMillis(BATCH_MS, "scanChunk", [data: [start: end + 1]])
    else runInMillis(1500, "saveSearch")
}

void saveSearch() {
    Map found = [:]
    FOUND.each { k, v -> found[k] = [ip: v.ip, model: v.model, fw: v.fw] }
    state.found = found
    state.lastSearch = now()
    state.searching = false
    int dual = found.count { k, v -> isDualZone(v.model as String) }
    log.info "WiZ discovery: ${found.size()} WiZ device(s) answered, ${dual} dual-zone"
    applyIpChanges(found)
    // Drop name/checkbox entries for bulbs that already have a device.
    knownBulbs().keySet().each { String mac ->
        app.removeSetting("add_${mac}")
        app.removeSetting("label_${mac}")
        app.removeSetting("flash_${mac}")
    }
}

void discoveryReply(response) {
    String description = response instanceof String ? response : response?.description
    def lan = description ? parseLanMessage(description) : null
    if (!lan?.payload) return
    Map resp
    try {
        resp = new JsonSlurper().parseText(new String(hubitat.helper.HexUtils.hexStringToByteArray(lan.payload), "UTF-8")) as Map
    } catch (e) {
        return
    }
    String ip = lan.ip ? hexToIp(lan.ip as String) : null
    Map result = resp?.result instanceof Map ? resp.result as Map : null
    String mac = result?.mac ? normalizeMac(result.mac as String) : null
    if (logEnable) log.debug "WiZ discovery: << ${ip}: ${JsonOutput.toJson(resp)}"
    if (!ip || !mac) return
    Map b = FOUND.computeIfAbsent(mac) { new ConcurrentHashMap([ip: ip]) }
    b.ip = ip
    if (result.moduleName) b.model = result.moduleName
    if (result.fwVersion) b.fw = result.fwVersion
}

private void send(String ip, Map msg, int timeoutSec = 3) {
    String json = JsonOutput.toJson(msg)
    if (logEnable) log.debug "WiZ discovery: >> ${ip}: ${json}"
    String hex = hubitat.helper.HexUtils.byteArrayToHexString(json.getBytes("UTF-8"))
    sendHubCommand(new hubitat.device.HubAction(hex, hubitat.device.Protocol.LAN, [
            type              : hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${ip}:${WIZ_PORT}",
            encoding          : hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreResponse    : false,
            parseWarning      : true,
            timeout           : timeoutSec,
            callback          : "discoveryReply"
    ]))
}

// ---------------------------------------------------------------------------
// Matching and device creation
// ---------------------------------------------------------------------------

// Bulb devices this app knows about, by MAC.
private Map knownBulbs() {
    Map byMac = [:]
    (getChildDevices() + (manualBulbs ?: [])).each { d ->
        String mac = d.getDataValue("mac")
        if (mac) byMac[normalizeMac(mac)] = d
    }
    return byMac
}

private void applyIpChanges(Map found) {
    List changes = []
    knownBulbs().each { String mac, d ->
        Map b = found[mac] as Map
        if (!b?.ip) return
        String oldIp = d.getDataValue("ip")
        if (oldIp != b.ip) {
            log.info "WiZ discovery: ${d.displayName} moved from ${oldIp} to ${b.ip}"
            changes << "${d.displayName}: ${oldIp ?: 'no address'} → ${b.ip}".toString()
            d.setIpAddress(b.ip as String)
        }
    }
    state.ipChanges = changes
}

void createSelected() {
    Map found = (state.found ?: [:]) as Map
    Map known = knownBulbs()
    found.each { String mac, Map b ->
        if (!isDualZone(b.model as String) || known[mac]) return
        if (settings["add_${mac}"] == false) return
        String label = (settings["label_${mac}"] ?: defaultLabel(mac)).toString().trim()
        String dni = "wiz-${mac}"
        def dev = getChildDevice(dni)
        if (!dev) {
            dev = addChildDevice(NAMESPACE, BULB_DRIVER, dni, [name: BULB_DRIVER, label: label, isComponent: false])
            log.info "WiZ discovery: created ${label} for ${b.ip}"
        }
        dev.updateDataValue("mac", mac)
        dev.setIpAddress(b.ip as String)
        app.removeSetting("add_${mac}")
        app.removeSetting("label_${mac}")
    }
}

// Blink a bulb (both zones) three times. It ends up on.
void flash(String mac) {
    Map b = ((state.found ?: [:]) as Map)[mac] as Map
    if (!b?.ip) return
    state.flashIp = b.ip
    flashStep([n: 0])
}

void flashStep(Map data) {
    int n = data.n as int
    if (n >= 6) return
    send(state.flashIp as String, [method: "setPilot", params: [state: (n % 2 == 1)]])
    runInMillis(600, "flashStep", [data: [n: n + 1], overwrite: false])
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private static boolean isDualZone(String module) {
    // e.g. ESP20_DHRGB_01: "DH" = dual head, RGB = full color.
    module && module.contains("DH") && module.contains("RGB")
}

private static String normalizeMac(String mac) { mac.replaceAll("[^0-9A-Fa-f]", "").toLowerCase() }

private static String prettyMac(String mac) { mac.toUpperCase().replaceAll("(..)(?!\$)", "\$1:") }

private static String defaultLabel(String mac) { "WiZ Bulb ${mac.toUpperCase().substring(Math.max(0, mac.length() - 6))}" }

private static String hexToIp(String hex) {
    if (hex.contains(".")) return hex
    (0..3).collect { Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16) }.join(".")
}

private static long ipSortKey(String ip) {
    ip ? ip.tokenize(".").inject(0L) { long acc, String p -> acc * 256 + (p as long) } : 0L
}
