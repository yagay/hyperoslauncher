package com.yagay.desktopgridx;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;

/**
 * Minimal Modern API 102 registration entry for LSPosed 2.2.0-it 7869.
 *
 * This class intentionally performs no Java hook and never calls System.loadLibrary().
 * Its only job is to let LSPosed complete the Modern module loading path so
 * META-INF/xposed/native_init.list is registered. Actual Launcher hooks live entirely in
 * libdesktopgridx_hyos.so and use LSPosed's native hook_func/unhook_func backend.
 */
public final class HookEntry extends XposedModule {
    private static final String TAG = "DesktopGridX";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "DGX_MODERN_ENTRY process=" + param.getProcessName()
                        + " systemServer=" + param.isSystemServer());
    }
}
