package org.pixel.customparts.parts;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.ViewConfiguration;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.pixel.customparts.R;
import org.pixel.customparts.dt2s.DT2SService;

import java.io.DataOutputStream;

public class LauncherPart implements Part {

    private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";
    private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";
    private static final String KEY_DT2S_INFO = "launcher_dt2s_info"; 
    private static final String KEY_DT2S_SLOP = "launcher_dt2s_slop";
    private static final String KEY_NATIVE_SEARCH = "pixel_launcher_native_search";

    private Context mContext;
    private SwitchPreferenceCompat mLauncherDt2sSwitch;
    private EditTextPreference mDt2sTimeoutPref;
    private EditTextPreference mDt2sSlopPref;
    private Preference mDt2sInfoPref;
    private SwitchPreferenceCompat mNativeSearchSwitch;

    @Override
    public void onCreate(PreferenceFragmentCompat fragment) {
        mContext = fragment.requireContext();

        mNativeSearchSwitch = fragment.findPreference(KEY_NATIVE_SEARCH);
        if (mNativeSearchSwitch != null) {
            mNativeSearchSwitch.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            boolean isSearchEnabled = Settings.Secure.getInt(mContext.getContentResolver(), KEY_NATIVE_SEARCH, 1) == 1;
            mNativeSearchSwitch.setChecked(isSearchEnabled);
        }

        mLauncherDt2sSwitch = fragment.findPreference(KEY_LAUNCHER_DT2S);
        mDt2sTimeoutPref = fragment.findPreference(KEY_DT2S_TIMEOUT);
        mDt2sInfoPref = fragment.findPreference(KEY_DT2S_INFO);
        mDt2sSlopPref = fragment.findPreference(KEY_DT2S_SLOP);

        if (mLauncherDt2sSwitch != null) {
            mLauncherDt2sSwitch.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            boolean enabled = Settings.Secure.getInt(mContext.getContentResolver(), KEY_LAUNCHER_DT2S, 0) == 1;
            mLauncherDt2sSwitch.setChecked(enabled);
            updateDt2sVisibility(enabled);
            updateService(enabled);
        }

        if (mDt2sTimeoutPref != null) {
            mDt2sTimeoutPref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            int val = Settings.Secure.getInt(mContext.getContentResolver(), KEY_DT2S_TIMEOUT, 150);
            mDt2sTimeoutPref.setSummary(mContext.getString(R.string.launcher_dt2s_timeout_summary, val));
        }

        if (mDt2sSlopPref != null) {
            mDt2sSlopPref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            int realSystemSlop = ViewConfiguration.get(mContext).getScaledDoubleTapSlop();
            mDt2sSlopPref.setDialogMessage(mContext.getString(R.string.launcher_dt2s_slop_dialog_msg, realSystemSlop));
            int currentVal = Settings.Secure.getInt(mContext.getContentResolver(), KEY_DT2S_SLOP, 0);
            updateSlopSummary(currentVal, realSystemSlop);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();

        if (KEY_NATIVE_SEARCH.equals(key)) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_NATIVE_SEARCH, enabled ? 1 : 0);
            
            String cmdValue = enabled ? "true" : "false";
            String command = "cmd device_config override launcher enable_one_search " + cmdValue +
                             " && am force-stop com.google.android.apps.nexuslauncher";
            
            new Thread(() -> runRootCommand(command)).start();
            return true;
        } 
        else if (KEY_LAUNCHER_DT2S.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_LAUNCHER_DT2S, isChecked ? 1 : 0);
            updateService(isChecked);
            updateDt2sVisibility(isChecked);
            return true;
        }
        else if (KEY_DT2S_TIMEOUT.equals(key)) {
            return handleTimeoutChange((String) newValue, KEY_DT2S_TIMEOUT, mDt2sTimeoutPref, R.string.launcher_dt2s_timeout_summary, true);
        }
        else if (KEY_DT2S_SLOP.equals(key)) {
            try {
                String input = (String) newValue;
                int value = input.isEmpty() ? 0 : Integer.parseInt(input);
                if (value < 0 || value > 600) {
                    Toast.makeText(mContext, R.string.launcher_dt2s_slop_error, Toast.LENGTH_LONG).show();
                    return false;
                }
                Settings.Secure.putInt(mContext.getContentResolver(), KEY_DT2S_SLOP, value);
                int realSystemSlop = ViewConfiguration.get(mContext).getScaledDoubleTapSlop();
                updateSlopSummary(value, realSystemSlop);
                updateService(true);
                return true;
            } catch (NumberFormatException e) {
                Toast.makeText(mContext, "Invalid number", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return false;
    }

    private void updateService(boolean enabled) {
        Intent serviceIntent = new Intent(mContext, DT2SService.class);
        if (enabled) mContext.startService(serviceIntent);
        else mContext.stopService(serviceIntent);
    }

    private void updateDt2sVisibility(boolean visible) {
        if (mDt2sTimeoutPref != null) mDt2sTimeoutPref.setVisible(visible);
        if (mDt2sSlopPref != null) mDt2sSlopPref.setVisible(visible); 
        if (mDt2sInfoPref != null) mDt2sInfoPref.setVisible(visible);
    }

    private void updateSlopSummary(int userVal, int systemVal) {
        if (mDt2sSlopPref == null) return;
        String text = (userVal <= 0) ? "Auto (" + systemVal + " px)" : userVal + " px";
        mDt2sSlopPref.setSummary(mContext.getString(R.string.launcher_dt2s_slop_summary, text));
    }

    private boolean handleTimeoutChange(String input, String settingsKey, EditTextPreference pref, int summaryResId, boolean restartService) {
        try {
            int value = Integer.parseInt(input);
            if (value < 10 || value > 2000) {
                Toast.makeText(mContext, R.string.launcher_dt2s_error, Toast.LENGTH_LONG).show();
                return false;
            }
            Settings.Secure.putInt(mContext.getContentResolver(), settingsKey, value);
            pref.setSummary(mContext.getString(summaryResId, value));
            if (restartService) updateService(true);
            return true;
        } catch (NumberFormatException e) {
            Toast.makeText(mContext, "Invalid number", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void runRootCommand(String command) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) {}
        }
    }
    @Override public void onResume() {}
    @Override public void onPause() {}
}