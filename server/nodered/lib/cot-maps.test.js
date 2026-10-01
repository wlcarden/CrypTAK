// Offline tests for cot-maps.js. Run: node cot-maps.test.js   (no dependencies)
"use strict";
const assert = require("assert");
const maps = require("./cot-maps.js");

const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const start = iso(now - 5000), stale = iso(now + 300000), year = iso(now + 365 * 86400000);
const HOUR = 3600000, DAY = 86400000;

function selfSA(callsign = "TAK-01") {
  return `<event version="2.0" uid="ANDROID-dd43d5a96c02f44b" type="a-f-G-U-C" how="m-g" time="${start}" start="${start}" stale="${stale}"><point lat="38.84" lon="-77.27" hae="60" ce="9" le="9"/><detail><takv os="36" version="5.5.1.8" device="GOOGLE PIXEL 6" platform="ATAK-CIV"/><status battery="87"/><contact endpoint="*:-1:stcp" callsign="${callsign}"/><__group role="Team Member" name="Cyan"/></detail></event>`;
}
// Real ATAK placed-marker shape: <link parent_callsign> BEFORE <contact>, archive, creator, re-sent with fresh time/start.
function placed({ uid = "b5655c73-ac54-4f40-95f6-fd37777c6a73", type = "a-h-G-U-C-I", callsign = "Skinwalker", producedAgoMs = 3 * HOUR, remarks = "" } = {}) {
  const prod = iso(now - producedAgoMs);
  return `<event version="2.0" uid="${uid}" type="${type}" how="h-g-i-g-o" time="${start}" start="${start}" stale="${year}"><point lat="38.8411" lon="-77.2737" hae="92" ce="9999999" le="9999999"/><detail><status readiness="true"/><archive/><usericon iconsetpath="COT_MAPPING_2525C/a-h/${type}"/><contact callsign="${callsign}"/><link uid="ANDROID-dd43d5a96c02f44b" production_time="${prod}" type="a-f-G-U-C" parent_callsign="TAK-01" relation="p-p"/><creator uid="ANDROID-dd43d5a96c02f44b" callsign="TAK-01" time="${prod}" type="a-f-G-U-C"/><remarks>${remarks}</remarks><color argb="-1"/></detail></event>`;
}
const meshPli = `<event version="2.0" uid="mesh-087a29a4" type="a-f-G-E-X" how="m-g" time="${start}" start="${start}" stale="${stale}"><point lat="38.84" lon="-77.29" hae="0" ce="9" le="9"/><detail><contact callsign="CrypTAK-GW01" endpoint="0.0.0.0:4242:tcp"/><takv platform="CrypTAK MeshRelay" device="Meshtastic" os="1" version="1.0"/><status battery="101"/></detail></event>`;

let passed = 0;
function test(name, fn) { fn(); passed++; console.log("ok   " + name); }

