package com.yagay.desktopgridx;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compatibility-only legacy entry for LSPosed 2.2.0-it 7869 HYOS module classification.
 *
 * The HyperOS 4 launcher itself has no ART Java runtime, so this class is not the native
 * hook implementation. Its presence mirrors working modules observed on-device and lets
 * LSPosed classify the APK through both legacy and Modern entry paths. The actual launcher
 * hook remains in the native entries.
 */
public final class LegacyHookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !"com.miui.home".equals(lpparam.packageName)) return;
        try {
            XposedBridge.log("DesktopGridX: DGX_LEGACY_ENTRY package=" + lpparam.packageName
                    + " process=" + lpparam.processName);
        } catch (Throwable ignored) {
            // Compatibility marker only; never make package loading fail.
        }
    }
}
