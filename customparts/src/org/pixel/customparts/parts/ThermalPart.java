package org.pixel.customparts.parts;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.pixel.customparts.R;
import org.pixel.customparts.ThermalUtils;

public class ThermalPart implements Part {

    private Context mContext;
    private ListPreference mThermalBatteryPref;
    private ListPreference mThermalSocPref;

    @Override
    public void onCreate(PreferenceFragmentCompat fragment) {
        mContext = fragment.requireContext();
        mThermalBatteryPref = fragment.findPreference(ThermalUtils.KEY_THERMAL_BATTERY);
        if (mThermalBatteryPref != null) {
            mThermalBatteryPref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            updateThermalSummaryAndIcon(mThermalBatteryPref, mThermalBatteryPref.getValue(), true);
        }
        mThermalSocPref = fragment.findPreference(ThermalUtils.KEY_THERMAL_SOC);
        if (mThermalSocPref != null) {
            mThermalSocPref.setOnPreferenceChangeListener((Preference.OnPreferenceChangeListener) fragment);
            updateThermalSummaryAndIcon(mThermalSocPref, mThermalSocPref.getValue(), false);
        }
    }
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        String value = (String) newValue;
        if (ThermalUtils.KEY_THERMAL_BATTERY.equals(key)) {
            updateThermalSummaryAndIcon(mThermalBatteryPref, value, true);
            applyThermalChanges(value);
            return true;
        } 
        else if (ThermalUtils.KEY_THERMAL_SOC.equals(key)) {
            updateThermalSummaryAndIcon(mThermalSocPref, value, false);
            applyThermalChanges(value);
            return true;
        }
        return false;
    }
    private void applyThermalChanges(String value) {
        new Handler(Looper.getMainLooper()).post(() -> ThermalUtils.updateThermalProps(mContext));
        boolean isSafe = "stock".equals(value) || "soft".equals(value);
        showRebootDialog(!isSafe);
    }
    private void updateThermalSummaryAndIcon(ListPreference pref, String value, boolean isBattery) {
        if (pref == null || value == null) return;
        int summaryResId;
        int iconResId;
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
        if (!isRiskyMode) return; 
        new AlertDialog.Builder(mContext)
            .setTitle(R.string.thermal_dialog_title)
            .setMessage(isRiskyMode ? mContext.getString(R.string.thermal_dialog_risk_msg) : mContext.getString(R.string.thermal_dialog_safe_msg))
            .setPositiveButton(R.string.thermal_btn_reboot, (dialog, which) -> {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                if (pm != null) pm.reboot(null);
            })
            .setNegativeButton(R.string.thermal_btn_ok, null)
            .show();
    }
    @Override public void onResume() {}
    @Override public void onPause() {}
}