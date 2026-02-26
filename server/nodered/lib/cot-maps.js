// CoT type → display mapping tables for CrypTAK WebMap
// Loaded into Node-RED via functionGlobalContext in settings.js
// Referenced in fn_cot as: var maps = global.get('cotMaps');

module.exports = {
  // MIL-STD-2525 affiliation code → marker color
  colorMap: {
    h: "#FF0000", // hostile
    s: "#FFA500", // suspect
    n: "#00AA00", // neutral
    u: "#CCCC00", // unknown
    f: "#0066FF", // friendly
    a: "#6699CC", // assumed friendly
    p: "#888888", // pending
    j: "#FF0000", // joker (hostile)
    k: "#FF0000", // faker (hostile)
    o: "white", // none
  },

  // CoT type suffix → Font Awesome icon class
  // Matched longest-first from parts[2..n] of the CoT type string
  iconMap: {
    "G-U-C-V": "fa-exclamation-circle",
    "G-I-i-l": "fa-exclamation-triangle",
    "G-O-E": "fa-crosshairs",
    "G-I-R": "fa-eye",
    "G-I-i-h": "fa-plus-square",
    "G-I-i-f": "fa-fire",
    "G-E-N": "fa-bolt",
    "G-I-i-d": "fa-car",
    "G-O-S": "fa-search",
    "G-I-i-c": "fa-bullhorn",
    "G-U-C": "fa-question-circle",
    A: "fa-plane",
    S: "fa-ship",
  },

  // MIL-STD-2525 affiliation code → worldmap layer name
  affiliationNames: {
    h: "TAK Hostile",
    s: "TAK Hostile",
    n: "TAK Neutral",
    u: "TAK Unknown",
    f: "TAK Friendly",
    a: "TAK Friendly",
    p: "TAK Unknown",
    j: "TAK Hostile",
    k: "TAK Hostile",
    o: "TAK Other",
  },
};
