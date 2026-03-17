// CrypTAK Node-RED settings override
// Mounted read-only at /data/settings.js via docker-compose
//
// Only settings that differ from defaults are listed here.
// See https://nodered.org/docs/user-guide/runtime/configuration

const fs = require('fs');
const yaml = require('js-yaml');

// Load nodes registry — single source of truth for owned mesh nodes
let nodesRegistry = {};
try {
  const raw = fs.readFileSync('/opt/cot-maps/nodes.yaml', 'utf8');
  nodesRegistry = yaml.load(raw);
} catch (e) {
  console.warn('Could not load nodes.yaml:', e.message);
}

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
    nodesRegistry: nodesRegistry,
  },

  // Allow function nodes to require() external npm packages
  functionExternalModules: true,

  // Persist flow context across restarts (mesh node state, etc.)
  // Using both: in-memory for speed, file as durable fallback on restart.
  // Use file-based context storage so mesh node state (registry, telemetry)
  // survives Node-RED restarts without any flow code changes.
  contextStorage: {
    default: { module: "localfilesystem" },
  },

  // Editor theme
  editorTheme: {
    projects: { enabled: false },
  },
};
