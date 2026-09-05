package com.yagay.desktopgridx;

final class HyosProbeMatrix {
    private HyosProbeMatrix() {}

    static String collect() {
        StringBuilder out = new StringBuilder();
        out.append("DesktopGridX HYOS multi-point probe matrix\n");

        append(out, "=== A. package / entry metadata ===", RootShell.run(
                "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; " +
                "echo '-- package flags --'; dumpsys package com.yagay.desktopgridx 2>/dev/null | grep -E 'versionName=|versionCode=|codePath=|primaryCpuAbi=|secondaryCpuAbi=|extractNativeLibs|nativeLibraryDir=' || true; " +
                "echo '-- modern entries --'; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null || true; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null || true; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null || true; " +
                "echo '-- legacy entries --'; unzip -p \"$APK\" assets/xposed_init 2>/dev/null || true; unzip -p \"$APK\" assets/native_init 2>/dev/null || true; " +
                "echo '-- native entries --'; unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 200 || true",
                20, 2 * 1024 * 1024));

        append(out, "=== B. extracted native files / dependencies ===", RootShell.run(
                "echo '-- extracted libs --'; for D in /data/app/*/*desktopgridx*/lib/arm64 /data/app/*/*desktopgridx*/lib/arm64-v8a; do [ -d \"$D\" ] && { echo dir=$D; ls -laZ \"$D\"; }; done; " +
                "echo '-- dependency strings --'; for F in /data/app/*/*desktopgridx*/lib/arm64/libdesktopgridx.so /data/app/*/*desktopgridx*/lib/arm64-v8a/libdesktopgridx.so; do [ -f \"$F\" ] && { echo file=$F; strings \"$F\" 2>/dev/null | grep -E '^lib[^ ]+\\.so$' | sort -u | head -n 200; }; done",
                20, 2 * 1024 * 1024));

        append(out, "=== C. LSPosed registration / scope ===", RootShell.run(
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do " +
                "if [ -f \"$DB\" ]; then echo db=$DB; if command -v sqlite3 >/dev/null 2>&1; then " +
                "sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; " +
                "sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; " +
                "else strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home' | head -n 300; fi; fi; done",
                20, 2 * 1024 * 1024));

        append(out, "=== D. launcher identity / mappings / fds ===", RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then " +
                "echo '-- identity --'; readlink /proc/$P/exe; cat /proc/$P/cmdline; echo; cat /proc/$P/attr/current 2>/dev/null; " +
                "echo '-- maps --'; grep -iE 'desktopgridx|libdesktopgridx|shadowhook|libapp_launcher|zygisk|lsposed|base.apk' /proc/$P/maps 2>/dev/null | head -n 4000; " +
                "echo '-- fds --'; for F in /proc/$P/fd/*; do L=$(readlink \"$F\" 2>/dev/null || true); echo \"$L\" | grep -iE 'desktopgridx|libdesktopgridx|base.apk|lsposed|zygisk' >/dev/null && echo \"$F -> $L\"; done | head -n 2000; fi",
                20, 4 * 1024 * 1024));

        append(out, "=== E. constructor / native_init / hook runtime ===", RootShell.run(
                "echo '-- constructor probe --'; FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-hyos-probe.conf /data/user/0/com.miui.home/cache/desktopgridx-hyos-probe.conf /data/data/com.miui.home/cache/desktopgridx-hyos-probe.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo hyos_probe_missing=1; " +
                "echo '-- native runtime --'; FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1; " +
                "echo '-- java runtime --'; FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo java_status_missing=1",
                15, 2 * 1024 * 1024));

        append(out, "=== F. linker / dlopen / namespace evidence ===", RootShell.run(
                "echo '-- linker logs --'; logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -iE 'desktopgridx|libdesktopgridx|dlopen|cannot locate|needed by|namespace|linker.*error|UnsatisfiedLinkError' | tail -n 6000 || true; " +
                "echo '-- linker config --'; for F in /linkerconfig/ld.config.txt /linkerconfig/*/ld.config.txt; do [ -f \"$F\" ] && { echo --- $F ---; grep -iE 'namespace|search.paths|permitted.paths|data/app' \"$F\" 2>/dev/null | head -n 1200; }; done",
                25, 4 * 1024 * 1024));

        append(out, "=== G. HYOS / LSPosed dispatch logs ===", RootShell.run(
                "logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -iE 'hyos_spawner|HyperOS Rust Runtime|HyperOS Runtime|onAppSpecialized|DGX_HYOS_PROBE|DGX_NATIVE_ENTRY|DesktopGridX|libdesktopgridx|module loaded|scope|native_init' | tail -n 8000 || true",
                25, 4 * 1024 * 1024));

        append(out, "=== H. crash / SELinux ===", RootShell.run(
                "echo '-- crash --'; logcat -d -b crash -v threadtime -t 5000 2>/dev/null | grep -iE 'desktopgridx|libdesktopgridx|com\\.miui\\.home|hyos_spawner|linker' | tail -n 3000 || true; " +
                "echo '-- selinux --'; dmesg 2>/dev/null | grep -iE 'avc:.*denied.*(desktopgridx|miui.home|hyos_spawner|linker)|desktopgridx' | tail -n 3000 || true",
                20, 3 * 1024 * 1024));

        append(out, "=== I. working-module comparison ===", RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); for PKG in com.chasers7ar.docksink com.kiminonawa.HyperLight com.fuck.HyperOSTheme; do " +
                "APK=$(pm path \"$PKG\" 2>/dev/null | head -n1 | cut -d: -f2); [ -n \"$APK\" ] || continue; echo ===== $PKG =====; echo apk=$APK; " +
                "dumpsys package \"$PKG\" 2>/dev/null | grep -E 'versionName=|versionCode=|extractNativeLibs|primaryCpuAbi=|codePath=' | head -n 80; " +
                "echo '-- entries --'; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null || true; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null || true; unzip -p \"$APK\" assets/native_init 2>/dev/null || true; unzip -p \"$APK\" assets/xposed_init 2>/dev/null || true; " +
                "echo '-- libs --'; unzip -l \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 200 || true; " +
                "if [ -n \"$P\" ]; then echo '-- mapped --'; grep -F \"$APK\" /proc/$P/maps 2>/dev/null | head -n 50 || true; fi; done",
                30, 4 * 1024 * 1024));

        return out.toString();
    }

    private static void append(StringBuilder out, String title, RootShell.Result r) {
        out.append('\n').append(title).append('\n');
        out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut ? 1 : 0).append('\n');
        if (r.output != null) out.append(r.output);
        if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') out.append('\n');
    }
}
