// CrypTAK Node-RED settings override
// Mounted read-only at /data/settings.js via docker-compose
//
// Only settings that differ from defaults are listed here.
// See https://nodered.org/docs/user-guide/runtime/configuration

module.exports = {
  flowFile: "flows.json",
  flowFilePretty: true,

  // Admin authentication — protects the editor and admin API (/flows, /context).
  // Does NOT protect httpNode routes (WebMap at /tak-map stays open).
  // To enable: set NR_ADMIN_PASS in .env on Unraid.
  // When unset, Node-RED runs without auth (backwards compatible).
  adminAuth: process.env.NR_ADMIN_PASS
    ? {
        type: "credentials",
        users: [
          {
            username: "admin",
            password: require("bcryptjs").hashSync(
              process.env.NR_ADMIN_PASS,
              8,
            ),
            permissions: "*",
          },
        ],
      }
    : undefined,

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
