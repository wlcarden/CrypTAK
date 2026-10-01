# CrypTAK Mesh Monitor — Node-RED Flow Setup

**Flow deployed to Node-RED on Unraid.** Now you need 2 quick configuration steps:

## 1. Wire the MQTT Tap to Mesh Packets

The monitor reads from the live `meshRegistry` (maintained by the WebMap tab), but it also tracks packet volume. To do this, you need to feed MQTT packets to the "MQTT Packet Counter" node.

**In Node-RED UI:**
1. Go to **Mesh Monitor** tab
2. Find the **"MQTT Mesh Packets"** link-in node (left side, around y=280)
3. Click it, then in the right panel, **Links**, connect to the **MQTT in** node from the **CrypTAK WebMap** tab
   - Click "Create new link"
   - Select **"Meshtastic JSON Topics"** (mqtt_mesh from WebMap tab)
4. Deploy

Or via Node-RED editor JSON (easier):
```
In the "MQTT Packet Counter" function node, modify its wiring to receive mqtt_mesh output directly.
```

## 2. Add Discord Webhook URL

The monitor sends alerts to Discord when nodes go stale.

**Get your Discord webhook:**
1. In Discord, go to #hardware channel → Settings → Integrations → Webhooks
2. Create a new webhook (or use existing) — copy the URL

**In Node-RED UI:**
1. Go to **Mesh Monitor** tab
2. Find the **"Discord Webhook"** HTTP request node (right side, around y=200)
3. Double-click it
4. In the **URL** field, paste your webhook URL
5. Deploy

**OR** set via API:
```bash
TOKEN=$(curl -s -X POST http://localhost:1880/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=node-red-admin&grant_type=password&scope=*&username=admin&password=Fi!!" | jq -r '.access_token')

curl -s -X PUT http://localhost:1880/flows/nodes/meshmon_discord_webhook \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "YOUR_DISCORD_WEBHOOK_URL_HERE",
    "method": "POST",
    "ret": "txt"
  }'
```

## 3. Test It

**Manual check button in Node-RED:**
1. Go to **Mesh Monitor** tab
2. Click the **"Get Status"** inject button
3. Look at the debug panel on the right — you'll see the full status report

**Or trigger a check:**
```bash
ssh unraid 'curl -s -X POST http://localhost:1880/inject/meshmon_inject_manual \
  -H "Authorization: Bearer <TOKEN>"'
```

## 4. How It Works

**Every 30 minutes:**
1. Reads `flow.get('meshRegistry')` — maintained live by the WebMap tab via MQTT
2. Checks `lastHeard` timestamp for each monitored node
3. Compares against configurable thresholds (staleWarn, staleCrit)
4. Generates alerts on state transitions
5. Sends Discord message if alerts exist

**Zero airtime cost** — purely passive monitoring of timestamps that already arrive via MQTT.

## Node Health Thresholds

Configured in the **"Mesh Health Check"** function:

```javascript
var MONITORED = {
    '!a51e2838': { name: 'CrypTAK Base',  type: 'own', staleWarn: 300,  staleCrit: 1800  },
    '!dce7b97d': { name: 'CrypTAK-TRK01', type: 'own', staleWarn: 600,  staleCrit: 3600  },
    // ... etc
}
```

- **staleWarn:** Turn 🟡 (yellow) if not heard for N seconds
- **staleCrit:** Turn 🔴 (red) and alert if not heard for N seconds
- Adjust these per-node based on expected duty cycles

## Status Display

**Debug output:** Shows real-time node status with icon codes:
- 🟢 **OK** — heard within staleWarn
- 🟡 **WARN** — heard > staleWarn but < staleCrit
- 🔴 **STALE** — not heard for > staleCrit
- ⚪ **UNKNOWN** — never heard

Example:
```
🟢 CrypTAK Base         3 secs ago   SNR: 4.3dB  hops: 1
🟡 CrypTAK-SOL01       1 day ago    SNR:   N/A  hops: ?
🔴 Tracker Alpha       2 days ago   SNR:   N/A  hops: ?
```

## Troubleshooting

**No status data:** Make sure WebMap tab is running and MQTT is connected. Check the debug tab.

**Alerts not sending:** 
1. Verify Discord webhook URL is in the HTTP node
2. Check Node-RED debug panel for HTTP request errors

**Thresholds too aggressive:** Edit the MONITORED config in the function node and adjust times.

## Links

- Node-RED UI: http://unraid:1880
- Admin: admin / Fi!!
- Meshtastic MQTT broker: mosquitto:1883
- WebMap tab: Maintains meshRegistry from MQTT
