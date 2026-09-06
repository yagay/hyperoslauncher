package com.yagay.desktopgridx;

final class HyosProbeMatrix {
    private HyosProbeMatrix() {}

    static String collect() {
        StringBuilder out=new StringBuilder("DesktopGridX LSPosed 7869 / HYOS STL-free execution matrix\n");
        append(out,"=== A. module contract ===",RootShell.run(
                "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; " +
                "echo '-- module.prop --'; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; " +
                "echo '-- java_init.list --'; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null; echo; " +
                "echo '-- native_init.list --'; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null; echo; " +
                "echo '-- forbidden legacy entries --'; unzip -l \"$APK\" 2>/dev/null | grep -E 'assets/xposed_init|assets/native_init' || true; " +
                "echo '-- scope.list --'; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null; echo; " +
                "echo '-- native libs --'; unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 100",
                20,2*1024*1024));

        append(out,"=== B. LSPosed registration / scope ===",RootShell.run(
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do if [ -f \"$DB\" ]; then echo db=$DB; if command -v sqlite3 >/dev/null 2>&1; then sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; else strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home' | head -n 300; fi; fi; done; " +
                "echo '-- parsed LSPosed scope cache --'; for F in /data/adb/lspd/log/scopes.txt /data/adb/lsposed/log/scopes.txt /data/adb/lspd/logs/scopes.txt /data/adb/lsposed/logs/scopes.txt; do [ -f \"$F\" ] && grep -n -A30 -B3 -E 'com\\.miui\\.home|com\\.yagay\\.desktopgridx|HookEntry|libdesktopgridx_hyos' \"$F\" | tail -n 500; done",
                20,3*1024*1024));

        append(out,"=== C. HYOS launcher identity / APK-backed ELF mappings ===",RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo module_apk=$APK; if [ -n \"$P\" ]; then echo exe=$(readlink /proc/$P/exe); echo -n cmdline=; cat /proc/$P/cmdline; echo; echo -n selinux=; cat /proc/$P/attr/current 2>/dev/null; echo '-- exact module APK maps --'; grep -F \"$APK\" /proc/$P/maps 2>/dev/null || true; echo '-- executable current APK maps --'; grep -F \"$APK\" /proc/$P/maps 2>/dev/null | grep 'r-xp' || true; echo '-- stale deleted DesktopGridX maps --'; { grep -i 'desktopgridx' /proc/$P/maps 2>/dev/null || true; grep -F 'base.apk (deleted)' /proc/$P/maps 2>/dev/null || true; } | tail -n 300; echo '-- launcher / framework maps --'; grep -iE 'libapp_launcher|zygisk|lsposed' /proc/$P/maps 2>/dev/null | head -n 3000; fi",
                20,3*1024*1024));

        append(out,"=== D. staged execution evidence ===",RootShell.run(
                "echo constructor_seen=$(logcat -d -b all -v brief -t 60000 2>/dev/null | grep -c 'DGX_HYOS_CTOR' || true); " +
                "echo native_entry_seen=$(logcat -d -b all -v brief -t 60000 2>/dev/null | grep -c 'DGX_NATIVE_ENTRY' || true); " +
                "echo launcher_callback_seen=$(logcat -d -b all -v brief -t 60000 2>/dev/null | grep -c 'libapp_launcher callback received' || true); " +
                "echo hook_success_seen=$(logcat -d -b all -v brief -t 60000 2>/dev/null | grep -c 'LSPosed hook OK' || true); " +
                "echo dock_sink_callback_seen=$(logcat -d -b all -v brief -t 60000 2>/dev/null | grep -c 'DockSink: on_library_loaded' || true); " +
                "echo '-- DesktopGridX stages --'; logcat -d -b all -v threadtime -t 60000 2>/dev/null | grep -E 'DGX_HYOS_CTOR|DGX_NATIVE_ENTRY|libapp_launcher callback received|libapp_launcher install=|LSPosed hook OK|LSPosed hook failed' | tail -n 4000 || true; " +
                "echo '-- DockSink successful native callback control --'; logcat -d -b all -v threadtime -t 60000 2>/dev/null | grep 'DockSink: on_library_loaded' | tail -n 300 || true",
                35,6*1024*1024));

        append(out,"=== E. native runtime / hook state ===",RootShell.run(
                "FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1",
                15,1024*1024));

        append(out,"=== F. LSPosed / HYOS dispatch timeline ===",RootShell.run(
                "logcat -d -b all -v threadtime -t 60000 2>/dev/null | grep -iE 'DesktopGridX|DockSink|native_api:|Loading module native library|libdesktopgridx_hyos|native_init|hyos_spawner|HyperOS Runtime|HyperOS Rust Runtime|LSPosed|libapp_launcher' | tail -n 14000 || true",
                35,7*1024*1024));

        append(out,"=== G. linker / SELinux / crash ===",RootShell.run(
                "echo '-- linker --'; logcat -d -b all -v threadtime -t 60000 2>/dev/null | grep -iE 'desktopgridx_hyos|cannot locate|needed by|dlopen|linker.*error|namespace|Broken pipe' | tail -n 4000 || true; " +
                "echo '-- selinux --'; dmesg 2>/dev/null | grep -iE 'avc:.*denied.*(desktopgridx|miui.home|hyos_spawner)' | tail -n 2000 || true; " +
                "echo '-- crash --'; logcat -d -b crash -v threadtime -t 8000 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home|hyos_spawner' | tail -n 3000 || true",
                25,4*1024*1024));

        return out.toString();
    }

    private static void append(StringBuilder out,String title,RootShell.Result r){out.append('\n').append(title).append('\n');out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut?1:0).append('\n');if(r.output!=null)out.append(r.output);if(out.length()==0||out.charAt(out.length()-1)!='\n')out.append('\n');}
}
