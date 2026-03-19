/* mesh-diag UI — WebSocket client, tab switching, diagnostics, modify */

(function () {
  "use strict";

  // ── State ──────────────────────────────────────────────────────────────

  let radioState = null; // Current RadioState from server
  let ws = null;
  let wsRetryDelay = 1000;

  // ── DOM refs ───────────────────────────────────────────────────────────

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  // ── WebSocket ──────────────────────────────────────────────────────────

  function connectWS() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(`${proto}//${location.host}/ws`);

    ws.onopen = function () {
      wsRetryDelay = 1000;
    };

    ws.onmessage = function (evt) {
      let msg;
      try {
        msg = JSON.parse(evt.data);
      } catch (e) {
        return;
      }
      handleMessage(msg);
    };

    ws.onclose = function () {
      setTimeout(connectWS, wsRetryDelay);
      wsRetryDelay = Math.min(wsRetryDelay * 1.5, 10000);
    };

    ws.onerror = function () {
      // onclose will fire after this
    };
  }

  function handleMessage(msg) {
    if (
      msg.type === "connected" ||
      msg.type === "disconnected" ||
      msg.type === "status_update"
    ) {
      radioState = msg.data;
      updateStatusTab();
      updateConnectionUI();
    }
  }

  // ── Tab switching ──────────────────────────────────────────────────────

  function initTabs() {
    $$(".tab").forEach(function (btn) {
      btn.addEventListener("click", function () {
        switchTab(btn.dataset.tab);
      });
    });

    // Physical button mapping
    // Desktop: F1-F3, O
    // reTerminal hardware buttons: F1=KEY_A, F2=KEY_S, F3=KEY_D, O=KEY_F
    document.addEventListener("keydown", function (e) {
      if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || e.target.tagName === "TEXTAREA") return;
      if (e.key === "F1" || e.key === "a") switchTab("status");
      else if (e.key === "F2" || e.key === "s") switchTab("diagnose");
      else if (e.key === "F3" || e.key === "d") switchTab("modify");
      else if (e.key === "o" || e.key === "O" || e.key === "f") switchTab("log");
    });
  }

  function switchTab(tabId) {
    $$(".tab").forEach(function (t) {
      t.classList.toggle("active", t.dataset.tab === tabId);
    });
    $$(".tab-panel").forEach(function (p) {
      p.classList.toggle("active", p.id === "tab-" + tabId);
    });

    // Auto-actions on tab switch
    if (tabId === "log") refreshLog();
    if (tabId === "modify") refreshBackups();
  }

  // ── Status tab rendering ───────────────────────────────────────────────

  function updateConnectionUI() {
    var connected = radioState && radioState.connected;
    var sbStatus = $("#sb-status");
    var sbNode = $("#sb-node");
    var sbPort = $("#sb-port");
    var btnEject = $("#btn-eject");

    if (connected) {
      sbStatus.textContent = "Connected";
      sbStatus.className = "sb-connected";
      sbNode.textContent =
        radioState.long_name + " (" + radioState.node_id + ")";
      sbPort.textContent = radioState.port;
      btnEject.classList.remove("hidden");

      // Show content, hide empty states
      $("#status-disconnected").classList.add("hidden");
      $("#status-content").classList.remove("hidden");
      $("#diag-disconnected").classList.add("hidden");
      $("#diag-content").classList.remove("hidden");
      $("#modify-disconnected").classList.add("hidden");
      $("#modify-content").classList.remove("hidden");
    } else {
      sbStatus.textContent = "Disconnected";
      sbStatus.className = "sb-disconnected";
      sbNode.textContent = "\u2014";
      sbPort.textContent = "\u2014";
      btnEject.classList.add("hidden");

      // Show empty states, hide content
      $("#status-disconnected").classList.remove("hidden");
      $("#status-content").classList.add("hidden");
      $("#diag-disconnected").classList.remove("hidden");
      $("#diag-content").classList.add("hidden");
      $("#modify-disconnected").classList.remove("hidden");
      $("#modify-content").classList.add("hidden");
    }
  }

  function updateStatusTab() {
    if (!radioState || !radioState.connected) return;
    var s = radioState;

    // Identity
    $("#s-longname").textContent = s.long_name || "\u2014";
    $("#s-shortname").textContent = s.short_name || "\u2014";
    $("#s-nodeid").textContent = s.node_id || "\u2014";
    $("#s-hwmodel").textContent = s.hw_model || "\u2014";
    $("#s-firmware").textContent = s.firmware_version || "\u2014";
    $("#s-role").textContent = s.role || "\u2014";

    // RF
    $("#s-region").textContent = s.region || "\u2014";
    $("#s-modem").textContent = s.modem_preset || "\u2014";
    $("#s-hoplimit").textContent = s.hop_limit;
    var dm = s.device_metrics || {};
    $("#s-chutil").textContent = dm.channel_utilization
      ? dm.channel_utilization.toFixed(1) + "%"
      : "\u2014";
    $("#s-airtx").textContent = dm.air_util_tx
      ? dm.air_util_tx.toFixed(1) + "%"
      : "\u2014";

    // Color-code RF card
    var rfCard = $("#card-rf");
    rfCard.className = "card";
    if (dm.channel_utilization > 50) rfCard.classList.add("health-critical");
    else if (dm.channel_utilization > 25) rfCard.classList.add("health-warn");
    else if (dm.channel_utilization > 0) rfCard.classList.add("health-ok");

    // GPS
    var gps = s.gps || {};
    $("#s-gpsfix").textContent = gps.fix_type || "\u2014";
    $("#s-gpssats").textContent =
      gps.satellites !== undefined ? gps.satellites : "\u2014";
    $("#s-gpslat").textContent =
      gps.latitude !== null && gps.latitude !== undefined
        ? gps.latitude.toFixed(6)
        : "\u2014";
    $("#s-gpslon").textContent =
      gps.longitude !== null && gps.longitude !== undefined
        ? gps.longitude.toFixed(6)
        : "\u2014";
    $("#s-gpsalt").textContent =
      gps.altitude !== null && gps.altitude !== undefined
        ? gps.altitude.toFixed(0) + "m"
        : "\u2014";

    // Color-code GPS card
    var gpsCard = $("#card-gps");
    gpsCard.className = "card";
    if (
      gps.fix_type === "NO_FIX" ||
      gps.fix_type === "0" ||
      gps.fix_type === "NO_GPS"
    )
      gpsCard.classList.add("health-warn");
    else if (gps.satellites >= 4) gpsCard.classList.add("health-ok");
    else gpsCard.classList.add("health-warn");

    // Power
    var pwr = s.power || {};
    var battText = "\u2014";
    if (pwr.battery_pct !== null && pwr.battery_pct !== undefined) {
      battText = pwr.battery_pct === 101 ? "External" : pwr.battery_pct + "%";
    }
    $("#s-battery").textContent = battText;
    $("#s-voltage").textContent = pwr.voltage
      ? pwr.voltage.toFixed(2) + "V"
      : "\u2014";
    $("#s-uptime").textContent = formatUptime(pwr.uptime_s || 0);
    $("#s-reboots").textContent =
      pwr.reboot_count !== undefined ? pwr.reboot_count : "\u2014";

    // Color-code power card
    var pwrCard = $("#card-power");
    pwrCard.className = "card";
    if (pwr.battery_pct !== null && pwr.battery_pct !== undefined) {
      if (pwr.battery_pct <= 10) pwrCard.classList.add("health-critical");
      else if (pwr.battery_pct <= 25) pwrCard.classList.add("health-warn");
      else pwrCard.classList.add("health-ok");
    }

    // Channels
    var chContainer = $("#s-channels");
    chContainer.innerHTML = "";
    if (s.channels && s.channels.length > 0) {
      s.channels.forEach(function (ch) {
        var row = document.createElement("div");
        row.className = "ch-row";
        row.innerHTML =
          '<span class="ch-idx">' +
          ch.index +
          "</span>" +
          '<span class="ch-name">' +
          escHtml(ch.name || "(default)") +
          "</span>" +
          '<span class="ch-psk psk-' +
          ch.psk_type +
          '">' +
          ch.psk_type.toUpperCase() +
          "</span>" +
          '<span class="ch-prec">prec=' +
          ch.position_precision +
          "</span>";
        chContainer.appendChild(row);
      });
    } else {
      chContainer.textContent = "No channels";
    }

    // Security
    var sec = s.security || {};
    $("#s-managed").textContent = sec.is_managed ? "YES" : "no";
    $("#s-managed").style.color = sec.is_managed
      ? "var(--red)"
      : "var(--green)";
    $("#s-serial").textContent = sec.serial_enabled ? "enabled" : "DISABLED";
    $("#s-serial").style.color = sec.serial_enabled
      ? "var(--green)"
      : "var(--yellow)";
    $("#s-adminkey").textContent = sec.admin_key_set ? "set" : "none";

    // Color-code security card
    var secCard = $("#card-security");
    secCard.className = "card";
    if (sec.is_managed) secCard.classList.add("health-critical");
    else secCard.classList.add("health-ok");

    // Mesh / nodedb
    var otherNodes = (s.nodedb || []).filter(function (n) {
      return n.node_id !== s.node_id;
    });
    $("#s-nodecount").textContent = otherNodes.length;
    var neighborsDiv = $("#s-neighbors");
    neighborsDiv.innerHTML = "";
    if (s.neighbors && s.neighbors.length > 0) {
      s.neighbors.slice(0, 8).forEach(function (nb) {
        var row = document.createElement("div");
        row.className = "kv";
        row.innerHTML =
          '<span class="k">' +
          escHtml(nb.short_name || nb.node_id) +
          "</span>" +
          '<span class="v">' +
          (nb.snr !== null ? nb.snr.toFixed(1) + " dB" : "\u2014") +
          "</span>";
        neighborsDiv.appendChild(row);
      });
    }

    // Color-code mesh card
    var meshCard = $("#card-mesh");
    meshCard.className = "card";
    if (otherNodes.length === 0) meshCard.classList.add("health-warn");
    else meshCard.classList.add("health-ok");
  }

  // ── Diagnostics ────────────────────────────────────────────────────────

  function initDiagnostics() {
    $("#btn-diagnose").addEventListener("click", runDiagnostics);
  }

  function runDiagnostics() {
    var btn = $("#btn-diagnose");
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span>Running\u2026';

    fetch("/api/diagnose")
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (findings) {
        renderFindings(findings);
      })
      .catch(function (err) {
        toast("Diagnostics failed: " + err.message, "error");
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = "Run Diagnostics";
      });
  }

  function renderFindings(findings) {
    var container = $("#diag-results");
    container.innerHTML = "";

    if (findings.length === 0) {
      container.innerHTML =
        '<div class="empty-desc" style="padding:20px;text-align:center">No findings</div>';
      return;
    }

    var sevIcons = {
      critical: "\ud83d\udd34",
      warning: "\ud83d\udfe1",
      info: "\ud83d\udd35",
      ok: "\ud83d\udfe2",
    };

    findings.forEach(function (f) {
      var div = document.createElement("div");
      div.className = "finding sev-" + f.severity;

      var icon = sevIcons[f.severity] || "\u2b55";
      div.innerHTML =
        '<div class="finding-icon">' +
        icon +
        "</div>" +
        '<div class="finding-text">' +
        '<div class="finding-title">' +
        escHtml(f.title) +
        "</div>" +
        '<div class="finding-desc">' +
        escHtml(f.description) +
        "</div>" +
        (f.recommendation
          ? '<div class="finding-rec">\u2192 ' +
            escHtml(f.recommendation) +
            "</div>"
          : "") +
        "</div>";

      // Tap a finding with a related_action to jump to Modify tab
      if (f.related_action) {
        div.addEventListener("click", function () {
          switchTab("modify");
        });
      }

      container.appendChild(div);
    });
  }

  // ── Modify ─────────────────────────────────────────────────────────────

  function initModify() {
    $$(".modify-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var action = btn.dataset.action;
        var params = {};
        try {
          params = JSON.parse(btn.dataset.params || "{}");
        } catch (e) {
          /* ignore */
        }
        var confirmMsg = btn.dataset.confirm;
        if (confirmMsg) {
          confirmModify(action, params, confirmMsg);
        } else {
          confirmModify(action, params, btn.textContent.trim());
        }
      });
    });

    // Set Owner button
    var ownerBtn = $("#btn-set-owner");
    if (ownerBtn) {
      ownerBtn.addEventListener("click", function () {
        var ln = $("#mod-longname").value.trim();
        var sn = $("#mod-shortname").value.trim();
        if (!ln && !sn) { toast("Enter a name", "error"); return; }
        confirmModify("set_owner", { long_name: ln, short_name: sn }, "Set owner: " + ln + " (" + sn + ")");
      });
    }

    // Load settings button
    var loadBtn = $("#btn-load-settings");
    if (loadBtn) {
      loadBtn.addEventListener("click", loadAllSettings);
    }

    // Settings filter
    var filterInput = $("#settings-filter");
    if (filterInput) {
      filterInput.addEventListener("input", function () {
        filterSettings(filterInput.value);
      });
    }
  }

  var allSettings = {};

  // Known enum options for Meshtastic settings
  var SETTING_ENUMS = {
    "device.role": ["CLIENT", "CLIENT_MUTE", "CLIENT_HIDDEN", "TRACKER", "LOST_AND_FOUND", "SENSOR", "TAK", "TAK_TRACKER", "REPEATER", "ROUTER", "ROUTER_CLIENT"],
    "device.rebroadcast_mode": ["ALL", "ALL_SKIP_DECODING", "LOCAL_ONLY", "KNOWN_ONLY", "NONE"],
    "lora.region": ["UNSET", "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH", "LORA_24", "UA_433", "UA_868", "MY_433", "MY_919", "SG_923"],
    "lora.modem_preset": ["LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "SHORT_SLOW", "SHORT_FAST", "MEDIUM_SLOW", "MEDIUM_FAST", "SHORT_TURBO"],
    "bluetooth.mode": ["RANDOM_PIN", "FIXED_PIN", "NO_PIN"],
    "position.gps_mode": ["DISABLED", "ENABLED", "NOT_PRESENT"],
    "display.gps_format": ["DEC", "DMS", "UTM", "MGRS", "OLC", "OSGR"],
    "display.units": ["METRIC", "IMPERIAL"],
    "display.oled": ["OLED_AUTO", "OLED_SSD1306", "OLED_SH1106", "OLED_SH1107"],
    "detection_sensor.detection_trigger_type": ["LOGIC_LOW", "LOGIC_HIGH"],
    "serial.mode": ["DEFAULT", "SIMPLE", "PROTO", "TEXTMSG", "NMEA", "CALTOPO"],
    "serial.baud": ["BAUD_DEFAULT", "BAUD_110", "BAUD_300", "BAUD_600", "BAUD_1200", "BAUD_2400", "BAUD_4800", "BAUD_9600", "BAUD_19200", "BAUD_38400", "BAUD_57600", "BAUD_115200", "BAUD_230400", "BAUD_460800", "BAUD_576000", "BAUD_921600"],
  };

  // Settings that are booleans (auto-detected too, but explicit list for safety)
  var BOOL_SETTINGS = [
    "bluetooth.enabled", "lora.tx_enabled", "lora.use_preset", "lora.sx126x_rx_boosted_gain",
    "lora.override_duty_cycle", "lora.config_ok_to_mqtt", "lora.ignore_mqtt",
    "position.position_broadcast_smart_enabled", "position.fixed_position",
    "power.is_power_saving", "device.serial_enabled", "device.led_heartbeat_disabled",
    "device.disable_triple_click", "device.double_tap_as_button_press", "device.is_managed",
    "display.compass_north_top", "display.flip_screen", "display.heading_bold",
    "display.wake_on_tap_or_motion", "display.use_12h_clock", "display.use_long_node_name",
    "network.wifi_enabled", "network.eth_enabled", "network.ipv6_enabled",
    "mqtt.enabled", "mqtt.json_enabled", "mqtt.tls_enabled", "mqtt.encryption_enabled",
    "mqtt.proxy_to_client_enabled", "mqtt.map_reporting_enabled",
    "serial.enabled", "serial.echo",
    "external_notification.enabled", "external_notification.active",
    "external_notification.alert_message", "external_notification.alert_message_buzzer",
    "external_notification.alert_message_vibra", "external_notification.alert_bell",
    "external_notification.alert_bell_buzzer", "external_notification.alert_bell_vibra",
    "external_notification.use_pwm", "external_notification.use_i2s_as_buzzer",
    "store_forward.enabled", "store_forward.is_server",
    "range_test.enabled", "range_test.save",
    "telemetry.device_telemetry_enabled", "telemetry.environment_measurement_enabled",
    "telemetry.environment_screen_enabled", "telemetry.environment_display_fahrenheit",
    "telemetry.air_quality_enabled", "telemetry.air_quality_screen_enabled",
    "telemetry.power_measurement_enabled", "telemetry.power_screen_enabled",
    "telemetry.health_measurement_enabled", "telemetry.health_screen_enabled",
    "neighbor_info.enabled", "neighbor_info.transmit_over_lora",
    "detection_sensor.enabled", "detection_sensor.send_bell", "detection_sensor.use_pullup",
    "paxcounter.enabled", "remote_hardware.enabled", "remote_hardware.allow_undefined_pin_access",
    "canned_message.enabled", "canned_message.rotary1_enabled", "canned_message.updown1_enabled",
    "canned_message.send_bell",
    "security.is_managed", "security.admin_channel_enabled", "security.debug_log_api_enabled",
    "security.serial_enabled",
  ];

  function loadAllSettings() {
    var btn = $("#btn-load-settings");
    btn.textContent = "Loading...";
    fetch("/api/settings")
      .then(function (r) { return r.json(); })
      .then(function (data) {
        allSettings = data;
        btn.textContent = "Reload";
        renderSettings(data);
      })
      .catch(function (err) {
        btn.textContent = "Load";
        toast("Failed to load settings: " + err.message, "error");
      });
  }

  function getSettingControl(key, val) {
    var valStr = (typeof val === "object") ? JSON.stringify(val) : String(val);

    // Check for enum (select/combobox)
    if (SETTING_ENUMS[key]) {
      var opts = SETTING_ENUMS[key];
      var html = '<select class="setting-edit" data-field="' + escHtml(key) + '">';
      opts.forEach(function (opt) {
        var sel = (opt === valStr) ? ' selected' : '';
        html += '<option value="' + escHtml(opt) + '"' + sel + '>' + escHtml(opt) + '</option>';
      });
      html += '</select>';
      return html;
    }

    // Check for boolean (toggle)
    if (BOOL_SETTINGS.indexOf(key) >= 0 || val === true || val === false) {
      var checked = (val === true || valStr === "true") ? ' checked' : '';
      return '<label class="toggle-label">' +
        '<input type="checkbox" class="setting-toggle" data-field="' + escHtml(key) + '"' + checked + '>' +
        '<span class="toggle-slider"></span>' +
        '</label>';
    }

    // Default: text input
    return '<input class="setting-edit" value="' + escHtml(valStr) + '" data-field="' + escHtml(key) + '">';
  }

  function renderSettings(settings) {
    var container = $("#settings-list");
    container.innerHTML = "";
    var keys = Object.keys(settings).sort();

    // Group by section
    var sections = {};
    keys.forEach(function (key) {
      var parts = key.split(".");
      var section = parts[0];
      if (!sections[section]) sections[section] = [];
      sections[section].push(key);
    });

    Object.keys(sections).sort().forEach(function (section) {
      var header = document.createElement("div");
      header.className = "settings-section-header";
      header.textContent = section;
      container.appendChild(header);

      sections[section].forEach(function (key) {
        var val = settings[key];
        var div = document.createElement("div");
        div.className = "setting-item";
        div.dataset.key = key.toLowerCase();

        var shortKey = key.split(".").slice(1).join(".");

        div.innerHTML =
          '<span class="setting-key">' + escHtml(shortKey || key) + '</span>' +
          '<span class="setting-control">' + getSettingControl(key, val) + '</span>' +
          '<button class="setting-apply" data-field="' + escHtml(key) + '">Set</button>';

        // Wire up the Set button
        div.querySelector(".setting-apply").addEventListener("click", function () {
          var field = this.dataset.field;
          var newVal;
          var toggle = div.querySelector(".setting-toggle");
          var select = div.querySelector("select.setting-edit");
          var input = div.querySelector("input.setting-edit");
          if (toggle) {
            newVal = toggle.checked ? "true" : "false";
          } else if (select) {
            newVal = select.value;
          } else if (input) {
            newVal = input.value;
          }
          confirmModify("set_any", { field: field, value: newVal }, "Set " + field + " = " + newVal);
        });

        // Auto-apply on toggle change (no need to hit Set)
        var toggle = div.querySelector(".setting-toggle");
        if (toggle) {
          toggle.addEventListener("change", function () {
            var field = this.dataset.field;
            var newVal = this.checked ? "true" : "false";
            confirmModify("set_any", { field: field, value: newVal }, "Set " + field + " = " + newVal);
          });
        }

        container.appendChild(div);
      });
    });
  }

  function filterSettings(query) {
    var q = query.toLowerCase();
    $$(".setting-item").forEach(function (item) {
      var key = item.dataset.key || "";
      item.style.display = key.indexOf(q) >= 0 ? "" : "none";
    });
    // Show/hide section headers based on whether they have visible items
    $$(".settings-section-header").forEach(function (header) {
      var next = header.nextElementSibling;
      var anyVisible = false;
      while (next && !next.classList.contains("settings-section-header")) {
        if (next.style.display !== "none") anyVisible = true;
        next = next.nextElementSibling;
      }
      header.style.display = anyVisible ? "" : "none";
    });
  }

  var pendingModify = null;

  function confirmModify(action, params, label) {
    pendingModify = { action: action, params: params };
    $("#confirm-title").textContent = "Confirm: " + label;
    var bodyHtml =
      "<p>Action: <strong>" +
      escHtml(action) +
      "</strong></p>" +
      "<p>Parameters:</p><pre>" +
      escHtml(JSON.stringify(params, null, 2)) +
      "</pre>" +
      "<p>Config will be auto-backed up before applying.</p>";
    $("#confirm-body").innerHTML = bodyHtml;
    $("#confirm-overlay").classList.remove("hidden");
  }

  function initDialog() {
    $("#confirm-cancel").addEventListener("click", function () {
      pendingModify = null;
      $("#confirm-overlay").classList.add("hidden");
    });

    $("#confirm-ok").addEventListener("click", function () {
      if (!pendingModify) return;
      var req = pendingModify;
      pendingModify = null;
      $("#confirm-overlay").classList.add("hidden");
      applyModify(req.action, req.params);
    });

    // Close on overlay tap
    $("#confirm-overlay").addEventListener("click", function (e) {
      if (e.target === $("#confirm-overlay")) {
        pendingModify = null;
        $("#confirm-overlay").classList.add("hidden");
      }
    });
  }

  function applyModify(action, params) {
    fetch("/api/modify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: action, params: params }),
    })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (result) {
        if (result.success) {
          toast(result.message, "ok");
          refreshBackups();
        } else {
          toast("Failed: " + result.message, "error");
        }
      })
      .catch(function (err) {
        toast("Modify error: " + err.message, "error");
      });
  }

  function manualBackup() {
    var btn = $("#btn-backup");
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span>Exporting\u2026';

    // Use the status endpoint to ensure we have current state, then trigger
    // a modify with a no-op that auto-backs up... or just call export directly
    // Actually we need a dedicated backup endpoint — for now, use modify with
    // a dummy action that we handle as backup-only. Or better: just hit diagnose
    // which doesn't modify anything, and rely on the modify auto-backup.
    // Simplest: add export_backup to the modify actions.
    fetch("/api/modify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: "export_backup_only",
        params: {},
      }),
    })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (result) {
        if (result.backup_id) {
          toast("Backup saved: " + result.backup_id, "ok");
        } else {
          toast(result.message || "Backup saved", "ok");
        }
        refreshBackups();
      })
      .catch(function (err) {
        toast("Backup failed: " + err.message, "error");
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = "Export Config Backup";
      });
  }

  function refreshBackups() {
    fetch("/api/backups")
      .then(function (r) {
        return r.json();
      })
      .then(function (backups) {
        renderBackups(backups);
      })
      .catch(function () {});
  }

  function renderBackups(backups) {
    var container = $("#backup-list");
    container.innerHTML = "";
    if (!backups || backups.length === 0) {
      container.innerHTML =
        '<div class="empty-desc" style="padding:10px">No backups yet</div>';
      return;
    }
    backups.slice(0, 10).forEach(function (b) {
      var div = document.createElement("div");
      div.className = "backup-item";
      var ts = new Date(b.timestamp);
      var sizeKb = (b.size_bytes / 1024).toFixed(1);
      div.innerHTML =
        '<div class="backup-info">' +
        '<div class="backup-name">' +
        escHtml(b.node_name || b.node_id) +
        "</div>" +
        '<div class="backup-meta">' +
        ts.toLocaleString() +
        " \u2022 " +
        sizeKb +
        " KB</div>" +
        "</div>" +
        '<button class="backup-restore" data-id="' +
        escAttr(b.id) +
        '">Restore</button>';
      container.appendChild(div);

      div
        .querySelector(".backup-restore")
        .addEventListener("click", function () {
          confirmRestore(b.id, b.node_name || b.node_id, ts);
        });
    });
  }

  function confirmRestore(backupId, nodeName, ts) {
    pendingModify = null;
    $("#confirm-title").textContent = "Restore Config?";
    $("#confirm-body").innerHTML =
      "<p>Restore config from backup:</p>" +
      "<p><strong>" +
      escHtml(nodeName) +
      "</strong></p>" +
      "<p>" +
      ts.toLocaleString() +
      "</p>" +
      "<p>Current config will be backed up first.</p>";
    $("#confirm-overlay").classList.remove("hidden");

    // Override the OK handler for restore
    var okBtn = $("#confirm-ok");
    var origHandler = okBtn.onclick;
    okBtn.onclick = function () {
      $("#confirm-overlay").classList.add("hidden");
      doRestore(backupId);
      okBtn.onclick = origHandler;
    };
  }

  function doRestore(backupId) {
    fetch("/api/restore/" + encodeURIComponent(backupId), { method: "POST" })
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (result) {
        toast(result.message || "Restored", "ok");
        refreshBackups();
      })
      .catch(function (err) {
        toast("Restore failed: " + err.message, "error");
      });
  }

  // ── Eject ──────────────────────────────────────────────────────────────

  function initEject() {
    $("#btn-eject").addEventListener("click", function () {
      // Just inform user — serial disconnect happens physically
      toast("Unplug the USB cable to disconnect", "warn");
    });
  }

  // ── Log tab ────────────────────────────────────────────────────────────

  function initLog() {
    $("#btn-refresh-log").addEventListener("click", refreshLog);
    $("#btn-export-log").addEventListener("click", exportLog);
  }

  function refreshLog() {
    fetch("/api/log")
      .then(function (r) {
        return r.json();
      })
      .then(function (entries) {
        renderLog(entries);
      })
      .catch(function () {});
  }

  function renderLog(entries) {
    var container = $("#log-entries");
    container.innerHTML = "";
    if (!entries || entries.length === 0) {
      container.innerHTML =
        '<div class="empty-desc" style="padding:20px;text-align:center">No log entries</div>';
      return;
    }
    // Show newest first
    entries
      .slice()
      .reverse()
      .forEach(function (e) {
        var div = document.createElement("div");
        div.className = "log-entry";
        var ts = new Date(e.timestamp);
        var timeStr =
          ts.toLocaleTimeString([], { hour12: false }) +
          "." +
          String(ts.getMilliseconds()).padStart(3, "0");
        var detail = e.node_id || "";
        if (e.details && Object.keys(e.details).length > 0) {
          detail += " " + JSON.stringify(e.details);
        }
        div.innerHTML =
          '<span class="log-time">' +
          timeStr +
          "</span>" +
          '<span class="log-event">' +
          escHtml(e.event) +
          "</span>" +
          '<span class="log-detail">' +
          escHtml(detail) +
          "</span>";
        container.appendChild(div);
      });
  }

  function exportLog() {
    fetch("/api/log")
      .then(function (r) {
        return r.json();
      })
      .then(function (entries) {
        var blob = new Blob([JSON.stringify(entries, null, 2)], {
          type: "application/json",
        });
        var url = URL.createObjectURL(blob);
        var a = document.createElement("a");
        a.href = url;
        a.download =
          "mesh-diag-log-" +
          new Date().toISOString().slice(0, 19).replace(/:/g, "") +
          ".json";
        a.click();
        URL.revokeObjectURL(url);
        toast("Log exported", "ok");
      })
      .catch(function (err) {
        toast("Export failed: " + err.message, "error");
      });
  }

  // ── Toast ──────────────────────────────────────────────────────────────

  var toastTimer = null;

  function toast(msg, type) {
    var el = $("#toast");
    el.textContent = msg;
    el.className = "toast toast-" + (type || "ok");
    el.classList.remove("hidden");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.add("hidden");
    }, 3000);
  }

  // ── Utilities ──────────────────────────────────────────────────────────

  function escHtml(str) {
    var div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
  }

  function escAttr(str) {
    return str.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
  }

  function formatUptime(seconds) {
    if (!seconds) return "\u2014";
    var d = Math.floor(seconds / 86400);
    var h = Math.floor((seconds % 86400) / 3600);
    var m = Math.floor((seconds % 3600) / 60);
    if (d > 0) return d + "d " + h + "h";
    if (h > 0) return h + "h " + m + "m";
    return m + "m";
  }

  // ── Init ───────────────────────────────────────────────────────────────

  function init() {
    initTabs();
    initDiagnostics();
    initModify();
    initDialog();
    initEject();
    initLog();
    connectWS();
    updateConnectionUI();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
