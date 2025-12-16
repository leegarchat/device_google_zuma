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
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
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
    
    // КЛЮЧИ НАСТРОЕК
    private static final String KEY_DT2S_ENABLED = "launcher_dt2s_enabled"; 
    private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";
    private static final String KEY_DT2S_SLOP = "launcher_dt2s_slop";

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputReceiver;

    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private WindowManager mWindowManager;
    private InputMethodManager mImm;

    private boolean mIsMonitoring = false;
    private boolean mIsEnabled = false;

    // --- Reflection для StatusBar ---
    private Object mStatusBarService;
    private final List<Method> mPanelExpansionMethods = new ArrayList<>();

    private String mLauncherPackage;
    
    // Параметры жеста
    private int mDoubleTapTimeout = 250;
    private int mDoubleTapSlop; // Активное значение
    private int mSystemSlop;    // Системное значение

    private long mTempDownTime; // Время начала текущего касания
    private long mLastTapTime = 0;
    private float mLastTapX;
    private float mLastTapY;

    private float mDownX;
    private float mDownY;
    private boolean mIsSwipe;
    
    private SettingsObserver mSettingsObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mImm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        
        // 1. Получаем системное значение
        mSystemSlop = ViewConfiguration.get(this).getScaledDoubleTapSlop();
        Log.d(TAG, "System Default Slop is: " + mSystemSlop + "px");
        
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        
        // Регистрируем наблюдателей
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_DT2S_ENABLED), false, mSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_DT2S_TIMEOUT), false, mSettingsObserver);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_DT2S_SLOP), false, mSettingsObserver);

        resolveLauncher();
        registerScreenReceiver();
        
        updateSettings(); // Первичная инициализация
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateSettings();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        if (mSettingsObserver != null) {
            getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) { super(handler); }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }
    }

    private void updateSettings() {
        mIsEnabled = Settings.Secure.getInt(getContentResolver(), KEY_DT2S_ENABLED, 1) == 1; 
        mDoubleTapTimeout = Settings.Secure.getInt(getContentResolver(), KEY_DT2S_TIMEOUT, 250);
        
        // Логика выбора Slop
        int userSlop = Settings.Secure.getInt(getContentResolver(), KEY_DT2S_SLOP, 0);
        if (userSlop > 0) {
            mDoubleTapSlop = userSlop;
        } else {
            mDoubleTapSlop = mSystemSlop;
        }
        
        Log.d(TAG, "Active Slop: " + mDoubleTapSlop + "px (System was: " + mSystemSlop + ")");

        if (mIsEnabled && mPowerManager.isInteractive()) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }

    // ---------------------------------------------------------
    // MONITORING
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
                if (mIsEnabled) startMonitoring();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                stopMonitoring();
                mLastTapTime = 0;
            }
        }
    };

    private void startMonitoring() {
        if (mIsMonitoring) return;
        if (!mIsEnabled) return; 

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
    // TOUCH HANDLER
    // ---------------------------------------------------------

    private class TouchReceiver extends InputEventReceiver {
        TouchReceiver(InputChannel c, Looper l) { super(c, l); }

        @Override
        public void onInputEvent(InputEvent e) {
            try {
                if (e instanceof MotionEvent) {
                    process((MotionEvent) e);
                }
            } finally {
                finishInputEvent(e, false);
            }
        }
    }

    private void process(MotionEvent e) {
        int action = e.getActionMasked();

        if ((e.getFlags() & (MotionEvent.FLAG_WINDOW_IS_OBSCURED | 
                             MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0) {
            mLastTapTime = 0;
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // 1. Сохраняем параметры текущего касания
                mDownX = e.getX();
                mDownY = e.getY();
                mTempDownTime = SystemClock.uptimeMillis();
                mIsSwipe = false;

                // 2. Проверяем на второй тап
                if (mLastTapTime > 0 && (mTempDownTime - mLastTapTime < mDoubleTapTimeout)) {
                    float dx = Math.abs(mDownX - mLastTapX);
                    float dy = Math.abs(mDownY - mLastTapY);

                    if (dx < mDoubleTapSlop && dy < mDoubleTapSlop) {
                        // Жест выполнен -> тяжелые проверки
                        if (performHeavyChecksAndSleep(e.getRawY())) {
                            mLastTapTime = 0; 
                            return;
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mIsSwipe) {
                    // Проверка на свайп во время движения
                    if (Math.abs(e.getX() - mDownX) > mDoubleTapSlop ||
                        Math.abs(e.getY() - mDownY) > mDoubleTapSlop) {
                        mIsSwipe = true;
                        mLastTapTime = 0; 
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mIsSwipe) {
                    mLastTapTime = 0;
                } else {
                    // 3. Финальная проверка: не уехал ли палец далеко к моменту отпускания?
                    if (Math.abs(e.getX() - mDownX) > mDoubleTapSlop ||
                        Math.abs(e.getY() - mDownY) > mDoubleTapSlop) {
                        mLastTapTime = 0; // Это был быстрый свайп
                    } else {
                        // 4. Чистый тап - запоминаем
                        mLastTapTime = mTempDownTime;
                        mLastTapX = mDownX;
                        mLastTapY = mDownY;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mLastTapTime = 0;
                mIsSwipe = false;
                break;
        }
    }

    private boolean performHeavyChecksAndSleep(float rawY) {
        if (isIgnoredZone(rawY)) return false;
        if (!isLauncherOnTop()) return false;
        if (isKeyboardShown()) return false;
        if (isShadeExpanded()) return false;
        if (isLockscreen()) return false;

        goSleep();
        return true;
    }

    private void goSleep() {
        if (mPowerManager != null && mPowerManager.isInteractive()) {
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }

    // ... (Методы isIgnoredZone, isLauncherOnTop, isKeyboardShown, isShadeExpanded, isLockscreen, resolveLauncher без изменений) ...
    // Скопируй их из своего кода, они там верные.
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

    private boolean isIgnoredZone(float y) {
        if (mWindowManager == null) return false;
        try {
            Display display = mWindowManager.getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point();
            display.getRealSize(p);
            int h = p.y;
            return y < h * 0.15f || y > h * 0.80f; 
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isKeyboardShown() {
         if (mImm != null && mImm.isAcceptingText()) return true;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mWindowManager != null) {
            try {
                WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
                WindowInsets insets = metrics.getWindowInsets();
                return insets.isVisible(WindowInsets.Type.ime());
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isLockscreen() {
        try { return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked(); } 
        catch (Exception e) { return false; }
    }

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
                String[] potentialMethods = {"isShadeExpanded", "getShadeExpanded", "getPanelExpansion", "getNotificationPanelExpansion", "isPanelExpanded", "isExpanded"};
                for (String name : potentialMethods) {
                    try { mPanelExpansionMethods.add(cls.getMethod(name, int.class)); continue; } catch (Exception ignored) {}
                    try { mPanelExpansionMethods.add(cls.getMethod(name)); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {}
    }

    private boolean isShadeExpanded() {
        if (mStatusBarService == null) initStatusBarService();
        if (mStatusBarService == null) return false;
        for (Method method : mPanelExpansionMethods) {
            try {
                Object result;
                if (method.getParameterCount() == 1) result = method.invoke(mStatusBarService, Display.DEFAULT_DISPLAY);
                else result = method.invoke(mStatusBarService);
                if (result instanceof Float) { if ((Float) result > 0.01f) return true; }
                else if (result instanceof Boolean) { if ((Boolean) result) return true; }
            } catch (Exception e) { mStatusBarService = null; }
        }
        return false;
    }
}