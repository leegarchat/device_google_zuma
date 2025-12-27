package org.pixel.customparts.parts;

import android.content.Context;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.pixel.customparts.ImsManager;

public class ImsPart implements Part {

    private Context mContext;

    @Override
    public void onCreate(PreferenceFragmentCompat fragment) {
        mContext = fragment.requireContext();
        
        setupSwitch(fragment, ImsManager.KEY_VOLTE);
        setupSwitch(fragment, ImsManager.KEY_WFC);
        setupSwitch(fragment, ImsManager.KEY_VT);
        setupSwitch(fragment, ImsManager.KEY_VONR);
        setupSwitch(fragment, ImsManager.KEY_CROSS_SIM);
        setupSwitch(fragment, ImsManager.KEY_UT);
        setupSwitch(fragment, ImsManager.KEY_5G);
        setupSwitch(fragment, ImsManager.KEY_5G_THRESH);
    }

    private void setupSwitch(PreferenceFragmentCompat fragment, String key) {
        SwitchPreferenceCompat pref = fragment.findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            // Если настройка не задана, используем 0 (false)
            boolean enabled = Settings.Secure.getInt(mContext.getContentResolver(), key, 0) == 1;
            pref.setChecked(enabled);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (ImsManager.KEY_VOLTE.equals(key) || 
            ImsManager.KEY_WFC.equals(key) || 
            ImsManager.KEY_VT.equals(key) ||
            ImsManager.KEY_VONR.equals(key) ||
            ImsManager.KEY_CROSS_SIM.equals(key) ||
            ImsManager.KEY_UT.equals(key) ||
            ImsManager.KEY_5G.equals(key) ||
            ImsManager.KEY_5G_THRESH.equals(key)) {
            
            boolean enabled = (Boolean) newValue;
            
            Settings.Secure.putInt(mContext.getContentResolver(), key, enabled ? 1 : 0);
            new Thread(() -> ImsManager.updateImsProfile(mContext)).start();
            
            Toast.makeText(mContext, "Settings updated. Toggle Airplane Mode to apply.", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    @Override public void onResume() {}
    @Override public void onPause() {}
}