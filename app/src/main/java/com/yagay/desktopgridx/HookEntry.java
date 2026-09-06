package com.yagay.desktopgridx;

import io.github.libxposed.api.XposedModule;

/**
 * Metadata-only Modern API 102 entry.
 * HyperOS 4 MiuiHome runs as a HYOS native child, so all active hooks live in
 * libdesktopgridx_hyos.so declared by META-INF/xposed/native_init.list.
 */
public final class HookEntry extends XposedModule {
    public HookEntry() {
        super();
    }
}
