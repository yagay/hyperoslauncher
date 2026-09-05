package com.yagay.desktopgridx;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
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
                if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("cannot create diagnostic dir");

                write(new File(dir,"00-summary.txt"),summary(context));
                write(new File(dir,"01-root-device.txt"),root("id; su -v 2>&1 || true; uname -a; getenforce 2>/dev/null; getprop ro.build.fingerprint; getprop ro.build.version.sdk; date; cat /proc/uptime 2>/dev/null"));
                write(new File(dir,"02-config.txt"),root("echo '=== config ==='; cat /data/adb/desktopgridx/config.conf 2>&1; echo '=== resolved ==='; cat /data/adb/desktopgridx/resolved.conf 2>&1; echo '=== original ==='; cat /data/adb/desktopgridx/original.conf 2>&1"));
                write(new File(dir,"03-runtime-status.txt"),root("FOUND=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do [ -f \"$F\" ] && { echo path=$F; cat \"$F\"; FOUND=1; break; }; done; [ -n \"$FOUND\" ] || echo native_status_missing=1"));
                write(new File(dir,"04-module-contract.txt"),root("APK=$(pm path com.yagay.desktopgridx | head -n1 | cut -d: -f2); echo apk=$APK; dumpsys package com.yagay.desktopgridx 2>/dev/null | grep -E 'versionName=|versionCode=|codePath=|primaryCpuAbi=|extractNativeLibs|nativeLibraryDir=' || true; echo '=== META-INF/xposed ==='; unzip -l \"$APK\" 'META-INF/xposed/*' 2>&1 || true; echo '=== module.prop ==='; unzip -p \"$APK\" META-INF/xposed/module.prop 2>/dev/null; echo; echo '=== java_init.list ==='; unzip -p \"$APK\" META-INF/xposed/java_init.list 2>/dev/null; echo; echo '=== native_init.list ==='; unzip -p \"$APK\" META-INF/xposed/native_init.list 2>/dev/null; echo; echo '=== scope.list ==='; unzip -p \"$APK\" META-INF/xposed/scope.list 2>/dev/null; echo; echo '=== native libs ==='; unzip -lv \"$APK\" 'lib/*/*.so' 2>/dev/null || true; echo '=== legacy entries must be absent ==='; unzip -l \"$APK\" 2>/dev/null | grep -E 'assets/(xposed_init|native_init)' || true"));

                String paths=root("pm path com.miui.home 2>&1");
                write(new File(dir,"05-launcher-package.txt"),paths+"\n"+root("dumpsys package com.miui.home 2>&1"));
                write(new File(dir,"06-launcher-process.txt"),root("P=$(pidof com.miui.home | awk '{print $1}'); echo pid=$P; if [ -n \"$P\" ]; then echo -n cmdline=; cat /proc/$P/cmdline; echo; echo exe=$(readlink /proc/$P/exe); echo -n selinux=; cat /proc/$P/attr/current 2>/dev/null; echo '=== relevant maps ==='; grep -iE 'desktopgridx_hyos|libapp_launcher|zygisk|lsposed|base.apk' /proc/$P/maps 2>/dev/null | head -n 4000; fi"));

                StringBuilder elf=new StringBuilder();int idx=0;boolean found=false;
                for(String apkPath:parsePackagePaths(paths)){
                    File copied=new File(dir,"launcher-"+idx+".apk");
                    RootShell.Result cp=RootShell.run("cat "+shq(apkPath)+" > "+shq(copied.getAbsolutePath())+"; chmod 0644 "+shq(copied.getAbsolutePath()),20,1024*1024);
                    if(!cp.ok()||!copied.isFile()||copied.length()==0){idx++;continue;}
                    try(ZipFile zf=new ZipFile(copied)){
                        ZipEntry ze=zf.getEntry("lib/arm64-v8a/libapp_launcher.so");
                        if(ze!=null){
                            File so=new File(dir,"libapp_launcher-"+idx+".so");
                            try(InputStream in=zf.getInputStream(ze);OutputStream out=new FileOutputStream(so)){copy(in,out);}
                            elf.append("source_apk=").append(apkPath).append('\n').append("lib_sha256=").append(sha256(so)).append('\n');
                            GnuDebugDataResolver.Result gd=GnuDebugDataResolver.resolve(so);
                            elf.append("=== GNU debugdata ===\n").append(gd.report);
                            try{System.loadLibrary("desktopgridx");elf.append("=== native offline verifier ===\n").append(NativeBridge.analyzeElf(so.getAbsolutePath())).append('\n');}catch(Throwable t){elf.append("native_analyzer_error=").append(t).append('\n');}
                            found=true;
                        }
                    }catch(Throwable t){elf.append("scan_error=").append(t).append('\n');}
                    copied.delete();idx++;
                }
                if(!found)elf.append("libapp_launcher.so NOT FOUND\n");
                write(new File(dir,"07-hookpoint-scan.txt"),elf.toString());

                write(new File(dir,"08-hyos-matrix.txt"),HyosProbeMatrix.collect());
                write(new File(dir,"09-lsposed-state.txt"),LsposedInspector.collect());
                write(new File(dir,"10-entry-stages.txt"),root(
                        "echo '=== Modern API102 entry ==='; " +
                        "M=$(logcat -d -b all -v brief -t 30000 2>/dev/null | grep -c 'DGX_MODERN_ENTRY' || true); echo modern_entry_seen=$M; " +
                        "logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -E 'DGX_MODERN_ENTRY|Loading module com.yagay.desktopgridx|Loaded module com.yagay.desktopgridx|Loading class.*HookEntry' | tail -n 2000 || true; " +
                        "echo '=== Native entry ==='; " +
                        "N=$(logcat -d -b all -v brief -t 30000 2>/dev/null | grep -c 'DGX_NATIVE_ENTRY' || true); echo native_entry_seen=$N; " +
                        "logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -E 'DGX_NATIVE_ENTRY|libdesktopgridx_hyos|native_init|recordNativeEntrypoint' | tail -n 3000 || true"));
                write(new File(dir,"10-logcat-related.txt"),root("logcat -d -b all -v threadtime -t 30000 2>/dev/null | grep -iE 'DGX_MODERN_ENTRY|DGX_NATIVE_ENTRY|DesktopGridX|libdesktopgridx_hyos|native_init|LSPosed|hyos_spawner|HyperOS Runtime|libapp_launcher|linker|dlopen|avc:.*denied' | tail -n 9000 || true"));
                write(new File(dir,"11-crash-selinux.txt"),root("echo '=== crash ==='; logcat -d -b crash -v threadtime -t 5000 2>/dev/null | grep -iE 'desktopgridx|com\\.miui\\.home|hyos_spawner' | tail -n 3000 || true; echo '=== selinux ==='; dmesg 2>/dev/null | grep -iE 'avc:.*denied.*(desktopgridx|miui.home|hyos_spawner)' | tail -n 3000 || true"));

                File zip=new File(context.getCacheDir(),"DesktopGridX-diagnostic-"+stamp+".zip");
                zipDirectory(dir,zip);Uri out=saveToDownloads(context,zip,zip.getName());
                result=out!=null?"诊断包已导出：Downloads/DesktopGridX/"+zip.getName():"诊断包生成成功，但保存失败："+zip.getAbsolutePath();
            }catch(Throwable t){result="导出失败："+t;}
            finally{if(dir!=null)deleteRecursive(dir);}
            callback.done(result);
        },"DesktopGridX-Diagnostic").start();
    }

    private static String summary(Context c){
        String version="unknown";long code=-1;
        try{PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);version=p.versionName;code=p.getLongVersionCode();}catch(Throwable ignored){}
        return "DesktopGridX diagnostic\ntimestamp="+new Date()+"\nmodule_version="+version+"\nversion_code="+code+"\nsdk="+Build.VERSION.SDK_INT+"\narchitecture=modern-api102-registration+hyos-native\nbackend=lsposed-native-api\n";
    }
    private static String root(String cmd){return RootShell.run(cmd,30,6*1024*1024).output;}
    private static List<String> parsePackagePaths(String raw){List<String>out=new ArrayList<>();for(String l:raw.split("\\R")){l=l.trim();if(l.startsWith("package:"))out.add(l.substring(8));}return out;}
    private static String shq(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static void write(File f,String s)throws IOException{try(OutputStream out=new FileOutputStream(f)){out.write((s==null?"":s).getBytes(StandardCharsets.UTF_8));}}
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[]b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString();}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[]b=new byte[1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    private static void zipDirectory(File dir,File zip)throws IOException{try(ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))){File[]fs=dir.listFiles();if(fs==null)return;Arrays.sort(fs,Comparator.comparing(File::getName));for(File f:fs){if(!f.isFile())continue;zos.putNextEntry(new ZipEntry(f.getName()));try(InputStream in=new FileInputStream(f)){copy(in,zos);}zos.closeEntry();}}}
    private static Uri saveToDownloads(Context c,File src,String name)throws IOException{ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DesktopGridX");v.put(MediaStore.Downloads.IS_PENDING,1);Uri u=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)return null;try(OutputStream out=c.getContentResolver().openOutputStream(u);InputStream in=new FileInputStream(src)){if(out==null)throw new IOException("openOutputStream failed");copy(in,out);}catch(Throwable t){c.getContentResolver().delete(u,null,null);throw t;}v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);c.getContentResolver().update(u,v,null,null);return u;}
    private static void deleteRecursive(File f){if(f==null)return;if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteRecursive(x);}f.delete();}
}
