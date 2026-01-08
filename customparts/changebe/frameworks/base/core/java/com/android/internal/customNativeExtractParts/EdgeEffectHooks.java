package com.android.internal.customNativeExtractParts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RenderNode;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EdgeEffect;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * Native implementation of iOS-style Overscroll (Spring).
 * Optimized for direct integration into Android Framework.
 */
public class EdgeEffectHooks {

    private static final String TAG = "PixelPartsOverscroll";

    // --- Keys ---
    private static final String KEY_ENABLED = "overscroll_enabled";
    private static final String KEY_PACKAGES_CONFIG = "overscroll_packages_config";
    private static final String KEY_LOGGING = "overscroll_logging";
    
    // Physics
    private static final String KEY_PULL_COEFF = "overscroll_pull";
    private static final String KEY_STIFFNESS = "overscroll_stiffness";
    private static final String KEY_DAMPING = "overscroll_damping";
    private static final String KEY_FLING = "overscroll_fling";
    private static final String KEY_PHYSICS_MIN_VEL = "overscroll_physics_min_vel";
    private static final String KEY_PHYSICS_MIN_VAL = "overscroll_physics_min_val";
    
    // Smoothing & Logic
    private static final String KEY_INPUT_SMOOTH_FACTOR = "overscroll_input_smooth";
    private static final String KEY_RESISTANCE_EXPONENT = "overscroll_res_exponent";
    private static final String KEY_LERP_MAIN_IDLE = "overscroll_lerp_main_idle";
    private static final String KEY_LERP_MAIN_RUN = "overscroll_lerp_main_run";
    private static final String KEY_COMPOSE_SCALE = "overscroll_compose_scale";
    
    // Vertical Scale
    private static final String KEY_SCALE_MODE = "overscroll_scale_mode";
    private static final String KEY_SCALE_INTENSITY = "overscroll_scale_intensity";
    private static final String KEY_SCALE_LIMIT_MIN = "overscroll_scale_limit_min";
    private static final String KEY_SCALE_ANCHOR_Y = "overscroll_scale_anchor_y";
    
    // Zoom
    private static final String KEY_ZOOM_MODE = "overscroll_zoom_mode";
    private static final String KEY_ZOOM_INTENSITY = "overscroll_zoom_intensity";
    private static final String KEY_ZOOM_LIMIT_MIN = "overscroll_zoom_limit_min";
    private static final String KEY_ZOOM_ANCHOR_X = "overscroll_zoom_anchor_x";
    private static final String KEY_ZOOM_ANCHOR_Y = "overscroll_zoom_anchor_y";
    
    // Horizontal Scale
    private static final String KEY_H_SCALE_MODE = "overscroll_h_scale_mode";
    private static final String KEY_H_SCALE_INTENSITY = "overscroll_h_scale_intensity";
    private static final String KEY_H_SCALE_LIMIT_MIN = "overscroll_h_scale_limit_min";
    private static final String KEY_H_SCALE_ANCHOR_X = "overscroll_h_scale_anchor_x";

    // Horizontal Specific Anchors & Intensities
    private static final String KEY_SCALE_ANCHOR_X_HORIZ = "overscroll_scale_anchor_x_horiz";
    private static final String KEY_H_SCALE_ANCHOR_Y_HORIZ = "overscroll_h_scale_anchor_y_horiz";
    private static final String KEY_ZOOM_ANCHOR_X_HORIZ = "overscroll_zoom_anchor_x_horiz";
    private static final String KEY_ZOOM_ANCHOR_Y_HORIZ = "overscroll_zoom_anchor_y_horiz";
    
    private static final String KEY_SCALE_INTENSITY_HORIZ = "overscroll_scale_intensity_horiz";
    private static final String KEY_ZOOM_INTENSITY_HORIZ = "overscroll_zoom_intensity_horiz";
    private static final String KEY_H_SCALE_INTENSITY_HORIZ = "overscroll_h_scale_intensity_horiz";

    private static final String KEY_INVERT_ANCHOR = "overscroll_invert_anchor";

    private static final float FILTER_THRESHOLD = 0.08f;

    // --- State Storage ---
    private static final WeakHashMap<EdgeEffect, EdgeEffectState> sStates = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> sComposeCache = new WeakHashMap<>();

    // --- Reflection Cache ---
    private static Field sEdgeEffectStateField;
    private static Field sEdgeEffectDistanceField;
    private static Field sCanvasNodeField; 

