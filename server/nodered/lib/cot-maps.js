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
  f: 'F', a: 'A', h: 'H', s: 'S', n: 'N', u: 'U',
  p: 'P', j: 'J', k: 'K', o: 'O'
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
  if (!parts || parts.length < 3 || parts[0] !== 'a') return null;

  var affil = sidcAffiliation[parts[1]] || 'U';

  // Map CoT dimension to SIDC battle dimension
  // Valid SIDC: P(space) A(air) G(ground) S(sea) U(subsurface) F(SOF)
  var dim = parts[2];
  if ('PAGSUF'.indexOf(dim) === -1) dim = 'G';

  // Function ID: parts[3..n] concatenated, padded to 6 chars with dashes
  var funcId = parts.slice(3).join('');
  while (funcId.length < 6) funcId += '-';
  funcId = funcId.substring(0, 6);

  // 15-char SIDC: scheme(S) + affiliation + dimension + status(P=present)
  //               + functionId(6) + size/mod(2) + country(2) + OB(1)
  return 'S' + affil + dim + 'P' + funcId + '-----';
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
  if (snr >= 5)  return { color: '#00DD00', weight: 3 };
  if (snr >= 0)  return { color: '#AADD00', weight: 2 };
  if (snr >= -7) return { color: '#FFAA00', weight: 2 };
  return { color: '#FF4444', weight: 1 };
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

