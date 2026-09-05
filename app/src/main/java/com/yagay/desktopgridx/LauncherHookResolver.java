package com.yagay.desktopgridx;

import android.content.Context;
import java.io.*;

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
        delete(work); if(!work.mkdirs() && !work.isDirectory()) {
            o.success=false; o.message="无法创建解析缓存目录"; o.report="cache_dir_failed\n"; return o;
        }
        try {
            RootShell.Result pathResult=RootShell.run("pm path com.miui.home 2>/dev/null",15,512*1024);
            String paths=pathResult.output;
            report.append("pm_path_exit=").append(pathResult.exitCode).append(" timeout=").append(pathResult.timedOut).append('\n')
                    .append("pm_path=\n").append(paths).append('\n');
            if(!pathResult.ok()) throw new IOException("pm path failed");

            int index=0;
            for(String line:paths.split("\\R")) {
                line=line.trim(); if(!line.startsWith("package:"))continue;
                String src=line.substring(8);
                File apk=new File(work,"launcher-"+(index++)+".apk");
                RootShell.Result cp=RootShell.run("cat "+shq(src)+" > "+shq(apk.getAbsolutePath())+"; chmod 0644 "+shq(apk.getAbsolutePath()),20,512*1024);
                if(!cp.ok() || !apk.isFile() || apk.length()==0){
                    report.append("copy_failed=").append(src).append(" exit=").append(cp.exitCode).append(" timeout=").append(cp.timedOut).append('\n').append(cp.output).append('\n');
                    continue;
                }
                File so=new File(work,"libapp_launcher.so");
                try {
                    if(GnuDebugDataResolver.extractLauncherSo(apk,so)==null) continue;
                    GnuDebugDataResolver.Result r=GnuDebugDataResolver.resolve(so); o.symbols=r;
                    report.append("source_apk=").append(src).append('\n').append("=== GNU debugdata resolver ===\n").append(r.report);
                    System.loadLibrary("desktopgridx");
                    String nativeReport=NativeBridge.analyzeElf(so.getAbsolutePath());
                    report.append("=== native signature verifier ===\n").append(nativeReport).append('\n');
                    boolean nativeSafe=nativeReport.contains("safe=true");
                    if(r.success && nativeSafe) {
                        String cfg="resolver=gnu_debugdata\\n"+
                                "launcher_sha256="+r.sha256+"\\n"+
                                "resolved_x=0x"+Long.toHexString(r.x)+"\\n"+
                                "resolved_y=0x"+Long.toHexString(r.y)+"\\n"+
                                "resolved_hotseat=0x"+Long.toHexString(r.hotseat)+"\\n"+
                                "resolved_preference_get_int=0x"+Long.toHexString(r.preferenceGetInt)+"\\n"+
                                "resolved_preference_put_int=0x"+Long.toHexString(r.preferencePutInt)+"\\n"+
                                "resolved_get_screen_grid=0x"+Long.toHexString(r.getScreenGrid)+"\\n"+
                                "resolved_icon_size_provider_qualified=0x"+Long.toHexString(r.iconSizeProviderQualified)+"\\n"+
                                "resolved_compute_cell_width=0x"+Long.toHexString(r.computeCellWidth)+"\\n"+
                                "structural_verified=1\\n";
                        String target="/data/adb/desktopgridx/resolved.conf";
                        String tmp=target+".new";
                        String cmd="mkdir -p /data/adb/desktopgridx; rm -f "+shq(tmp)+"; printf '%b' "+shq(cfg)+" > "+shq(tmp)+
                                "; chmod 0644 "+shq(tmp)+"; test -s "+shq(tmp)+"; mv -f "+shq(tmp)+" "+shq(target)+"; sync";
                        RootShell.Result wr=RootShell.run(cmd,15,512*1024);
                        RootShell.Result check=RootShell.run("cat "+shq(target)+" 2>&1",10,512*1024);
                        report.append("=== persisted ===\nwrite_exit=").append(wr.exitCode).append(" timeout=").append(wr.timedOut).append('\n')
                                .append(wr.output).append(check.output).append('\n');
                        o.success=wr.ok() && check.ok() && check.output.contains("resolved_x=0x") && check.output.contains("resolved_y=0x") &&
                                check.output.contains("resolved_hotseat=0x") && check.output.contains("structural_verified=1");
                        o.message=o.success
                                ? (r.preferenceGetInt>0 ? "自动定位成功：符号与机器码双重验证通过；Preference ABI 将在运行时再次校验" : "自动定位成功：Getter Hook 安全兜底已就绪")
                                : "已解析并验证 Hook 点，但 Root 原子写入失败";
                        break;
                    } else {
                        report.append("resolver_rejected: javaSafe=").append(r.success).append(" nativeSafe=").append(nativeSafe).append('\n');
                    }
                } catch(Throwable t){ report.append("resolver_error=").append(stack(t)).append('\n'); }
            }
            if(o.message==null){ o.success=false; o.message="当前桌面没有通过符号+机器码双重验证；运行时将仅使用安全特征扫描"; }
        } catch(Throwable t){ o.success=false; o.message="自动定位失败："+t; report.append(stack(t)); }
        finally { delete(work); }
        o.report=report.toString();
        return o;
    }

    private static String shq(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static String stack(Throwable t){StringWriter sw=new StringWriter();t.printStackTrace(new PrintWriter(sw));return sw.toString();}
    private static void delete(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)delete(x);}f.delete();}
}
