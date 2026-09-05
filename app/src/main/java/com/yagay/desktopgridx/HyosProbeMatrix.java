package com.yagay.desktopgridx;

final class HyosProbeMatrix {
    private HyosProbeMatrix() {}

    static String collect() {
        StringBuilder out=new StringBuilder("DesktopGridX LSPosed 7869 / HYOS multi-point matrix\n");
        append(out,"=== A. module contract ===",RootShell.run(
                "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; " +
                "echo '-- module.prop --'; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; " +
                "echo '-- native_init.list --'; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null; echo; " +
                "echo '-- scope.list --'; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null; echo; " +
                "echo '-- forbidden legacy/java entries --'; unzip -l \"$APK\" 2>/dev/null | grep -E 'assets/(xposed_init|native_init)|META-INF/xposed/java_init.list' || true; " +
                "echo '-- native libs --'; unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 100",
                20,2*1024*1024));

        append(out,"=== B. LSPosed registration / scope ===",RootShell.run(
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do if [ -f \"$DB\" ]; then echo db=$DB; if command -v sqlite3 >/dev/null 2>&1; then sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; else strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home' | head -n 300; fi; fi; done",
                20,2*1024*1024));

        append(out,"=== C. HYOS launcher identity / mappings ===",RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then echo exe=$(readlink /proc/$P/exe); echo -n cmdline=; cat /proc/$P/cmdline; echo; echo -n selinux=; cat /proc/$P/attr/current 2>/dev/null; echo '-- maps --'; grep -iE 'desktopgridx_hyos|libapp_launcher|zygisk|lsposed|base.apk' /proc/$P/maps 2>/dev/null | head -n 3000; fi",
                20,3*1024*1024));

        append(out,"=== D. native entry / hook state ===",RootShell.run(
                "FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1",
                15,1024*1024));

        append(out,"=== E. LSPosed / HYOS dispatch logs ===",RootShell.run(
                "logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -iE 'DesktopGridX|DGX_NATIVE_ENTRY|libdesktopgridx_hyos|native_init|hyos_spawner|HyperOS Runtime|LSPosed|libapp_launcher' | tail -n 8000 || true",
                25,4*1024*1024));

        append(out,"=== F. linker / SELinux / crash ===",RootShell.run(
                "echo '-- linker --'; logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -iE 'desktopgridx_hyos|cannot locate|needed by|dlopen|linker.*error|namespace' | tail -n 3000 || true; " +
                "echo '-- selinux --'; dmesg 2>/dev/null | grep -iE 'avc:.*denied.*(desktopgridx|miui.home|hyos_spawner)' | tail -n 2000 || true; " +
                "echo '-- crash --'; logcat -d -b crash -v threadtime -t 4000 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home|hyos_spawner' | tail -n 2000 || true",
                25,3*1024*1024));

        return out.toString();
    }

    private static void append(StringBuilder out,String title,RootShell.Result r){out.append('\n').append(title).append('\n');out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut?1:0).append('\n');if(r.output!=null)out.append(r.output);if(out.length()==0||out.charAt(out.length()-1)!='\n')out.append('\n');}
}
