package com.yagay.desktopgridx;

final class LsposedInspector {
    private static final String MODULE = "com.yagay.desktopgridx";
    private static final String TARGET = "com.miui.home";

    private LsposedInspector() {}

    static String collect() {
        StringBuilder out = new StringBuilder();
        out.append("DesktopGridX LSPosed/HYOS state inspector\n");
        out.append("module=").append(MODULE).append('\n');
        out.append("target=").append(TARGET).append('\n');

        append(out, "=== framework paths ===", RootShell.run(
                "for P in /data/adb/lspd /data/adb/lsposed; do if [ -e \"$P\" ]; then echo \"--- $P ---\"; ls -laZ \"$P\" 2>&1; find \"$P\" -maxdepth 3 -type f \\( -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' -o -name 'cli' \\) -print 2>/dev/null; fi; done",
                15, 1024 * 1024));

        append(out, "=== LSPosed CLI ===", RootShell.run(
                "for C in /data/adb/lspd/bin/cli /data/adb/lsposed/bin/cli; do if [ -x \"$C\" ]; then echo cli=$C; echo '-- status --'; \"$C\" status 2>&1 || true; echo '-- modules --'; \"$C\" modules ls 2>&1 | grep -iE 'desktopgridx|com\\.yagay\\.desktopgridx' || true; echo '-- scope --'; \"$C\" scope ls com.yagay.desktopgridx 2>&1 || true; fi; done",
                15, 1024 * 1024));

        append(out, "=== modules_config.db ===", RootShell.run(
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do " +
                "if [ -f \"$DB\" ]; then echo db=$DB; ls -lZ \"$DB\"; " +
                "if command -v sqlite3 >/dev/null 2>&1; then " +
                "echo '-- tables --'; sqlite3 -readonly \"$DB\" '.tables' 2>&1 || true; " +
                "echo '-- modules schema --'; sqlite3 -readonly \"$DB\" 'PRAGMA table_info(modules);' 2>&1 || true; " +
                "echo '-- scope schema --'; sqlite3 -readonly \"$DB\" 'PRAGMA table_info(scope);' 2>&1 || true; " +
                "echo '-- module row --'; sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; " +
                "echo '-- target scope rows --'; sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; " +
                "echo '-- target reverse lookup --'; sqlite3 -readonly -header -column \"$DB\" \"SELECT m.module_pkg_name,m.enabled,s.app_pkg_name,s.user_id FROM modules m LEFT JOIN scope s ON m.mid=s.mid WHERE m.module_pkg_name='com.yagay.desktopgridx' OR s.app_pkg_name='com.miui.home';\" 2>&1 | grep -iE 'desktopgridx|com\\.miui\\.home|module_pkg_name|enabled|user_id' || true; " +
                "else echo sqlite3_missing=1; strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.yagay\\.desktopgridx|com\\.miui\\.home' | head -n 300 || true; fi; fi; done",
                20, 2 * 1024 * 1024));

        append(out, "=== framework process/status ===", RootShell.run(
                "echo '-- processes --'; ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | grep -iE 'lspd|lsposed|zygisk|hyos_spawner' | head -n 300 || true; " +
                "echo '-- props --'; getprop | grep -iE 'lsposed|lspd|zygisk' | head -n 200 || true; " +
                "echo '-- modules directory --'; for D in /data/adb/lspd/modules /data/adb/lsposed/modules; do if [ -d \"$D\" ]; then find \"$D\" -maxdepth 4 -type d -o -type f 2>/dev/null | grep -i 'desktopgridx' | head -n 300; fi; done",
                15, 2 * 1024 * 1024));

        append(out, "=== package/runtime cross-check ===", RootShell.run(
                "echo '-- DesktopGridX package --'; dumpsys package com.yagay.desktopgridx 2>/dev/null | grep -E 'versionName=|versionCode=|lastUpdateTime=|firstInstallTime=|User 0:|enabled=' | head -n 100; " +
                "echo '-- Launcher package --'; dumpsys package com.miui.home 2>/dev/null | grep -E 'versionName=|versionCode=|lastUpdateTime=|User 0:|enabled=' | head -n 100; " +
                "echo '-- Launcher maps markers --'; P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then grep -iE 'zygisk_lsposed|desktopgridx|libdesktopgridx|shadowhook|libapp_launcher|hyos_spawner' /proc/$P/maps 2>/dev/null || true; fi",
                15, 2 * 1024 * 1024));

        append(out, "=== HYOS native constructor probe ===", RootShell.run(
                "FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-hyos-probe.conf /data/user/0/com.miui.home/cache/desktopgridx-hyos-probe.conf /data/data/com.miui.home/cache/desktopgridx-hyos-probe.conf; do if [ -f \"$F\" ]; then echo path=$F; ls -lZ \"$F\"; cat \"$F\"; FOUND=1; break; fi; done; [ -n \"$FOUND\" ] || echo hyos_probe_missing=1",
                10, 512 * 1024));

        append(out, "=== HYOS loader process evidence ===", RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); echo launcher_pid=$P; " +
                "if [ -n \"$P\" ]; then " +
                "echo '-- exe/cmdline/context --'; readlink /proc/$P/exe; cat /proc/$P/cmdline; echo; cat /proc/$P/attr/current 2>/dev/null; " +
                "echo '-- fd references --'; for F in /proc/$P/fd/*; do L=$(readlink \"$F\" 2>/dev/null || true); echo \"$L\" | grep -iE 'desktopgridx|com.yagay.desktopgridx|lspd|lsposed|libapp_launcher|base.apk' >/dev/null && echo \"$F -> $L\"; done | head -n 2000; " +
                "echo '-- map references --'; grep -iE 'desktopgridx|com.yagay.desktopgridx|lspd|lsposed|zygisk|libapp_launcher|base.apk' /proc/$P/maps 2>/dev/null | head -n 4000; " +
                "echo '-- task count --'; ls /proc/$P/task 2>/dev/null | wc -l; " +
                "fi; " +
                "echo '-- hyos spawner processes --'; for P in $(pidof hyos_spawner 2>/dev/null); do echo pid=$P; readlink /proc/$P/exe; cat /proc/$P/cmdline 2>/dev/null; echo; grep -iE 'zygisk|lsposed|desktopgridx' /proc/$P/maps 2>/dev/null | head -n 1000; done",
                20, 4 * 1024 * 1024));

        append(out, "=== HYOS loader log markers ===", RootShell.run(
                "logcat -d -b all -v threadtime -t 20000 2>/dev/null | grep -iE 'DGX_HYOS_PROBE|DGX_NATIVE_ENTRY|DesktopGridX|libdesktopgridx|hyos_spawner|HyperOS Rust Runtime|HyperOS Runtime|onAppSpecialized|native_init|dlopen|linker.*desktopgridx' | tail -n 6000 || true",
                20, 4 * 1024 * 1024));

        append(out, "=== focused framework logs ===", RootShell.run(
                "for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do " +
                "if [ -d \"$D\" ]; then find \"$D\" -maxdepth 1 -type f \\( -name 'modules*.log' -o -name 'verbose*.log' -o -name 'logcat*.log' \\) 2>/dev/null | sort | tail -n 40 | while read F; do " +
                "grep -H -n -iE 'desktopgridx|com\\.yagay\\.desktopgridx|com\\.miui\\.home|native_init|HookEntry|libdesktopgridx|UnsatisfiedLinkError|ClassNotFound|scope|module loaded|hyos_spawner|HyperOS Runtime' \"$F\" 2>/dev/null | tail -n 1200 || true; done; fi; done",
                20, 4 * 1024 * 1024));

        return sanitize(out.toString());
    }

    private static void append(StringBuilder out, String title, RootShell.Result r) {
        out.append('\n').append(title).append('\n');
        out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut ? 1 : 0).append('\n');
        out.append(r.output == null ? "" : r.output);
        if (!out.toString().endsWith("\n")) out.append('\n');
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("(?i)(token|password|passwd|secret|authorization|cookie)=\\S+", "$1=<redacted>");
        s = s.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1<redacted>");
        s = s.replaceAll("(?i)(Authorization:\\s*)\\S+", "$1<redacted>");
        return s;
    }
}
