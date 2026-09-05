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
            File dir = null;
            String result;
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                dir = new File(context.getCacheDir(), "diagnostic-" + stamp);
                if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create diagnostic dir");

                write(new File(dir,"00-summary.txt"), summary(context));
                write(new File(dir,"01-root-device.txt"), root(
                        "echo '=== id ==='; id; echo '=== su ==='; (su -v 2>&1 || true); echo '=== kernel ==='; uname -a; " +
                        "echo '=== selinux ==='; getenforce 2>/dev/null; cat /sys/fs/selinux/enforce 2>/dev/null; " +
                        "echo '=== root managers ==='; (magisk -v 2>&1 || true); (ksud --version 2>&1 || true); " +
                        "echo '=== build ==='; getprop ro.build.fingerprint; getprop ro.build.version.release; getprop ro.build.version.sdk"));

                write(new File(dir,"02-config.txt"), root(
                        "echo '=== config ==='; cat /data/adb/desktopgridx/config.conf 2>&1; " +
                        "echo '=== resolved ==='; cat /data/adb/desktopgridx/resolved.conf 2>&1; " +
                        "echo '=== original ==='; cat /data/adb/desktopgridx/original.conf 2>&1; " +
                        "echo '=== module dir ==='; ls -laZ /data/adb/desktopgridx 2>&1"));

                write(new File(dir,"03-runtime-status.txt"), root(
                        "echo '=== JAVA ENTRY STATUS ==='; J=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf; do " +
                        "if [ -f \"$F\" ]; then echo path=$F; cat \"$F\"; J=1; break; fi; done; [ -n \"$J\" ] || echo java_status_missing=1; " +
                        "echo '=== NATIVE RUNTIME STATUS ==='; cat /data/adb/desktopgridx/runtime-status.conf 2>&1; " +
                        "echo '=== launcher pid ==='; P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; " +
                        "if [ -n \"$P\" ]; then echo '=== relevant live mappings ==='; grep -E 'desktopgridx|shadowhook|libapp_launcher|base.apk|zygisk_lsposed' /proc/$P/maps 2>/dev/null || true; fi"));

                String paths = root("pm path com.miui.home 2>&1");
                write(new File(dir,"04-launcher-paths.txt"), paths);
                write(new File(dir,"05-launcher-package.txt"), root("dumpsys package com.miui.home 2>&1"));
                write(new File(dir,"06-module-package.txt"), root("dumpsys package com.yagay.desktopgridx 2>&1"));

                StringBuilder elf = new StringBuilder();
                int idx=0; boolean found=false;
                for(String apkPath: parsePackagePaths(paths)) {
                    File copied = new File(dir,"launcher-"+idx+".apk");
                    root("cat "+shq(apkPath)+" > "+shq(copied.getAbsolutePath())+"; chmod 0644 "+shq(copied.getAbsolutePath()));
                    if(!copied.isFile() || copied.length()==0){ elf.append("copy_failed[").append(idx).append("]=").append(apkPath).append('\n'); idx++; continue; }
                    elf.append("apk[").append(idx).append("]=").append(apkPath).append('\n');
                    elf.append("apk_sha256[").append(idx).append("]=").append(sha256(copied)).append('\n');
                    try(ZipFile zf=new ZipFile(copied)){
                        ZipEntry bi=zf.getEntry("assets/debug/build_info.txt");
                        if(bi!=null) try(InputStream in=zf.getInputStream(bi)){ elf.append("=== build_info ===\n").append(new String(readLimited(in,1024*1024),StandardCharsets.UTF_8)).append('\n'); }
                        Enumeration<? extends ZipEntry> en=zf.entries();
                        while(en.hasMoreElements()){
                            ZipEntry ze=en.nextElement();
                            if(!ze.getName().equals("lib/arm64-v8a/libapp_launcher.so")) continue;
                            File so=new File(dir,"libapp_launcher-"+idx+".so");
                            try(InputStream in=zf.getInputStream(ze); OutputStream out=new FileOutputStream(so)){ copy(in,out); }
                            elf.append("lib_sha256=").append(sha256(so)).append('\n');
                            GnuDebugDataResolver.Result gd=GnuDebugDataResolver.resolve(so);
                            elf.append("=== GNU debugdata resolver ===\n").append(gd.report).append('\n');
                            try{ System.loadLibrary("desktopgridx"); elf.append("=== native offline locator ===\n").append(NativeBridge.analyzeElf(so.getAbsolutePath())).append('\n'); }
                            catch(Throwable t){ elf.append("native_analyzer_error=").append(stack(t)).append('\n'); }
                            found=true; break;
                        }
                    } catch(Throwable t){ elf.append("zip_error=").append(stack(t)).append('\n'); }
                    copied.delete(); idx++;
                }
                if(!found) elf.append("libapp_launcher.so NOT FOUND\n");
                write(new File(dir,"07-hookpoint-scan.txt"),elf.toString());

                write(new File(dir,"08-launcher-process.txt"),root(
                        "P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then " +
                        "echo '=== cmdline ==='; cat /proc/$P/cmdline; echo; echo '=== exe ==='; readlink /proc/$P/exe; " +
                        "echo '=== maps ==='; cat /proc/$P/maps; echo '=== status ==='; cat /proc/$P/status; fi"));

                write(new File(dir,"09-logcat-related.txt"),root(
                        "logcat -d -b all -v threadtime 2>/dev/null | grep -iE 'DGX_|DesktopGridX|com\\.yagay\\.desktopgridx|HookEntry|ClassNotFound|NoClassDefFound|UnsatisfiedLinkError|LSPosed|libxposed|Xposed|ShadowHook|shadowhook|com\\.miui\\.home|libapp_launcher|hyos_spawner' | tail -n 16000 || true"));

                write(new File(dir,"10-lsposed-logs.txt"),root(
                        "for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do if [ -d \"$D\" ]; then " +
                        "echo \"=== $D ===\"; find \"$D\" -type f 2>/dev/null | sort | tail -n 30 | while read F; do echo \"--- $F ---\"; tail -n 4000 \"$F\" 2>/dev/null; done; fi; done"));

                write(new File(dir,"10b-lsposed-entry-focus.txt"),root(
                        "for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do if [ -d \"$D\" ]; then " +
                        "find \"$D\" -type f 2>/dev/null | sort | tail -n 50 | while read F; do grep -H -n -iE 'DesktopGridX|com\\.yagay\\.desktopgridx|HookEntry|DGX_|ClassNotFound|NoClassDefFound|UnsatisfiedLinkError|loadLibrary|libdesktopgridx' \"$F\" 2>/dev/null || true; done; fi; done"));

                write(new File(dir,"11-kernel-selinux.txt"),root(
                        "dmesg 2>/dev/null | grep -iE 'DesktopGridX|desktopgridx|LSPosed|xposed|miui.home|launcher|app_launcher|shadowhook|avc:.*denied' | tail -n 6000 || true"));

                write(new File(dir,"12-launcher-preferences.txt"),root(
                        "for D in /data/user/0/com.miui.home /data/user_de/0/com.miui.home /data/data/com.miui.home; do if [ -d \"$D\" ]; then " +
                        "echo \"=== $D ===\"; find \"$D\" -maxdepth 4 -type f \\( -name '*.xml' -o -name '*.json' -o -name '*.conf' \\) -print 2>/dev/null; " +
                        "grep -R -n -a -E 'desktopgridx|pref_key_cell_x|pref_key_cell_y|icon_size|hotseat|folder|grid' \"$D\" 2>/dev/null | head -n 4000; fi; done"));

                write(new File(dir,"13-settings-provider.txt"),root(
                        "echo '=== relevant Settings.System ==='; settings list system 2>/dev/null | grep -iE 'icon|cell|grid|home|launcher|hotseat|folder' || true; " +
                        "echo '=== icon_size_scale ==='; settings get system icon_size_scale 2>/dev/null; " +
                        "echo '=== provider manifest ==='; dumpsys package com.miui.home 2>/dev/null | grep -i -A8 -B4 'bigicon\\|iconsize\\|IconSizeProvider' || true; " +
                        "echo '=== safe provider probe ==='; content call --uri content://com.miui.home.launcher.bigicon.iconsize --method getIconLocation 2>&1 || true"));

                File zip=new File(context.getCacheDir(),"DesktopGridX-diagnostic-"+stamp+".zip");
                zipDirectory(dir,zip);
                Uri out=saveToDownloads(context,zip,zip.getName());
                result=out!=null ? "诊断包已导出：Downloads/DesktopGridX/"+zip.getName() : "诊断包生成成功，但保存到 Downloads 失败："+zip.getAbsolutePath();
            } catch(Throwable t){ result="导出失败："+t; }
            finally { if(dir!=null) deleteRecursive(dir); }
            callback.done(result);
        },"DesktopGridX-Diagnostic").start();
    }

    private static String summary(Context c){
        return "DesktopGridX diagnostic\n"+
                "timestamp="+new Date()+"\nmodule_version=0.8.0\n"+
                "sdk="+Build.VERSION.SDK_INT+"\nrelease="+Build.VERSION.RELEASE+"\n"+
                "device="+Build.MANUFACTURER+" "+Build.MODEL+"\n"+
                "fingerprint="+Build.FINGERPRINT+"\npackage="+c.getPackageName()+"\n"+
                "java_status=Launcher cache/desktopgridx-java-runtime.conf\n"+
                "native_status=/data/adb/desktopgridx/runtime-status.conf\n"+
                "entry_policy=onModuleLoaded -> Java status -> System.loadLibrary -> NativeBridge.install -> ShadowHook\n"+
                "locator_policy=GNU debugdata -> live ARM64 verify -> pre-init PreferenceUtils GridConfig -> conditional getter fallback -> known DB -> fail closed\n";
    }

    private static List<String> parsePackagePaths(String text){ List<String> out=new ArrayList<>(); for(String l:text.split("\\R")){ l=l.trim(); if(l.startsWith("package:"))out.add(l.substring(8)); } return out; }
    private static String root(String cmd){
        Process p=null;
        try{
            p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out=new ByteArrayOutputStream(); InputStream in=p.getInputStream();
            final boolean[] truncated={false};
            Thread r=new Thread(() -> { try{ byte[] b=new byte[8192]; int n; long total=0; while((n=in.read(b))>0){ int keep=(int)Math.min(n,Math.max(0,CMD_LIMIT-total)); if(keep>0)synchronized(out){out.write(b,0,keep);} total+=n; if(total>=CMD_LIMIT)truncated[0]=true; } }catch(IOException ignored){} });
            r.start(); boolean done=p.waitFor(25, TimeUnit.SECONDS); if(!done)p.destroyForcibly(); r.join(2000);
            synchronized(out){ if(truncated[0])out.write("\n[OUTPUT TRUNCATED]\n".getBytes(StandardCharsets.UTF_8)); if(!done)out.write("\n[COMMAND TIMEOUT]\n".getBytes(StandardCharsets.UTF_8)); return out.toString(StandardCharsets.UTF_8.name()); }
        }catch(Throwable t){ if(p!=null)p.destroyForcibly(); return "ROOT_COMMAND_ERROR: "+stack(t)+"\n"; }
    }
    private static String shq(String s){ return "'"+s.replace("'","'\\''")+"'"; }
    private static String sha256(File f)throws Exception{ MessageDigest md=MessageDigest.getInstance("SHA-256"); try(InputStream in=new FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);} StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString(); }
    private static void write(File f,String s)throws IOException{ try(Writer w=new OutputStreamWriter(new FileOutputStream(f),StandardCharsets.UTF_8)){w.write(s==null?"":s);} }
    private static byte[] readLimited(InputStream in,int limit)throws IOException{ ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0){if(out.size()+n>limit)throw new IOException("entry too large");out.write(b,0,n);}return out.toByteArray(); }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    private static void zipDirectory(File dir,File zip)throws IOException{ try(ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))){File[] fs=dir.listFiles();if(fs==null)return;Arrays.sort(fs,Comparator.comparing(File::getName));for(File f:fs){if(!f.isFile())continue;zos.putNextEntry(new ZipEntry(f.getName()));try(InputStream in=new FileInputStream(f)){copy(in,zos);}zos.closeEntry();}} }
    private static Uri saveToDownloads(Context c,File src,String name)throws IOException{
        ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DesktopGridX");v.put(MediaStore.Downloads.IS_PENDING,1);
        Uri u=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)return null;
        try(OutputStream out=c.getContentResolver().openOutputStream(u);InputStream in=new FileInputStream(src)){if(out==null)throw new IOException("openOutputStream failed");copy(in,out);} catch(Throwable t){c.getContentResolver().delete(u,null,null);throw t;}
        v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);c.getContentResolver().update(u,v,null,null);return u;
    }
    private static void deleteRecursive(File f){if(f==null)return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteRecursive(x);}f.delete();}
    private static String stack(Throwable t){StringWriter s=new StringWriter();t.printStackTrace(new PrintWriter(s));return s.toString();}
}
