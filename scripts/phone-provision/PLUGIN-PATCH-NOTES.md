# CrypTAK Plugin Patch Notes

## Version: 1.1.40 (patched)
## Date: 2026-03-15

### Problem
Pre-built plugin APK declared atakApiVersion `com.atakmap.app@5.5.1.8.CIV`
but the ATAK CIV debug APK (5.5.1.8) broadcasts `com.atakmap.app@5.5.1.CIV`
(no patch version in the broadcast). ATAK rejected the plugin as incompatible.

### Fix (applied with apktool)
1. Decoded APK with apktool
2. Patched AndroidManifest.xml: `5.5.1.8.CIV` → `5.5.1.CIV`
3. Updated res/values/strings.xml: Meshtastic Plugin → CrypTAK Plugin, CrypTAK branding
4. Replaced launcher icons with CrypTAK assets from plugin/app/src/main/res/
5. Rebuilt and signed with debug keystore

### To regenerate
```bash
cd ~/Desktop/CrypTAK
java -jar /tmp/apktool.jar d -f apks/releases/ATAK-Plugin-*.apk -o /tmp/plugin-decoded
# Apply patches (see above)
java -jar /tmp/apktool.jar b /tmp/plugin-decoded -o /tmp/plugin-unsigned.apk
apksigner sign --ks /tmp/debug.jks --ks-pass pass:android --key-pass pass:android \
  --out phone-provision/apks/CrypTAK-Plugin.apk /tmp/plugin-unsigned.apk
```

### Long-term fix
Build plugin from source with ATAK_VERSION = "5.5.1" (not 5.5.1.8) against
the debug ATAK APK stubs. Requires TAK.gov Maven credentials for SDK.
