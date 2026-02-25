package com.atakmap.android.meshtastic;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.atakmap.android.meshtastic.encryption.AppLayerEncryptionManager;
import com.atakmap.android.meshtastic.encryption.DataPackageExporter;
import com.atakmap.android.meshtastic.encryption.KeyQrDialog;
import com.atakmap.android.meshtastic.encryption.KeyQrScanDialog;
import com.atakmap.android.meshtastic.encryption.MeshtasticConfigImporter;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.preference.PluginPreferenceFragment;

public class PluginPreferencesFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int REQUEST_IMPORT_PACKAGE = 1338;

    @SuppressLint("StaticFieldLeak")
    private static Context pluginContext;

    private SharedPreferences prefs;

    public PluginPreferencesFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public PluginPreferencesFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        this.pluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new ProtectedSharedPreferences(
                PreferenceManager.getDefaultSharedPreferences(getActivity()));
        updatePskSummary();
        updateEpochDisplay();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
        updatePskSummary();
        updateEpochDisplay();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;
        if (Constants.PREF_PLUGIN_ENCRYPTION_PSK.equals(key)
                || Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN.equals(key)) {
            updatePskSummary();
        }
        if (Constants.PREF_PLUGIN_EPOCH_ROTATION.equals(key)
                || Constants.PREF_PLUGIN_EPOCH_INTERVAL.equals(key)
                || Constants.PREF_PLUGIN_EXTRA_ENCRYPTION.equals(key)) {
            applyEpochRotationSettings();
            updateEpochDisplay();
        }
    }

    /** Reflects the current PSK state in the preference summary. */
    private void updatePskSummary() {
        Preference pskPref = findPreference(Constants.PREF_PLUGIN_ENCRYPTION_PSK);
        if (pskPref == null || prefs == null) return;
        String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");
        if (psk.isEmpty()) {
            pskPref.setSummary("Tap to configure");
            return;
        }
        String origin = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN, "manual");
        switch (origin) {
            case "generated": pskPref.setSummary("Key generated — share via QR to sync team"); break;
            case "scanned":   pskPref.setSummary("Key imported via QR"); break;
            case "package":   pskPref.setSummary("Key imported from onboarding package"); break;
            default:          pskPref.setSummary("Key set manually — share via QR to sync team"); break;
        }
    }

    private void applyEpochRotationSettings() {
        if (prefs == null) return;
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        boolean encEnabled = prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false);
        boolean epochEnabled = prefs.getBoolean(Constants.PREF_PLUGIN_EPOCH_ROTATION, false);
        if (encEnabled && epochEnabled) {
            int intervalHours;
            try {
                intervalHours = Integer.parseInt(
                        prefs.getString(Constants.PREF_PLUGIN_EPOCH_INTERVAL, "6"));
            } catch (NumberFormatException e) {
                intervalHours = 6;
            }
            encManager.enableEpochRotation(intervalHours * 60L * 60L * 1000L);
        } else {
            encManager.disableEpochRotation();
        }
    }

    private void updateEpochDisplay() {
        Preference epochPref = findPreference(Constants.PREF_PLUGIN_EPOCH_CURRENT);
        if (epochPref == null) return;
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        boolean encEnabled = prefs != null && prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false);
        boolean epochEnabled = prefs != null && prefs.getBoolean(Constants.PREF_PLUGIN_EPOCH_ROTATION, false);
        if (encEnabled && epochEnabled && encManager.isEpochRotationEnabled()) {
            epochPref.setSummary("Epoch " + encManager.getCurrentEpoch());
        } else if (encEnabled && !epochEnabled) {
            epochPref.setSummary("Epoch rotation disabled");
        } else {
            epochPref.setSummary("Not active");
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        if (Constants.PREF_PLUGIN_ENCRYPTION_PSK.equals(preference.getKey())) {
            Activity activity = getActivity();
            if (activity == null) return true;
            showPskDialog(activity);
            return true;
        }
        if ("plugin_meshtastic_scan_key_qr".equals(preference.getKey())) {
            Activity activity = getActivity();
            if (activity == null) return true;
            KeyQrScanDialog.show(activity, scanned -> {
                prefs.edit()
                        .putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, scanned)
                        .putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN, "scanned")
                        .apply();
                Toast.makeText(activity, "Encryption key imported", Toast.LENGTH_SHORT).show();
            });
            return true;
        }
        if ("plugin_meshtastic_show_key_qr".equals(preference.getKey())) {
            Activity activity = getActivity();
            if (activity == null) return true;
            String pskValue = prefs != null
                    ? prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "").trim() : "";
            KeyQrDialog.show(activity, pskValue);
            return true;
        }
        if ("plugin_meshtastic_export_data_package".equals(preference.getKey())) {
            Activity activity = getActivity();
            if (activity == null) return true;
            try {
                DataPackageExporter.generateAndShare(activity, prefs);
            } catch (java.io.IOException e) {
                Toast.makeText(pluginContext, "Export failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("plugin_meshtastic_import_data_package".equals(preference.getKey())) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/octet-stream"});
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, REQUEST_IMPORT_PACKAGE);
            return true;
        }
        return super.onPreferenceTreeClick(screen, preference);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_PACKAGE
                && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            // Pass ATAK's activity context so prefs are written to the same file
            // that AppLayerEncryptionManager.initialize(view.getContext()) reads.
            boolean ok = MeshtasticConfigImporter.applyFromZipUri(getActivity(), uri);
            Toast.makeText(pluginContext,
                    ok ? "Configuration imported" : "Import failed — check the file",
                    Toast.LENGTH_LONG).show();
            if (ok) updatePskSummary();
        }
    }

    /**
     * Custom PSK dialog: EditText for manual entry + Save / Cancel / Generate New Key.
     * The Generate button shows a confirmation before populating the EditText;
     * the user still taps Save to commit.
     */
    private void showPskDialog(Activity activity) {
        String current = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");

        EditText editText = new EditText(activity);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setText(current);
        editText.setSelection(current.length());
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        editText.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Pre-Shared Key (PSK)")
                .setMessage("All team members must share the same key. Use Generate or import via QR.")
                .setView(editText)
                .setPositiveButton("Save", null)          // wired below to prevent auto-dismiss
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Generate New Key", null) // wired below
                .create();

        dialog.show();

        // Wire Save — validates non-empty, then commits.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = editText.getText().toString().trim();
            if (value.isEmpty()) {
                editText.setError("Key cannot be empty");
                return;
            }
            prefs.edit()
                    .putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, value)
                    .putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN, "manual")
                    .apply();
            dialog.dismiss();
        });

        // Wire Generate — confirmation first, then fills EditText without dismissing dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            new AlertDialog.Builder(activity)
                    .setTitle("Generate New Key?")
                    .setMessage("This will replace the current PSK with a new random AES-256 key. "
                            + "All team members will need the new key — share it via QR code.")
                    .setPositiveButton("Generate", (d2, w) -> {
                        byte[] keyBytes = new byte[32];
                        new java.security.SecureRandom().nextBytes(keyBytes);
                        String generated = android.util.Base64.encodeToString(
                                keyBytes, android.util.Base64.NO_WRAP);
                        editText.setText(generated);
                        editText.setSelection(generated.length());
                        // Mark origin now; Save will commit the value.
                        prefs.edit().putString(
                                Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN, "generated").apply();
                        Toast.makeText(activity, "Key generated — tap Save to apply",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.preferences_title));
    }
}
