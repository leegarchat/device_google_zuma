
// --- DT2W PATCH IMPOERTS START ---
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
// --- DT2W PATCH IMPOERTS END ---


constructor(
    private val notificationShadeWindowView: NotificationShadeWindowView,
    private val falsingManager: FalsingManager,
    private val dockManager: DockManager,
    private val powerInteractor: PowerInteractor,
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val statusBarStateController: StatusBarStateController,
    private val shadeLogger: ShadeLogger,
    private val dozeInteractor: DozeInteractor,
    userTracker: UserTracker,
    tunerService: TunerService,
    dumpManager: DumpManager
) : GestureDetector.SimpleOnGestureListener(), Dumpable {
    private val vibrator: Vibrator
    private var doubleTapEnabled = false
    private var singleTapEnabled = false
    private var doubleTapEnabledNative = false
    private var doubleTapVibrate = false
    private var singleTapVibrate = false
	// --- DT2W PATCH VARIABLES START ---
    private val mHandler = Handler(Looper.getMainLooper())
    private var mDoubleTapPending = false
    private var mLastTapX = -1f
    private var mLastTapY = -1f
    private var mDoubleTapSlop = -1
    private val KEY_DOZE_DOUBLE_TAP_HOOK = "doze_double_tap_hook"
    private val KEY_DOZE_DOUBLE_TAP_TIMEOUT = "doze_double_tap_timeout"
    
    private val mDoubleTapTimeoutRunnable = Runnable {
        mDoubleTapPending = false
        mLastTapX = -1f
        mLastTapY = -1f
    }
    // --- DT2W PATCH VARIABLES END ---



fun onSingleTapUp(x: Float, y: Float): Boolean {
// --- DT2W PATCH START ---
        val contentResolver = notificationShadeWindowView.context.contentResolver
        val isHookEnabled = Settings.Secure.getInt(contentResolver, KEY_DOZE_DOUBLE_TAP_HOOK, 0) == 1
        
        if (isHookEnabled && statusBarStateController.isDozing) {
            if (mDoubleTapSlop < 0) {
                mDoubleTapSlop = ViewConfiguration.get(notificationShadeWindowView.context).scaledDoubleTapSlop
            }

            val timeout = Settings.Secure.getInt(contentResolver, KEY_DOZE_DOUBLE_TAP_TIMEOUT, 200).toLong()

            if (!mDoubleTapPending) {
                // ПЕРВЫЙ ТАП
                mDoubleTapPending = true
                mLastTapX = x
                mLastTapY = y
                
                mHandler.removeCallbacks(mDoubleTapTimeoutRunnable)
                mHandler.postDelayed(mDoubleTapTimeoutRunnable, timeout)
                
                // Возвращаем true, но НЕ будим экран. Система думает, что тап обработан.
                shadeLogger.d("DT2W: First tap detected, waiting...")
                return true
            } else {
                // ВТОРОЙ ТАП
                val dx = Math.abs(x - mLastTapX)
                val dy = Math.abs(y - mLastTapY)

                if (dx < mDoubleTapSlop && dy < mDoubleTapSlop) {
                    // УСПЕХ: Двойной тап подтвержден!
                    mDoubleTapPending = false
                    mHandler.removeCallbacks(mDoubleTapTimeoutRunnable)
                    shadeLogger.d("DT2W: Double tap confirmed! Waking up...")
                    // Код пойдет дальше вниз и выполнит powerInteractor.wakeUpIfDozing
                } else {
                    // НЕУДАЧА: Тапнули далеко.
                    shadeLogger.d("DT2W: Second tap too far. Resetting.")
                    mLastTapX = x
                    mLastTapY = y
                    mHandler.removeCallbacks(mDoubleTapTimeoutRunnable)
                    mHandler.postDelayed(mDoubleTapTimeoutRunnable, timeout)
                    return true
                }
            }
        }
        // --- DT2W PATCH END ---