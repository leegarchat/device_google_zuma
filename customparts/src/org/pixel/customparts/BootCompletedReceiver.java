package org.pixel.customparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import org.pixel.customparts.ThermalUtils;
import org.pixel.customparts.dt2s.DT2SService;
import org.pixel.customparts.ImsManager;
import java.io.DataOutputStream;
public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "PixelPartsBoot";
    private static final String KEY_LAUNCHER_DT2S = "launcher_dt2s_enabled";
    private static final String KEY_NATIVE_SEARCH = "pixel_launcher_native_search";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.d(TAG, "onReceive called with action: " + intent.getAction());

        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || 
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
                
                Log.d(TAG, "Boot completed, restoring properties...");
                try {
                    ThermalUtils.updateThermalProps(context);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update thermal props", e);
                }
                new Thread(() -> {
                    try {
                        Log.d(TAG, "Starting IMS initialization...");
                        Thread.sleep(2000); 
                        ImsManager.updateImsProfile(context);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to apply IMS config on boot", e);
                    }
                }).start();
                try {
                    int searchEnabled = Settings.Secure.getInt(context.getContentResolver(), 
                            KEY_NATIVE_SEARCH, 1);
                    
                    String cmdValue = (searchEnabled == 1) ? "true" : "false";
                    Log.d(TAG, "Restoring Native Search: " + cmdValue);
                    runRootCommand("cmd device_config override launcher enable_one_search " + cmdValue);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Failed to restore native search prop", e);
                }
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