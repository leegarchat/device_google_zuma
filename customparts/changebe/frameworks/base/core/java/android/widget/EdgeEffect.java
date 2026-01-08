
public class EdgeEffect {
    public EdgeEffect(@NonNull Context context, @Nullable AttributeSet attrs) {
        // ... стандартный код ...
        // --- [CustomNativeParts] End line ---
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.init(this, context);
    }

    public boolean isFinished() {
        // --- [CustomNativeParts] On start line ---
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
        // --- [CustomNativeParts] On start line ---
        if (com.android.internal.customNativeExtractParts.NativePartsManager.onEdgeEffectPull(this, deltaDistance, displacement, mWidth, mHeight)) {
            return;
        }
        // ... стандартный код ...
    }

    public void onRelease() {
        // --- [CustomNativeParts] On start line ---
        com.android.internal.customNativeExtractParts.EdgeEffectHooks.onRelease(this);
        // ... стандартный код ...
    }

    public void onAbsorb(int velocity) {
        // --- [CustomNativeParts] On start line ---
        com.android.internal.customNativeExtractParts.NativePartsManager.onEdgeEffectAbsorb(this, velocity, mHeight);
        // ... стандартный код ...
    }


    public boolean draw(Canvas canvas) {
        // --- [CustomNativeParts] On start line ---
        if (com.android.internal.customNativeExtractParts.NativePartsManager.onEdgeEffectDraw(this, canvas, mWidth, mHeight)) {
            return true;
        }
        // ... стандартный код ..
    }
}