function buildPopup(callsign, r, color, battery, isTracker, mesh) {
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
  if (isTracker) {
    var safeCs = escHtml(callsign).replace(/'/g, "\\'");
    var affs = [
      { code: "f", label: "Friendly", color: "#0066FF" },
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
    var icons = [
      { cls: "fa-crosshairs", label: "Crosshairs" },
      { cls: "fa-car", label: "Vehicle" },
      { cls: "fa-user", label: "Person" },
      { cls: "fa-cube", label: "Cargo" },
    ];
    html += '<div style="margin-top:4px;font-size:11px;"><b>Icon:</b> ';
    for (var ic = 0; ic < icons.length; ic++) {
      html +=
        "<a href=\"#\" onclick=\"fetch('api/tracker/icon',{method:'POST'," +
        "headers:{'Content-Type':'application/json'}," +
        "body:JSON.stringify({name:'" +
        safeCs +
        "',icon:'" +
        icons[ic].cls +
        "'})}).then(function(){location.reload()});return false;\" " +
        'style="color:#aaa;margin:0 5px;" title="' +
        icons[ic].label +
        '"><i class="fa ' +
        icons[ic].cls +
        '"></i></a>';
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
  if (isNaN(v)) return { hex: '#FFFF00', alpha: 1 };
  // Convert to unsigned 32-bit
  var u = v >>> 0;
  var a = (u >>> 24) & 0xFF;
  var r = (u >>> 16) & 0xFF;
  var g = (u >>> 8) & 0xFF;
  var b = u & 0xFF;
  var hex = '#' + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
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
  if (!typeM || !typeM[1].startsWith('u-d-')) return null;

  var uidM = xml.match(/\buid="([^"]+)"/);
  if (!uidM || uidM[1].startsWith('CrypTAK-NR-')) return null;

  var type = typeM[1];
  var uid = uidM[1];

  var csM = xml.match(/callsign="([^"]+)"/);
  var callsign = csM ? csM[1] : uid;

  // Check for force-delete
  if (xml.indexOf('<__forcedelete') !== -1) {
    return { name: callsign, deleted: true };
  }

  // Parse colors
  var strokeM = xml.match(/<strokeColor[^>]+value="([^"]+)"/);
  var fillM = xml.match(/<fillColor[^>]+value="([^"]+)"/);
  var weightM = xml.match(/<strokeWeight[^>]+value="([^"]+)"/);

  var stroke = strokeM ? argbToCSS(strokeM[1]) : { hex: '#FFFF00', alpha: 1 };
  var fill = fillM ? argbToCSS(fillM[1]) : { hex: '#FFFF00', alpha: 0.3 };
  var weight = weightM ? parseFloat(weightM[1]) : 3;

  // Parse stale time for TTL
  var staleM = xml.match(/\bstale="([^"]+)"/);
  var ttl = 86400; // default 24h
  if (staleM && staleM[1]) {
    var stMs = new Date(staleM[1]).getTime();
    if (!isNaN(stMs)) ttl = Math.max(60, Math.round((stMs - Date.now()) / 1000));
  }

  // Circle/ellipse: u-d-c-c
  if (type.startsWith('u-d-c')) {
    var latM = xml.match(/\blat="([^"]+)"/);
    var lonM = xml.match(/\blon="([^"]+)"/);
    if (!latM || !lonM) return null;
    var lat = parseFloat(latM[1]);
    var lon = parseFloat(lonM[1]);
    if (lat === 0 && lon === 0) return null;

    var ellipseM = xml.match(/<ellipse[^>]+major="([^"]+)"/);
    var radius = ellipseM ? parseFloat(ellipseM[1]) : 100;

    return {
      name: callsign,
      lat: lat,
      lon: lon,
      radius: radius,
      color: stroke.hex,
      fillColor: fill.hex,
      fillOpacity: fill.alpha,
      weight: weight,
      layer: 'TAK Drawings',
      popup: callsign,
      ttl: ttl
    };
  }

  // Polygon/polyline (u-d-f) or rectangle (u-d-r): parse <link point="lat,lon"/> elements
  var linkRegex = /<link[^>]+point="([^"]+)"/g;
  var points = [];
  var linkMatch;
  while ((linkMatch = linkRegex.exec(xml)) !== null) {
    var coords = linkMatch[1].split(',');
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
  var isClosed = (points.length >= 3 &&
    Math.abs(points[0].lat - points[points.length - 1].lat) < 0.00001 &&
    Math.abs(points[0].lng - points[points.length - 1].lng) < 0.00001);
  var isFilled = type === 'u-d-r' || isClosed || fill.alpha > 0.05;

  var shape = {
    name: callsign,
    color: stroke.hex,
    fillColor: fill.hex,
    fillOpacity: fill.alpha,
    weight: weight,
    layer: 'TAK Drawings',
    popup: callsign,
    ttl: ttl
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

  var csM = xml.match(/callsign="([^"]+)"/);
  var cs = csM ? csM[1] : uidM ? uidM[1] : "Unknown";
  if (cs === "CrypTAK-WebMap") return null;

  var parts = typeM[1].split("-");
  var color = colorMap[parts[1] || "u"] || "#888888";
  var icon = getIcon(parts);
  var sidc = cotTypeToSIDC(parts);

  var remM = xml.match(/<remarks[^>]*>([^<]*)<\/remarks>/);
  var r = parseRemarks(remM ? remM[1] : "");

  var batM = xml.match(/<status[^>]+battery="(\d+)"/);
  var battery = batM ? parseInt(batM[1], 10) : 0;

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

  var marker = {
    name: cs,
    lat: lat,
    lon: lon,
    layer: layerName,
    iconColor: color,
    tooltip: buildTooltip(cs, r, battery, meshTelem),
    popup: buildPopup(cs, r, color, battery, isTracker, meshTelem),
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
    '<detail>' +
    '<contact endpoint="127.0.0.1:4242:tcp" callsign="CrypTAK-WebMap"/>' +
    '<__group name="Cyan" role="Team Member"/>' +
    '<uid Droid="CrypTAK-WebMap"/>' +
    '</detail>' +
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

// Meshtastic hardware model IDs → display names
var HW_MODELS = {
  0:'?', 4:'T-Beam', 6:'Heltec', 8:'Heltec', 9:'NanoG1',
  10:'RAK4631', 17:'Heltec V3', 24:'NanoG1', 37:'RAK',
  43:'RAK', 57:'T-Echo', 65:'RAK', 68:'T-Beam S3',
  113:'Heltec V3'
};

// Meshtastic device role IDs → display names
var ROLES = {
  0:'CLIENT', 1:'MUTE', 2:'ROUTER', 3:'RTR+CLI',
  4:'TRACKER', 5:'RPTR', 6:'TAK'
};

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
  parseCotDrawing: parseCotDrawing,
  argbToCSS: argbToCSS,
  refreshMarkerColors: refreshMarkerColors,
  makeSA: makeSA,
  snrToLinkStyle: snrToLinkStyle,
  cotTypeToSIDC: cotTypeToSIDC,
  sidcAffiliation: sidcAffiliation,
  HW_MODELS: HW_MODELS,
  ROLES: ROLES,
};
