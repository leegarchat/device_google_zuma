package com.android.internal.customNativeExtractParts;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class LauncherGridSize {

    private static final String TAG = "LauncherGridSize";
    private static final String KEY_HOME_ENABLE = "launcher_homepage_sizer";
    private static final String KEY_HOME_COLS = "launcher_homepage_h";
    private static final String KEY_HOME_ROWS = "launcher_homepage_v";
    private static final String KEY_HOME_HIDE_TEXT = "launcher_homepage_hide_text";
    private static final String KEY_MENU_ENABLE = "launcher_menupage_sizer";
    private static final String KEY_MENU_COLS = "launcher_menupage_h";
    private static final String KEY_MENU_SEARCH_COLS = "launcher_menupage_search_h";
    private static final String KEY_MENU_HIDE_TEXT = "launcher_menupage_hide_text";
    private static final String KEY_MENU_ROW_HEIGHT = "launcher_menupage_row_height";

    private static int lastAppliedConfigHash = 0;

    public static void attach(final Activity activity) {
        if (activity == null) return;
        View contentView = activity.findViewById(android.R.id.content);
        if (contentView != null) {
            contentView.post(() -> applyGridSettings(activity));
        } else {
            applyGridSettings(activity);
        }
    }

    private static void applyGridSettings(Activity activity) {
        try {
            boolean isMenuEnabled = isEnabled(activity, KEY_MENU_ENABLE);
            boolean isHomeEnabled = isEnabled(activity, KEY_HOME_ENABLE);

            if (!isMenuEnabled && !isHomeEnabled) return;
            int menuCols = getInt(activity, KEY_MENU_COLS, 0);
            int searchCols = getInt(activity, KEY_MENU_SEARCH_COLS, 0);
            int rowHeightRaw = getInt(activity, KEY_MENU_ROW_HEIGHT, 100);
            boolean hideMenuText = isEnabled(activity, KEY_MENU_HIDE_TEXT);
            int homeCols = getInt(activity, KEY_HOME_COLS, 0);
            int homeRows = getInt(activity, KEY_HOME_ROWS, 0);
            boolean hideHomeText = isEnabled(activity, KEY_HOME_HIDE_TEXT);
            int currentConfigHash = (menuCols + "|" + searchCols + "|" + rowHeightRaw + "|" + hideMenuText + "|"
                                + homeCols + "|" + homeRows + "|" + hideHomeText).hashCode();
            
            if (lastAppliedConfigHash == currentConfigHash) return;
            Object deviceProfile = getField(activity, "mDeviceProfile");
            if (deviceProfile == null) return;

            Object invariantDeviceProfile = getField(deviceProfile, "inv");
            Object allAppsProfile = getField(deviceProfile, "mAllAppsProfile");

            boolean dpChanged = false;
            int gridCols = (menuCols > 0) ? menuCols : 4;
            int headerCols = (searchCols > 0) ? searchCols : gridCols;
            if (isHomeEnabled) {
                if (invariantDeviceProfile != null) {
                    if (homeCols > 0) {
                        setIntFieldSilently(invariantDeviceProfile, "numColumns", homeCols);
                        setIntFieldSilently(invariantDeviceProfile, "numShownHotseatIcons", homeCols);
                        dpChanged = true;
                    }
                    if (homeRows > 0) {
                        setIntFieldSilently(invariantDeviceProfile, "numRows", homeRows);
                        dpChanged = true;
                    }
                }
                
                if (hideHomeText) {
                    setIntFieldSilently(deviceProfile, "iconTextSizePx", 0);
                    dpChanged = true;
                }
            }

            if (isMenuEnabled) {
                if (gridCols > 0) {
                    applyColumnsToDp(invariantDeviceProfile, deviceProfile, allAppsProfile, gridCols);
                    recalculateCellWidth(deviceProfile, allAppsProfile, gridCols);
                    dpChanged = true;
                }

                float rowHeightScale = (rowHeightRaw <= 0) ? 1.0f : rowHeightRaw / 100f;
                if (rowHeightScale != 1.0f) {
                    applyHeightScale(deviceProfile, "allAppsCellHeightPx", rowHeightScale);
                    setIntFieldSilently(deviceProfile, "allAppsIconDrawablePaddingPx", 0);
                    if (allAppsProfile != null) {
                        applyHeightScale(allAppsProfile, "cellHeightPx", rowHeightScale);
                        setIntFieldSilently(allAppsProfile, "iconDrawablePaddingPx", 0);
                    }
                    dpChanged = true;
                }

                if (hideMenuText) {
                    setIntFieldSilently(deviceProfile, "allAppsIconTextSizePx", 0);
                    setIntFieldSilently(deviceProfile, "allAppsIconDrawablePaddingPx", 0);
                    if (allAppsProfile != null) {
                        setFloatFieldSilently(allAppsProfile, "iconTextSizePx", 0f);
                        setIntFieldSilently(allAppsProfile, "iconDrawablePaddingPx", 0);
                    }
                    dpChanged = true;
                }
            }

            if (dpChanged) {
                lastAppliedConfigHash = currentConfigHash;
                View appsView = getAppsView(activity);
                
                if (appsView != null) {
                    triggerNativeOnDpChanged(appsView, deviceProfile);
                    manualForceUpdateList(activity, appsView, gridCols);
                    if (headerCols > 0 && headerCols != gridCols) {
                        applyColumnsToDp(invariantDeviceProfile, deviceProfile, allAppsProfile, headerCols);
                        recalculateCellWidth(deviceProfile, allAppsProfile, headerCols);
                        updateFloatingHeader(appsView, deviceProfile);
                        applyColumnsToDp(invariantDeviceProfile, deviceProfile, allAppsProfile, gridCols);
                        recalculateCellWidth(deviceProfile, allAppsProfile, gridCols);
                    }
                    appsView.post(() -> fixSearchBar(appsView));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to apply grid settings", e);
        }
    }

    private static void applyColumnsToDp(Object invDp, Object dp, Object allAppsProfile, int cols) {
        if (invDp != null) {
            setIntFieldSilently(invDp, "numAllAppsColumns", cols);
            setIntFieldSilently(invDp, "numShownAllAppsColumns", cols);
            setIntFieldSilently(invDp, "numDatabaseAllAppsColumns", cols);
        }
        setIntFieldSilently(dp, "numAllAppsColumns", cols);
        setIntFieldSilently(dp, "numShownAllAppsColumns", cols);
        if (allAppsProfile != null) {
            setIntFieldSilently(allAppsProfile, "numShownAllAppsColumns", cols);
        }
    }

    private static View getAppsView(Activity activity) {
        try {
            Method getAppsViewMethod = findMethod(activity.getClass(), "getAppsView");
            if (getAppsViewMethod == null) return null;
            getAppsViewMethod.setAccessible(true);
            return (View) getAppsViewMethod.invoke(activity);
        } catch (Exception e) {
            return null;
        }
    }

    private static void triggerNativeOnDpChanged(View appsView, Object deviceProfile) {
        try {
            Method onDpChangedMethod = findMethod(appsView.getClass(), "onDeviceProfileChanged", deviceProfile.getClass());
            if (onDpChangedMethod != null) {
                onDpChangedMethod.setAccessible(true);
                onDpChangedMethod.invoke(appsView, deviceProfile);
                Log.d(TAG, "Called native onDeviceProfileChanged success");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger native update", e);
        }
    }

    private static void updateFloatingHeader(View appsView, Object deviceProfile) {
        try {
            Field headerField = findField(appsView.getClass(), "mFloatingHeaderView");
            if (headerField != null) {
                headerField.setAccessible(true);
                View headerView = (View) headerField.get(appsView);
                if (headerView != null) {
                    Method onDpMethod = findMethod(headerView.getClass(), "onDeviceProfileChanged", deviceProfile.getClass());
                    if (onDpMethod != null) {
                        onDpMethod.setAccessible(true);
                        onDpMethod.invoke(headerView, deviceProfile);
                        Log.d(TAG, "Isolated Header update success");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update floating header", e);
        }
    }

    private static void manualForceUpdateList(Activity activity, View appsView, int cols) {
        try {
            updateAlphabeticalAppsList(appsView, cols);

            int listId = activity.getResources().getIdentifier("apps_list_view", "id", activity.getPackageName());
            View recyclerView = (listId != 0) ? appsView.findViewById(listId) : null;

            if (recyclerView != null) {
                Method getLayoutManager = findMethod(recyclerView.getClass(), "getLayoutManager");
                Object layoutManager = (getLayoutManager != null) ? getLayoutManager.invoke(recyclerView) : null;
                if (layoutManager != null) {
                    Method setSpanCount = findMethod(layoutManager.getClass(), "setSpanCount", int.class);
                    if (setSpanCount != null) setSpanCount.invoke(layoutManager, cols);

                    Method getSpanSizeLookup = findMethod(layoutManager.getClass(), "getSpanSizeLookup");
                    Object spanSizeLookup = (getSpanSizeLookup != null) ? getSpanSizeLookup.invoke(layoutManager) : null;
                    
                    if (spanSizeLookup != null) {
                        Method invalidateCache = findMethod(spanSizeLookup.getClass(), "invalidateSpanIndexCache");
                        if (invalidateCache != null) invalidateCache.invoke(spanSizeLookup);
                    }
                }
                Method getAdapter = findMethod(recyclerView.getClass(), "getAdapter");
                Object adapter = (getAdapter != null) ? getAdapter.invoke(recyclerView) : null;
                if (adapter != null) {
                    Method notifyDataSetChanged = findMethod(adapter.getClass(), "notifyDataSetChanged");
                    if (notifyDataSetChanged != null) notifyDataSetChanged.invoke(adapter);
                }
                Method invalidateDecors = findMethod(recyclerView.getClass(), "invalidateItemDecorations");
                if (invalidateDecors != null) invalidateDecors.invoke(recyclerView);
            }
        } catch (Exception e) {
            Log.e(TAG, "Manual list update failed", e);
        }
    }

    private static void updateAlphabeticalAppsList(View appsView, int cols) {
        try {
            Field mAppsField = findField(appsView.getClass(), "mApps");
            if (mAppsField == null) return;
            mAppsField.setAccessible(true);
            Object alphaAppsList = mAppsField.get(appsView);
            if (alphaAppsList == null) return;

            setIntFieldSilently(alphaAppsList, "mNumAppsPerRow", cols);
            
            Method updateItemsMethod = findMethod(alphaAppsList.getClass(), "updateAdapterItems");
            if (updateItemsMethod != null) {
                updateItemsMethod.setAccessible(true);
                updateItemsMethod.invoke(alphaAppsList);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update AlphabeticalAppsList", e);
        }
    }

    private static void fixSearchBar(View appsView) {
        try {
            View searchContainer = null;

            Field mSearchContainerField = findField(appsView.getClass(), "mSearchContainer");
            if (mSearchContainerField != null) {
                mSearchContainerField.setAccessible(true);
                searchContainer = (View) mSearchContainerField.get(appsView);
            }

            if (searchContainer == null) {
                int searchId = appsView.getResources().getIdentifier("search_container_all_apps", "id", appsView.getContext().getPackageName());
                if (searchId != 0) {
                    searchContainer = appsView.findViewById(searchId);
                }
            }

            if (searchContainer != null) {
                searchContainer.setPadding(0, searchContainer.getPaddingTop(), 0, searchContainer.getPaddingBottom());

                ViewGroup.LayoutParams params = searchContainer.getLayoutParams();
                
                if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                }
                
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
                    marginParams.leftMargin = 0;
                    marginParams.rightMargin = 0;
                }
                
                searchContainer.setLayoutParams(params);
                searchContainer.requestLayout();
            }
        } catch (Exception ignored) {
            Log.w(TAG, "Failed to fix search bar layout: " + ignored);
        }
    }

    private static void recalculateCellWidth(Object dp, Object allAppsProfile, int cols) {
        try {
            int widthPx = getIntField(dp, "availableWidthPx");
            if (widthPx <= 0) widthPx = getIntField(dp, "widthPx");
            if (widthPx <= 0) return;

            int totalPadding = 0;
            Object paddingObj = getField(dp, "allAppsPadding");
            if (paddingObj instanceof Rect) {
                Rect r = (Rect) paddingObj;
                totalPadding = r.left + r.right;
            }

            int borderSpaceW = 0;
            Object borderSpaceObj = null;
            if (allAppsProfile != null) borderSpaceObj = getField(allAppsProfile, "borderSpacePx");
            if (borderSpaceObj == null) borderSpaceObj = getField(dp, "allAppsBorderSpacePx");

            if (borderSpaceObj != null) {
                Field xField = findField(borderSpaceObj.getClass(), "x");
                if (xField != null) {
                    xField.setAccessible(true);
                    Object value = xField.get(borderSpaceObj);
                    if (value instanceof Integer) borderSpaceW = (Integer) value;
                    else if (value instanceof Float) borderSpaceW = ((Float) value).intValue();
                }
            }

            int totalSpace = (cols > 1) ? (cols - 1) * borderSpaceW : 0;
            int availableForCells = widthPx - totalPadding - totalSpace;

            if (availableForCells > 0) {
                int newCellWidth = availableForCells / cols;
                setIntFieldSilently(dp, "allAppsCellWidthPx", newCellWidth);
                if (allAppsProfile != null) {
                    setIntFieldSilently(allAppsProfile, "cellWidthPx", newCellWidth);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void applyHeightScale(Object obj, String fieldName, float scale) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    int current = field.getInt(obj);
                    if (current > 0) {
                        field.setInt(obj, Math.round(current * scale));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean isEnabled(Context context, String key) {
        return Settings.Secure.getInt(context.getContentResolver(), key, 0) == 1;
    }

    private static int getInt(Context context, String key, int def) {
        return Settings.Secure.getInt(context.getContentResolver(), key, def);
    }

    private static void setIntFieldSilently(Object obj, String fieldName, int value) {
        if (obj == null) return;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.setInt(obj, value);
            }
        } catch (Exception ignored) {}
    }

    private static void setFloatFieldSilently(Object obj, String fieldName, float value) {
        if (obj == null) return;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.setFloat(obj, value);
            }
        } catch (Exception ignored) {}
    }

    private static int getIntField(Object obj, String fieldName) {
        if (obj == null) return 0;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.getInt(obj);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static Object getField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}