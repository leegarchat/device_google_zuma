package org.pixel.customparts.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.pixel.customparts.R;

public class DozePart implements Part {

    private static final String KEY_DOZE_DOUBLE_TAP = "doze_double_tap_hook";
    private static final String KEY_DOZE_TIMEOUT = "doze_double_tap_timeout";
    private static final String KEY_DOZE_INFO = "doze_double_tap_info"; 

    private Context mContext;
    private SwitchPreferenceCompat mDoubleTapSwitch;
    private EditTextPreference mDozeTimeoutPref;
    private Preference mDozeInfoPref;
    private SettingsObserver mSettingsObserver;

    @Override
    public void onCreate(PreferenceFragmentCompat fragment) {
        mContext = fragment.requireContext();
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));

        mDoubleTapSwitch = fragment.findPreference(KEY_DOZE_DOUBLE_TAP);
        mDozeTimeoutPref = fragment.findPreference(KEY_DOZE_TIMEOUT);
        mDozeInfoPref = fragment.findPreference(KEY_DOZE_INFO);

        if (mDoubleTapSwitch != null) {
            mDoubleTapSwitch.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            updateDoubleTapState(); 
        }
        
        if (mDozeTimeoutPref != null) {
            mDozeTimeoutPref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            int val = Settings.Secure.getInt(mContext.getContentResolver(), KEY_DOZE_TIMEOUT, 200);
            mDozeTimeoutPref.setSummary(mContext.getString(R.string.doze_double_tap_timeout_summary, val));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_DOZE_DOUBLE_TAP.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_DOZE_DOUBLE_TAP, isChecked ? 1 : 0);
            updateDoubleTapState(isChecked);
            return true;
        }
        else if (KEY_DOZE_TIMEOUT.equals(key)) {
            try {
                int value = Integer.parseInt((String) newValue);
                Settings.Secure.putInt(mContext.getContentResolver(), KEY_DOZE_TIMEOUT, value);
                mDozeTimeoutPref.setSummary(mContext.getString(R.string.doze_double_tap_timeout_summary, value));
                return true;
            } catch (NumberFormatException e) {
                Toast.makeText(mContext, "Invalid number", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return false;
    }

    @Override
    public void onResume() {
        if (mSettingsObserver != null) mSettingsObserver.register(mContext.getContentResolver());
        updateDoubleTapState();
    }

    @Override
    public void onPause() {
        if (mSettingsObserver != null) mSettingsObserver.unregister(mContext.getContentResolver());
    }

    private void updateDoubleTapState() {
        boolean enabled = Settings.Secure.getInt(mContext.getContentResolver(), KEY_DOZE_DOUBLE_TAP, 0) == 1;
        if (mDoubleTapSwitch != null) mDoubleTapSwitch.setChecked(enabled);
        updateDoubleTapState(enabled);
    }

    private void updateDoubleTapState(boolean enabled) {
        if (mDozeTimeoutPref != null) mDozeTimeoutPref.setVisible(enabled);
        if (mDozeInfoPref != null) mDozeInfoPref.setVisible(enabled);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) { super(handler); }
        void register(ContentResolver cr) { cr.registerContentObserver(Settings.Secure.getUriFor(KEY_DOZE_DOUBLE_TAP), false, this); }
        void unregister(ContentResolver cr) { cr.unregisterContentObserver(this); }
        @Override
        public void onChange(boolean selfChange, Uri uri) { super.onChange(selfChange, uri); updateDoubleTapState(); }
    }
}