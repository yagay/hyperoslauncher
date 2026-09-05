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
        log(Log.INFO, TAG, "DGX_ENTRY_MODULE_LOADED process=" + process + " systemServer=" + param.isSystemServer());
        JavaRuntimeStatus.mark("stage", "onModuleLoaded");
        JavaRuntimeStatus.mark("process", process);
        JavaRuntimeStatus.mark("system_server", param.isSystemServer() ? 1 : 0);
        JavaRuntimeStatus.mark("java_entry", 1);

        if (!param.isSystemServer() && "com.miui.home".equals(process)) {
            attemptedEarly = true;
            JavaRuntimeStatus.mark("target_process_match", 1);
            installNative("onModuleLoaded");
        } else {
            JavaRuntimeStatus.mark("target_process_match", 0);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        log(Log.INFO, TAG, "DGX_ENTRY_PACKAGE_READY package=" + param.getPackageName() + " first=" + param.isFirstPackage());
        JavaRuntimeStatus.mark("package_ready_seen", 1);
        JavaRuntimeStatus.mark("package_name", param.getPackageName());
        JavaRuntimeStatus.mark("package_first", param.isFirstPackage() ? 1 : 0);
        if (!param.isFirstPackage() || !"com.miui.home".equals(param.getPackageName()) || installed) return;
        log(Log.INFO, TAG, "package ready; earlyAttempt=" + attemptedEarly + ", retrying native install");
        installNative("onPackageReady");
    }

    private synchronized void installNative(String stage) {
        if (installed) return;
        JavaRuntimeStatus.mark("stage", stage + ":before_loadLibrary");
        JavaRuntimeStatus.mark("native_load_attempted", 1);
        log(Log.INFO, TAG, "DGX_BEFORE_LOAD_LIBRARY stage=" + stage);
        try {
            System.loadLibrary("desktopgridx");
            JavaRuntimeStatus.mark("native_library_loaded", 1);
            JavaRuntimeStatus.mark("stage", stage + ":after_loadLibrary");
            log(Log.INFO, TAG, "DGX_AFTER_LOAD_LIBRARY stage=" + stage);

            JavaRuntimeStatus.mark("native_install_attempted", 1);
            JavaRuntimeStatus.mark("stage", stage + ":before_native_install");
            log(Log.INFO, TAG, "DGX_BEFORE_NATIVE_INSTALL stage=" + stage);
            int result = NativeBridge.install();
            JavaRuntimeStatus.mark("native_install_result", result);
            JavaRuntimeStatus.mark("stage", stage + ":after_native_install");
            log(Log.INFO, TAG, "DGX_AFTER_NATIVE_INSTALL stage=" + stage + " result=" + result);

            installed = result >= 0;
            JavaRuntimeStatus.mark("installed", installed ? 1 : 0);
            log(result >= 0 ? Log.INFO : Log.ERROR, TAG,
                    "native install stage=" + stage + " result=" + result + " installed=" + installed);
        } catch (Throwable t) {
            installed = false;
            JavaRuntimeStatus.mark("installed", 0);
            JavaRuntimeStatus.markError(stage + ":native_failure", t);
            log(Log.ERROR, TAG, "DGX_NATIVE_FAILURE stage=" + stage + ": " + Log.getStackTraceString(t));
        }
    }
}
