// CoT type → display mapping tables and parsing utilities for CrypTAK WebMap
// Loaded into Node-RED via functionGlobalContext in settings.js
// Referenced in fn_cot as: var maps = global.get('cotMaps');

// MIL-STD-2525 affiliation code → marker color
var colorMap = {
  h: "#FF0000", // hostile
  s: "#FF8C00", // suspect
  n: "#00AA00", // neutral
  u: "#CCCC00", // unknown
  f: "#0066FF", // friendly
  a: "#6699CC", // assumed friendly
  p: "#888888", // pending
  j: "#FF0000", // joker (hostile)
  k: "#FF0000", // faker (hostile)
  o: "white", // none
};

// CoT type suffix → Font Awesome icon class
// Matched longest-first from parts[2..n] of the CoT type string
var iconMap = {
  "G-U-C-V": "fa-exclamation-circle",
  "G-I-i-l": "fa-exclamation-triangle",
  "G-O-E": "fa-crosshairs",
  "G-I-R": "fa-eye",
  "G-I-i-h": "fa-plus-square",
  "G-I-i-f": "fa-fire",
  "G-E-S": "fa-rss", // ground equipment sensor (mesh nodes) — FA 4.7
  "G-E-N": "fa-bolt",
  "G-I-i-d": "fa-car",
  "G-O-S": "fa-search",
  "G-I-i-c": "fa-bullhorn",
  "G-U-C": "fa-question-circle",
  A: "fa-plane",
  S: "fa-ship",
};

// MIL-STD-2525 affiliation code → worldmap layer name
var affiliationNames = {
  h: "TAK Hostile",
  s: "TAK Suspect",
  n: "TAK Neutral",
  u: "TAK Unknown",
  f: "TAK Friendly",
  a: "TAK Friendly",
  p: "TAK Unknown",
  j: "TAK Hostile",
  k: "TAK Hostile",
  o: "TAK Other",
};

// CoT affiliation code → SIDC affiliation character (MIL-STD-2525B)
var sidcAffiliation = {
  f: "F",
  a: "A",
  h: "H",
  s: "S",
  n: "N",
  u: "U",
  p: "P",
  j: "J",
  k: "K",
  o: "O",
};

/**
 * Convert CoT atom type parts to a 15-character MIL-STD-2525B SIDC code.
 * CoT type hierarchy maps directly to SIDC function ID characters by design:
 *   a-f-G-U-C-I → SFGPUCI--------  (friendly ground unit, combat, infantry)
 *   a-h-G-I-R   → SHGPIR---------  (hostile ground installation, recon)
 *   a-f-A        → SFAP-----------  (friendly air)
 *   a-f-G-E-S   → SFGPES---------  (friendly ground equipment sensor)
 *
 * @param {string[]} parts - CoT type split by '-' (e.g. ['a','f','G','U','C','I'])
 * @returns {string|null} 15-char SIDC code, or null if conversion not possible
 */
function cotTypeToSIDC(parts) {
  if (!parts || parts.length < 3 || parts[0] !== "a") return null;

  var affil = sidcAffiliation[parts[1]] || "U";

  // Map CoT dimension to SIDC battle dimension
  // Valid SIDC: P(space) A(air) G(ground) S(sea) U(subsurface) F(SOF)
  var dim = parts[2];
  if ("PAGSUF".indexOf(dim) === -1) dim = "G";

  // Function ID: parts[3..n] concatenated, padded to 6 chars with dashes
  var funcId = parts.slice(3).join("");
  while (funcId.length < 6) funcId += "-";
  funcId = funcId.substring(0, 6);

  // 15-char SIDC: scheme(S) + affiliation + dimension + status(P=present)
  //               + functionId(6) + size/mod(2) + country(2) + OB(1)
  return "S" + affil + dim + "P" + funcId + "-----";
}

/**
 * Return worldmap line style (color + weight) for a mesh link based on SNR.
 * SNR thresholds match Meshtastic signal quality guidance:
 *   >= 5 dB  → strong (green)
 *   >= 0 dB  → good (yellow-green)
 *   >= -7 dB → marginal (orange)
 *   <  -7 dB → weak (red)
 * @param {number} snr - signal-to-noise ratio in dB
 * @returns {{ color: string, weight: number }}
 */
function snrToLinkStyle(snr) {
  if (snr >= 5) return { color: "#00DD00", weight: 3 };
  if (snr >= 0) return { color: "#AADD00", weight: 2 };
  if (snr >= -7) return { color: "#FFAA00", weight: 2 };
  return { color: "#FF4444", weight: 1 };
}

/**
 * Interpolate a hex color toward dark gray using a power curve.
 * Markers stay vivid for most of their lifespan, then drop off sharply.
 * factor=1 returns the original color, factor=0 returns #444444.
 * @param {string} hex - CSS hex color (e.g. "#FF0000")
 * @param {number} factor - 0 (fully faded) to 1 (fully saturated)
 * @returns {string} blended hex color
 */
function fadeColor(hex, factor) {
  if (factor >= 1) return hex;
  if (factor <= 0) return "#444444";
  var f = factor * factor; // power curve: stays vivid longer, drops sharply
  var r = parseInt(hex.slice(1, 3), 16);
  var g = parseInt(hex.slice(3, 5), 16);
  var b = parseInt(hex.slice(5, 7), 16);
  var gr = 0x44; // dark gray target
  r = Math.round(r * f + gr * (1 - f));
  g = Math.round(g * f + gr * (1 - f));
  b = Math.round(b * f + gr * (1 - f));
  return "#" + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}

