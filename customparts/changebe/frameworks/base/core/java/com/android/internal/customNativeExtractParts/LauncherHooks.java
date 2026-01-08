package com.android.internal.customNativeExtractParts;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Менеджер хуков для NexusLauncher.
 * Инициализируется из NativePartsManager.
 */
public class LauncherHooks {

    public static void init(Application app) {
        // Инициализация статических ресурсов подмодулей (например, рефлексия полей)
        LauncherDT2S.init();
        
        // Регистрация единого колбэка для всех хуков лаунчера
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                // Подключаем функционал к активной Activity
                LauncherDT2S.attach(activity);
                LauncherClearAll.attach(activity);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}