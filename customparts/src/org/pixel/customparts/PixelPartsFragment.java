package org.pixel.customparts;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface; 
import android.os.PowerManager; 
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
// Dt2s
import android.content.Intent;
import org.pixel.customparts.dt2s.DT2SService;
import android.widget.Toast;
import androidx.preference.EditTextPreference;
public class PixelPartsFragment extends PreferenceFragmentCompat 
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_DOZE_DOUBLE_TAP = "doze_double_tap_hook";
	private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";
	private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";
    
    private SwitchPreferenceCompat mDoubleTapSwitch;
	private SwitchPreferenceCompat mLauncherDt2sSwitch;
	private EditTextPreference mDt2sTimeoutPref;
    
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
		// 2. НОВЫЙ Launcher DT2S свитч
        mLauncherDt2sSwitch = findPreference(KEY_LAUNCHER_DT2S);
        if (mLauncherDt2sSwitch != null) {
            mLauncherDt2sSwitch.setOnPreferenceChangeListener(this);
            // Проверяем текущее состояние и запускаем/останавливаем сервис при входе в настройки
            // на случай, если он упал
            boolean enabled = Settings.Secure.getInt(requireContext().getContentResolver(),
                    KEY_LAUNCHER_DT2S, 0) == 1;
            mLauncherDt2sSwitch.setChecked(enabled);
            
            Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
            if (enabled) {
                requireContext().startService(serviceIntent);
            } else {
                requireContext().stopService(serviceIntent);
            }
        }
		mDt2sTimeoutPref = findPreference(KEY_DT2S_TIMEOUT);
        if (mDt2sTimeoutPref != null) {
            mDt2sTimeoutPref.setOnPreferenceChangeListener(this);
            // Получаем текущее значение для отображения в Summary
            int currentVal = Settings.Secure.getInt(requireContext().getContentResolver(),
                    KEY_DT2S_TIMEOUT, 300);
            mDt2sTimeoutPref.setSummary(getString(R.string.launcher_dt2s_timeout_summary, currentVal));
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
        ContentResolver resolver = requireContext().getContentResolver();
        if (KEY_DOZE_DOUBLE_TAP.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            Settings.Secure.putInt(requireContext().getContentResolver(),
                    KEY_DOZE_DOUBLE_TAP, isChecked ? 1 : 0);
            return true;
        }
        else if (KEY_LAUNCHER_DT2S.equals(key)) {
            boolean isChecked = (Boolean) newValue;
            // 1. Сохраняем в Settings.Secure
            Settings.Secure.putInt(requireContext().getContentResolver(),
                    KEY_LAUNCHER_DT2S, isChecked ? 1 : 0);
            
            // 2. Управляем сервисом
            Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
            if (isChecked) {
                requireContext().startService(serviceIntent);
            } else {
                requireContext().stopService(serviceIntent);
            }
            return true;
        }
		else if (KEY_DT2S_TIMEOUT.equals(key)) {
            String input = (String) newValue;
            try {
                int value = Integer.parseInt(input);
                
                // Валидация: от 10 до 1000
                if (value < 10 || value > 1000) {
                    Toast.makeText(requireContext(), R.string.launcher_dt2s_error, Toast.LENGTH_LONG).show();
                    return false; // Не сохраняем
                }

                // Сохраняем в Settings.Secure
                Settings.Secure.putInt(resolver, KEY_DT2S_TIMEOUT, value);
                
                // Обновляем описание
                mDt2sTimeoutPref.setSummary(getString(R.string.launcher_dt2s_timeout_summary, value));

                // Перезапускаем сервис, чтобы применилось новое время
                Intent serviceIntent = new Intent(requireContext(), DT2SService.class);
                requireContext().stopService(serviceIntent);
                requireContext().startService(serviceIntent);
                
                return true;
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
                return false;
            }
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