/**
 * Look up a Font Awesome icon for a CoT type string.
 * Tries longest suffix match first, falls back to fa-question-circle.
 * @param {string[]} parts - CoT type split by '-' (e.g. ['a','h','G','I','R'])
 */
function getIcon(parts) {
  for (var len = parts.length - 2; len >= 1; len--) {
    var key = parts.slice(2, 2 + len).join("-");
    if (iconMap[key]) return iconMap[key];
  }
  return "fa-question-circle";
}

/**
 * Calculate marker opacity based on age.
 *
 * Two modes controlled by `ageBased`:
 * - ageBased=true (incident markers): fade from 1.0→0 over 1x lifespan
 *   starting from the actual published time. Combined with the power curve
 *   in fadeColor(), markers stay vivid early and darken sharply near expiry.
 * - ageBased=false (PLI/SA markers): full opacity until stale, then fade over
 *   the same duration as the lifespan. Standard CoT behavior.
 *
 * @param {string|null} startStr - ISO datetime for event start (or published time)
 * @param {string|null} staleStr - ISO datetime for event stale time
 * @param {boolean} ageBased - true for incident markers, false for PLI/SA
 * @returns {number} opacity between 0 and 1
 */
function calcOpacity(startStr, staleStr, ageBased) {
  if (!startStr || !staleStr) return 1.0;
  var startMs = new Date(startStr).getTime();
  var staleMs = new Date(staleStr).getTime();
  var nowMs = Date.now();
  if (isNaN(startMs) || isNaN(staleMs)) return 1.0;
  if (ageBased) {
    var lifespan = staleMs - startMs;
    if (lifespan <= 0) return 1.0;
    var age = nowMs - startMs;
    if (age <= 0) return 1.0;
    if (age >= lifespan) return 0;
    return Math.max(0, 1.0 - age / lifespan);
  }
  if (nowMs < staleMs) return 1.0;
  var fadeDuration = staleMs - startMs;
  if (fadeDuration <= 0) return 0;
  var fadeEnd = staleMs + fadeDuration;
  if (nowMs >= fadeEnd) return 0;
  return 1.0 - (nowMs - staleMs) / fadeDuration;
}

/**
 * Decode the XML entities ATAK emits inside text nodes such as <remarks>.
 * Callers re-escape for HTML via escHtml, so decoding here is safe.
 */
function unescapeXml(s) {
  return String(s)
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

/**
 * Extract a CoT object's OWN callsign.
 *
 * ATAK puts parent_callsign="<placing device>" on the <link> element of a
 * placed marker. A loose /callsign="/ match hits that substring first and
 * mislabels every placed marker with the sending device's callsign, so the
 * <contact> element is preferred and the fallback is \b-anchored
 * (parent_callsign cannot match: "_" is a word char, so there is no
 * boundary before "callsign").
 *
 * @param {string} xml - complete CoT XML event string
 * @param {string} fallback - value to use when no callsign is present
 */
function extractCallsign(xml, fallback) {
  var contactM = xml.match(/<contact\b[^>]*?\bcallsign="([^"]*)"/);
  if (contactM && contactM[1]) return unescapeXml(contactM[1]);
  var csM = xml.match(/\bcallsign="([^"]*)"/);
  if (csM && csM[1]) return unescapeXml(csM[1]);
  return fallback;
}

/**
 * Parse pipe-delimited remarks into structured fields.
 * Format: summary | location | source | severity | url | published_utc
 * @param {string} raw - raw remarks text from CoT XML
 */
function parseRemarks(raw, attrs) {
  var text = unescapeXml(raw || "");

  // incident-tracker tags its element <remarks source="incident-tracker"> and
  // always emits exactly 6 " | " fields (src/cot/builder.py). Anything else is
  // operator free text -- an ATAK marker comment -- which must be preserved
  // verbatim rather than split across summary/location/source/severity.
  var parts = text.split(" | ");
  var structured = /\bsource="incident-tracker"/.test(attrs || "");
  if (!structured && parts.length >= 6 && !isNaN(Date.parse(parts[5]))) {
    structured = true;
  }

  return {
    summary: structured ? parts[0] || "" : "",
    location: structured ? parts[1] || "" : "",
    source: structured ? parts[2] || "" : "",
    severity: structured ? parts[3] || "" : "",
    url: structured ? parts[4] || "" : "",
    published: structured ? parts[5] || "" : "",
    comment: structured ? "" : text,
    raw: text,
  };
}

/**
 * Retention policy for operator-placed markers.
 *
 * ATAK sends a placed marker with <archive/> ("persistent"), how="h-g-i-g-o"
 * and stale = +1 year: lifecycle is managed, not timed, and the only end is a
 * t-x-d-d delete. Left alone, every contact anyone ever dropped stays on the
 * WebMap for a year. Policy: age each placed marker from its production_time
 * (re-sends refresh time/start every ~10 s, so those cannot be used), fade it
 * over the last quarter of its retention, then retire it by broadcasting a
 * t-x-d-d so the phones agree, and tombstone the uid so a device that has not
 * yet processed the delete cannot resurrect it.
 *
 * Classes: contacts (hostile/suspect/unknown/pending/joker/faker: observations
 * of something that moves) retire after 12 h; places (friendly/neutral/assumed
 * friendly: assets and locations) after 7 d. Operators override per marker in
 * ATAK remarks: "#keep" (never auto-retire) or "#exp 6h" / "#exp 2d" / "#exp 90m".
 */
