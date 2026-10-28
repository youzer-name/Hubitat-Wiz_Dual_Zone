# WiZ Dual Zone for Hubitat

> **Draft.** Tested on a Hubitat C7 (firmware 2.5.2) with 22 WiZ dual-zone bulbs
> (module `ESP20_DHRGB_01`, firmware 1.38–1.39).

Local control of **WiZ dual-zone bulbs** (bulbs with a separately controllable main
light and outer "ring"/halo) from Hubitat, with each zone as its own full color light,
plus fast **groups** for switching many lights at once.

## Why this exists

The built-in Hubitat WiZ integration can control the ring zone, but it's slow and
sometimes misses commands when several lights are controlled together. This
package talks to the bulbs the same way the
[ha-wiz-dualzone](https://github.com/chadkuester/ha-wiz-dualzone) Home Assistant
integration (and the `pywizlight` library it uses) does:

- **Local only.** Plain UDP on your LAN (port 38899); no cloud, no WiZ account.
- **Two zone devices per bulb.** Main and Ring, each with on/off, dimming, color,
  color temperature, and the built-in WiZ effects (with a dropdown and adjustable speed).
- **Reliable delivery.** Each bulb gets one message at a time, every message is
  confirmed by the bulb, and unconfirmed ones are resent quickly.
- **Fast groups.** Hubitat apps like Room Lighting command lights one after another
  (about 60 ms apart on a C7, so a room "ripples" on). A WiZ group sends to every
  bulb in one quick loop (9 bulbs in about 60 ms total), close to how Home Assistant
  behaves.

## What's in the package

| File | Type | Purpose |
|---|---|---|
| `WizDualZoneBulb.groovy` | Driver | One device per bulb. Talks to the bulb and creates its two zone devices. |
| `WizDualZoneLight.groovy` | Driver | A zone (Main or Ring). Created automatically by the bulb device. |
| `WizDualZoneGroupApp.groovy` | App | One instance per group. Sends to all member zones at once. |
| `WizDualZoneGroupDevice.groovy` | Driver | The light device each group app creates. |
| `WizDualZoneDiscovery.groovy` | App | Finds the bulbs on your network and creates their devices. Keeps their IP addresses up to date. |
| `packageManifest.json` | HPM manifest | Lets Hubitat Package Manager install and update the package. |

## Before you start

1. **Give every bulb a fixed IP address.** Add a DHCP reservation for each bulb in
   your router. The drivers find bulbs by IP; if a bulb's IP changes, it shows
   *offline* until you update it.
2. **Make a list of bulb IPs and names.** Your router's DHCP list, the WiZ app
   (device settings), or an existing Hubitat WiZ device's name ("Wiz ... on
   192.168.x.x") will show them.
3. **The hub must be on the same network/subnet as the bulbs.** UDP replies don't
   cross most routers.

## Setup

### Step 1: Install the code

**Option A: Hubitat Package Manager (HPM)**

1. In HPM, choose **Install → From a URL**.
2. Enter
   `https://raw.githubusercontent.com/youzer-name/Hubitat-Wiz_Dual_Zone/main/packageManifest.json`
   and follow the prompts.

HPM installs all three drivers and both apps, and offers updates when a new
version is released.

**Option B: By hand**

In the Hubitat web UI:

1. **Drivers Code → New Driver**, paste `WizDualZoneLight.groovy`, **Save**.
2. Repeat for `WizDualZoneBulb.groovy` and `WizDualZoneGroupDevice.groovy`.
3. **Apps Code → New App**, paste `WizDualZoneGroupApp.groovy`, **Save**.
4. Repeat for `WizDualZoneDiscovery.groovy`.

Install the zone driver (`WizDualZoneLight`) before you create any bulb devices.

### Step 2: Add the bulbs

**Option A: Discovery (recommended)**

1. **Apps → Add User App → WiZ Dual Zone Discovery**.
2. Click **Search for bulbs**. The app checks every address on the hub's subnet,
   which takes about 40 seconds.
3. Under **New dual-zone bulbs**, name each bulb. **Flash** blinks a bulb so you
   can tell which one it is.
4. Click **Create selected devices**, then **Done**.

Each bulb device is remembered by its MAC address. If a bulb gets a new IP address,
open the app and search again; its device is pointed at the new address.

Bulb devices you created by hand (Option B) can be added under **Bulb devices you
created by hand**, so searches keep their addresses up to date too.

**Option B: By hand**

For each bulb:

1. **Devices → Add Device → Virtual**.
2. Name it (e.g. `Kitchen Bulb 1`) and choose the **WiZ Dual Zone Bulb** driver.
   Click **Save Device**.
3. On the new device page, under **Preferences**, enter the bulb's **IP address**
   and click **Save Preferences**.

The bulb device then creates two zone devices, `<name> Main` and `<name> Ring`,
and reads their current state from the bulb. Its **healthStatus** should change
to `online` within a few seconds.

Optional settings on the bulb device:

| Setting | Default | Notes |
|---|---|---|
| Poll bulb state every | 1 minute | Picks up changes made in the WiZ app or by other hubs. |
| Lowest brightness % | 10 | WiZ bulbs report 10% as their minimum. |
| Zone name suffixes | Main / Ring | Used only when the zones are first created; after that, just rename the zone devices. |
| Re-read state after each command | Off | Extra traffic; the bulb's confirmation is normally enough. |
| Debug logging | Off | Turns itself off after 30 minutes. |

### Step 3: Create groups (recommended)

For each set of lights you usually control together (a room, or "all rings in the
kitchen"):

1. **Apps → Add User App → WiZ Dual Zone Group**.
2. Enter a **group name**. This also becomes the group device's name.
3. Select the **zone lights** for the group, then click **Done**.

A light device with the group's name appears. Use it in Room Lighting, dashboards,
and rules **in place of the individual zones**.

- If both zones of a bulb are in the same group, they share a single message.
- Member zone devices update as each bulb confirms the change.
- The group shows **on** whenever any member is on. Its brightness is the average of
  the members that are on. Its color, color temperature and effect follow the members
  when all the ones that are on match; otherwise it keeps the last value it had.

### Step 4 (optional): Share with another hub

To control the lights from a second hub (e.g. a C8 Pro), share the **group
devices** with **Hub Mesh**. The other hub needs no code from this package.

Keep the bulbs, groups, and any Room Lighting instances that use them on the hub
the bulbs are installed on. If the other hub commands many individually shared
zones, each one makes its own trip across the network, which brings the ripple back.

### Step 5: Retire the old WiZ devices

When your rules use the new devices, disable or remove the bulbs' old devices from
the built-in WiZ integration, so two integrations aren't polling the same bulbs.

## Using the lights

Every zone and group device supports:

- **On/off, Set Level, Start/Stop Level Change**
- **Set Color** (hue/saturation) and **Set Color Temperature** (2200–6500 K on
  tested bulbs)
- **Effects:** *Set Effect* (by number), *Set Effect By Name* (dropdown), *Next/Previous
  Effect*, *Set Effect Speed* (10 slow to 200 fast). The effect list is in the
  `lightEffects` attribute.

Zone devices also have **Set RGBW** for raw channel values (0–255 each).

The bulb device has **on/off for both zones together**, **Refresh**, and a
**healthStatus** attribute (`online`/`offline`).

## Limits

- **No instant updates from outside Hubitat.** Hubitat can't receive the bulbs'
  push messages, so changes made in the WiZ app or by a wall switch appear at the
  next poll.
- **Transition times are ignored.** WiZ's local protocol has no fade/duration setting.
- **"Rhythm" and WiZ custom modes** aren't in the effect list.
- After a group command, a **bulb device's own on/off status** (not the zone devices')
  updates at its next poll.

## Troubleshooting

- **Bulb shows offline:** check that the IP is correct and fixed, and that the bulb has
  power (a wall switch turned off cuts it). Click **Refresh** on the bulb device.
- **A bulb's first commands after setup are slow:** this has been seen on a bulb's
  first contact with the hub. The driver retries for up to about 13 seconds, and it
  settles down after that.
- **To see what's being sent:** turn on **debug logging** on the bulb device or the
  group app. Messages to the bulb are logged with `>>`, replies with `<<`. The group
  app logs how long it took to send to all bulbs and how long each one took to confirm.

## Protocol notes

From `pywizlight` 0.6.5 / ha-wiz-dualzone, confirmed on these bulbs:

- Commands: `{"method":"setPilot","params":{"devices":N,...}}` with **1-based** zones
  (1 = main, 2 = ring). With no `devices` key, the command applies to both zones.
- Status reads: `{"method":"getPilot","params":{"devices":N}}` with **0-based** zones
  (0 = main, 1 = ring).
- Effect speed must be sent on its own, without `state` in the same message.
- Hubitat UDP payloads must be sent as hex (`HubAction.Encoding.HEX_STRING`).
- Hubitat apps can call only a device's *declared* commands on devices they don't
  own, which is why zone devices have an internal `applyGroupState` command.