    static {
        try {
            sEdgeEffectStateField = EdgeEffect.class.getDeclaredField("mState");
            sEdgeEffectStateField.setAccessible(true);
            sEdgeEffectDistanceField = EdgeEffect.class.getDeclaredField("mDistance");
            sEdgeEffectDistanceField.setAccessible(true);
        } catch (Exception e) {
            Log.e(TAG, "Reflection init failed", e);
        }
    }

    private static class EdgeEffectState {
        Context context;
        SpringDynamics spring = new SpringDynamics();
        float smoothOffsetY = 0f;
        float smoothScale = 1.0f;
        float smoothZoom = 1.0f;
        float smoothHScale = 1.0f;
        Matrix matrix = new Matrix();
        float[] points = new float[4];
        float lastDelta = 0f;
        float targetFingerX = 0.5f;
        float currentFingerX = 0.5f;
        boolean firstTouch = true;
        
        float screenHeight = 2200f;
        float screenWidth = 1080f;
        
        float cfgScale = 1.0f;
        boolean cfgFilter = false;
        boolean cfgIgnore = false;
    }

    private static EdgeEffectState getState(EdgeEffect effect) {
        return sStates.get(effect);
    }
    
    private static EdgeEffectState getOrCreateState(EdgeEffect effect, Context context) {
        EdgeEffectState state = sStates.get(effect);
        if (state == null && context != null) {
            state = new EdgeEffectState();
            state.context = context;
            initScreenMetrics(state, context);
            initConfig(state, context);
            sStates.put(effect, state);
        }
        return state;
    }

    // ============================================================================================
    // PUBLIC API
    // ============================================================================================

    public static void init(EdgeEffect effect, Context context) {
        getOrCreateState(effect, context);
    }

    public static boolean isFinished(EdgeEffect effect) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return false; 
        
        float minVal = getFloatSetting(state.context, KEY_PHYSICS_MIN_VAL, 0.5f);
        boolean physicsDone = !state.spring.isRunning() && Math.abs(state.spring.mValue) < minVal;
        boolean visualDone = Math.abs(state.smoothOffsetY) < minVal;
        boolean fullyFinished = physicsDone && visualDone;

