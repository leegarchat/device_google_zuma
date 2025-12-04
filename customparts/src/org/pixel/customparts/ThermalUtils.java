package org.pixel.customparts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import androidx.preference.PreferenceManager;
import android.util.Log;

public final class ThermalUtils {

    private static final String TAG = "PixelPartsThermal";

    public static final String KEY_THERMAL_BATTERY = "thermal_battery_mode";
    public static final String KEY_THERMAL_SOC = "thermal_soc_mode";

    // ИСПОЛЬЗУЕМ НОВЫЕ СИСТЕМНЫЕ ПРОПЫ (persist.sys...)
    // Они доступны для записи system_app и сохраняются после перезагрузки
    private static final String PROP_BATTERY = "persist.sys.pixelparts.battery";
    private static final String PROP_SOC = "persist.sys.pixelparts.soc";
    
    // Это наш промежуточный проп. Init скопирует его в vendor.thermal.config
    private static final String PROP_CONFIG_TARGET = "persist.sys.pixelparts.thermal_config";

    public static void updateThermalProps(Context context) {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        String batteryMode = sharedPrefs.getString(KEY_THERMAL_BATTERY, "stock");
        String socMode = sharedPrefs.getString(KEY_THERMAL_SOC, "stock");

        Log.d(TAG, "Updating thermal props. Battery: " + batteryMode + ", SoC: " + socMode);

        // 1. Сохраняем состояние кнопок (для UI и логики)
        SystemProperties.set(PROP_BATTERY, batteryMode);
        SystemProperties.set(PROP_SOC, socMode);

        // 2. Генерируем имя файла
        String socAddName = "";
        if (!"stock".equals(socMode)) {
            socAddName = "_soc_" + socMode;
        }

        String batteryAddName = "";
        if (!"stock".equals(batteryMode)) {
            batteryAddName = "_battery_" + batteryMode;
        }

        String configFileName = "thermal_info_config" + socAddName + batteryAddName + ".json";

        // 3. Пишем в НАШ системный проп. 
        // Init.rc увидит это и скопирует в vendor.thermal.config
        SystemProperties.set(PROP_CONFIG_TARGET, configFileName);
        
        Log.d(TAG, "Set " + PROP_CONFIG_TARGET + " to " + configFileName);
    }
}