function envHours(name, fallback) {
  var v = typeof process !== "undefined" && process.env ? process.env[name] : "";
  var f = parseFloat(v);
  return isNaN(f) ? fallback : f;
}
var RETENTION_POLICY = {
  contactMs: envHours("WEBMAP_RETENTION_CONTACT_HOURS", 12) * 3600000,
  placeMs: envHours("WEBMAP_RETENTION_PLACE_HOURS", 7 * 24) * 3600000,
  tombstoneMs: envHours("WEBMAP_TOMBSTONE_HOURS", 24) * 3600000,
  fadeFrom: 0.75, // fraction of retention at which the fade begins
  fadeFloor: 0.2, // opacity at retirement -- still visible until the delete goes out
};
var CONTACT_AFFILIATIONS = { h: 1, s: 1, u: 1, p: 1, j: 1, k: 1 };

/**
 * Is this CoT an operator-placed ("persistent") object rather than a live
 * report? ATAK marks them with <archive/> and how="h-g-i-g-o".
 */
function isManagedCot(xml, how) {
  return xml.indexOf("<archive") !== -1 || (how || "").indexOf("h-g-i-g-o") === 0;
}

/**
 * When the object was created, as opposed to when this copy was sent.
 * ATAK keeps production_time on the parent <link> and <creator time> across
 * re-sends; fall back to start, then to now.
 */
function producedMs(xml, startStr) {
  var m =
    xml.match(/<link\b[^>]*\bproduction_time="([^"]+)"/) ||
    xml.match(/<creator\b[^>]*\btime="([^"]+)"/);
  var candidates = [m ? m[1] : null, startStr];
  for (var i = 0; i < candidates.length; i++) {
    if (!candidates[i]) continue;
    var t = new Date(candidates[i]).getTime();
    if (!isNaN(t)) return t;
  }
  return Date.now();
}

/**
 * Retention in ms for a placed marker: remarks override, else by affiliation.
 * Returns 0 for "keep until removed".
 */
function retentionFor(affCode, remarksText) {
  var text = remarksText || "";
  if (/(^|\s)#keep\b/i.test(text)) return 0;
  var exp = text.match(/(^|\s)#exp\s*(\d+(?:\.\d+)?)\s*([mhd])\b/i);
  if (exp) {
    var mult = { m: 60000, h: 3600000, d: 86400000 }[exp[3].toLowerCase()];
    return Math.max(60000, Math.round(parseFloat(exp[2]) * mult));
  }
  return CONTACT_AFFILIATIONS[affCode] ? RETENTION_POLICY.contactMs : RETENTION_POLICY.placeMs;
}

/**
 * Opacity for a managed marker: solid until fadeFrom of its retention, then
 * linear down to fadeFloor at retirement (never 0: it stays visible until the
 * delete is broadcast).
 */
function calcRetireOpacity(producedAt, retireAt, nowMs) {
  if (!retireAt) return 1.0;
  var span = retireAt - producedAt;
  if (span <= 0) return RETENTION_POLICY.fadeFloor;
  var fadeStart = producedAt + span * RETENTION_POLICY.fadeFrom;
  if (nowMs <= fadeStart) return 1.0;
  var f = (nowMs - fadeStart) / (retireAt - fadeStart);
  return Math.max(RETENTION_POLICY.fadeFloor, 1.0 - f * (1.0 - RETENTION_POLICY.fadeFloor));
}

/**
 * Parse a t-x-d-d delete event. Returns { name: <target uid>, deleted: true }
 * or null. ATAK references the deleted object through <link uid="...">.
 */
function parseCotDelete(xml) {
  var typeM = xml.match(/\btype="([^"]+)"/);
  if (!typeM || typeM[1] !== "t-x-d-d") return null;
  var linkM = xml.match(/<link\b[^>]*\buid="([^"]+)"/);
  if (!linkM) return null;
  return { name: linkM[1], deleted: true, _delete: true };
}

/**
 * Build the t-x-d-d the WebMap broadcasts when it retires or removes a
 * marker, in the shape ATAK sends so every TAK client drops the object.
 */
function buildDeleteCot(targetUid, targetType, reason) {
  var now = new Date();
  var fmt = function (d) { return d.toISOString(); };
  var stale = new Date(now.getTime() + 60000);
  var uid = "CrypTAK-NR-del-" + Math.random().toString(16).slice(2, 10);
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<event version="2.0" uid="' + uid + '" type="t-x-d-d" how="m-g"' +
    ' time="' + fmt(now) + '" start="' + fmt(now) + '" stale="' + fmt(stale) + '">' +
    '<point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>' +
    "<detail>" +
    '<link uid="' + escHtml(targetUid) + '" relation="none" type="' + escHtml(targetType || "a-u-G") + '"/>' +
    "<__forcedelete/>" +
    (reason ? "<remarks>" + escHtml(reason) + "</remarks>" : "") +
    "</detail></event>\n"
  );
}

/**
 * Build tooltip text for a marker (shown on hover).
 */
function buildTooltip(callsign, r, battery, mesh) {
  var v = mesh && mesh.voltage > 0 ? mesh.voltage.toFixed(1) + "V" : "";
  if (battery > 100)
    return callsign + " (USB" + (v ? " \u00b7 " + v : "") + ")";
  if (battery > 0)
    return callsign + " (" + battery + "%" + (v ? " \u00b7 " + v : "") + ")";
  if (v) return callsign + " (" + v + ")";
  return callsign;
}

/**
 * Build HTML popup for a marker (shown on click).
 */
function escHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatUptime(secs) {
  var d = Math.floor(secs / 86400);
  var h = Math.floor((secs % 86400) / 3600);
  var m = Math.floor((secs % 3600) / 60);
  if (d > 0) return d + "d " + h + "h";
  if (h > 0) return h + "h " + m + "m";
  return m + "m";
}

function buildPopup(callsign, r, color, battery, isTracker, mesh, uid, managed, retireAtMs, producedAt) {
  var html =
    '<div style="font-family:sans-serif;font-size:13px;max-width:300px;">';
  // Title: the CoT object's own callsign. Markers are keyed by uid, so the
  // callsign is carried here for display rather than inferred from the key.
  if (callsign)
    html +=
      '<div style="font-weight:600;margin-bottom:2px;">' +
      escHtml(callsign) +
      "</div>";
  if (r.location) html += "<b>" + escHtml(r.location) + "</b><br>";
  if (r.summary) html += escHtml(r.summary) + "<br>";
  // Operator free-text comment (ATAK marker remarks), preserved verbatim.
  if (r.comment)
    html +=
      '<div style="white-space:pre-wrap;margin:2px 0;">' +
      escHtml(r.comment) +
      "</div>";
  if (r.source || r.severity) {
    html += '<span style="color:#888;font-size:11px;">';
    var meta = [];
    if (r.source) meta.push(escHtml(r.source));
    if (r.severity) meta.push(escHtml(r.severity));
    html += meta.join(" &middot; ");
    html += "</span>";
  }
  var voltStr =
    mesh && mesh.voltage > 0 ? " (" + mesh.voltage.toFixed(2) + "V)" : "";
  if (battery > 100) {
    html +=
      '<br><span style="color:#888;font-size:11px;">Power: USB' +
      voltStr +
      "</span>";
  } else if (battery > 0) {
    html +=
      '<br><span style="color:#888;font-size:11px;">Battery: ' +
      battery +
      "%" +
      voltStr +
      "</span>";
  }
  if (mesh) {
    var meshLines = [];
    if (mesh.channelUtil > 0 || mesh.airUtilTx > 0) {
      var chParts = [];
      if (mesh.channelUtil > 0)
        chParts.push(mesh.channelUtil.toFixed(1) + "% ch util");
      if (mesh.airUtilTx > 0) chParts.push(mesh.airUtilTx.toFixed(1) + "% TX");
      meshLines.push("Mesh: " + chParts.join(" &middot; "));
    }
    if (mesh.snr !== null) {
      var hopsStr =
        mesh.hopsAway !== null
          ? mesh.hopsAway === 0
            ? "direct"
            : mesh.hopsAway + " hop" + (mesh.hopsAway !== 1 ? "s" : "")
          : "";
      meshLines.push(
        "Signal: " +
          mesh.snr.toFixed(1) +
          " dB" +
          (hopsStr ? " &middot; " + hopsStr : ""),
      );
    } else if (mesh.hopsAway !== null) {
      meshLines.push(
        mesh.hopsAway === 0
          ? "Direct link"
          : mesh.hopsAway + " hop" + (mesh.hopsAway !== 1 ? "s" : ""),
      );
    }
    if (mesh.uptime > 0) {
      meshLines.push("Up: " + formatUptime(mesh.uptime));
    }
    if (meshLines.length > 0) {
      html +=
        '<br><span style="color:#888;font-size:11px;">' +
        meshLines.join("<br>") +
        "</span>";
    }
  }
  if (r.url && /^https?:\/\//i.test(r.url)) {
    html +=
      '<br><a href="' +
      r.url.replace(/"/g, "%22") +
      '" target="_blank" ' +
      'style="color:#4A90D9;font-size:12px;">View details &rarr;</a>';
  }
  if (managed && uid) {
    var placed = producedAt ? formatUptime(Math.max(0, Math.round((Date.now() - producedAt) / 1000))) + " ago" : "";
    var life = retireAtMs
      ? "retires in " + formatUptime(Math.max(0, Math.round((retireAtMs - Date.now()) / 1000)))
      : "kept until removed";
    var safeUid = escHtml(uid).replace(/'/g, "\\'");
    html +=
      '<div style="margin-top:6px;font-size:11px;color:#888;">Placed ' + escHtml(placed) + " &middot; " + life + "</div>" +
      '<div style="margin-top:4px;font-size:11px;">' +
      "<a href=\"#\" onclick=\"if(!confirm('Remove " + escHtml(callsign).replace(/'/g, "\\'") +
      " from the map and every TAK client?'))return false;fetch('api/marker/remove',{method:'POST'," +
      "headers:{'Content-Type':'application/json'},body:JSON.stringify({uid:'" + safeUid +
      "'})}).then(function(){location.reload()});return false;\" style=\"color:#FF4444;\">Remove from map</a></div>";
  }
  if (isTracker) {
    var safeCs = escHtml(callsign).replace(/'/g, "\\'");
    var affs = [
      { code: "f", label: "Friendly", color: "#0066FF" },
      { code: "n", label: "Neutral", color: "#00AA00" },
      { code: "u", label: "Unknown", color: "#CCCC00" },
      { code: "s", label: "Suspect", color: "#FF8C00" },
      { code: "h", label: "Hostile", color: "#FF0000" },
    ];
    html += '<div style="margin-top:6px;font-size:11px;"><b>Classify:</b> ';
    for (var i = 0; i < affs.length; i++) {
      html +=
        "<a href=\"#\" onclick=\"fetch('api/tracker/affiliation',{method:'POST'," +
        "headers:{'Content-Type':'application/json'}," +
        "body:JSON.stringify({name:'" +
        safeCs +
        "',affiliation:'" +
        affs[i].code +
        "'})}).then(function(){location.reload()});return false;\" " +
        'style="color:' +
        affs[i].color +
        ';margin:0 4px;">' +
        affs[i].label +
        "</a>";
    }
    html += "</div>";
    var assets = [
      { type: "vehicle", emoji: "\uD83D\uDE97", label: "Vehicle" },
      { type: "person", emoji: "\uD83D\uDC64", label: "Person" },
      { type: "cargo", emoji: "\uD83D\uDCE6", label: "Cargo" },
      { type: "sensor", emoji: "\uD83D\uDCE1", label: "Sensor" },
    ];
    html += '<div style="margin-top:4px;font-size:11px;"><b>Asset:</b> ';
    for (var at = 0; at < assets.length; at++) {
      html +=
        "<a href=\"#\" onclick=\"fetch('api/tracker/asset-type',{method:'POST'," +
        "headers:{'Content-Type':'application/json'}," +
        "body:JSON.stringify({name:'" +
        safeCs +
        "',assetType:'" +
        assets[at].type +
        "'})}).then(function(){location.reload()});return false;\" " +
        'style="color:#aaa;margin:0 4px;text-decoration:none;" title="' +
        assets[at].label +
        '">' +
        assets[at].emoji +
        " " +
        assets[at].label +
        "</a>";
    }
    html += "</div>";
  }
  html += "</div>";
  return html;
}

/**
 * Convert a TAK signed 32-bit ARGB integer to a CSS hex color string.
 * TAK stores colors as signed 32-bit integers: bits 24-31 = alpha, 16-23 = red, 8-15 = green, 0-7 = blue.
 * @param {number|string} argb - signed 32-bit ARGB integer (may be string)
 * @returns {{ hex: string, alpha: number }} CSS hex color and alpha (0-1)
 */
function argbToCSS(argb) {
  var v = parseInt(argb, 10);
  if (isNaN(v)) return { hex: "#FFFF00", alpha: 1 };
  // Convert to unsigned 32-bit
  var u = v >>> 0;
  var a = (u >>> 24) & 0xff;
  var r = (u >>> 16) & 0xff;
  var g = (u >>> 8) & 0xff;
  var b = u & 0xff;
  var hex = "#" + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
  return { hex: hex, alpha: a / 255 };
}

/**
 * Parse a CoT drawing event (u-d-*) into a worldmap shape object.
 * Handles polygons (u-d-f), rectangles (u-d-r), and circles (u-d-c-c).
 * Returns null if the event is not a drawing type or cannot be parsed.
 *
 * @param {string} xml - complete CoT XML event string
 * @returns {object|null} worldmap shape object, or null to skip
 */
function parseCotDrawing(xml) {
  var typeM = xml.match(/\btype="([^"]+)"/);
  if (!typeM || !typeM[1].startsWith("u-d-")) return null;

  var uidM = xml.match(/\buid="([^"]+)"/);
  if (!uidM || uidM[1].startsWith("CrypTAK-NR-")) return null;

  var type = typeM[1];
  var uid = uidM[1];

  var callsign = extractCallsign(xml, uid);

  // Check for force-delete
  if (xml.indexOf("<__forcedelete") !== -1) {
    return { name: uid, deleted: true };
  }

  // Parse colors
  var strokeM = xml.match(/<strokeColor[^>]+value="([^"]+)"/);
  var fillM = xml.match(/<fillColor[^>]+value="([^"]+)"/);
  var weightM = xml.match(/<strokeWeight[^>]+value="([^"]+)"/);

  var stroke = strokeM ? argbToCSS(strokeM[1]) : { hex: "#FFFF00", alpha: 1 };
  var fill = fillM ? argbToCSS(fillM[1]) : { hex: "#FFFF00", alpha: 0.3 };
  var weight = weightM ? parseFloat(weightM[1]) : 3;

  // Parse stale time for TTL
  var staleM = xml.match(/\bstale="([^"]+)"/);
  var ttl = 86400; // default 24h
  if (staleM && staleM[1]) {
    var stMs = new Date(staleM[1]).getTime();
    if (!isNaN(stMs))
      ttl = Math.max(60, Math.round((stMs - Date.now()) / 1000));
  }

  // Circle/ellipse: u-d-c-c
  if (type.startsWith("u-d-c")) {
    var latM = xml.match(/\blat="([^"]+)"/);
    var lonM = xml.match(/\blon="([^"]+)"/);
    if (!latM || !lonM) return null;
    var lat = parseFloat(latM[1]);
    var lon = parseFloat(lonM[1]);
    if (lat === 0 && lon === 0) return null;

    var ellipseM = xml.match(/<ellipse[^>]+major="([^"]+)"/);
    var radius = ellipseM ? parseFloat(ellipseM[1]) : 100;

    return {
      name: uid,
      _uid: uid,
      _callsign: callsign,
      lat: lat,
      lon: lon,
      radius: radius,
      color: stroke.hex,
      fillColor: fill.hex,
      fillOpacity: fill.alpha,
      weight: weight,
      layer: "TAK Drawings",
      popup: callsign,
      ttl: ttl,
    };
  }

  // Polygon/polyline (u-d-f) or rectangle (u-d-r): parse <link point="lat,lon"/> elements
  var linkRegex = /<link[^>]+point="([^"]+)"/g;
  var points = [];
  var linkMatch;
  while ((linkMatch = linkRegex.exec(xml)) !== null) {
    var coords = linkMatch[1].split(",");
    if (coords.length >= 2) {
      var pLat = parseFloat(coords[0]);
      var pLon = parseFloat(coords[1]);
      if (!isNaN(pLat) && !isNaN(pLon)) {
        points.push({ lat: pLat, lng: pLon });
      }
    }
  }

  if (points.length < 2) return null;

  // Determine if filled polygon or polyline
  // Filled if: fillColor has meaningful alpha, or it's a rectangle, or first==last point (closed)
  var isClosed =
    points.length >= 3 &&
    Math.abs(points[0].lat - points[points.length - 1].lat) < 0.00001 &&
    Math.abs(points[0].lng - points[points.length - 1].lng) < 0.00001;
  var isFilled = type === "u-d-r" || isClosed || fill.alpha > 0.05;

  var shape = {
    name: uid,
    _uid: uid,
    _callsign: callsign,
    color: stroke.hex,
    fillColor: fill.hex,
    fillOpacity: fill.alpha,
    weight: weight,
    layer: "TAK Drawings",
    popup: callsign,
    ttl: ttl,
  };

  if (isFilled) {
    shape.area = points;
  } else {
    shape.line = points;
  }

  return shape;
}

