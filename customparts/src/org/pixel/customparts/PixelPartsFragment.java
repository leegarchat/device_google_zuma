package org.pixel.customparts;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;
import android.view.ViewConfiguration;
import android.util.Log; // Добавлено

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.pixel.customparts.dt2s.DT2SService;

import java.io.DataOutputStream; // Добавлено

public class PixelPartsFragment extends PreferenceFragmentCompat 
        implements Preference.OnPreferenceChangeListener {

    // --- KEYS ---
    private static final String KEY_DOZE_DOUBLE_TAP = "doze_double_tap_hook";
    private static final String KEY_DOZE_TIMEOUT = "doze_double_tap_timeout";
    private static final String KEY_DOZE_INFO = "doze_double_tap_info"; 
    
    private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";
    private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";
    private static final String KEY_DT2S_INFO = "launcher_dt2s_info"; 
    private static final String KEY_DT2S_SLOP = "launcher_dt2s_slop";

    private static final String KEY_NATIVE_SEARCH = "pixel_launcher_native_search"; // НОВЫЙ КЛЮЧ
    
    // --- UI OBJECTS ---
    private SwitchPreferenceCompat mDoubleTapSwitch;
    private EditTextPreference mDozeTimeoutPref;
    private Preference mDozeInfoPref;
    
    private SwitchPreferenceCompat mLauncherDt2sSwitch;
    private EditTextPreference mDt2sTimeoutPref;
    private Preference mDt2sInfoPref; 
    private EditTextPreference mDt2sSlopPref;
    
    private SwitchPreferenceCompat mNativeSearchSwitch; // НОВЫЙ ОБЪЕКТ

    private ListPreference mThermalBatteryPref;
    private ListPreference mThermalSocPref;

    private SettingsObserver mSettingsObserver;
        
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pixel_custom_parts_settings, rootKey);
        
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        ContentResolver resolver = requireContext().getContentResolver();

        // --- 0. Launcher Native Search Setup (НОВОЕ) ---
        mNativeSearchSwitch = findPreference(KEY_NATIVE_SEARCH);
        if (mNativeSearchSwitch != null) {
            mNativeSearchSwitch.setOnPreferenceChangeListener(this);
            // По умолчанию включено (1)
            boolean isSearchEnabled = Settings.Secure.getInt(resolver, KEY_NATIVE_SEARCH, 1) == 1;
            mNativeSearchSwitch.setChecked(isSearchEnabled);
        }

        // --- 1. DT2W (Doze) Setup ---
        mDoubleTapSwitch = findPreference(KEY_DOZE_DOUBLE_TAP);
        mDozeTimeoutPref = findPreference(KEY_DOZE_TIMEOUT);
        mDozeInfoPref = findPreference(KEY_DOZE_INFO);

        if (mDoubleTapSwitch != null) {
            mDoubleTapSwitch.setOnPreferenceChangeListener(this);
            updateDoubleTapState(); 
        }
        
        if (mDozeTimeoutPref != null) {
            mDozeTimeoutPref.setOnPreferenceChangeListener(this);
            int val = Settings.Secure.getInt(resolver, KEY_DOZE_TIMEOUT, 200);
            mDozeTimeoutPref.setSummary(getString(R.string.doze_double_tap_timeout_summary, val));
        }

        // --- 2. DT2S (Launcher) Setup ---
        mLauncherDt2sSwitch = findPreference(KEY_LAUNCHER_DT2S);
        mDt2sTimeoutPref = findPreference(KEY_DT2S_TIMEOUT);
        mDt2sInfoPref = findPreference(KEY_DT2S_INFO);
        mDt2sSlopPref = findPreference(KEY_DT2S_SLOP);

        // Настройка Slop Preference
        if (mDt2sSlopPref != null) {
            mDt2sSlopPref.setOnPreferenceChangeListener(this);
            
            int realSystemSlop = ViewConfiguration.get(requireContext()).getScaledDoubleTapSlop();
            mDt2sSlopPref.setDialogMessage(getString(R.string.launcher_dt2s_slop_dialog_msg, realSystemSlop));
            int currentVal = Settings.Secure.getInt(resolver, KEY_DT2S_SLOP, 0);
            updateSlopSummary(currentVal, realSystemSlop);
        }

        if (mLauncherDt2sSwitch != null) {
            mLauncherDt2sSwitch.setOnPreferenceChangeListener(this);
            boolean enabled = Settings.Secure.getInt(resolver, KEY_LAUNCHER_DT2S, 0) == 1;
            mLauncherDt2sSwitch.setChecked(enabled);
            
            Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
            if (enabled) {
                requireContext().startService(serviceIntent);
            } else {
                requireContext().stopService(serviceIntent);
            }

            updateDt2sVisibility(enabled);
        }

        if (mDt2sTimeoutPref != null) {
            mDt2sTimeoutPref.setOnPreferenceChangeListener(this);
            int val = Settings.Secure.getInt(resolver, KEY_DT2S_TIMEOUT, 150);
            mDt2sTimeoutPref.setSummary(getString(R.string.launcher_dt2s_timeout_summary, val));
        }

        // --- 3. Thermal Init ---
        mThermalBatteryPref = findPreference(ThermalUtils.KEY_THERMAL_BATTERY);
        if (mThermalBatteryPref != null) {
            mThermalBatteryPref.setOnPreferenceChangeListener(this);
            updateThermalSummaryAndIcon(mThermalBatteryPref, mThermalBatteryPref.getValue(), true);
        }

        mThermalSocPref = findPreference(ThermalUtils.KEY_THERMAL_SOC);
        if (mThermalSocPref != null) {
            mThermalSocPref.setOnPreferenceChangeListener(this);
            updateThermalSummaryAndIcon(mThermalSocPref, mThermalSocPref.getValue(), false);
        }
    }

    private void updateSlopSummary(int userVal, int systemVal) {
        if (mDt2sSlopPref == null) return;
        if (userVal <= 0) {
            mDt2sSlopPref.setSummary(getString(R.string.launcher_dt2s_slop_summary, "Auto (" + systemVal + " px)"));
        } else {
            mDt2sSlopPref.setSummary(getString(R.string.launcher_dt2s_slop_summary, userVal + " px"));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mSettingsObserver != null) mSettingsObserver.register(requireContext().getContentResolver());
        updateDoubleTapState();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSettingsObserver != null) mSettingsObserver.unregister(requireContext().getContentResolver());
    }

    private void updateDoubleTapState() {
        if (mDoubleTapSwitch == null) return;
        boolean enabled = Settings.Secure.getInt(requireContext().getContentResolver(), KEY_DOZE_DOUBLE_TAP, 0) == 1;
        mDoubleTapSwitch.setChecked(enabled);
        if (mDozeTimeoutPref != null) mDozeTimeoutPref.setVisible(enabled);
        if (mDozeInfoPref != null) mDozeInfoPref.setVisible(enabled);
    }

    private void updateDt2sVisibility(boolean visible) {
        if (mDt2sTimeoutPref != null) mDt2sTimeoutPref.setVisible(visible);
        if (mDt2sSlopPref != null) mDt2sSlopPref.setVisible(visible); 
        if (mDt2sInfoPref != null) mDt2sInfoPref.setVisible(visible);
    }

    private void updateThermalSummaryAndIcon(ListPreference pref, String value, boolean isBattery) {
        if (pref == null || value == null) return;
        int summaryResId;
        int iconResId;
        switch (value) {
            case "medium": summaryResId = isBattery ? R.string.thermal_battery_summary_medium : R.string.thermal_soc_summary_medium; iconResId = R.drawable.ic_medium; break;
            case "hard": summaryResId = isBattery ? R.string.thermal_battery_summary_hard : R.string.thermal_soc_summary_hard; iconResId = R.drawable.ic_hard; break;
            case "soft": summaryResId = isBattery ? R.string.thermal_battery_summary_soft : R.string.thermal_soc_summary_soft; iconResId = R.drawable.ic_soft; break;
            case "off": summaryResId = isBattery ? R.string.thermal_battery_summary_off : R.string.thermal_soc_summary_off; iconResId = R.drawable.ic_off_sun; break;
            case "stock": default: summaryResId = isBattery ? R.string.thermal_battery_summary_stock : R.string.thermal_soc_summary_stock; iconResId = R.drawable.ic_stock_arrow; break;
        }
        pref.setSummary(summaryResId);
        pref.setIcon(iconResId);
    }

    private void showRebootDialog(boolean isRiskyMode) {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.thermal_dialog_title)
            .setMessage(isRiskyMode ? getString(R.string.thermal_dialog_risk_msg) : getString(R.string.thermal_dialog_safe_msg))
            .setPositiveButton(R.string.thermal_btn_reboot, (dialog, which) -> {
                PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) pm.reboot(null);
            })
            .setNegativeButton(R.string.thermal_btn_ok, null).show();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        ContentResolver resolver = requireContext().getContentResolver();

        // --- NATIVE SEARCH HANDLER ---
        if (KEY_NATIVE_SEARCH.equals(key)) {
            boolean enabled = (Boolean) newValue;
            // 1. Сохраняем в Settings.Secure
            Settings.Secure.putInt(resolver, KEY_NATIVE_SEARCH, enabled ? 1 : 0);
            
            // 2. Формируем команду
            String cmdValue = enabled ? "true" : "false";
            String command = "cmd device_config override launcher enable_one_search " + cmdValue +
                             " && am force-stop com.google.android.apps.nexuslauncher";
            
            // 3. Выполняем в отдельном потоке (хоть это и быстро, но shell лучше не в UI)
            new Thread(() -> runRootCommand(command)).start();
            
            return true;
        }

        else if (KEY_DT2S_SLOP.equals(key)) {
            try {
                String input = (String) newValue;
                int value = input.isEmpty() ? 0 : Integer.parseInt(input);

                if (value < 0 || value > 600) {
                    Toast.makeText(requireContext(), R.string.launcher_dt2s_slop_error, Toast.LENGTH_LONG).show();
                    return false;
                }
                
                Settings.Secure.putInt(requireContext().getContentResolver(), KEY_DT2S_SLOP, value);
                
                int realSystemSlop = ViewConfiguration.get(requireContext()).getScaledDoubleTapSlop();
                updateSlopSummary(value, realSystemSlop);

                Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
                requireContext().stopService(serviceIntent);
                requireContext().startService(serviceIntent);

                return true;
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
                return false;
            }
        } 
        
        else if (KEY_DOZE_DOUBLE_TAP.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(resolver, KEY_DOZE_DOUBLE_TAP, isChecked ? 1 : 0);
            if (mDozeTimeoutPref != null) mDozeTimeoutPref.setVisible(isChecked);
            if (mDozeInfoPref != null) mDozeInfoPref.setVisible(isChecked);
            return true;
        }
        else if (KEY_DOZE_TIMEOUT.equals(key)) {
            return handleTimeoutChange((String) newValue, KEY_DOZE_TIMEOUT, mDozeTimeoutPref, R.string.doze_double_tap_timeout_summary, false);
        }
        else if (KEY_LAUNCHER_DT2S.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(resolver, KEY_LAUNCHER_DT2S, isChecked ? 1 : 0);
            Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
            if (isChecked) requireContext().startService(serviceIntent);
            else requireContext().stopService(serviceIntent);
            updateDt2sVisibility(isChecked);
            return true;
        }
        else if (KEY_DT2S_TIMEOUT.equals(key)) {
            return handleTimeoutChange((String) newValue, KEY_DT2S_TIMEOUT, mDt2sTimeoutPref, R.string.launcher_dt2s_timeout_summary, true);
        }
        else if (ThermalUtils.KEY_THERMAL_BATTERY.equals(key)) {
            String value = (String) newValue;
            updateThermalSummaryAndIcon(mThermalBatteryPref, value, true);
            new Handler(Looper.getMainLooper()).post(() -> ThermalUtils.updateThermalProps(requireContext()));
            boolean isSafe = "stock".equals(value) || "soft".equals(value);
            showRebootDialog(!isSafe);
            return true;
        } 
        else if (ThermalUtils.KEY_THERMAL_SOC.equals(key)) {
            String value = (String) newValue;
            updateThermalSummaryAndIcon(mThermalSocPref, value, false);
            new Handler(Looper.getMainLooper()).post(() -> ThermalUtils.updateThermalProps(requireContext()));
            boolean isSafe = "stock".equals(value) || "soft".equals(value);
            showRebootDialog(!isSafe);
            return true;
        }

        return false;
    }

    private boolean handleTimeoutChange(String input, String settingsKey, Preference pref, int summaryResId, boolean restartService) {
        try {
            int value = Integer.parseInt(input);
            if (value < 10 || value > 2000) {
                Toast.makeText(requireContext(), R.string.launcher_dt2s_error, Toast.LENGTH_LONG).show();
                return false;
            }
            Settings.Secure.putInt(requireContext().getContentResolver(), settingsKey, value);
            pref.setSummary(getString(summaryResId, value));
            if (restartService) {
                Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
                requireContext().stopService(serviceIntent);
                requireContext().startService(serviceIntent);
            }
            return true;
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // Вспомогательный метод для выполнения Shell команд
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
            // Если root не удался, пробуем обычный exec (иногда работает для device_config в системных апп)
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) {}
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) { super(handler); }
        void register(ContentResolver cr) { cr.registerContentObserver(Settings.Secure.getUriFor(KEY_DOZE_DOUBLE_TAP), false, this); }
        void unregister(ContentResolver cr) { cr.unregisterContentObserver(this); }
        @Override
        public void onChange(boolean selfChange, Uri uri) { super.onChange(selfChange, uri); updateDoubleTapState(); }
    }
}