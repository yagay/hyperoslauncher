package com.yagay.desktopgridx;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compatibility-only legacy registration entry for LSPosed IT 7869 HYOS.
 *
 * This class intentionally installs no Java hooks and never loads native code.
 * Its only purpose is to mirror the dual Modern+legacy registration shape used
 * by native modules that are known to dispatch correctly in the same Launcher.
 */
public final class LegacyHookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !"com.miui.home".equals(lpparam.packageName)) return;
        try {
            XposedBridge.log("DesktopGridX: DGX_LEGACY_ENTRY package=" + lpparam.packageName
                    + " process=" + lpparam.processName);
        } catch (Throwable ignored) {
            // Compatibility marker only; never let this path affect Launcher startup.
        }
    }
}
