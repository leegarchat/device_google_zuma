public class EdgeEffect {
    public EdgeEffect(@NonNull Context context, @Nullable AttributeSet attrs) {
        // ... стандартный код ...
        // --- [CustomNativeParts] INIT ---
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.init(this, context);
    }

    public boolean isFinished() {
        // --- [CustomNativeParts] IS_FINISHED ---
        // Возвращает true только если физика и визуальная часть пружины полностью завершены
        if (com.android.internal.customNativeExtractParts.EdgeEffectHooks.isFinished(this)) {
            return true; 
        }
        // ... стандартный код ...
    }

    public void finish() {
        // --- [CustomNativeParts] FINISH ---
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.finish(this);
        // ... стандартный код ...
    }

    public void onPull(float deltaDistance, float displacement) {
        // --- [CustomNativeParts] ON_PULL ---
        // Перехватывает событие. Если возвращает true, стандартный код не выполняется.
        if (com.android.internal.customNativeExtractParts.EdgeEffectHooks.onPull(this, deltaDistance, displacement, mWidth, mHeight)) {
            return;
        }
        // ... стандартный код ...
    }

    public void onRelease() {
        // --- [CustomNativeParts] ON_RELEASE ---
        // Запускает анимацию возврата пружины
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.onRelease(this);
        // ... стандартный код ...
    }

    public void onAbsorb(int velocity) {
        // --- [CustomNativeParts] ON_ABSORB ---
        // Запускает анимацию инерции (fling)
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.onAbsorb(this, velocity, mHeight);
        // ... стандартный код ...
    }


    public boolean draw(Canvas canvas) {
        // --- [CustomNativeParts] DRAW ---
        // Если возвращает true, значит пружина рисуется и мы пропускаем стандартный draw
        if (com.android.internal.customNativeExtractParts.EdgeEffectHooks.draw(this, canvas, mWidth, mHeight)) {
            return true;
        }
        // ... стандартный код ..
    }
}