package com.yagay.desktopgridx;

final class LsposedInspector {
    private LsposedInspector() {}

    static String collect() {
        StringBuilder out=new StringBuilder("DesktopGridX LSPosed 2.2.0-it 7869 inspector\n");
        append(out,"=== framework / module database ===",RootShell.run(
                "for P in /data/adb/lspd /data/adb/lsposed; do [ -e \"$P\" ] && { echo --- $P ---; ls -laZ \"$P\" 2>&1; }; done; " +
                "for DB in /data/adb/lspd/config/modules_config.db /data/adb/lsposed/config/modules_config.db; do if [ -f \"$DB\" ]; then echo db=$DB; if command -v sqlite3 >/dev/null 2>&1; then sqlite3 -readonly -header -column \"$DB\" \"SELECT * FROM modules WHERE module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; sqlite3 -readonly -header -column \"$DB\" \"SELECT s.* FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name='com.yagay.desktopgridx';\" 2>&1 || true; else strings \"$DB\" 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home' | head -n 300; fi; fi; done",
                20,2*1024*1024));

        append(out,"=== framework / HYOS processes ===",RootShell.run(
                "ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | grep -iE 'lspd|lsposed|zygisk|hyos_spawner' | head -n 400 || true; getprop | grep -iE 'lsposed|lspd|zygisk' | head -n 200 || true",
                15,1024*1024));

        append(out,"=== module contract / launcher mapping ===",RootShell.run(
                "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null; echo; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null; echo; P=$(pidof com.miui.home | awk '{print $1}'); echo launcher_pid=$P; if [ -n \"$P\" ]; then echo exe=$(readlink /proc/$P/exe); echo -n cmdline=; cat /proc/$P/cmdline; echo; grep -iE 'desktopgridx_hyos|libapp_launcher|zygisk|lsposed|base.apk' /proc/$P/maps 2>/dev/null | head -n 3000; fi",
                20,3*1024*1024));

        append(out,"=== native runtime ===",RootShell.run(
                "FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1",
                10,512*1024));

        append(out,"=== framework log focus ===",RootShell.run(
                "for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do [ -d \"$D\" ] || continue; find \"$D\" -maxdepth 1 -type f 2>/dev/null | sort | tail -n 50 | while read F; do grep -aH -n -iE 'com\\.yagay\\.desktopgridx|libdesktopgridx_hyos|DGX_NATIVE_ENTRY|native_init|com\\.miui\\.home|hyos_spawner|HyperOS Runtime' \"$F\" 2>/dev/null | tail -n 1500 || true; done; done",
                25,4*1024*1024));

        append(out,"=== known-working module comparison ===",RootShell.run(
                "P=$(pidof com.miui.home | awk '{print $1}'); for PKG in com.chasers7ar.docksink com.kiminonawa.HyperLight com.fuck.HyperOSTheme; do APK=$(pm path \"$PKG\" 2>/dev/null | head -n1 | cut -d: -f2); [ -n \"$APK\" ] || continue; echo ===== $PKG =====; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; echo native=$(unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null | tr '\\n' ','); echo java=$(unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null | tr '\\n' ','); unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null | head -n 120; if [ -n \"$P\" ]; then grep -F \"$APK\" /proc/$P/maps 2>/dev/null | head -n 50 || true; fi; done",
                25,4*1024*1024));

        return sanitize(out.toString());
    }

    private static void append(StringBuilder out,String title,RootShell.Result r){out.append('\n').append(title).append('\n');out.append("exit=").append(r.exitCode).append(" timeout=").append(r.timedOut?1:0).append('\n');if(r.output!=null)out.append(r.output);if(out.length()==0||out.charAt(out.length()-1)!='\n')out.append('\n');}
    private static String sanitize(String raw){if(raw==null)return "";String s=raw;s=s.replaceAll("(?i)(token|password|passwd|secret|authorization|cookie)=\\S+","$1=<redacted>");s=s.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*","$1<redacted>");return s;}
}
