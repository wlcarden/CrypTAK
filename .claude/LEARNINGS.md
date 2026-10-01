# CrypTAK — Project Learnings

## [ANTIPATTERN] Meshtastic::CLI::SetAfterAdminKey
**Situation**: Ran `--set security.admin_channel_enabled true` after `--set security.admin_key`
**What failed**: The read phase failed (NO_CHANNEL from newly-set key), CLI built from empty defaults, write wiped the admin_key
**Cost**: admin_key lost, required reprovisioning
**Better approach**: Set both security fields in one `--configure` YAML (transaction path), or combine in a single `--set` call
**When to watch for**: Any `--set` on a node that already has `admin_key` configured
**Related**: session:2026-04-04, commit:8d2d8c8
**Tags**: #meshtastic #provisioning #security #destructive-write

## [ANTIPATTERN] Meshtastic::Profile::SerialDisable
**Situation**: relay.yaml had `serial_enabled: false` to reduce attack surface
**What failed**: `--configure` applies atomically; after reboot, USB serial is dead. No CLI access. BT also disabled by profile. Recovery required full flash erase + reflash via esptool.
**Cost**: ~30 min per device to recover (download mode + erase + flash + reprovision)
**Better approach**: Keep `serial_enabled: true` — serial requires physical possession, not a remote vector
**When to watch for**: Any embedded device profile that disables ALL communication interfaces
**Related**: session:2026-04-04, commit:8d2d8c8
**Tags**: #meshtastic #provisioning #serial #bricking

## [PATTERN] Meshtastic::ESP32S3::ConfigureVsSet
**Situation**: T-Beam S3 Core boards silently drop `--set` config writes
**Applied**: Use `--configure` (transaction-based: `beginSettingsTransaction`/`commitSettingsTransaction`) for all config that must persist
**Result**: `--configure` persists through power cycles; `--set` does not on ESP32-S3
**When to apply**: Any T-Beam S3 Core provisioning. RAK4631 boards are unaffected.
**Related**: session:2026-04-04
**Tags**: #meshtastic #esp32s3 #nvs #firmware-bug

## [PATTERN] Meshtastic::MeshAdmin::NoChannelAfterAdminKey
**Situation**: Set `security.admin_key` on relay node via mesh admin from BRG01
**Applied**: All subsequent `--get`/`--set` commands return NAK: NO_CHANNEL, even from authorized admin device
**Result**: Traceroute and sendtext still work (different protocol). Config writes may apply despite NAK but cannot be verified remotely.
**When to apply**: Expect NO_CHANNEL on any node with admin_key set. Physical serial access is the only reliable verification path.
**Related**: session:2026-04-04
**Tags**: #meshtastic #mesh-admin #firmware-bug #no-channel

## [PATTERN] Meshtastic::MQTT::UplinkEnabled
**Situation**: GW01 had MQTT module fully configured but published nothing to mosquitto
**Applied**: Set `--ch-set uplink_enabled true --ch-index 0` on the gateway node
**Result**: MQTT traffic immediately started flowing; mesh-relay received position + telemetry
**When to apply**: Any Meshtastic MQTT gateway setup. `uplinkEnabled` is a per-channel setting NOT covered by `--configure` YAML profiles.
**Related**: session:2026-04-04, commit:28223a9
**Tags**: #meshtastic #mqtt #gateway #uplink

## [WORKFLOW] Meshtastic::ESP32S3::FlashRecovery
**Situation**: T-Beam S3 Core locked out (serial_enabled=false, BT disabled)
**Applied**: Download mode (hold BOOT + press RST), then `esptool erase-flash` + `write-flash`
**Result**: Clean firmware restored, full reprovision successful
**Gotcha**: Hard reset after erase kicks out of download mode — must re-enter before write
**Command**: `esptool --chip esp32s3 --port /dev/ttyACMx --baud 921600 erase-flash && esptool ... write-flash 0x0 firmware.bin`
**Related**: session:2026-04-04
**Tags**: #meshtastic #esp32s3 #esptool #recovery

---

## Cross-Session Patterns (2026-03 → 2026-04)

## [PATTERN] Meshtastic::Hardware::BoardSpecificFailureModes
**Situation**: Each board family has a distinct, recurring failure mode
**Pattern**:
- **RAK4631**: Firmware hangs (solid red LED). DTR toggle recovery. Recurred since 2026-03-02.
- **T-Beam S3 Core**: NVS write failures — `--set` doesn't persist, requires `--configure` transactions. Both RPT02 and RPT03 affected.
- **T-Beam (original)**: TCP idle timeout at ~130s. Requires application-level heartbeat.
- **RAK11200 (WisMesh)**: Most reliable for remote provisioning. No board-specific issues.
**When to apply**: Choose provisioning strategy based on `hwModel` detected in step 2 of provision.sh. Consider adding `hw_quirks` to nodes.yaml.
**Related**: sessions:2026-03-02, 2026-03-17, 2026-04-04
**Tags**: #meshtastic #hardware #reliability #board-specific

## [ANTIPATTERN] Meshtastic::Provisioning::FalseConfidence
**Situation**: provision.sh remote mode reports `✓ device: 4 settings applied` when settings didn't persist
**What failed**: The `✓` marks mean the CLI didn't error, not that the setting persisted. On T-Beam S3 Core, `--set` via mesh admin produces clean-looking output but NVS writes silently fail. RPT02 showed `✓` for role=ROUTER across multiple provision runs but remained CLIENT.
**Cost**: Hours of debugging (traceroute, MQTT capture, nodedb analysis) to discover the role never changed.
**Better approach**: Add read-back verification to provision.sh — after each critical write, `--get` the setting and compare. At minimum, verify `device.role` after step 4.
**When to watch for**: Any provisioning output that reports success without read-back confirmation.
**Related**: sessions:2026-03-05 (BSE01 drift), 2026-04-04 (RPT02 false positives)
**Tags**: #meshtastic #provisioning #verification #silent-failure