/**
 * Parse a single CoT XML event string into a worldmap marker object.
 * Returns null if the event should be skipped (non-atom, self-echo, zero coords, etc.)
 *
 * @param {string} xml - complete CoT XML event string
 * @returns {object|null} marker object for worldmap, or null to skip
 */
function parseCotToMarker(xml) {
  var typeM = xml.match(/\btype="([^"]+)"/);
  if (!typeM || !typeM[1].startsWith("a-")) return null;

  var uidM = xml.match(/\buid="([^"]+)"/);
  if (!uidM || uidM[1].startsWith("CrypTAK-NR-")) return null;

  var latM = xml.match(/\blat="([^"]+)"/);
  var lonM = xml.match(/\blon="([^"]+)"/);
  if (!latM || !lonM) return null;

  var lat = parseFloat(latM[1]);
  var lon = parseFloat(lonM[1]);
  if (lat === 0 && lon === 0) return null;

  var uid = uidM[1];
  var cs = extractCallsign(xml, uid);
  if (cs === "CrypTAK-WebMap") return null;

  var parts = typeM[1].split("-");
  var color = colorMap[parts[1] || "u"] || "#888888";
  var icon = getIcon(parts);
  var sidc = cotTypeToSIDC(parts);

  var remM = xml.match(/<remarks\b([^>]*)>([\s\S]*?)<\/remarks>/);
  var r = parseRemarks(remM ? remM[2] : "", remM ? remM[1] : "");

  var batM = xml.match(/<status[^>]+battery="(\d+)"/);
  var battery = batM ? parseInt(batM[1], 10) : 0;

  // Is this a TAK client's own position report, or an object it placed?
  // ATAK/iTAK/WinTAK self-SA carries <takv platform="..."> and a
  // <contact endpoint="..."> (how the client can be reached); an operator-
  // placed marker carries neither and is how="h-g-i-g-o". The sidebar's
  // CONNECTED list must only count the former, so this is decided here, at
  // parse time, rather than inferred later from what a marker is *not*.
  var howM = xml.match(/\bhow="([^"]*)"/);
  var how = howM ? howM[1] : "";
  var takvM = xml.match(/<takv\b([^>]*)\/?>/);
  var takvAttrs = takvM ? takvM[1] : "";
  var takvAttr = function (name) {
    var m = takvAttrs.match(new RegExp("\\b" + name + '="([^"]*)"'));
    return m ? unescapeXml(m[1]) : "";
  };
  var platform = takvAttr("platform");
  var device = takvAttr("device");
  var appVersion = takvAttr("version");
  var hasEndpoint = /<contact\b[^>]*?\bendpoint="[^"]+"/.test(xml);
  var isPlaced = how.indexOf("h-g-i-g-o") === 0;
  // mesh-relay's PLIs also carry a <takv> and an endpoint, so mesh/tracker
  // uids are excluded here as well as in fn_serve_clients.
  var isClient =
    !isPlaced &&
    (platform !== "" || hasEndpoint) &&
    uid.indexOf("CrypTAK-") !== 0 && // our own services also announce an endpoint
    uid.indexOf("mesh-") !== 0 &&
    uid.indexOf("tracker-") !== 0;

  // Parse mesh telemetry (voltage, channel util, SNR, etc.)
  var meshTelem = null;
  var telemM = xml.match(/<__meshTelemetry([^/]*)\/?>/);
  if (telemM) {
    var ta = telemM[1];
    var ga = function (n) {
      var m = ta.match(new RegExp(n + '="([^"]*)"'));
      return m ? m[1] : null;
    };
    meshTelem = {
      voltage: parseFloat(ga("voltage")) || 0,
      channelUtil: parseFloat(ga("channelUtil")) || 0,
      airUtilTx: parseFloat(ga("airUtilTx")) || 0,
      uptime: parseInt(ga("uptime"), 10) || 0,
      snr: ga("snr") !== null ? parseFloat(ga("snr")) : null,
      hopsAway: ga("hopsAway") !== null ? parseInt(ga("hopsAway"), 10) : null,
    };
  }

  var isTracker = uidM[1].indexOf("tracker-") === 0;
  var isMeshtastic = uidM[1].indexOf("mesh-") === 0 || isTracker;
  var isBridge = xml.indexOf("<__meshBridge") !== -1;

  var startM = xml.match(/\bstart="([^"]+)"/);
  var staleM = xml.match(/\bstale="([^"]+)"/);
  var effectiveStart = r.published || (startM ? startM[1] : null);
  var opacity = calcOpacity(
    effectiveStart,
    staleM ? staleM[1] : null,
    !!r.published,
  );

  if (opacity <= 0.05) {
    return { name: uid, deleted: true };
  }

  var affCode = parts[1] || "u";
  var layerName = affiliationNames[affCode] || "TAK Other";
  var managed = isManagedCot(xml, how);
  var produced = managed ? producedMs(xml, startM ? startM[1] : null) : 0;
  var retentionMs = managed ? retentionFor(affCode, r.raw) : 0;
  var retireAtMs = managed && retentionMs > 0 ? produced + retentionMs : 0;
  var ttlSec = 300;
  if (staleM && staleM[1]) {
    var stMs = new Date(staleM[1]).getTime();
    if (!isNaN(stMs))
      ttlSec = Math.max(60, Math.round((stMs - Date.now()) / 1000));
  }

  var ageBased = !!r.published;

  var marker = {
    name: uid,
    _uid: uid,
    _callsign: cs,
    _remarks: r.comment || "",
    _client: isClient,
    _managed: managed,
    _cotType: typeM[1],
    _producedMs: produced,
    _retentionMs: retentionMs,
    _retireAtMs: retireAtMs,
    _how: how,
    _platform: platform,
    _device: device,
    _appVersion: appVersion,
    lat: lat,
    lon: lon,
    layer: layerName,
    iconColor: color,
    tooltip: buildTooltip(cs, r, battery, meshTelem),
    popup: buildPopup(cs, r, color, battery, isTracker, meshTelem, uid, managed, retireAtMs, produced),
    opacity: Math.round(opacity * 100) / 100,
    ttl: ttlSec,
    _staleMs: staleM ? new Date(staleM[1]).getTime() : 0,
    _startMs: effectiveStart ? new Date(effectiveStart).getTime() : 0,
    _baseColor: color,
    _ageBased: ageBased,
    _battery: battery,
    _tracker: isTracker,
    _meshtastic: isMeshtastic,
    _meshBridge: isBridge,
    _mesh: meshTelem,
  };

  if (managed) {
    // Age and "Recent" sorting follow the placement, not the latest re-send,
    // and the fade is driven by the retention policy rather than stale.
    marker._startMs = produced;
    marker.opacity = Math.round(calcRetireOpacity(produced, retireAtMs, Date.now()) * 100) / 100;
  }

  // Use SIDC for milsymbol rendering when available; fall back to FA icon
  if (sidc) {
    marker.SIDC = sidc;
  } else {
    marker.icon = icon;
  }

  return marker;
}

