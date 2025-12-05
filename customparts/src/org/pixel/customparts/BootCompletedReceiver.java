package org.pixel.customparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import org.pixel.customparts.ThermalUtils;
import org.pixel.customparts.dt2s.DT2SService;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "PixelPartsBoot";
    private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";

    @Override
    public void onReceive(final Context context, Intent intent) {
        // Логируем сам факт запуска ресивера
        Log.d(TAG, "onReceive called with action: " + intent.getAction());

        // Используем try-catch, чтобы ошибка здесь НЕ привела к бутлупу
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || 
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
                
                Log.d(TAG, "Boot completed, restoring properties...");
                
                // 1. Thermal (оборачиваем в отдельный try, чтобы ошибка тут не сломала DT2S)
                try {
                    ThermalUtils.updateThermalProps(context);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update thermal props", e);
                }
                
                // 2. DT2S
                try {
                    int dt2sEnabled = Settings.Secure.getInt(context.getContentResolver(), 
                            KEY_LAUNCHER_DT2S, 0);
                    
                    if (dt2sEnabled == 1) {
                        Log.d(TAG, "Starting Launcher DT2S Service");
                        // Проверяем, не null ли контекст (на всякий случай)
                        if (context != null) {
                            context.startService(new Intent(context, DT2SService.class));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start DT2S Service", e);
                }
            }
        } catch (Throwable t) {
            // Перехватываем вообще всё, чтобы не убить процесс загрузки
            Log.e(TAG, "CRITICAL ERROR in BootCompletedReceiver", t);
        }
    }
}