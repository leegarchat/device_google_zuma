package org.pixel.customparts.dt2s;

import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
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

import java.lang.reflect.Method;

public class DT2SService extends Service {
    private static final String TAG = "PixelPartsDT2S";
    private static final String KEY_DT2S_TIMEOUT = "launcher_dt2s_timeout";

    private boolean mIsMonitoring = false;
    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;
    private PowerManager mPowerManager;
    
    private String mLauncherPackage;

    // Настройки жестов
    private int mDoubleTapTimeout = 300;
    private int mDoubleTapSlop; // Допустимое смещение для тапа

    // Состояние предыдущего ВАЛИДНОГО тапа
    private long mLastValidTapTime = 0;
    private float mLastValidTapX = 0;
    private float mLastValidTapY = 0;

    // Состояние текущего жеста
    private float mCurrentDownX = 0;
    private float mCurrentDownY = 0;
    private boolean mIsCurrentGestureSwipe = false; // Флаг: превратился ли текущий жест в свайп

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    
    // Рефлексия StatusBar
    private Object mStatusBarService;
    private Method mGetPanelExpansionMethod;

    @Override
    public void onCreate() {
        super.onCreate();
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // Получаем системное значение, сколько пикселей можно сдвинуть, чтобы это все еще считалось тапом
        mDoubleTapSlop = ViewConfiguration.get(this).getScaledDoubleTapSlop();
        
        resolveLauncherPackage();
        initStatusBarReflection();

        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void initStatusBarReflection() {
        try {
            IBinder binder = ServiceManager.getService("statusbar");
            Class<?> stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            mStatusBarService = asInterface.invoke(null, binder);
            Class<?> serviceClass = mStatusBarService.getClass();
            mGetPanelExpansionMethod = serviceClass.getMethod("getPanelExpansion");
        } catch (Exception e) {
            // Log.e(TAG, "StatusBar reflection failed", e);
        }
    }

    private void resolveLauncherPackage() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            mLauncherPackage = resolveInfo.activityInfo.packageName;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mDoubleTapTimeout = Settings.Secure.getInt(getContentResolver(), KEY_DT2S_TIMEOUT, 300);
        resolveLauncherPackage();
        initStatusBarReflection();
        checkCurrentTopApp();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopInputMonitoring();
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
        } catch (Exception e) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(DT2SService.this::checkCurrentTopApp);
        }
    };

    private boolean isLauncherTopApp() {
        try {
            ActivityTaskManager.RootTaskInfo info = ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info != null && info.topActivity != null && mLauncherPackage != null) {
                return mLauncherPackage.equals(info.topActivity.getPackageName());
            }
        } catch (Exception e) { }
        return false;
    }

    private void checkCurrentTopApp() {
        if (isLauncherTopApp()) {
            startInputMonitoring();
        } else {
            stopInputMonitoring();
        }
    }

    private boolean isNotificationShadeExpanded() {
        if (mStatusBarService == null || mGetPanelExpansionMethod == null) {
            initStatusBarReflection();
            if (mStatusBarService == null) return false;
        }
        try {
            float expansion = (float) mGetPanelExpansionMethod.invoke(mStatusBarService);
            return expansion > 0.0f;
        } catch (Exception e) {
            return false;
        }
    }

    private void startInputMonitoring() {
        if (mIsMonitoring) return;
        try {
            mInputMonitor = InputManager.getInstance().monitorGestureInput("pixelparts-dt2s", Display.DEFAULT_DISPLAY);
            mInputEventReceiver = new DT2SInputEventReceiver(mInputMonitor.getInputChannel(), Looper.getMainLooper());
            mIsMonitoring = true;
        } catch (Exception e) { }
    }

    private void stopInputMonitoring() {
        if (!mIsMonitoring) return;
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
        mIsMonitoring = false;
    }

    private class DT2SInputEventReceiver extends InputEventReceiver {
        public DT2SInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) {
                    processMotionEvent((MotionEvent) event);
                }
            } finally {
                finishInputEvent(event, false);
            }
        }
    }

    // --- ОБНОВЛЕННАЯ ЛОГИКА ОБРАБОТКИ ЖЕСТОВ ---
    private void processMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // 1. Проверка условий (шторка, приложение)
                if (isNotificationShadeExpanded() || !isLauncherTopApp()) {
                    stopInputMonitoring();
                    mLastValidTapTime = 0; // Сбрасываем цепочку
                    mIsCurrentGestureSwipe = true; // Считаем этот жест невалидным
                    return;
                }

                long currentTime = SystemClock.uptimeMillis();
                mCurrentDownX = event.getX();
                mCurrentDownY = event.getY();
                mIsCurrentGestureSwipe = false; // Новый жест, пока еще не свайп

                // 2. Проверяем, является ли ЭТОТ Down вторым тапом
                long timeDelta = currentTime - mLastValidTapTime;
                float distFromLastTapX = Math.abs(mCurrentDownX - mLastValidTapX);
                float distFromLastTapY = Math.abs(mCurrentDownY - mLastValidTapY);

                if (timeDelta < mDoubleTapTimeout && 
                    distFromLastTapX < mDoubleTapSlop && 
                    distFromLastTapY < mDoubleTapSlop) {
                    
                    if (mPowerManager != null && mPowerManager.isInteractive()) {
                        mPowerManager.goToSleep(SystemClock.uptimeMillis());
                        mLastValidTapTime = 0; // Сброс
                        return;
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mIsCurrentGestureSwipe) return; // Уже определили, что свайп, игнорируем

                float deltaX = Math.abs(event.getX() - mCurrentDownX);
                float deltaY = Math.abs(event.getY() - mCurrentDownY);

                // Если палец сдвинулся больше чем на Slop (обычно 8-16px), то это СВАЙП
                if (deltaX > mDoubleTapSlop || deltaY > mDoubleTapSlop) {
                    mIsCurrentGestureSwipe = true;
                    // Так как это свайп, он "разрывает" цепочку двойного тапа.
                    // Даже если мы поднимем палец, это не будет считаться первым тапом.
                    mLastValidTapTime = 0; 
                }
                break;

            case MotionEvent.ACTION_UP:
                // Если жест завершился и это НЕ было свайпом
                if (!mIsCurrentGestureSwipe) {
                    // Запоминаем этот тап как "потенциальный первый тап" для следующего раза
                    mLastValidTapTime = SystemClock.uptimeMillis();
                    mLastValidTapX = mCurrentDownX;
                    mLastValidTapY = mCurrentDownY;
                }
                break;
                
            case MotionEvent.ACTION_CANCEL:
                mLastValidTapTime = 0;
                break;
        }
    }
}