/**
 * Build a CoT SA (Situation Awareness) XML message for FTS keepalive.
 * @param {string} uid - unique identifier for this connection
 */
function makeSA(uid) {
  var now = new Date().toISOString();
  var stale = new Date(Date.now() + 300000).toISOString();
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<event version="2.0" uid="' +
    uid +
    '" type="a-f-G-U-C"' +
    ' time="' +
    now +
    '" start="' +
    now +
    '" stale="' +
    stale +
    '" how="m-g">' +
    '<point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>' +
    "<detail>" +
    '<contact endpoint="*:-1:stcp" callsign="CrypTAK-WebMap"/>' +
    '<__group name="Cyan" role="Team Member"/>' +
    '<uid Droid="CrypTAK-WebMap"/>' +
    "</detail>" +
    "</event>"
  );
}

/**
 * Recalculate opacity for all cached markers.
 * Returns markers whose opacity changed (so worldmap re-renders them)
 * and markers that have fully expired (for deletion).
 *
 * @param {object} cache - the takMarkers cache (name → marker)
 * @returns {{ updated: object[], expired: string[] }}
 */
function refreshMarkerColors(cache) {
  var updated = [];
  var expired = [];
  var retired = [];
  var keys = Object.keys(cache);
  var nowMs = Date.now();
  for (var i = 0; i < keys.length; i++) {
    var m = cache[keys[i]];
    if (m && m._managed) {
      // Placed marker: policy-driven. Retired ones are returned separately so
      // the caller can broadcast the delete before removing them.
      if (m._retireAtMs && nowMs >= m._retireAtMs) {
        retired.push(keys[i]);
        continue;
      }
      var ro = Math.round(calcRetireOpacity(m._producedMs, m._retireAtMs, nowMs) * 100) / 100;
      if (ro !== m.opacity) {
        m.opacity = ro;
        updated.push(m);
      }
      continue;
    }
    if (!m._staleMs || !m._startMs) continue;

    var startStr = new Date(m._startMs).toISOString();
    var staleStr = new Date(m._staleMs).toISOString();
    var opacity = calcOpacity(startStr, staleStr, m._ageBased);

    if (opacity <= 0.05) {
      expired.push(keys[i]);
      continue;
    }

    var newOpacity = Math.round(opacity * 100) / 100;
    if (newOpacity !== m.opacity) {
      m.opacity = newOpacity;
      updated.push(m);
    }
  }
  return { updated: updated, expired: expired, retired: retired };
}

