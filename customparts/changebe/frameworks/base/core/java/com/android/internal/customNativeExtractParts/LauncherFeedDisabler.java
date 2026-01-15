package com.android.internal.customNativeExtractParts;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


public class LauncherFeedDisabler {

    private static final String TAG = "LauncherFeedDisabler";
    private static final String KEY_DISABLE_FEED = "launcher_disable_google_feed";

    public static void checkAndDisableFeed(final Activity launcherActivity) {
        if (launcherActivity == null) return;

        try {
            if (isFeedDisabled(launcherActivity)) {
                forceDisconnectOverlay(launcherActivity);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable feed", e);
        }
    }

    private static void forceDisconnectOverlay(Activity launcherActivity) {
        try {
            Field workspaceField = findField(launcherActivity.getClass(), "mWorkspace");
            if (workspaceField == null) return;
            
            workspaceField.setAccessible(true);
            Object workspace = workspaceField.get(launcherActivity);
            
            if (workspace == null) return;

            Method setOverlayMethod = findMethodByNameAndParamCount(workspace.getClass(), "setLauncherOverlay", 1);
            
            if (setOverlayMethod != null) {
                setOverlayMethod.setAccessible(true);
                setOverlayMethod.invoke(workspace, (Object) null);
            } else {
                Log.w(TAG, "Method setLauncherOverlay not found");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to disconnect overlay", e);
        }
    }

    private static boolean isFeedDisabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_DISABLE_FEED, 0) == 1;
    }


    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethodByNameAndParamCount(Class<?> clazz, String methodName, int paramCount) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals(methodName) && method.getParameterTypes().length == paramCount) {
                        return method;
                    }
                }
            } catch (Exception e) {
                // Continue searching in superclass
            }
            current = current.getSuperclass();
        }
        return null;
    }
}