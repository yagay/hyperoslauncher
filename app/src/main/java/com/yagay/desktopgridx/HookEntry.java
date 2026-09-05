package com.yagay.desktopgridx;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class HookEntry extends XposedModule {
    private static final String TAG = "DesktopGridX";
    private static volatile boolean installed;
    private static volatile boolean attemptedEarly;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        final String process = param.getProcessName();
        log(Log.INFO, TAG, "module loaded, process=" + process);
        // Native-only interception does not need the app ClassLoader. Installing here gives us the
        // earliest lifecycle point in API 102 and maximizes the chance that ShadowHook can observe
        // libapp_launcher.so before its .init/.init_array executes.
        if (!param.isSystemServer() && "com.miui.home".equals(process)) {
            attemptedEarly = true;
            installNative("onModuleLoaded");
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || !"com.miui.home".equals(param.getPackageName()) || installed) return;
        // Compensation path: native library loading can fail unusually early on some ROM/module
        // environments. Retry once the package ClassLoader is ready instead of permanently disabling.
        log(Log.INFO, TAG, "package ready; earlyAttempt=" + attemptedEarly + ", retrying native install");
        installNative("onPackageReady");
    }

    private synchronized void installNative(String stage) {
        if (installed) return;
        try {
            System.loadLibrary("desktopgridx");
            int result = NativeBridge.install();
            // 0 = no overrides, 1 = installed, 2 = waiting for target SO. All are successful setup.
            installed = result >= 0;
            log(result >= 0 ? Log.INFO : Log.ERROR, TAG,
                    "native install stage=" + stage + " result=" + result + " installed=" + installed);
        } catch (Throwable t) {
            installed = false;
            log(Log.ERROR, TAG, "native install failed stage=" + stage + ": " + Log.getStackTraceString(t));
        }
    }
}
