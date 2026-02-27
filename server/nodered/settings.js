// CrypTAK Node-RED settings override
// Mounted read-only at /data/settings.js via docker-compose
//
// Only settings that differ from defaults are listed here.
// See https://nodered.org/docs/user-guide/runtime/configuration

module.exports = {
  flowFile: "flows.json",
  flowFilePretty: true,

  // Serve static assets (favicon, logo) from /data/public
  httpStatic: "/data/public",

  // Expose Node.js modules and CrypTAK libs to function nodes
  // Usage in function nodes: var net = global.get('net');
  //                          var maps = global.get('cotMaps');
  functionGlobalContext: {
    net: require("net"),
    cotMaps: require("/opt/cot-maps/cot-maps"),
  },

  // Allow function nodes to require() external npm packages
  functionExternalModules: true,

  // Editor theme
  editorTheme: {
    projects: { enabled: false },
  },
};
