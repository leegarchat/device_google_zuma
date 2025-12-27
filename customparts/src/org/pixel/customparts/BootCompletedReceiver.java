package org.pixel.customparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import org.pixel.customparts.ThermalUtils;
import org.pixel.customparts.dt2s.DT2SService;

import java.io.DataOutputStream; // Добавлено для shell команд

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "PixelPartsBoot";
    private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";
    private static final String KEY_NATIVE_SEARCH = "pixel_launcher_native_search"; // Новый ключ

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
                
                // 1. Thermal
                try {
                    ThermalUtils.updateThermalProps(context);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update thermal props", e);
                }

                // 2. Native Search Restoration (НОВОЕ)
                try {
                    // По умолчанию 1 (включено), так как мы хотим это поведение "из коробки"
                    int searchEnabled = Settings.Secure.getInt(context.getContentResolver(), 
                            KEY_NATIVE_SEARCH, 1);
                    
                    String cmdValue = (searchEnabled == 1) ? "true" : "false";
                    Log.d(TAG, "Restoring Native Search: " + cmdValue);
                    
                    // Выполняем команду. При загрузке force-stop лаунчера не обязателен, 
                    // но флаг device_config нужно выставить.
                    runRootCommand("cmd device_config override launcher enable_one_search " + cmdValue);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Failed to restore native search prop", e);
                }
                
                // 3. DT2S
                try {
                    int dt2sEnabled = Settings.Secure.getInt(context.getContentResolver(), 
                            KEY_LAUNCHER_DT2S, 0);
                    
                    if (dt2sEnabled == 1) {
                        Log.d(TAG, "Starting Launcher DT2S Service");
                        if (context != null) {
                            context.startService(new Intent(context, DT2SService.class));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start DT2S Service", e);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "CRITICAL ERROR in BootCompletedReceiver", t);
        }
    }

    // Простой метод для выполнения shell команд (суперпользователь)
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
            Log.e(TAG, "Root command failed: " + command, e);
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) {}
        }
    }
}