## [DECISION] Meshtastic::Admin::RemoteAdminUnreliable
**Decision**: Remote mesh admin is unreliable for config writes; treat as best-effort only
**Evolution**:
- 2026-03-05: Admin key concept introduced for security
- 2026-04-03: NAK issues discovered during remote provisioning
- 2026-04-04: Confirmed NO_CHANNEL blocks all admin after key is set; `--set` silently fails on ESP32-S3
**Current reality**: Remote mesh admin works for one-shot channel/PSK changes on RAK boards WITHOUT admin_key set. For anything else, physical serial access is required.
**Would reconsider if**: Meshtastic firmware fixes NO_CHANNEL response routing for PKC-authenticated admin
**Related**: sessions:2026-03-05, 2026-04-03, 2026-04-04
**Tags**: #meshtastic #mesh-admin #architecture #remote-provisioning

## [WORKFLOW] Meshtastic::Provisioning::CorrectOrder
**Problem**: Provisioning order matters — wrong sequence wipes settings or bricks devices
**Solution**: Established through painful iteration across multiple sessions:
```
1. Flash firmware (if needed)           — esptool, clean slate
2. --configure profile.yaml             — atomic transaction, sets role/radio/power
3. Wait 20s for reboot
4. --set-owner name + short             — identity (serial only, doesn't work via mesh)
5. --ch-add + --ch-set with 15s delays  — channels
6. --set security.admin_key             — PKC auth (BEFORE admin_channel_enabled)
7. --configure security.yaml            — admin_channel_enabled (transaction path, NOT --set)
8. Verify via --info                    — read-back critical settings
9. Power cycle test                     — confirm NVS persistence
```
**Critical ordering**: Step 7 MUST use `--configure` not `--set` — the read-modify-write in `--set` wipes the admin_key from step 6. Step 4 doesn't work via mesh admin.
**Related**: sessions:2026-04-03, 2026-04-04
**Tags**: #meshtastic #provisioning #workflow #ordering


## [PATTERN] Meshtastic::Firmware::RoleEnforcedDefaults
**Situation**: Settings applied via `--configure` appear to revert after reboot on ROUTER role
**Root cause**: `installRoleDefaults()` in NodeDB.cpp overrides certain settings on every boot based on device role. This is NOT an NVS persistence failure — the values are in NVS but the firmware ignores them.
**ROUTER role enforced settings**:
- `rebroadcast_mode` → forced to `CORE_PORTNUMS_ONLY` (cannot set to ALL)
- `telemetry.device_update_interval` → forced to `default_telemetry_broadcast_interval_secs`
- `node_info_broadcast_secs` → reset via `initConfigIntervals()`
- `owner.is_unmessagable` → forced to true
**TRACKER role enforced settings**:
- `telemetry.device_update_interval` → forced to default
- `owner.is_unmessagable` → forced to true
**Other roles with enforced settings**:
- `CLIENT_HIDDEN`: rebroadcast_mode → LOCAL_ONLY, all broadcast intervals → MAX_INTERVAL
- `TAK`: node_info_broadcast_secs → ONE_DAY, position_broadcast_secs → ONE_DAY, device_update_interval → ONE_DAY
- `TAK_TRACKER`: node_info_broadcast_secs → ONE_DAY, smart position enabled, device_update_interval → ONE_DAY
- `SENSOR`: device_update_interval → default, environment_measurement_enabled → true, environment_update_interval → 300
- `LOST_AND_FOUND`: smart position disabled, position_broadcast_secs → 300
**When to apply**: Do NOT set firmware-enforced values in profile YAMLs — they will be silently overridden. Comment them out with an explanation of why.
**Source**: https://github.com/meshtastic/firmware/blob/master/src/mesh/NodeDB.cpp (`installRoleDefaults()`)
**Related**: sessions:2026-04-08 (RPT03 provisioning investigation)
**Tags**: #meshtastic #firmware #provisioning #nvs #router #roles

## [PATTERN] Meshtastic::Provisioning::SingleConfigureTransaction
**Situation**: T-Beam S3 Core NVS writes appear to fail when multiple reboots occur during provisioning
**Root cause**: Each reboot from `--set` commands that touch the device section can corrupt NVS writes from a prior `--configure` transaction that haven't fully flushed. The --set-owner command also triggers an unexpected reboot.
**Pattern**: Use a single `--configure` YAML with all settings, allow ONE reboot, then set owner via Bluetooth app (which doesn't trigger a reboot). Never run `--set` commands after `--configure` on S3 Core boards.
**What persists reliably with single --configure on S3 Core**: role, serialEnabled, positionBroadcastSecs, positionBroadcastSmartEnabled, gpsEnabled, gpsUpdateInterval, isPowerSaving, autoScreenCarouselSecs, lora settings (region, hopLimit, txPower), bluetooth, deviceTelemetryEnabled
**What never persists (firmware-enforced, not NVS bug)**: rebroadcastMode, nodeInfoBroadcastSecs, deviceUpdateInterval for ROUTER role
**Safety rail**: Never set bluetooth.enabled=false during initial provisioning on ESP32-S3 boards — it can kill USB CDC. Disable BT via remote admin post-deployment.
**Tags**: #meshtastic #provisioning #tbeam-s3-core #nvs #esp32-s3