// Asset type → CoT function suffix mapping (for tracker classification)
var assetTypeToCotSuffix = {
  vehicle: "G-E-V",
  person: "G-U-C",
  cargo: "G-U-C",
  sensor: "G-E-S",
  infrastructure: "G-E-X",
};

/**
 * Build a 15-char SIDC for a tracker given its affiliation and asset type.
 * @param {string} affiliation - CoT affiliation code (f/s/h/u etc.)
 * @param {string} assetType - asset type key (vehicle/person/cargo/sensor/infrastructure)
 * @returns {string} 15-char SIDC code
 */
function buildTrackerSIDC(affiliation, assetType) {
  var suffix = assetTypeToCotSuffix[assetType] || "G-E-V";
  var cotType = "a-" + (affiliation || "f") + "-" + suffix;
  return cotTypeToSIDC(cotType.split("-"));
}

// Meshtastic hardware model IDs → display names
// Generated from meshtastic protobuf HardwareModel enum (v2.7.x)
var HW_MODELS = {
  0: "?",
  1: "T-Lora V2",
  2: "T-Lora V1",
  3: "T-Lora V2.1",
  4: "T-Beam",
  5: "Heltec V2",
  6: "T-Beam v0.7",
  7: "T-Echo",
  8: "T-Lora V1.3",
  9: "RAK4631",
  10: "Heltec V2.1",
  11: "Heltec V1",
  12: "T-Beam S3",
  13: "RAK11200",
  14: "Nano G1",
  15: "T-Lora V2.1",
  16: "T-Lora T3 S3",
  17: "Nano G1 Exp",
  18: "Nano G2 Ultra",
  21: "Wio WM1110",
  22: "RAK2560",
  23: "Heltec HRU",
  24: "Heltec Bridge",
  25: "Station G1",
  26: "RAK11310",
  31: "Station G2",
  33: "T-Echo+",
  36: "nRF52",
  37: "Linux Native",
  40: "nRF52840 Dongle",
  42: "M5Stack",
  43: "Heltec V3",
  44: "Heltec WSL V3",
  47: "RPi Pico",
  48: "Heltec Tracker",
  49: "Heltec Paper",
  50: "T-Deck",
  51: "T-Watch S3",
  53: "Heltec HT62",
  57: "Heltec Paper v1",
  58: "Heltec Tracker v1",
  63: "nRF52 ProMicro",
  65: "Heltec Capsule",
  66: "Heltec VM T190",
  67: "Heltec VM E213",
  68: "Heltec VM E290",
  69: "Heltec T114",
  70: "SenseCAP Indicator",
  71: "T1000-E",
  72: "RAK3172",
  73: "Wio-E5",
  77: "M5Stack Core",
  78: "M5Stack Core2",
  79: "RPi Pico2",
  80: "M5Stack CoreS3",
  81: "Xiao S3",
  84: "WisMesh Tap",
  85: "Routastic",
  86: "MeshTab",
  87: "MeshLink",
  88: "Xiao nRF52 Kit",
  89: "ThinkNode M1",
  90: "ThinkNode M2",
  91: "T-ETH Elite",
  92: "Heltec Sensor Hub",
  94: "Heltec Pocket",
  95: "SenseCAP Solar",
  97: "CrowPanel",
  99: "Wio Tracker L1",
  100: "Wio Tracker eInk",
  102: "T-Deck Pro",
  103: "T-Lora Pager",
  105: "WisMesh Tag",
  106: "RAK3312",
  107: "ThinkNode M5",
  108: "Heltec Solar",
  109: "T-Echo Lite",
  110: "Heltec V4",
  113: "Heltec Tracker v2",
  114: "T-Watch Ultra",
  115: "ThinkNode M3",
  116: "WisMesh Tap v2",
  117: "RAK3401",
  118: "RAK6421",
  119: "ThinkNode M4",
  120: "ThinkNode M6",
  121: "MeshStick",
  122: "T-Beam 1W",
  255: "Custom",
};

