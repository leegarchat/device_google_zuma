package org.pixel.customparts;

import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import java.util.List;

public class ImsManager {

    private static final String TAG = "PixelPartsIMS";
    
    public static final String KEY_VOLTE        = "pixel_ims_volte";
    public static final String KEY_WFC          = "pixel_ims_wfc";
    public static final String KEY_VT           = "pixel_ims_vt";
    public static final String KEY_VONR         = "pixel_ims_vonr";
    public static final String KEY_CROSS_SIM    = "pixel_ims_cross_sim";
    public static final String KEY_UT           = "pixel_ims_ut";
    public static final String KEY_5G           = "pixel_ims_5g";
    public static final String KEY_5G_THRESH    = "pixel_ims_5g_thresh";
    public static void updateImsProfile(Context context) {
        SubscriptionManager subMgr = context.getSystemService(SubscriptionManager.class);
        CarrierConfigManager configMgr = context.getSystemService(CarrierConfigManager.class);
        if (subMgr == null || configMgr == null) return;
        List<SubscriptionInfo> subs = subMgr.getActiveSubscriptionInfoList();
        if (subs == null || subs.isEmpty()) return;
        boolean enableVoLTE     = getInt(context, KEY_VOLTE, 0);
        boolean enableWFC       = getInt(context, KEY_WFC, 0);
        boolean enableVT        = getInt(context, KEY_VT, 0);
        boolean enableVoNR      = getInt(context, KEY_VONR, 0);
        boolean enableCrossSim  = getInt(context, KEY_CROSS_SIM, 0);
        boolean enableUT        = getInt(context, KEY_UT, 0);
        boolean enable5G        = getInt(context, KEY_5G, 0);
        boolean enable5GThresh  = getInt(context, KEY_5G_THRESH, 0);
        for (SubscriptionInfo sub : subs) {
            int subId = sub.getSubscriptionId();
            if (!enableVoLTE && !enableWFC && !enableVT && !enableVoNR && 
                !enableCrossSim && !enableUT && !enable5G) {
                try {
                    configMgr.overrideConfig(subId, null);
                    Log.d(TAG, "All toggles OFF. Resetting config to stock for subId " + subId);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reset config", e);
                }
                continue;
            }
            PersistableBundle bundle = new PersistableBundle();
            if (enableVoLTE) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false);
                bundle.putBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, true);
            }
            if (enableWFC) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true);
                bundle.putBoolean("show_wifi_calling_icon_in_status_bar_bool", true);
                bundle.putInt("wfc_spn_format_idx_int", 6);
            }

            if (enableVT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true);
            }

            if (enableUT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true);
            }

            if (enableCrossSim) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, true);
                bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", true);
            }

            if (Build.VERSION.SDK_INT >= 34 && enableVoNR) {
                bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true);
                bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true);
            }

            if (enable5G) {
                bundle.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                        new int[]{
                                1, // CARRIER_NR_AVAILABILITY_NSA
                                2  // CARRIER_NR_AVAILABILITY_SA
                        });

                if (enable5GThresh) {
                    bundle.putIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                            new int[]{
                                    -128, /* POOR */
                                    -118, /* MODERATE */
                                    -108, /* GOOD */
                                    -98   /* GREAT */
                            });
                }
            }
            try {
                configMgr.overrideConfig(subId, bundle);
                Log.d(TAG, "Applied Turbo IMS Config for Sub " + subId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to override config for subId " + subId, e);
            }
        }
    }
    private static boolean getInt(Context c, String key, int def) {
        return Settings.Secure.getInt(c.getContentResolver(), key, def) == 1;
    }
}