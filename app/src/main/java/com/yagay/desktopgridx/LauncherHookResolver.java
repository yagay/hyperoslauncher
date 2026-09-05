package com.yagay.desktopgridx;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class LauncherHookResolver {
    static final class Outcome {
        boolean success;
        String message;
        String report;
        GnuDebugDataResolver.Result symbols;
    }
    private LauncherHookResolver() {}

    static Outcome resolveAndPersist(Context context) {
        Outcome o=new Outcome(); StringBuilder report=new StringBuilder();
        File work=new File(context.getCacheDir(),"hook-resolver");
        delete(work); work.mkdirs();
        try {
            String paths=root("pm path com.miui.home 2>/dev/null");
            report.append("pm_path=\n").append(paths).append('\n');
            int index=0;
            for(String line:paths.split("\\R")) {
                line=line.trim(); if(!line.startsWith("package:"))continue;
                String src=line.substring(8);
                File apk=new File(work,"launcher-"+(index++)+".apk");
                String cp=root("cat "+shq(src)+" > "+shq(apk.getAbsolutePath())+"; chmod 0644 "+shq(apk.getAbsolutePath()));
                if(!apk.isFile()||apk.length()==0){report.append("copy_failed=").append(src).append('\n').append(cp).append('\n');continue;}
                File so=new File(work,"libapp_launcher.so");
                try {
                    if(GnuDebugDataResolver.extractLauncherSo(apk,so)==null)continue;
                    GnuDebugDataResolver.Result r=GnuDebugDataResolver.resolve(so); o.symbols=r;
                    report.append("source_apk=").append(src).append('\n').append("=== GNU debugdata resolver ===\n").append(r.report);
                    System.loadLibrary("desktopgridx");
                    report.append("=== native signature verifier ===\n").append(NativeBridge.analyzeElf(so.getAbsolutePath())).append('\n');
                    if(r.success) {
                        String cfg="resolver=gnu_debugdata\\n"+
                                "launcher_sha256="+r.sha256+"\\n"+
                                "resolved_x=0x"+Long.toHexString(r.x)+"\\n"+
                                "resolved_y=0x"+Long.toHexString(r.y)+"\\n"+
                                "resolved_hotseat=0x"+Long.toHexString(r.hotseat)+"\\n"+
                                "resolved_preference_get_int=0x"+Long.toHexString(r.preferenceGetInt)+"\\n"+
                                "resolved_preference_put_int=0x"+Long.toHexString(r.preferencePutInt)+"\\n"+
                                "resolved_get_screen_grid=0x"+Long.toHexString(r.getScreenGrid)+"\\n"+
                                "resolved_icon_size_provider_qualified=0x"+Long.toHexString(r.iconSizeProviderQualified)+"\\n"+
                                "resolved_icon_size_provider_call=0x"+Long.toHexString(r.iconSizeProviderCall)+"\\n"+
                                "resolved_compute_cell_width=0x"+Long.toHexString(r.computeCellWidth)+"\\n";
                        String cmd="mkdir -p /data/adb/desktopgridx; printf '%b' "+shq(cfg)+" > /data/adb/desktopgridx/resolved.conf; chmod 0644 /data/adb/desktopgridx/resolved.conf";
                        String wr=root(cmd);
                        String check=root("cat /data/adb/desktopgridx/resolved.conf 2>&1");
                        report.append("=== persisted ===\n").append(check).append("\nwrite_output=").append(wr).append('\n');
                        o.success=check.contains("resolved_x=0x")&&check.contains("resolved_y=0x")&&check.contains("resolved_hotseat=0x");
                        o.message=o.success?(r.preferenceGetInt>0?"自动定位成功：GridConfig 上游注入已就绪；运行时将优先 pre-init，必要时自动启用 Getter 兜底":"自动定位成功：Getter Hook 兜底已就绪（未找到 PreferenceUtils::get_int）"):"已解析符号，但 Root 写入 Hook 点失败";
                        break;
                    }
                } catch(Throwable t){report.append("resolver_error=").append(stack(t)).append('\n');}
            }
            if(o.message==null){o.success=false;o.message="未能从当前桌面解析出唯一 Hook 点；将由运行时安全特征扫描尝试定位";}
        } catch(Throwable t){o.success=false;o.message="自动定位失败："+t;report.append(stack(t));}
        finally {delete(work);}
        o.report=report.toString();
        return o;
    }

    private static String root(String cmd) throws Exception {
        Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
        try(InputStream in=p.getInputStream()){while((n=in.read(b))>0 && out.size()<4*1024*1024)out.write(b,0,n);}
        if(!p.waitFor(30, TimeUnit.SECONDS))p.destroyForcibly();
        return out.toString(StandardCharsets.UTF_8.name());
    }
    private static String shq(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String stack(Throwable t){StringWriter sw=new StringWriter();t.printStackTrace(new PrintWriter(sw));return sw.toString();}
    private static void delete(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)delete(x);}f.delete();}
}
