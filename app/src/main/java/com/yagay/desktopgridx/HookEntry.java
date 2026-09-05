package com.yagay.desktopgridx;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;

/**
 * Minimal Modern API 102 registration entry for LSPosed 2.2.0-it 7869.
 *
 * This class intentionally performs no Java hook and never calls System.loadLibrary().
 * Its only job is to let LSPosed complete the Modern module load path so native_init.list
 * entries are registered through NativeAPI::recordNativeEntrypoint. Actual Launcher hooks
 * are installed by libdesktopgridx_hyos.so through LSPosed's native hook_func/unhook_func.
 */
public final class HookEntry extends XposedModule {
    public HookEntry(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        log("DGX_MODERN_ENTRY process=" + param.getProcessName() + " systemServer=" + param.isSystemServer());
    }
}
