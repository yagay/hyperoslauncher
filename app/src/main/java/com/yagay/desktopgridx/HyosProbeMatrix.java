package com.yagay.desktopgridx;

final class HyosProbeMatrix {
    private HyosProbeMatrix() {}

    static String collect() {
        StringBuilder out=new StringBuilder("DesktopGridX LSPosed 7869 / HYOS staged execution matrix\n");
        append(out,"=== A. module contract ===",RootShell.run(
                "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; " +
                "echo '-- module.prop --'; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; " +
                "echo '-- java_init.list --'; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null; echo; " +
                "echo '-- native_init.list --'; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null; echo; " +
                "echo '-- scope.list --'; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null; echo; " +
                "echo '-- forbidden legacy entries --'; unzip -l \"$APK\" 2>/dev/null | grep -E 'assets/(xposed_init|native_init)' || true; " +
                "echo '-- native libs --'; unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 100",
                20,2*1024*1024));

        append(out,"=== B. LSPosed registration / scope ===",RootShell.run(
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do if [ -f \"$DB\" ]; then echo db=$DB; if command -v sqlite3 >/dev/null 2>&1; then sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; else strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home' | head -n 300; fi; fi; done; " +
                "echo '-- parsed LSPosed scope cache --'; for F in /data/adb/lspd/log/scopes.txt /data/adb/lsposed/log/scopes.txt /data/adb/lspd/logs/scopes.txt /data/adb/lsposed/logs/scopes.txt; do [ -f \"$F\" ] && grep -n -A20 -B3 -E 'com\\.miui\\.home|com\\.yagay\\.desktopgridx|libdesktopgridx_hyos' \"$F\" | tail -n 300; done",
                20,2*1024*1024));

        append(out,"=== C. HYOS launcher identity / APK-backed ELF mappings ===",RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo module_apk=$APK; if [ -n \"$P\" ]; then echo exe=$(readlink /proc/$P/exe); echo -n cmdline=; cat /proc/$P/cmdline; echo; echo -n selinux=; cat /proc/$P/attr/current 2>/dev/null; echo '-- exact module APK maps --'; grep -F \"$APK\" /proc/$P/maps 2>/dev/null || true; echo '-- executable APK maps --'; grep -F \"$APK\" /proc/$P/maps 2>/dev/null | grep 'r-xp' || true; echo '-- launcher / framework maps --'; grep -iE 'libapp_launcher|zygisk|lsposed' /proc/$P/maps 2>/dev/null | head -n 3000; fi",
                20,3*1024*1024));

        append(out,"=== D. three-stage execution evidence ===",RootShell.run(
                "LOG=$(logcat -d -b all -v threadtime -t 50000 2>/dev/null); " +
                "echo constructor_seen=$(printf '%s\\n' \"$LOG\" | grep -c 'DGX_HYOS_CTOR' || true); " +
                "echo modern_entry_seen=$(printf '%s\\n' \"$LOG\" | grep -c 'DGX_MODERN_ENTRY' || true); " +
                "echo native_entry_seen=$(printf '%s\\n' \"$LOG\" | grep -c 'DGX_NATIVE_ENTRY' || true); " +
                "echo launcher_callback_seen=$(printf '%s\\n' \"$LOG\" | grep -c 'libapp_launcher callback install' || true); " +
                "echo dock_sink_callback_seen=$(printf '%s\\n' \"$LOG\" | grep -c 'DockSink: on_library_loaded' || true); " +
                "echo '-- DesktopGridX stages --'; printf '%s\\n' \"$LOG\" | grep -E 'DGX_HYOS_CTOR|DGX_MODERN_ENTRY|DGX_NATIVE_ENTRY|libapp_launcher callback install|LSPosed hook OK|LSPosed hook failed' | tail -n 3000 || true; " +
                "echo '-- DockSink successful native callback control --'; printf '%s\\n' \"$LOG\" | grep 'DockSink: on_library_loaded' | tail -n 300 || true",
                30,5*1024*1024));

        append(out,"=== E. native runtime / hook state ===",RootShell.run(
                "FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1",
                15,1024*1024));

        append(out,"=== F. LSPosed / HYOS dispatch timeline ===",RootShell.run(
                "logcat -d -b all -v threadtime -t 50000 2>/dev/null | grep -iE 'DesktopGridX|DockSink|native_api:|Loading module native library|libdesktopgridx_hyos|native_init|hyos_spawner|HyperOS Runtime|HyperOS Rust Runtime|LSPosed|libapp_launcher' | tail -n 12000 || true",
                30,6*1024*1024));

        append(out,"=== G. linker / SELinux / crash ===",RootShell.run(
                "echo '-- linker --'; logcat -d -b all -v threadtime -t 50000 2>/dev/null | grep -iE 'desktopgridx_hyos|cannot locate|needed by|dlopen|linker.*error|namespace' | tail -n 3000 || true; " +
                "echo '-- selinux --'; dmesg 2>/dev/null | grep -iE 'avc:.*denied.*(desktopgridx|miui.home|hyos_spawner)' | tail -n 2000 || true; " +
                "echo '-- crash --'; logcat -d -b crash -v threadtime -t 6000 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home|hyos_spawner' | tail -n 2000 || true",
                25,3*1024*1024));

        return out.toString();
    }

    private static void append(StringBuilder out,String title,RootShell.Result r){out.append('\n').append(title).append('\n');out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut?1:0).append('\n');if(r.output!=null)out.append(r.output);if(out.length()==0||out.charAt(out.length()-1)!='\n')out.append('\n');}
}
