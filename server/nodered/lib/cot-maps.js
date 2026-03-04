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
 * Parse pipe-delimited remarks into structured fields.
 * Format: summary | location | source | severity | url | published_utc
 * @param {string} raw - raw remarks text from CoT XML
 */
function parseRemarks(raw) {
  var parts = (raw || "").split(" | ");
  return {
    summary: parts[0] || "",
    location: parts[1] || "",
    source: parts[2] || "",
    severity: parts[3] || "",
    url: parts[4] || "",
    published: parts[5] || "",
  };
}

/**
 * Build tooltip text for a marker (shown on hover).
 */
function buildTooltip(callsign, r) {
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

function buildPopup(callsign, r, color) {
  var html =
    '<div style="font-family:sans-serif;font-size:13px;max-width:300px;">';
  if (r.location) html += "<b>" + escHtml(r.location) + "</b><br>";
  if (r.summary) html += escHtml(r.summary) + "<br>";
  if (r.source || r.severity) {
    html += '<span style="color:#888;font-size:11px;">';
    var meta = [];
    if (r.source) meta.push(escHtml(r.source));
    if (r.severity) meta.push(escHtml(r.severity));
    html += meta.join(" &middot; ");
    html += "</span>";
  }
  if (r.url && /^https?:\/\//i.test(r.url)) {
    html +=
      '<br><a href="' +
      r.url.replace(/"/g, "%22") +
      '" target="_blank" ' +
      'style="color:#4A90D9;font-size:12px;">View details &rarr;</a>';
  }
  html += "</div>";
  return html;
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

  var csM = xml.match(/callsign="([^"]+)"/);
  var cs = csM ? csM[1] : uidM ? uidM[1] : "Unknown";
  if (cs === "CrypTAK-WebMap") return null;

  var parts = typeM[1].split("-");
  var color = colorMap[parts[1] || "u"] || "#888888";
  var icon = getIcon(parts);

  var remM = xml.match(/<remarks[^>]*>([^<]*)<\/remarks>/);
  var r = parseRemarks(remM ? remM[1] : "");

  var startM = xml.match(/\bstart="([^"]+)"/);
  var staleM = xml.match(/\bstale="([^"]+)"/);
  var effectiveStart = r.published || (startM ? startM[1] : null);
  var opacity = calcOpacity(
    effectiveStart,
    staleM ? staleM[1] : null,
    !!r.published,
  );

  if (opacity <= 0.05) {
    return { name: cs, deleted: true };
  }

  var affCode = parts[1] || "u";
  var layerName = affiliationNames[affCode] || "TAK Other";
  var ttlSec = 300;
  if (staleM && staleM[1]) {
    var stMs = new Date(staleM[1]).getTime();
    if (!isNaN(stMs))
      ttlSec = Math.max(60, Math.round((stMs - Date.now()) / 1000));
  }

  var ageBased = !!r.published;

  return {
    name: cs,
    lat: lat,
    lon: lon,
    layer: layerName,
    icon: icon,
    iconColor: color,
    tooltip: buildTooltip(cs, r),
    popup: buildPopup(cs, r, color),
    opacity: Math.round(opacity * 100) / 100,
    ttl: ttlSec,
    _staleMs: staleM ? new Date(staleM[1]).getTime() : 0,
    _startMs: effectiveStart ? new Date(effectiveStart).getTime() : 0,
    _baseColor: color,
    _ageBased: ageBased,
  };
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
    '<detail><contact endpoint="*:-1:stcp" callsign="CrypTAK-WebMap"/></detail>' +
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
  var keys = Object.keys(cache);
  for (var i = 0; i < keys.length; i++) {
    var m = cache[keys[i]];
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
  return { updated: updated, expired: expired };
}

module.exports = {
  colorMap: colorMap,
  iconMap: iconMap,
  affiliationNames: affiliationNames,
  fadeColor: fadeColor,
  getIcon: getIcon,
  calcOpacity: calcOpacity,
  parseRemarks: parseRemarks,
  escHtml: escHtml,
  buildTooltip: buildTooltip,
  buildPopup: buildPopup,
  parseCotToMarker: parseCotToMarker,
  refreshMarkerColors: refreshMarkerColors,
  makeSA: makeSA,
};
