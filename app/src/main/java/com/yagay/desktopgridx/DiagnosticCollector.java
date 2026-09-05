package com.yagay.desktopgridx;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class DiagnosticCollector {
    interface Callback { void done(String message); }
    private static final long CMD_LIMIT = 12L * 1024L * 1024L;

    private DiagnosticCollector() {}

    static void export(Context context, Callback callback) {
        new Thread(() -> {
            String result;
            File dir = null;
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                dir = new File(context.getCacheDir(), "diagnostic-" + stamp);
                if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create diagnostic dir");

                write(new File(dir, "00-summary.txt"), buildSummary(context));
                write(new File(dir, "01-root-device.txt"), root(
                        "echo '=== id ==='; id; " +
                        "echo '=== su ==='; (su -v 2>&1 || true); " +
                        "echo '=== kernel ==='; uname -a; " +
                        "echo '=== selinux ==='; getenforce 2>/dev/null; cat /sys/fs/selinux/enforce 2>/dev/null; " +
                        "echo '=== root managers ==='; (magisk -v 2>&1 || true); (ksud --version 2>&1 || true); " +
                        "echo '=== getprop ==='; getprop"));

                write(new File(dir, "02-config.txt"), root(
                        "echo '=== DesktopGridX config ==='; cat /data/adb/desktopgridx/config.conf 2>&1; echo '=== resolved hook points ==='; cat /data/adb/desktopgridx/resolved.conf 2>&1; " +
                        "echo '=== module dir ==='; ls -laZ /data/adb/desktopgridx 2>&1; " +
                        "echo '=== LSPosed related modules ==='; ls -la /data/adb/modules 2>/dev/null | grep -iE 'xposed|lsposed|desktop|grid|zygisk' || true"));

                String paths = root("pm path com.miui.home 2>&1");
                write(new File(dir, "03-launcher-paths.txt"), paths);
                write(new File(dir, "04-launcher-package.txt"), root("dumpsys package com.miui.home 2>&1"));
                write(new File(dir, "05-module-package.txt"), root("dumpsys package com.yagay.desktopgridx 2>&1"));

                StringBuilder elfReport = new StringBuilder();
                List<String> apkPaths = parsePackagePaths(paths);
                int idx = 0;
                boolean foundLib = false;
                for (String apkPath : apkPaths) {
                    File copied = new File(dir, "launcher-" + idx + ".apk");
                    String cp = "cat " + shq(apkPath) + " > " + shq(copied.getAbsolutePath()) +
                            "; chmod 0644 " + shq(copied.getAbsolutePath()) +
                            "; restorecon " + shq(copied.getAbsolutePath()) + " 2>/dev/null || true";
                    String cpOut = root(cp);
                    if (!copied.isFile() || copied.length() == 0) {
                        elfReport.append("copy_failed[").append(idx).append("]=").append(apkPath).append('\n').append(cpOut).append('\n');
                        idx++; continue;
                    }
                    elfReport.append("apk[").append(idx).append("]=").append(apkPath)
                            .append("\napk_sha256[").append(idx).append("]=").append(sha256(copied)).append('\n');
                    try (ZipFile zf = new ZipFile(copied)) {
                        ZipEntry bi = zf.getEntry("assets/debug/build_info.txt");
                        if (bi != null) {
                            try (InputStream bin = zf.getInputStream(bi)) {
                                elfReport.append("=== assets/debug/build_info.txt ===\n")
                                        .append(new String(readLimited(bin, 1024 * 1024), StandardCharsets.UTF_8)).append('\n');
                            }
                        }
                        Enumeration<? extends ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) {
                            ZipEntry ze = en.nextElement();
                            if (ze.getName().endsWith("lib/arm64-v8a/libapp_launcher.so") || ze.getName().equals("lib/arm64-v8a/libapp_launcher.so")) {
                                File so = new File(dir, "libapp_launcher-" + idx + ".so");
                                try (InputStream in = zf.getInputStream(ze); OutputStream out = new FileOutputStream(so)) { copy(in, out); }
                                elfReport.append("lib_entry=").append(ze.getName()).append('\n')
                                        .append("lib_sha256=").append(sha256(so)).append('\n');
                                GnuDebugDataResolver.Result gd = GnuDebugDataResolver.resolve(so);
                                elfReport.append("=== GNU debugdata symbol resolver ===\n").append(gd.report).append('\n');
                                try {
                                    System.loadLibrary("desktopgridx");
                                    elfReport.append("=== native offline locator ===\n").append(NativeBridge.analyzeElf(so.getAbsolutePath())).append('\n');
                                } catch (Throwable t) {
                                    elfReport.append("native_analyzer_error=").append(stack(t)).append('\n');
                                }
                                foundLib = true;
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        elfReport.append("zip_error[").append(idx).append("]=").append(stack(t)).append('\n');
                    }
                    if (!copied.delete()) copied.deleteOnExit();
                    idx++;
                }
                if (!foundLib) elfReport.append("libapp_launcher.so NOT FOUND in package APKs\n");
                write(new File(dir, "06-hookpoint-scan.txt"), elfReport.toString());

                write(new File(dir, "07-launcher-process.txt"), root(
                        "echo '=== pid ==='; pidof com.miui.home; " +
                        "P=$(pidof com.miui.home | awk '{print $1}'); " +
                        "if [ -n \"$P\" ]; then echo '=== cmdline ==='; cat /proc/$P/cmdline; echo; " +
                        "echo '=== maps ==='; cat /proc/$P/maps; echo '=== status ==='; cat /proc/$P/status; fi"));

                write(new File(dir, "08-logcat-related.txt"), root(
                        "logcat -d -b all -v threadtime 2>/dev/null | " +
                        "grep -iE 'DesktopGridX|LSPosed|libxposed|Xposed|ShadowHook|shadowhook|com\\.miui\\.home|libapp_launcher|app_launcher|launcher' || true"));

                write(new File(dir, "09-lsposed-logs.txt"), root(
                        "for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do " +
                        "if [ -d \"$D\" ]; then echo \"=== $D ===\"; " +
                        "find \"$D\" -type f 2>/dev/null | sort | tail -n 20 | while read F; do echo \"--- $F ---\"; tail -n 2500 \"$F\" 2>/dev/null; done; fi; done"));

                write(new File(dir, "10-kernel-related.txt"), root(
                        "dmesg 2>/dev/null | grep -iE 'DesktopGridX|LSPosed|xposed|miui.home|launcher|app_launcher|shadowhook|avc:.*denied' | tail -n 5000 || true"));

                write(new File(dir, "11-native-files.txt"), root(
                        "echo '=== launcher native files ==='; " +
                        "for P in $(pm path com.miui.home 2>/dev/null | cut -d: -f2-); do echo \"--- $P ---\"; ls -lZ \"$P\"; done; " +
                        "echo '=== /data/app launcher dirs ==='; find /data/app -maxdepth 4 -type f -name 'libapp_launcher.so' 2>/dev/null -exec ls -lZ {} \\;"));

                write(new File(dir, "12-launcher-preferences.txt"), root(
                        "echo '=== preference files ==='; " +
                        "for D in /data/user/0/com.miui.home /data/user_de/0/com.miui.home /data/data/com.miui.home; do " +
                        "if [ -d \"$D\" ]; then echo \"--- $D ---\"; " +
                        "find \"$D\" -maxdepth 4 -type f \\( -name '*.xml' -o -name '*.json' -o -name '*.conf' \\) -print 2>/dev/null; " +
                        "grep -R -n -a -E 'pref_key_cell_x|pref_key_cell_y|pref_key_layout_type' \"$D\" 2>/dev/null | head -n 2000; fi; done"));

                write(new File(dir, "13-icon-layout-settings.txt"), root(
                        "echo '=== Settings.System icon/layout keys ==='; " +
                        "settings list system 2>/dev/null | grep -iE 'icon|cell|grid|home|launcher|hotseat|folder' || true; " +
                        "echo '=== direct icon_size_scale ==='; settings get system icon_size_scale 2>/dev/null; " +
                        "echo '=== IconSizeProvider manifest/authority ==='; dumpsys package com.miui.home 2>/dev/null | " +
                        "grep -i -A8 -B4 'bigicon\\|iconsize\\|IconSizeProvider' || true; " +
                        "echo '=== DesktopGridX original values ==='; cat /data/adb/desktopgridx/original.conf 2>/dev/null || true"));

                write(new File(dir, "14-provider-probe.txt"), root(
                        "echo '=== safe read-only provider probes ==='; " +
                        "content call --uri content://com.miui.home.launcher.bigicon.iconsize --method getIconLocation 2>&1 || true; " +
                        "echo '=== provider related logcat ==='; logcat -d -b all -v threadtime 2>/dev/null | " +
                        "grep -iE 'IconSizeProvider|icon_size_scale|convertIconSize|getIconLocation' | tail -n 2000 || true"));

                File zip = new File(context.getCacheDir(), "DesktopGridX-diagnostic-" + stamp + ".zip");
                zipDirectory(dir, zip);
                Uri out = saveToDownloads(context, zip, zip.getName());
                result = out != null ? "诊断包已导出：Downloads/DesktopGridX/" + zip.getName() : "诊断包生成成功，但保存到 Downloads 失败：" + zip.getAbsolutePath();
            } catch (Throwable t) {
                result = "导出失败：" + t;
            } finally {
                if (dir != null) deleteRecursive(dir);
            }
            callback.done(result);
        }, "DesktopGridX-Diagnostic").start();
    }

    private static String buildSummary(Context context) {
        return "DesktopGridX diagnostic\n" +
                "timestamp=" + new Date() + "\n" +
                "module_version=0.6.0\n" +
                "sdk=" + Build.VERSION.SDK_INT + "\n" +
                "release=" + Build.VERSION.RELEASE + "\n" +
                "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "fingerprint=" + Build.FINGERPRINT + "\n" +
                "package=" + context.getPackageName() + "\n" +
                "locator_policy=GNU .gnu_debugdata symbols -> pre-init PreferenceUtils::get_int GridConfig injection -> runtime ARM64 verification -> conditional getter fallback -> known-build DB -> fail closed\n" +
                "known_launcher_sha256_1=1c8ad848e768b437c1f997e78b4cc6d245f60622354bd901668508a53efedb9c\n" +
                "known_launcher_sha256_2=433232fb60c439e0a2537264c58058212df229a2ffdcbdaf1092f38295e04b4f\n";
    }

    private static List<String> parsePackagePaths(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static String root(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            final boolean[] truncated = {false};
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        int keep = (int)Math.min(n, Math.max(0, CMD_LIMIT - total));
                        if (keep > 0) synchronized (out) { out.write(buf, 0, keep); }
                        total += n;
                        if (total >= CMD_LIMIT) truncated[0] = true;
                    }
                } catch (IOException ignored) {}
            }, "DesktopGridX-root-reader");
            reader.start();
            boolean finished = p.waitFor(25, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            reader.join(2000);
            synchronized (out) {
                if (truncated[0]) out.write("\n[OUTPUT TRUNCATED]\n".getBytes(StandardCharsets.UTF_8));
                if (!finished) out.write("\n[COMMAND TIMEOUT]\n".getBytes(StandardCharsets.UTF_8));
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return "ROOT_COMMAND_ERROR: " + stack(t) + "\ncommand=" + cmd + "\n";
        }
    }

    private static String shq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1024*1024]; int n; while ((n=in.read(b))>0) md.update(b,0,n); }
        StringBuilder s = new StringBuilder(); for (byte x: md.digest()) s.append(String.format(Locale.US,"%02x",x)); return s.toString();
    }

    private static void write(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s == null ? "" : s); }
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
        while((n=in.read(b))>0){ if(out.size()+n>limit) throw new IOException("entry too large"); out.write(b,0,n); }
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] b=new byte[1024*1024]; int n; while((n=in.read(b))>0) out.write(b,0,n); }

    private static void zipDirectory(File dir, File zip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            File[] fs = dir.listFiles(); if (fs == null) return;
            Arrays.sort(fs, Comparator.comparing(File::getName));
            for (File f : fs) {
                if (!f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { copy(in, zos); }
                zos.closeEntry();
            }
        }
    }

    private static Uri saveToDownloads(Context c, File source, String name) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DesktopGridX");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        try (OutputStream out = c.getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(source)) {
            if (out == null) throw new IOException("openOutputStream returned null");
            copy(in, out);
        } catch (Throwable t) {
            c.getContentResolver().delete(uri, null, null);
            if (t instanceof IOException) throw (IOException)t;
            throw new IOException(t);
        }
        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0);
        c.getContentResolver().update(uri, cv, null, null);
        return uri;
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); return sw.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] a=f.listFiles(); if(a!=null) for(File x:a) deleteRecursive(x); }
        f.delete();
    }
}