// Meshtastic device role IDs → display names
// Generated from meshtastic protobuf Config.DeviceConfig.Role enum (v2.7.x)
var ROLES = {
  0: "CLIENT",
  1: "MUTE",
  2: "ROUTER",
  3: "RTR+CLI",
  4: "RPTR",
  5: "TRACKER",
  6: "SENSOR",
  7: "TAK",
  8: "HIDDEN",
  9: "LOST+FOUND",
  10: "TAK+TRK",
  11: "RTR_LATE",
  12: "BASE",
};

module.exports = {
  colorMap: colorMap,
  iconMap: iconMap,
  affiliationNames: affiliationNames,
  assetTypeToCotSuffix: assetTypeToCotSuffix,
  fadeColor: fadeColor,
  getIcon: getIcon,
  calcOpacity: calcOpacity,
  parseRemarks: parseRemarks,
  escHtml: escHtml,
  buildTooltip: buildTooltip,
  buildPopup: buildPopup,
  parseCotToMarker: parseCotToMarker,
  parseCotDrawing: parseCotDrawing,
  argbToCSS: argbToCSS,
  refreshMarkerColors: refreshMarkerColors,
  makeSA: makeSA,
  snrToLinkStyle: snrToLinkStyle,
  cotTypeToSIDC: cotTypeToSIDC,
  parseCotDelete: parseCotDelete,
  buildDeleteCot: buildDeleteCot,
  isManagedCot: isManagedCot,
  retentionFor: retentionFor,
  calcRetireOpacity: calcRetireOpacity,
  RETENTION_POLICY: RETENTION_POLICY,
  buildTrackerSIDC: buildTrackerSIDC,
  sidcAffiliation: sidcAffiliation,
  HW_MODELS: HW_MODELS,
  ROLES: ROLES,
};