        if (fullyFinished) {
            resetState(state);
            // Reset system state to IDLE
            setPrivateInt(effect, sEdgeEffectStateField, 0); 
            setPrivateFloat(effect, sEdgeEffectDistanceField, 0f);
        }
        return fullyFinished;
    }

    public static void finish(EdgeEffect effect) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return;

        state.spring.cancel();
        state.spring.mValue = 0;
        state.spring.mVelocity = 0;
        
        setPrivateInt(effect, sEdgeEffectStateField, 0); 
        setPrivateFloat(effect, sEdgeEffectDistanceField, 0f);
        
        resetState(state);
    }

    public static boolean onPull(EdgeEffect effect, float deltaDistance, float displacement, float width, float height) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return false;

        // Compose Fix
        if (isComposeCaller(effect)) {
            float composeDivisor = getFloatSetting(state.context, KEY_COMPOSE_SCALE, 3.33f);
            if (composeDivisor < 0.01f) composeDivisor = 1.0f;
            deltaDistance /= composeDivisor;
        }

        if (state.cfgFilter && Math.abs(deltaDistance) > FILTER_THRESHOLD) return true;

        float correctedDelta = (Math.abs(state.cfgScale) > 0.001f) ? deltaDistance / state.cfgScale : deltaDistance;
        float inputSmoothFactor = getFloatSetting(state.context, KEY_INPUT_SMOOTH_FACTOR, 0.5f);
        
        if (state.firstTouch) {
            state.currentFingerX = 0.5f;
            state.firstTouch = false;
            state.lastDelta = correctedDelta;
        }

        state.targetFingerX = displacement;

        boolean directionChanged = (correctedDelta > 0 && state.lastDelta < 0) || (correctedDelta < 0 && state.lastDelta > 0);
        float filteredDelta = directionChanged ? correctedDelta : (correctedDelta * (1.0f - inputSmoothFactor) + state.lastDelta * inputSmoothFactor);
        state.lastDelta = filteredDelta;

        setPrivateInt(effect, sEdgeEffectStateField, 1); // STATE_PULL
        state.spring.cancel();

        float currentTranslation = state.spring.mValue;
        
        // Use passed width/height directly
        float effectiveSize = Math.max(Math.abs(height), Math.abs(width));
        if (effectiveSize < 1f) effectiveSize = state.screenHeight;

        float rawMove = filteredDelta * effectiveSize;
        float pullCoeff = getFloatSetting(state.context, KEY_PULL_COEFF, 0.5f);
        float resExponent = getFloatSetting(state.context, KEY_RESISTANCE_EXPONENT, 4.0f);

        boolean isPullingAway = (currentTranslation > 0 && rawMove > 0) || (currentTranslation < 0 && rawMove < 0);
        float change;

        if (pullCoeff >= 1.0f) {
            change = rawMove * pullCoeff;
        } else {
            if (isPullingAway) {
                float ratio = Math.min(Math.abs(currentTranslation) / state.screenHeight, 1f);
                float resistance = (float) Math.pow(1.0f - ratio, resExponent);
                change = rawMove * resistance;
            } else {
                change = rawMove;
            }
        }

        float nextTranslation = currentTranslation + change;
        // Zero crossing check
        if ((currentTranslation > 0 && nextTranslation < 0) || (currentTranslation < 0 && nextTranslation > 0)) {
            nextTranslation = 0f;
        }

        state.spring.mValue = nextTranslation;
        setPrivateFloat(effect, sEdgeEffectDistanceField, nextTranslation / effectiveSize);

        return true; 
    }

    public static void onRelease(EdgeEffect effect) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return;

        if (state.spring.mValue != 0) {
            float stiffness = getFloatSetting(state.context, KEY_STIFFNESS, 450f);
            float damping = getFloatSetting(state.context, KEY_DAMPING, 0.7f);
            float minVel = getFloatSetting(state.context, KEY_PHYSICS_MIN_VEL, 1.0f);
            float minVal = getFloatSetting(state.context, KEY_PHYSICS_MIN_VAL, 0.5f);

            state.spring.setParams(stiffness, damping, minVel, minVal);
            state.spring.setTargetValue(0);
            state.spring.setVelocity(0);
            state.spring.start();
            
            setPrivateInt(effect, sEdgeEffectStateField, 3); // STATE_RECEDE
        } else {
            setPrivateInt(effect, sEdgeEffectStateField, 0); // STATE_IDLE
            setPrivateFloat(effect, sEdgeEffectDistanceField, 0f);
        }

        state.targetFingerX = 0.5f;
        state.lastDelta = 0f;
        state.firstTouch = true;
    }

    public static void onAbsorb(EdgeEffect effect, int velocity, float height) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return;

        setPrivateInt(effect, sEdgeEffectStateField, 3); // STATE_RECEDE
        state.spring.cancel();

        float flingMult = getFloatSetting(state.context, KEY_FLING, 0.6f);
        float stiffness = getFloatSetting(state.context, KEY_STIFFNESS, 450f);
        float damping = getFloatSetting(state.context, KEY_DAMPING, 0.7f);
        float minVel = getFloatSetting(state.context, KEY_PHYSICS_MIN_VEL, 1.0f);
        float minVal = getFloatSetting(state.context, KEY_PHYSICS_MIN_VAL, 0.5f);

        float velocityPx = velocity * flingMult;
        if (flingMult > 1.0f) stiffness /= flingMult;

        // Use passed height directly
        float maxVel = (height > 0 ? height : state.screenHeight) * 10f;
        if (Math.abs(velocityPx) > maxVel) velocityPx = Math.signum(velocityPx) * maxVel;

        state.spring.setParams(stiffness, damping, minVel, minVal);
        state.spring.setTargetValue(0);
        state.spring.setVelocity(velocityPx);
        state.spring.start();

        state.targetFingerX = 0.5f;
        state.lastDelta = 0f;
        state.firstTouch = true;
    }

    public static boolean draw(EdgeEffect effect, Canvas canvas, float width, float height) {
        EdgeEffectState state = getState(effect);
        if (state == null || !isBounceEnabled(state)) return false; 

        if (!canvas.isHardwareAccelerated()) return false;

        if (state.spring.isRunning()) {
            state.spring.doFrame(System.nanoTime());
        }

        RenderNode renderNode = null;
        try {
            renderNode = getCanvasRenderNode(canvas);
        } catch (Exception e) {
            return false;
        }

        if (renderNode == null) return false;

        // --- Vector Snapping Logic ---
        state.points[0] = 0; state.points[1] = 0;
        state.points[2] = 0; state.points[3] = 1;
        
        try {
            canvas.getMatrix(state.matrix);
            state.matrix.mapPoints(state.points);
        } catch (Exception ignored) {}

        float rawVecX = state.points[2] - state.points[0];
        float rawVecY = state.points[3] - state.points[1];
        
        float vx = rawVecX;
        float vy = rawVecY;

        // Snap to Grid
        if (Math.abs(vx) > Math.abs(vy)) {
            vx = Math.signum(vx); 
            vy = 0f;
        } else {
            vy = Math.signum(vy);
            vx = 0f;
        }

        boolean isVertical = (vy != 0);

        float lerpMainIdle = getFloatSetting(state.context, KEY_LERP_MAIN_IDLE, 0.4f);
        float lerpMainRun = getFloatSetting(state.context, KEY_LERP_MAIN_RUN, 0.7f);
        float lerpFactorMain = state.spring.isRunning() ? lerpMainRun : lerpMainIdle;

        float targetOffset = state.spring.mValue;
        float currentOffset = state.smoothOffsetY;
        float newOffset = currentOffset + (targetOffset - currentOffset) * lerpFactorMain;
        
        if (Math.abs(targetOffset - newOffset) < 0.5f) newOffset = targetOffset;
        state.smoothOffsetY = newOffset;

        float maxDistance = isVertical ? state.screenHeight : state.screenWidth;
        float ratio = (maxDistance > 0) ? Math.min(Math.abs(newOffset) / maxDistance, 1.0f) : 0f;
        boolean isActive = Math.abs(newOffset) > 1.0f;

        // SCALING
        float targetScaleV = 1f, targetScaleZ = 1f, targetScaleH = 1f;
        boolean zoomActive = getIntSetting(state.context, KEY_ZOOM_MODE, 0) != 0;
        boolean scaleActive = getIntSetting(state.context, KEY_SCALE_MODE, 0) != 0;
        boolean hScaleActive = getIntSetting(state.context, KEY_H_SCALE_MODE, 0) != 0;

        if (isActive) {
            String keyScaleInt = isVertical ? KEY_SCALE_INTENSITY : KEY_SCALE_INTENSITY_HORIZ;
            String keyZoomInt = isVertical ? KEY_ZOOM_INTENSITY : KEY_ZOOM_INTENSITY_HORIZ;
            String keyHScaleInt = isVertical ? KEY_H_SCALE_INTENSITY : KEY_H_SCALE_INTENSITY_HORIZ;

            targetScaleV = calcScale(state.context, KEY_SCALE_MODE, keyScaleInt, KEY_SCALE_LIMIT_MIN, ratio);
            targetScaleZ = calcScale(state.context, KEY_ZOOM_MODE, keyZoomInt, KEY_ZOOM_LIMIT_MIN, ratio);
            targetScaleH = calcScale(state.context, KEY_H_SCALE_MODE, keyHScaleInt, KEY_H_SCALE_LIMIT_MIN, ratio);
        }

        state.smoothScale = lerp(state.smoothScale, targetScaleV, lerpFactorMain);
        state.smoothZoom = lerp(state.smoothZoom, targetScaleZ, lerpFactorMain);
        state.smoothHScale = lerp(state.smoothHScale, targetScaleH, lerpFactorMain);

        boolean isResting = Math.abs(newOffset) < 0.1f 
                && Math.abs(state.smoothScale - 1f) < 0.001f 
                && Math.abs(state.smoothZoom - 1f) < 0.001f
                && Math.abs(state.smoothHScale - 1f) < 0.001f;

        if (isResting && !state.spring.isRunning()) {
            safeResetRenderNode(renderNode);
            return false;
        }
        
        // Use passed width/height
        float effectiveSize = Math.max(Math.abs(height), Math.abs(width));
        if (effectiveSize < 1f) effectiveSize = state.screenHeight;
        
        setPrivateFloat(effect, sEdgeEffectDistanceField, newOffset / effectiveSize);

        try {
            renderNode.setTranslationX(newOffset * vx);
            renderNode.setTranslationY(newOffset * vy);

            float axisMainScale = state.smoothScale * state.smoothZoom;
            float axisCrossScale = state.smoothHScale * state.smoothZoom;
            
            float finalScaleX, finalScaleY;
            if (isVertical) {
                finalScaleX = axisCrossScale; 
                finalScaleY = axisMainScale;  
            } else {
                finalScaleX = axisMainScale;  
                finalScaleY = axisCrossScale; 
            }

            // --- ANCHOR LOGIC ---
            float ax = 0.5f;
            float ay = 0.5f;
            
            if (isVertical) {
                if (zoomActive) {
                    ax = getFloatSetting(state.context, KEY_ZOOM_ANCHOR_X, 0.5f);
                    ay = getFloatSetting(state.context, KEY_ZOOM_ANCHOR_Y, 0.5f);
                } else if (scaleActive) {
                    ax = 0.5f;
                    ay = getFloatSetting(state.context, KEY_SCALE_ANCHOR_Y, 0.5f);
                } else if (hScaleActive) {
                    ax = getFloatSetting(state.context, KEY_H_SCALE_ANCHOR_X, 0.5f);
                    ay = 0.5f;
                }
            } else {
                // Horizontal: Swap Logic
                if (zoomActive) {
                    ax = getFloatSetting(state.context, KEY_ZOOM_ANCHOR_X_HORIZ, 0.5f);
                    ay = getFloatSetting(state.context, KEY_ZOOM_ANCHOR_Y_HORIZ, 0.5f);
                } else if (scaleActive) {
                    ax = getFloatSetting(state.context, KEY_SCALE_ANCHOR_X_HORIZ, 0.5f);
                    ay = 0.5f;
                } else if (hScaleActive) {
                    ax = 0.5f;
                    ay = getFloatSetting(state.context, KEY_H_SCALE_ANCHOR_Y_HORIZ, 0.5f);
                }
            }

            boolean invertAnchor = getIntSetting(state.context, KEY_INVERT_ANCHOR, 1) == 1;
            
            float canvasW = (float) canvas.getWidth();
            float canvasH = (float) canvas.getHeight();
            float pX, pY;

            if (isVertical) {
                pX = canvasW * ax;
                if (vy > 0) { // Top
                    pY = canvasH * ay;
                } else {      // Bottom
                    pY = canvasH * (invertAnchor ? (1.0f - ay) : ay);
                }
            } else {
                pY = canvasH * ay;
                if (vx > 0) { // Left
                    pX = canvasW * ax;
                } else {      // Right
                    pX = canvasW * (invertAnchor ? (1.0f - ax) : ax);
                }
            }

            renderNode.setPivotX(pX);
            renderNode.setPivotY(pY);
            renderNode.setScaleX(finalScaleX);
            renderNode.setScaleY(finalScaleY);
            
            renderNode.setRotationX(0f);
            renderNode.setRotationY(0f);
            renderNode.setRotationZ(0f);
            
            try {
               renderNode.getClass().getMethod("stretch", float.class, float.class, float.class, float.class)
                   .invoke(renderNode, 0f, 0f, width, height);
            } catch (Exception ignored) {}

        } catch (Throwable t) {
            // Ignore
        }

        return state.spring.isRunning() || !isResting;
    }

    // --- RECURSIVE RENDER NODE LOOKUP ---
    private static RenderNode getCanvasRenderNode(Canvas canvas) throws IllegalAccessException {
        if (sCanvasNodeField != null) {
            try {
                return (RenderNode) sCanvasNodeField.get(canvas);
            } catch (Exception ignored) {}
        }
        
        Class<?> clazz = canvas.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField("mNode");
                f.setAccessible(true);
                sCanvasNodeField = f;
                return (RenderNode) f.get(canvas);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    // --- UTILS ---

    private static void resetState(EdgeEffectState state) {
        state.smoothOffsetY = 0f;
        state.smoothScale = 1.0f;
        state.smoothZoom = 1.0f;
        state.smoothHScale = 1.0f;
        state.targetFingerX = 0.5f;
        state.currentFingerX = 0.5f;
        state.firstTouch = true;
    }

    private static void safeResetRenderNode(RenderNode node) {
        if (node == null) return;
        try {
            node.setTranslationX(0f); node.setTranslationY(0f);
            node.setScaleX(1f); node.setScaleY(1f);
            node.setPivotX(0f); node.setPivotY(0f);
            node.setRotationX(0f); node.setRotationY(0f); node.setRotationZ(0f);
            
            try {
                node.getClass().getMethod("stretch", float.class, float.class, float.class, float.class)
                   .invoke(node, 0f, 0f, 0f, 0f);
            } catch (Exception ignored) {}
        } catch (Throwable ignored) {}
    }
    
    private static void initScreenMetrics(EdgeEffectState state, Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getMetrics(dm);
                state.screenHeight = dm.heightPixels;
                state.screenWidth = dm.widthPixels;
            }
        } catch (Exception ignored) {}
    }

    private static void initConfig(EdgeEffectState state, Context context) {
        try {
            String pkgName = context.getPackageName();
            String configString = Settings.Secure.getString(context.getContentResolver(), KEY_PACKAGES_CONFIG);
            if (!TextUtils.isEmpty(configString) && pkgName != null) {
                String[] apps = configString.split(" ");
                for (String appConfig : apps) {
                    String[] parts = appConfig.split(":");
                    if (parts.length >= 3 && parts[0].equals(pkgName)) {
                        state.cfgFilter = Integer.parseInt(parts[1]) == 1;
                        state.cfgScale = Float.parseFloat(parts[2]);
                        if (parts.length >= 4) {
                            state.cfgIgnore = parts[3].equals("1");
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean isBounceEnabled(EdgeEffectState state) {
        if (state == null || state.context == null) return true;
        try {
            if (state.cfgIgnore) return false;
            return Settings.Secure.getInt(state.context.getContentResolver(), KEY_ENABLED, 1) == 1;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isComposeCaller(Object thiz) {
        if (sComposeCache.containsKey(thiz)) return Boolean.TRUE.equals(sComposeCache.get(thiz));
        boolean isCompose = false;
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stack) {
                if (element.getClassName().startsWith("androidx.compose")) {
                    isCompose = true;
                    break;
                }
            }
        } catch (Exception ignored) {}
        sComposeCache.put(thiz, isCompose);
        return isCompose;
    }

    private static float getFloatSetting(Context ctx, String key, float def) {
        try {
            return Settings.Secure.getFloat(ctx.getContentResolver(), key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static int getIntSetting(Context ctx, String key, int def) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static float calcScale(Context ctx, String modeKey, String intKey, String limKey, float ratio) {
        int mode = getIntSetting(ctx, modeKey, 0);
        float intensity = getFloatSetting(ctx, intKey, 0.0f);
        float limit = getFloatSetting(ctx, limKey, 0.3f);
        if (mode == 0 || intensity <= 0) return 1.0f;
        if (mode == 1) return Math.max(1.0f - (ratio * intensity), limit);
        if (mode == 2) return 1.0f + (ratio * intensity);
        return 1.0f;
    }

    private static float lerp(float start, float end, float factor) {
        return start + (end - start) * factor;
    }

    private static void setPrivateInt(Object obj, Field field, int value) {
        try { if (field != null) field.setInt(obj, value); } catch (Exception ignored) {}
    }
    private static void setPrivateFloat(Object obj, Field field, float value) {
        try { if (field != null) field.setFloat(obj, value); } catch (Exception ignored) {}
    }

    private static class SpringDynamics {
        private float mStiffness = 450.0f;
        private float mDampingRatio = 0.7f;
        private float mMinVel = 1.0f;
        private float mMinVal = 0.5f;

        public float mValue;
        public float mVelocity;
        public float mTargetValue = 0f;
        private boolean mIsRunning = false;
        private long mLastFrameTimeNanos = 0;

        public void setParams(float stiffness, float damping, float minVel, float minVal) {
            mStiffness = stiffness > 0 ? stiffness : 0.1f;
            mDampingRatio = damping >= 0 ? damping : 0;
            mMinVel = minVel;
            mMinVal = minVal;
        }

        public void setTargetValue(float targetValue) { mTargetValue = targetValue; }
        public void setVelocity(float velocity) { mVelocity = velocity; }
        public boolean isRunning() { return mIsRunning; }

        public void start() {
            if (mIsRunning) return;
            mIsRunning = true;
            mLastFrameTimeNanos = System.nanoTime();
        }

        public void cancel() { mIsRunning = false; }

        public void doFrame(long frameTimeNanos) {
            if (!mIsRunning) return;
            long deltaTimeNanos = frameTimeNanos - mLastFrameTimeNanos;
            if (deltaTimeNanos > 100_000_000) deltaTimeNanos = 16_000_000;
            mLastFrameTimeNanos = frameTimeNanos;
            float dt = deltaTimeNanos / 1_000_000_000.0f;

            float displacement = mValue - mTargetValue;
            float dampingCoefficient = 2 * mDampingRatio * (float) Math.sqrt(mStiffness);
            float force = -mStiffness * displacement - dampingCoefficient * mVelocity;

            if (Float.isNaN(force) || Float.isInfinite(force)) force = 0;

            mVelocity += force * dt;
            mValue += mVelocity * dt;

            if (Float.isNaN(mValue) || Float.isInfinite(mValue)) {
                mValue = mTargetValue;
                mVelocity = 0;
                cancel();
                return;
            }

            if (Math.abs(mVelocity) < mMinVel && Math.abs(mValue - mTargetValue) < mMinVal) {
                mValue = mTargetValue;
                mVelocity = 0;
                cancel();
            }
        }
    }
}