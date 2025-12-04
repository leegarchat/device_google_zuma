package org.pixel.customparts;

import android.app.AlertDialog; // Добавить
import android.content.Context; // Добавить
import android.content.DialogInterface; // Добавить
import android.os.PowerManager; // Добавить
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.pixel.customparts.R;

public class PixelPartsFragment extends PreferenceFragmentCompat 
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_DOZE_DOUBLE_TAP = "doze_double_tap_hook";
    
    private SwitchPreferenceCompat mDoubleTapSwitch;
    
    // Thermal Preferences
    private ListPreference mThermalBatteryPref;
    private ListPreference mThermalSocPref;

    private SettingsObserver mSettingsObserver;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pixel_custom_parts_settings, rootKey);
        // DEBUG LOG
        Preference batPref = findPreference("thermal_battery_mode");
        android.util.Log.e("PixelPartsDebug", "Battery Pref found: " + (batPref != null));
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        
        mDoubleTapSwitch = findPreference(KEY_DOZE_DOUBLE_TAP);
        if (mDoubleTapSwitch != null) {
            mDoubleTapSwitch.setOnPreferenceChangeListener(this);
            updateDoubleTapState();
        }

        // --- Thermal Init ---
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

    @Override
    public void onResume() {
        super.onResume();
        if (mSettingsObserver != null) {
            mSettingsObserver.register(requireContext().getContentResolver());
        }
        updateDoubleTapState();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSettingsObserver != null) {
            mSettingsObserver.unregister(requireContext().getContentResolver());
        }
    }

    private void updateDoubleTapState() {
        if (mDoubleTapSwitch == null) return;
        int value = Settings.Secure.getInt(requireContext().getContentResolver(),
                KEY_DOZE_DOUBLE_TAP, 0);
        mDoubleTapSwitch.setChecked(value == 1);
    }

    // Хелпер для установки иконок и текста
    private void updateThermalSummaryAndIcon(ListPreference pref, String value, boolean isBattery) {
        if (pref == null || value == null) return;

        int summaryResId;
        int iconResId;

        // Выбор ресурсов в зависимости от значения
        switch (value) {
            case "medium":
                summaryResId = isBattery ? R.string.thermal_battery_summary_medium : R.string.thermal_soc_summary_medium;
                iconResId = R.drawable.ic_medium; 
                break;
            case "hard":
                summaryResId = isBattery ? R.string.thermal_battery_summary_hard : R.string.thermal_soc_summary_hard;
                iconResId = R.drawable.ic_hard; 
                break;
            case "soft":
                summaryResId = isBattery ? R.string.thermal_battery_summary_soft : R.string.thermal_soc_summary_soft;
                iconResId = R.drawable.ic_soft;
                break;
            case "off":
                summaryResId = isBattery ? R.string.thermal_battery_summary_off : R.string.thermal_soc_summary_off;
                iconResId = R.drawable.ic_off_sun;
                break;
            case "stock":
            default:
                summaryResId = isBattery ? R.string.thermal_battery_summary_stock : R.string.thermal_soc_summary_stock;
                iconResId = R.drawable.ic_stock_arrow;
                break;
        }

        pref.setSummary(summaryResId);
        pref.setIcon(iconResId);
    }

    private void showRebootDialog(boolean isRiskyMode) {
        String message = isRiskyMode 
                ? getString(R.string.thermal_dialog_risk_msg)
                : getString(R.string.thermal_dialog_safe_msg);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.thermal_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.thermal_btn_reboot, (dialog, which) -> {
                    PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        pm.reboot(null);
                    }
                })
                .setNegativeButton(R.string.thermal_btn_ok, null)
                .show();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        
        if (KEY_DOZE_DOUBLE_TAP.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(requireContext().getContentResolver(),
                    KEY_DOZE_DOUBLE_TAP, isChecked ? 1 : 0);
            return true;
        } 
        else if (ThermalUtils.KEY_THERMAL_BATTERY.equals(key)) {
            String value = (String) newValue;
            updateThermalSummaryAndIcon(mThermalBatteryPref, value, true);
            new Handler(Looper.getMainLooper()).post(() -> 
                ThermalUtils.updateThermalProps(requireContext()));
            
            // Логика вызова диалога
            boolean isSafe = "stock".equals(value) || "soft".equals(value);
            showRebootDialog(!isSafe);

            return true;
        } 
        else if (ThermalUtils.KEY_THERMAL_SOC.equals(key)) {
            String value = (String) newValue;
            updateThermalSummaryAndIcon(mThermalSocPref, value, false);
            new Handler(Looper.getMainLooper()).post(() -> 
                ThermalUtils.updateThermalProps(requireContext()));

            // Логика вызова диалога
            boolean isSafe = "stock".equals(value) || "soft".equals(value);
            showRebootDialog(!isSafe);

            return true;
        }

        return false;
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        void register(ContentResolver cr) {
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_DOZE_DOUBLE_TAP),
                    false, this);
        }
        void unregister(ContentResolver cr) {
            cr.unregisterContentObserver(this);
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateDoubleTapState();
        }
    }
}