test("identity is the uid; callsign is the title; placed marker is not mis-titled by parent_callsign", () => {
  const a = maps.parseCotToMarker(selfSA()), b = maps.parseCotToMarker(placed());
  assert.strictEqual(a.name, "ANDROID-dd43d5a96c02f44b"); assert.strictEqual(a._callsign, "TAK-01");
  assert.strictEqual(b.name, "b5655c73-ac54-4f40-95f6-fd37777c6a73"); assert.strictEqual(b._callsign, "Skinwalker");
});
test("client detection: phone yes; placed, mesh PLI no", () => {
  assert.strictEqual(maps.parseCotToMarker(selfSA())._client, true);
  assert.strictEqual(maps.parseCotToMarker(placed())._client, false);
  assert.strictEqual(maps.parseCotToMarker(meshPli)._client, false);
});
test("placed markers are managed; live reports are not", () => {
  assert.strictEqual(maps.parseCotToMarker(placed())._managed, true);
  assert.strictEqual(maps.parseCotToMarker(selfSA())._managed, false);
  assert.strictEqual(maps.parseCotToMarker(meshPli)._managed, false);
});
test("age comes from production_time, not from the re-sent start", () => {
  const m = maps.parseCotToMarker(placed({ producedAgoMs: 5 * HOUR }));
  assert.ok(Math.abs((now - m._producedMs) - 5 * HOUR) < 5000);
  assert.strictEqual(m._startMs, m._producedMs);
});
test("retention: hostile/suspect/unknown contacts 12 h; friendly/neutral places 7 d", () => {
  assert.strictEqual(maps.parseCotToMarker(placed({ type: "a-h-G-U-C-I" }))._retentionMs, 12 * HOUR);
  assert.strictEqual(maps.parseCotToMarker(placed({ type: "a-s-G-U-C-I" }))._retentionMs, 12 * HOUR);
  assert.strictEqual(maps.parseCotToMarker(placed({ type: "a-u-G-U-C-I" }))._retentionMs, 12 * HOUR);
  assert.strictEqual(maps.parseCotToMarker(placed({ type: "a-n-G-U-C-I" }))._retentionMs, 7 * DAY);
  assert.strictEqual(maps.parseCotToMarker(placed({ type: "a-f-G-E-X" }))._retentionMs, 7 * DAY);
});
test("remarks overrides: #keep never retires; #exp sets a custom window", () => {
  const keep = maps.parseCotToMarker(placed({ remarks: "fuel point #keep" }));
  assert.strictEqual(keep._retentionMs, 0); assert.strictEqual(keep._retireAtMs, 0);
  assert.strictEqual(maps.parseCotToMarker(placed({ remarks: "#exp 6h" }))._retentionMs, 6 * HOUR);
  assert.strictEqual(maps.parseCotToMarker(placed({ remarks: "watch #exp 2d" }))._retentionMs, 2 * DAY);
  assert.strictEqual(maps.parseCotToMarker(placed({ remarks: "#exp 90m" }))._retentionMs, 90 * 60000);
  assert.strictEqual(maps.retentionFor("h", "nothing special"), 12 * HOUR);
});
test("retire opacity: solid for 75% of retention, fades to the floor, never 0", () => {
  const p = 0, r = 12 * HOUR;
  assert.strictEqual(maps.calcRetireOpacity(p, r, 8 * HOUR), 1.0);
  assert.ok(maps.calcRetireOpacity(p, r, 10.5 * HOUR) < 1.0 && maps.calcRetireOpacity(p, r, 10.5 * HOUR) > 0.2);
  assert.strictEqual(maps.calcRetireOpacity(p, r, 12 * HOUR), maps.RETENTION_POLICY.fadeFloor);
  assert.strictEqual(maps.calcRetireOpacity(p, 0, 99 * HOUR), 1.0); // #keep
});
test("refreshMarkerColors retires managed markers past retention and reports them separately", () => {
  const fresh = maps.parseCotToMarker(placed({ uid: "fresh", producedAgoMs: 1 * HOUR }));
  const old = maps.parseCotToMarker(placed({ uid: "old", producedAgoMs: 13 * HOUR }));
  const kept = maps.parseCotToMarker(placed({ uid: "kept", producedAgoMs: 40 * DAY, remarks: "#keep" }));
  const r = maps.refreshMarkerColors({ fresh, old, kept });
  assert.deepStrictEqual(r.retired, ["old"]);
  assert.ok(!r.expired.includes("kept") && !r.retired.includes("kept"));
});
test("parseCotDelete reads the target uid from <link>; other types return null", () => {
  const del = `<event version="2.0" uid="x1" type="t-x-d-d" how="h-g-i-g-o" time="${start}" start="${start}" stale="${stale}"><point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/><detail><link uid="b5655c73-ac54-4f40-95f6-fd37777c6a73" relation="none" type="a-h-G-U-C-I"/><__forcedelete/></detail></event>`;
  assert.deepStrictEqual(maps.parseCotDelete(del), { name: "b5655c73-ac54-4f40-95f6-fd37777c6a73", deleted: true, _delete: true });
  assert.strictEqual(maps.parseCotDelete(selfSA()), null);
  assert.strictEqual(maps.parseCotToMarker(del), null);
});
test("buildDeleteCot produces a t-x-d-d that parseCotDelete round-trips, filtered from the map by uid prefix", () => {
  const xml = maps.buildDeleteCot("abc-123", "a-h-G-U-C-I", "retired");
  assert.ok(xml.includes('type="t-x-d-d"') && xml.includes("<__forcedelete/>") && xml.endsWith("\n"));
  assert.strictEqual(maps.parseCotDelete(xml).name, "abc-123");
  assert.ok(/uid="CrypTAK-NR-del-/.test(xml));
});
test("popup carries the lifecycle line and a Remove link for managed markers only", () => {
  assert.ok(maps.parseCotToMarker(placed()).popup.includes("Remove from map"));
  assert.ok(!maps.parseCotToMarker(selfSA()).popup.includes("Remove from map"));
});
console.log(`\n${passed} passed`);
