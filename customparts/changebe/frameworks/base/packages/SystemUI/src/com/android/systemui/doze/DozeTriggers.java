
// Откройте frameworks/base/packages/SystemUI/src/com/android/systemui/doze/DozeTriggers.java.



// Шаг 2.1: Добавьте импорты
// --- 2T2W HOOK VARIABLES START ---
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
// --- 2T2W HOOK VARIABLES END ---

// Шаг 2.2: Добавить Поля класса
// В начало класса DozeTriggers, сразу после объявления 
// private static final String TAG = "DozeTriggers"; 
// и других переменных, добавьте переменные для логики двойного тапа:
	// --- 2T2W PATCH VARIABLES START ---
    private static final String KEY_DOZE_DOUBLE_TAP_HOOK = "doze_double_tap_hook";
    private static final long DOUBLE_TAP_TIMEOUT_MS = 400; // Время ожидания второго тапа
    private boolean mDoubleTapPending = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDoubleTapTimeoutRunnable = () -> mDoubleTapPending = false;
    // --- 2T2W PATCH VARIABLES END ---

// Шаг 2.3: Изменить метод onSensor
// Найдите метод void onSensor(...). Вам 
// нужно вставить логику перехвата в самое начало этого метода.
	@VisibleForTesting
    void onSensor(int pulseReason, boolean sensorPerformedProxCheck,
            float screenX, float screenY, float[] rawValues) {
        // --- 2T2W PATCH LOGIC START ---
        if (pulseReason == DozeLog.REASON_SENSOR_TAP || pulseReason == DozeLog.REASON_SENSOR_DOUBLE_TAP) {
            boolean isHookEnabled = Settings.Secure.getInt(
                    mContext.getContentResolver(), KEY_DOZE_DOUBLE_TAP_HOOK, 0) == 1;

            if (isHookEnabled) {
                if (!mDoubleTapPending) {
                    mDoubleTapPending = true;
                    mHandler.postDelayed(mDoubleTapTimeoutRunnable, DOUBLE_TAP_TIMEOUT_MS);
                    mDozeSensors.reregisterTapSensor();
                    return;
                } else {
                    mDoubleTapPending = false;
                    mHandler.removeCallbacks(mDoubleTapTimeoutRunnable);
                }
            }
        }
        // --- 2T2W PATCH LOGIC END ---

        // Далее идет оригинальный код метода (без изменений)...
        boolean isDoubleTap = pulseReason == DozeLog.REASON_SENSOR_DOUBLE_TAP;
        boolean isTap = pulseReason == DozeLog.REASON_SENSOR_TAP;
        // ... и так далее до конца метода