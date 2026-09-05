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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class DiagnosticCollector {
    interface Callback { void done(String message); }
    private DiagnosticCollector() {}

    static void export(Context context, Callback callback) {
        new Thread(() -> {
            File dir=null;
            String result;
            try {
                String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
                dir=new File(context.getCacheDir(),"diagnostic-"+stamp);
                if(!dir.mkdirs()&&!dir.isDirectory()) throw new IOException("cannot create diagnostic dir");

                write(new File(dir,"00-summary.txt"),summary(context));
                write(new File(dir,"01-root-device.txt"),root("id; echo '=== su ==='; (su -v 2>&1 || true); echo '=== kernel ==='; uname -a; echo '=== selinux ==='; getenforce 2>/dev/null; echo '=== build ==='; getprop ro.build.fingerprint; getprop ro.build.version.sdk"));
                write(new File(dir,"02-config.txt"),root("echo '=== config ==='; cat /data/adb/desktopgridx/config.conf 2>&1; echo '=== resolved ==='; cat /data/adb/desktopgridx/resolved.conf 2>&1; echo '=== original ==='; cat /data/adb/desktopgridx/original.conf 2>&1; echo '=== dir ==='; ls -laZ /data/adb/desktopgridx 2>&1"));
                write(new File(dir,"03-runtime-status.txt"),root(
                        "echo '=== JAVA FALLBACK STATUS ==='; J=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf; do if [ -f \"$F\" ]; then echo path=$F; cat \"$F\"; J=1; break; fi; done; [ -n \"$J\" ] || echo java_status_missing=1; " +
                        "echo '=== NATIVE ENTRY STATUS ==='; N=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do if [ -f \"$F\" ]; then echo path=$F; cat \"$F\"; N=1; break; fi; done; [ -n \"$N\" ] || echo native_status_missing=1; " +
                        "echo '=== launcher mappings ==='; P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then grep -E 'desktopgridx|shadowhook|libapp_launcher|base.apk|zygisk_lsposed' /proc/$P/maps 2>/dev/null || true; fi"));

                String paths=root("pm path com.miui.home 2>&1");
                write(new File(dir,"04-launcher-paths.txt"),paths);
                write(new File(dir,"05-launcher-package.txt"),root("dumpsys package com.miui.home 2>&1"));
                write(new File(dir,"06-module-package.txt"),root(
                        "dumpsys package com.yagay.desktopgridx 2>&1; " +
                        "APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo module_apk=$APK; " +
                        "echo '=== xposed resources ==='; unzip -l \"$APK\" 'META-INF/xposed/*' 2>&1 || true; " +
                        "echo '=== java_init.list ==='; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>&1 || true; " +
                        "echo '=== native_init.list ==='; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>&1 || true; " +
                        "echo '=== module.prop ==='; unzip -p \"$APK\" META-INF/xposed/module.prop 2>&1 || true; " +
                        "echo '=== scope.list ==='; unzip -p \"$APK\" META-INF/xposed/scope.list 2>&1 || true; " +
                        "echo '=== native library zip entry ==='; unzip -lv \"$APK\" lib/arm64-v8a/libdesktopgridx.so 2>&1 || true"));

                StringBuilder elf=new StringBuilder(); int idx=0; boolean found=false;
                for(String apkPath:parsePackagePaths(paths)) {
                    File copied=new File(dir,"launcher-"+idx+".apk");
                    RootShell.Result cp=RootShell.run("cat "+shq(apkPath)+" > "+shq(copied.getAbsolutePath())+"; chmod 0644 "+shq(copied.getAbsolutePath()),20,1024*1024);
                    if(!cp.ok()||!copied.isFile()||copied.length()==0){elf.append("copy_failed[").append(idx).append("]=").append(apkPath).append('\n');idx++;continue;}
                    elf.append("apk[").append(idx).append("]=").append(apkPath).append('\n').append("apk_sha256=").append(sha256(copied)).append('\n');
                    try(ZipFile zf=new ZipFile(copied)) {
                        for(String buildInfoPath:new String[]{"assets/flutter_assets/assets/debug/build_info.txt","assets/debug/build_info.txt"}) {
                            ZipEntry bi=zf.getEntry(buildInfoPath); if(bi!=null) try(InputStream in=zf.getInputStream(bi)){elf.append("=== ").append(buildInfoPath).append(" ===\n").append(new String(readLimited(in,1024*1024),StandardCharsets.UTF_8)).append('\n');}
                        }
                        ZipEntry ze=zf.getEntry("lib/arm64-v8a/libapp_launcher.so");
                        if(ze!=null) {
                            File so=new File(dir,"libapp_launcher-"+idx+".so");
                            try(InputStream in=zf.getInputStream(ze);OutputStream out=new FileOutputStream(so)){copy(in,out);}
                            elf.append("lib_sha256=").append(sha256(so)).append('\n');
                            GnuDebugDataResolver.Result gd=GnuDebugDataResolver.resolve(so);
                            elf.append("=== GNU debugdata resolver ===\n").append(gd.report).append('\n');
                            try {System.loadLibrary("desktopgridx");elf.append("=== native offline locator ===\n").append(NativeBridge.analyzeElf(so.getAbsolutePath())).append('\n');}
                            catch(Throwable t){elf.append("native_analyzer_error=").append(stack(t)).append('\n');}
                            found=true;
                        }
                    } catch(Throwable t){elf.append("zip_error=").append(stack(t)).append('\n');}
                    copied.delete(); idx++;
                }
                if(!found)elf.append("libapp_launcher.so NOT FOUND\n");
                write(new File(dir,"07-hookpoint-scan.txt"),elf.toString());

                write(new File(dir,"08-launcher-process.txt"),root("P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then echo '=== cmdline ==='; cat /proc/$P/cmdline; echo; echo '=== exe ==='; readlink /proc/$P/exe; echo '=== maps ==='; cat /proc/$P/maps; echo '=== status ==='; cat /proc/$P/status; fi"));
                write(new File(dir,"09-logcat-related.txt"),root("logcat -d -b all -v threadtime -t 12000 2>/dev/null | grep -iE 'DGX_|DesktopGridX|native_init|libdesktopgridx|com\\.yagay\\.desktopgridx|LSPosed|libxposed|ShadowHook|shadowhook|com\\.miui\\.home|libapp_launcher|hyos_spawner' | tail -n 6000 || true"));
                write(new File(dir,"10-lsposed-logs.txt"),root("for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do if [ -d \"$D\" ]; then echo \"=== $D ===\"; find \"$D\" -maxdepth 1 -type f \\( -name 'modules*.log' -o -name 'verbose*.log' -o -name 'logcat*.log' \\) 2>/dev/null | sort | tail -n 20 | while read F; do echo \"--- $F ---\"; tail -n 3000 \"$F\" 2>/dev/null; done; fi; done"));
                write(new File(dir,"10b-entry-focus.txt"),root("for D in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/logs /data/adb/lsposed/logs; do if [ -d \"$D\" ]; then find \"$D\" -maxdepth 1 -type f \\( -name 'modules*.log' -o -name 'verbose*.log' -o -name 'logcat*.log' \\) 2>/dev/null | sort | tail -n 30 | while read F; do grep -H -n -iE 'DesktopGridX|libdesktopgridx|native_init|DGX_NATIVE_ENTRY|DGX_ENTRY_MODULE_LOADED|com\\.yagay\\.desktopgridx|HookEntry|UnsatisfiedLinkError|ClassNotFound' \"$F\" 2>/dev/null || true; done; fi; done"));
                write(new File(dir,"11-kernel-selinux.txt"),root("dmesg 2>/dev/null | grep -iE 'DesktopGridX|desktopgridx|LSPosed|xposed|miui.home|launcher|app_launcher|shadowhook|avc:.*denied' | tail -n 4000 || true"));
                write(new File(dir,"12-launcher-preferences.txt"),root("for D in /data/user/0/com.miui.home /data/user_de/0/com.miui.home /data/data/com.miui.home; do if [ -d \"$D\" ]; then echo \"=== $D ===\"; grep -R -n -a -E 'desktopgridx|pref_key_cell_x|pref_key_cell_y|icon_size|hotseat|folder|grid' \"$D\" 2>/dev/null | head -n 3000; fi; done"));
                write(new File(dir,"13-settings-provider.txt"),root("settings list system 2>/dev/null | grep -iE 'icon|cell|grid|home|launcher|hotseat|folder' || true; echo '=== icon_size_scale ==='; settings get system icon_size_scale 2>/dev/null; dumpsys package com.miui.home 2>/dev/null | grep -i -A8 -B4 'bigicon\\|iconsize\\|IconSizeProvider' || true"));

                File zip=new File(context.getCacheDir(),"DesktopGridX-diagnostic-"+stamp+".zip");
                zipDirectory(dir,zip); Uri out=saveToDownloads(context,zip,zip.getName());
                result=out!=null?"诊断包已导出：Downloads/DesktopGridX/"+zip.getName():"诊断包生成成功，但保存到 Downloads 失败："+zip.getAbsolutePath();
            } catch(Throwable t){result="导出失败："+t;}
            finally{if(dir!=null)deleteRecursive(dir);}
            callback.done(result);
        },"DesktopGridX-Diagnostic").start();
    }

    private static String summary(Context c){return "DesktopGridX diagnostic\n"+"timestamp="+new Date()+"\nmodule_version=0.10.0\n"+"sdk="+Build.VERSION.SDK_INT+"\nrelease="+Build.VERSION.RELEASE+"\n"+"device="+Build.MANUFACTURER+" "+Build.MODEL+"\n"+"fingerprint="+Build.FINGERPRINT+"\npackage="+c.getPackageName()+"\nentry_policy=native_init.list primary + unconditional Java loadLibrary fallback; native side owns Launcher detection\nlocator_policy=GNU debugdata + structural validation -> live ARM64 verify -> Preference ABI gate -> getter fallback -> verified RVA profiles -> fail closed\n";}
    private static String root(String cmd){return RootShell.run(cmd,25,12*1024*1024).output;}
    private static List<String> parsePackagePaths(String text){List<String> out=new ArrayList<>();for(String l:text.split("\\R")){l=l.trim();if(l.startsWith("package:"))out.add(l.substring(8));}return out;}
    private static String shq(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString();}
    private static void write(File f,String s)throws IOException{try(Writer w=new OutputStreamWriter(new FileOutputStream(f),StandardCharsets.UTF_8)){w.write(s==null?"":s);}}
    private static byte[] readLimited(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0){if(out.size()+n>limit)throw new IOException("entry too large");out.write(b,0,n);}return out.toByteArray();}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    private static void zipDirectory(File dir,File zip)throws IOException{try(ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))){File[] fs=dir.listFiles();if(fs==null)return;Arrays.sort(fs,Comparator.comparing(File::getName));for(File f:fs){if(!f.isFile())continue;zos.putNextEntry(new ZipEntry(f.getName()));try(InputStream in=new FileInputStream(f)){copy(in,zos);}zos.closeEntry();}}}
    private static Uri saveToDownloads(Context c,File src,String name)throws IOException{ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DesktopGridX");v.put(MediaStore.Downloads.IS_PENDING,1);Uri u=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)return null;try(OutputStream out=c.getContentResolver().openOutputStream(u);InputStream in=new FileInputStream(src)){if(out==null)throw new IOException("openOutputStream failed");copy(in,out);}catch(Throwable t){c.getContentResolver().delete(u,null,null);throw t;}v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);c.getContentResolver().update(u,v,null,null);return u;}
    private static void deleteRecursive(File f){if(f==null)return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteRecursive(x);}f.delete();}
    private static String stack(Throwable t){StringWriter s=new StringWriter();t.printStackTrace(new PrintWriter(s));return s.toString();}
}
