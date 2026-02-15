package com.atakmap.android.meshtastic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.atakmap.android.meshtastic.encryption.AppLayerEncryptionManager;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.preference.PluginPreferenceFragment;

public class PluginPreferencesFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

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
                PreferenceManager.getDefaultSharedPreferences(
                        getActivity()));

        updateEpochDisplay();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prefs != null) {
            prefs.registerOnSharedPreferenceChangeListener(this);
        }
        updateEpochDisplay();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;

        if (Constants.PREF_PLUGIN_EPOCH_ROTATION.equals(key)
                || Constants.PREF_PLUGIN_EPOCH_INTERVAL.equals(key)
                || Constants.PREF_PLUGIN_EXTRA_ENCRYPTION.equals(key)) {
            applyEpochRotationSettings();
            updateEpochDisplay();
        }
    }

    /**
     * Apply epoch rotation settings to the encryption manager based on current preferences.
     */
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
            long epochDurationMs = intervalHours * 60L * 60L * 1000L;
            encManager.enableEpochRotation(epochDurationMs);
        } else {
            encManager.disableEpochRotation();
        }
    }

    /**
     * Update the read-only "Current Epoch" preference summary.
     */
    private void updateEpochDisplay() {
        Preference epochPref = findPreference(Constants.PREF_PLUGIN_EPOCH_CURRENT);
        if (epochPref == null) return;

        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        boolean encEnabled = prefs != null
                && prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false);
        boolean epochEnabled = prefs != null
                && prefs.getBoolean(Constants.PREF_PLUGIN_EPOCH_ROTATION, false);

        if (encEnabled && epochEnabled && encManager.isEpochRotationEnabled()) {
            int epoch = encManager.getCurrentEpoch();
            epochPref.setSummary("Epoch " + epoch);
        } else if (encEnabled && !epochEnabled) {
            epochPref.setSummary("Epoch rotation disabled");
        } else {
            epochPref.setSummary("Not active");
        }
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.preferences_title));
    }
}
