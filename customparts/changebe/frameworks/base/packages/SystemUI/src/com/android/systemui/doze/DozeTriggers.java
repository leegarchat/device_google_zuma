
// Откройте frameworks/base/packages/SystemUI/src/com/android/systemui/doze/DozeTriggers.java.



// Шаг 2.1: Добавьте импорты
// --- DT2W HOOK VARIABLES START ---
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewConfiguration;
// --- DT2W HOOK VARIABLES END ---

// Шаг 2.2: Добавить Поля класса
// В начало класса DozeTriggers, сразу после объявления 
// private static final String TAG = "DozeTriggers"; 
// и других переменных, добавьте переменные для логики двойного тапа:
	// --- DT2W PATCH VARIABLES START ---
    private static final String KEY_DOZE_DOUBLE_TAP_HOOK = "doze_double_tap_hook";
    private static final String KEY_DOZE_DOUBLE_TAP_TIMEOUT = "doze_double_tap_timeout";
    
    private boolean mDoubleTapPending = false;
    private float mLastTapX = -1; // Храним X первого тапа
    private float mLastTapY = -1; // Храним Y первого тапа
    private int mDoubleTapSlop = -1; // Допустимое расстояние
    
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDoubleTapTimeoutRunnable = () -> {
        mDoubleTapPending = false;
        mLastTapX = -1;
        mLastTapY = -1;
    };
    // --- DT2W PATCH VARIABLES END ---

// Шаг 2.3: Изменить метод onSensor
// Найдите метод void onSensor(...). Вам 
// нужно вставить логику перехвата в самое начало этого метода.
	@VisibleForTesting
    void onSensor(int pulseReason, boolean sensorPerformedProxCheck,
            float screenX, float screenY, float[] rawValues) {
        
        // --- DT2W PATCH LOGIC START ---
        if (pulseReason == DozeLog.REASON_SENSOR_TAP || pulseReason == DozeLog.REASON_SENSOR_DOUBLE_TAP) {
            boolean isHookEnabled = Settings.Secure.getInt(
                    mContext.getContentResolver(), KEY_DOZE_DOUBLE_TAP_HOOK, 0) == 1;

            if (isHookEnabled) {
                // Инициализируем slop (расстояние) один раз
                if (mDoubleTapSlop < 0) {
                    mDoubleTapSlop = ViewConfiguration.get(mContext).getScaledDoubleTapSlop();
                }

                // Читаем таймаут
                int timeout = Settings.Secure.getInt(
                            mContext.getContentResolver(), KEY_DOZE_DOUBLE_TAP_TIMEOUT, 200);

                if (!mDoubleTapPending) {
                    // --- ЭТО ПЕРВЫЙ ТАП ---
                    mDoubleTapPending = true;
                    mLastTapX = screenX;
                    mLastTapY = screenY;
                    
                    mHandler.postDelayed(mDoubleTapTimeoutRunnable, timeout);
                    mDozeSensors.reregisterTapSensor(); // Перезаряжаем сенсор
                    return; // Игнорируем этот тап, ждем второй
                } else {
                    // --- ЭТО ВТОРОЙ ТАП ---
                    float dx = Math.abs(screenX - mLastTapX);
                    float dy = Math.abs(screenY - mLastTapY);

                    // Проверяем, рядом ли пальцы (учитываем, что screenX может быть -1, если сенсор тупой)
                    boolean isCloseEnough = (screenX < 0 || mLastTapX < 0) || (dx < mDoubleTapSlop && dy < mDoubleTapSlop);

                    if (isCloseEnough) {
                        // УСПЕХ: Тапнули в ту же область
                        mDoubleTapPending = false;
                        mHandler.removeCallbacks(mDoubleTapTimeoutRunnable);
                        // Код пойдет дальше и разбудит телефон
                    } else {
                        // НЕУДАЧА: Тапнули слишком далеко
                        // Считаем этот тап новым "первым" тапом
                        mLastTapX = screenX;
                        mLastTapY = screenY;
                        
                        // Перезапускаем таймер ожидания
                        mHandler.removeCallbacks(mDoubleTapTimeoutRunnable);
                        mHandler.postDelayed(mDoubleTapTimeoutRunnable, timeout);
                        mDozeSensors.reregisterTapSensor();
                        return; // Не будим телефон
                    }
                }
            }
        }
        // --- DT2W PATCH LOGIC END ---

        // Далее идет оригинальный код метода (без изменений)...
        boolean isDoubleTap = pulseReason == DozeLog.REASON_SENSOR_DOUBLE_TAP;
        // ...