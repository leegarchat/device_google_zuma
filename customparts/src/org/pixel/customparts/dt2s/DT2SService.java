package org.pixel.customparts.dt2s;

import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class DT2SService extends Service {
    private static final String TAG = "PixelPartsDT2S";
    private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputReceiver;

    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private WindowManager mWindowManager;
    private InputMethodManager mImm;

    private boolean mIsMonitoring = false;

    // --- Reflection для StatusBar (Шторка) ---
    private Object mStatusBarService;
    private final List<Method> mPanelExpansionMethods = new ArrayList<>();
    // -----------------------------------------

    private String mLauncherPackage;

    private int mDoubleTapTimeout;
    private int mDoubleTapSlop;

    private long mLastTapTime = 0;
    private float mLastTapX;
    private float mLastTapY;

    private float mDownX;
    private float mDownY;
    private boolean mIsSwipe;

    @Override
    public void onCreate() {
        super.onCreate();

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mImm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        mDoubleTapSlop = ViewConfiguration.get(this).getScaledDoubleTapSlop();

        resolveLauncher();
        initStatusBarService(); // Подключаемся к SystemUI

        registerScreenReceiver();

        if (mPowerManager.isInteractive()) startMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mDoubleTapTimeout = Settings.Secure.getInt(getContentResolver(), KEY_DT2S_TIMEOUT, 150);
        resolveLauncher();
        if (mPowerManager.isInteractive()) startMonitoring();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------
    // STATUS BAR REFLECTION (ШТОРКА)
    // ---------------------------------------------------------

    private void initStatusBarService() {
        try {
            mPanelExpansionMethods.clear();
            IBinder binder = ServiceManager.getService("statusbar");
            if (binder == null) return;

            Class<?> stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            mStatusBarService = asInterface.invoke(null, binder);

            if (mStatusBarService != null) {
                Class<?> cls = mStatusBarService.getClass();
                // Методы, которые возвращают состояние панели.
                // Порядок важен: от новых к старым.
                String[] potentialMethods = {
                    "isShadeExpanded",               // Android 14 QPR+
                    "getShadeExpanded",              // Альтернатива
                    "getPanelExpansion",             // Классика (float 0..1)
                    "getNotificationPanelExpansion", // Часто используется
                    "isPanelExpanded",               // Boolean
                    "isExpanded"                     // Старый
                };

                for (String name : potentialMethods) {
                    // Ищем методы, принимающие int (DisplayId) - ВАЖНО для A14
                    try {
                        Method m = cls.getMethod(name, int.class);
                        mPanelExpansionMethods.add(m);
                        continue; // Нашли - идем к следующему имени
                    } catch (NoSuchMethodException ignored) {}

                    // Ищем методы без аргументов (старые API)
                    try {
                        Method m = cls.getMethod(name);
                        mPanelExpansionMethods.add(m);
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "StatusBar reflection init failed", e);
        }
    }

    private boolean isShadeExpanded() {
        if (mStatusBarService == null || mPanelExpansionMethods.isEmpty()) {
            initStatusBarService();
            if (mStatusBarService == null) return false;
        }

        for (Method method : mPanelExpansionMethods) {
            try {
                Object result;
                // Если метод требует int, передаем 0 (Default Display)
                if (method.getParameterCount() == 1) {
                    result = method.invoke(mStatusBarService, Display.DEFAULT_DISPLAY);
                } else {
                    result = method.invoke(mStatusBarService);
                }

                if (result instanceof Float) {
                    // Если возвращает float (степень открытия), считаем открытым если > 0
                    if ((Float) result > 0.01f) return true;
                } else if (result instanceof Boolean) {
                    if ((Boolean) result) return true;
                }
            } catch (Exception e) {
                // Если произошла ошибка (SystemUI упал), сбрасываем сервис
                mStatusBarService = null;
            }
        }
        return false;
    }

    // ---------------------------------------------------------
    // MONITORING & RECEIVERS
    // ---------------------------------------------------------

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter);
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                startMonitoring();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                stopMonitoring();
            }
        }
    };

    private void startMonitoring() {
        if (mIsMonitoring) return;
        try {
            mInputMonitor = InputManager.getInstance()
                    .monitorGestureInput("pixelparts-dt2s", Display.DEFAULT_DISPLAY);
            mInputReceiver = new TouchReceiver(mInputMonitor.getInputChannel(), Looper.getMainLooper());
            mIsMonitoring = true;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot start monitoring", t);
        }
    }

    private void stopMonitoring() {
        if (!mIsMonitoring) return;
        try { if (mInputReceiver != null) mInputReceiver.dispose(); } catch (Exception ignored) {}
        try { if (mInputMonitor != null) mInputMonitor.dispose(); } catch (Exception ignored) {}
        mInputReceiver = null;
        mInputMonitor = null;
        mIsMonitoring = false;
    }

    // ---------------------------------------------------------
    // LOGIC & CHECKS
    // ---------------------------------------------------------

    private void resolveLauncher() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null)
                mLauncherPackage = ri.activityInfo.packageName;
        } catch (Exception ignored) {}
    }

    private boolean isLauncherOnTop() {
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return info != null &&
                   info.topActivity != null &&
                   info.topActivity.getPackageName().equals(mLauncherPackage);
        } catch (Exception e) {
            return false;
        }
    }

    // Улучшенная проверка клавиатуры
    private boolean isKeyboardShown() {
        // 1. Стандартная проверка менеджера ввода
        if (mImm != null && mImm.isAcceptingText()) return true;
        if (mImm != null && mImm.isActive()) return true; // Доп. проверка

        // 2. Проверка через WindowInsets (высота IME) - работает в A11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mWindowManager != null) {
            try {
                WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
                WindowInsets insets = metrics.getWindowInsets();
                boolean isImeVisible = insets.isVisible(WindowInsets.Type.ime());
                if (isImeVisible) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isLockscreen() {
        try {
            return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIgnoredZone(float y) {
        if (mWindowManager == null) return false;
        try {
            Display display = mWindowManager.getDefaultDisplay();
            Point p = new Point();
            display.getRealSize(p);
            int h = p.y;
            
            // Верхние 15% (статусбар + пространство под ним, чтобы не мешать свайпу шторки)
            // Нижние 22% (док бар, место клавиатуры, жест "домой")
            return y < h * 0.15f || y > h * 0.80f;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------
    // TOUCH HANDLER
    // ---------------------------------------------------------

    private class TouchReceiver extends InputEventReceiver {
        TouchReceiver(InputChannel c, Looper l) { super(c, l); }

        @Override
        public void onInputEvent(InputEvent e) {
            try {
                if (e instanceof MotionEvent) process((MotionEvent) e);
            } finally {
                finishInputEvent(e, false);
            }
        }
    }

    private void process(MotionEvent e) {
        int action = e.getActionMasked();

        // --- БЛОК 1: Глобальные проверки (самые дешевые и важные) ---
        
        // Перекрыто ли окно? (Например, диалоговым окном разрешений или шторкой, если она считается overlay)
        int flags = e.getFlags();
        if ((flags & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (flags & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) {
            invalidateGesture();
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // --- БЛОК 2: Тяжелые проверки при нажатии ---

                // 1. Шторка (через обновленную рефлексию)
                if (isShadeExpanded()) {
                    invalidateGesture();
                    return;
                }

                // 2. Клавиатура (ввод текста / поиск в меню приложений)
                if (isKeyboardShown()) {
                    invalidateGesture();
                    return;
                }

                // 3. Локскрин
                if (isLockscreen()) {
                    invalidateGesture();
                    return;
                }

                // 4. Зоны (верх/низ)
                if (isIgnoredZone(e.getRawY())) {
                    invalidateGesture();
                    return;
                }

                // 5. Лаунчер должен быть сверху
                if (!isLauncherOnTop()) {
                    invalidateGesture();
                    return;
                }

                // --- БЛОК 3: Логика жеста ---
                mDownX = e.getX();
                mDownY = e.getY();
                mIsSwipe = false;

                long now = SystemClock.uptimeMillis();
                float dx = Math.abs(mDownX - mLastTapX);
                float dy = Math.abs(mDownY - mLastTapY);

                if (mLastTapTime > 0 &&
                    now - mLastTapTime < mDoubleTapTimeout &&
                    dx < mDoubleTapSlop &&
                    dy < mDoubleTapSlop) {

                    goSleep();
                    mLastTapTime = 0;
                    return;
                }

                mLastTapTime = now;
                mLastTapX = mDownX;
                mLastTapY = mDownY;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mIsSwipe) {
                    if (Math.abs(e.getX() - mDownX) > mDoubleTapSlop ||
                        Math.abs(e.getY() - mDownY) > mDoubleTapSlop) {
                        mIsSwipe = true;
                        mLastTapTime = 0;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mLastTapTime = 0;
                break;
        }
    }

    private void invalidateGesture() {
        mIsSwipe = true;
        mLastTapTime = 0;
    }

    private void goSleep() {
        if (mPowerManager != null && mPowerManager.isInteractive()) {
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }
}