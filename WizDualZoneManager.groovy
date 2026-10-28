/**
 *  WiZ Dual Zone Manager (app)
 *
 *  Creates one "WiZ Dual Zone Bulb" device per line in a list of
 *  "IP address, Name" entries. Each bulb device then creates its own
 *  two zone lights.
 */

definition(
        name: "WiZ Dual Zone Manager",
        namespace: "youzer-name",
        author: "youzer-name",
        description: "Create WiZ dual-zone bulb devices from a list of IP addresses",
        category: "Lighting",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "WiZ Dual Zone Manager", install: true, uninstall: true) {
        section("Bulbs") {
            paragraph "One bulb per line: <b>IP address, Name</b>  (e.g. <code>192.168.2.53, Family Room Bulb 3</code>). " +
                    "Give each bulb a fixed IP (DHCP reservation) in your router."
            input name: "bulbList", type: "textarea", title: "Bulb list", required: false, rows: 12
            input name: "removeMissing", type: "bool", title: "Delete bulb devices that are no longer in the list",
                  defaultValue: false, submitOnChange: true
            if (removeMissing) {
                paragraph "<b>Warning:</b> removing a line will delete that bulb and its two zone devices when you click Done."
            }
        }
        section("Defaults for new bulbs") {
            input name: "pollSeconds", type: "enum", title: "Poll bulb state every",
                  options: ["0": "Never", "30": "30 seconds", "60": "1 minute", "120": "2 minutes",
                            "300": "5 minutes", "900": "15 minutes"], defaultValue: "60"
        }
        List kids = getChildDevices()
        if (kids) {
            section("Installed bulbs (${kids.size()})") {
                kids.sort { it.displayName }.each { d ->
                    paragraph "<a href='/device/edit/${d.id}' target='_blank'>${d.displayName}</a> - " +
                            "${d.getDataValue('ip') ?: '?'} - ${d.currentValue('healthStatus') ?: 'unknown'}"
                }
            }
        }
    }
}

void installed() { syncDevices() }

void updated() { syncDevices() }

void uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private List<Map> parseList() {
    List<Map> out = []
    (bulbList ?: "").readLines().each { String line ->
        line = line.trim()
        if (!line || line.startsWith("#")) return
        List parts = line.split(/\s*,\s*/, 2) as List
        String ip = parts[0].trim()
        if (!(ip ==~ /\d{1,3}(\.\d{1,3}){3}/)) {
            log.warn "WiZ Dual Zone Manager: skipping line, not an IP address: ${line}"
            return
        }
        String name = parts.size() > 1 && parts[1]?.trim() ? parts[1].trim() : "WiZ Dual Zone ${ip}"
        out << [ip: ip, name: name]
    }
    return out
}

private String dniFor(String ip) { "WIZDZ-${ip.replace('.', '-')}" }

private void syncDevices() {
    List<Map> wanted = parseList()
    Set<String> wantedDnis = wanted.collect { dniFor(it.ip) } as Set

    wanted.eachWithIndex { Map b, int i ->
        String dni = dniFor(b.ip)
        if (getChildDevice(dni)) return
        try {
            def d = addChildDevice("youzer-name", "WiZ Dual Zone Bulb", dni,
                    [name: "WiZ Dual Zone Bulb", label: b.name, isComponent: false])
            d.updateSetting("ipAddress", [value: b.ip, type: "text"])
            d.updateSetting("pollSeconds", [value: pollSeconds ?: "60", type: "enum"])
            log.info "WiZ Dual Zone Manager: created ${b.name} (${b.ip})"
            // Stagger start-up so 20+ bulbs don't all initialize at once.
            runIn(2 + i, "initBulb", [data: [dni: dni], overwrite: false])
        } catch (e) {
            log.error "WiZ Dual Zone Manager: could not create ${b.name} (${b.ip}): ${e}"
        }
    }

    if (removeMissing) {
        getChildDevices().findAll { !(it.deviceNetworkId in wantedDnis) }.each {
            log.info "WiZ Dual Zone Manager: deleting ${it.displayName}"
            deleteChildDevice(it.deviceNetworkId)
        }
    }
}

void initBulb(Map data) {
    getChildDevice(data.dni)?.initialize()
}
