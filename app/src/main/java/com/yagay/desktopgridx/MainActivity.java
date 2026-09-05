package com.yagay.desktopgridx;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class MainActivity extends Activity {
    private EditText columns, rows, hotseat, iconSize;
    private CheckBox tracePrefs;
    private TextView status, runtimeStatus;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        root.addView(text("DesktopGridX", 26));
        root.addView(text(
                "HyperOS 4 / com.miui.home · LSPosed API 102\n" +
                "v0.8 增加 Java Entry → Native Library → Native install → ShadowHook 的分层运行时自检。" +
                "图标大小继续使用 Launcher 自己的 Settings.System: icon_size_scale。修改后强制停止桌面或重启手机生效。", 15));

        columns = numberField("桌面列数，例如 4 / 5 / 6 / 7");
        rows = numberField("桌面行数，例如 6 / 7 / 8 / 9");
        hotseat = numberField("底栏最大图标数，例如 4 / 5 / 6 / 7");
        iconSize = numberField("图标大小 1–100；留空/0 表示不修改系统原值");
        root.addView(columns); root.addView(rows); root.addView(hotseat); root.addView(iconSize);

        tracePrefs = new CheckBox(this);
        tracePrefs.setText("诊断时记录 Launcher PreferenceUtils 整数键（只记录键名/返回值，不修改未知键）");
        root.addView(tracePrefs);

        Button save = new Button(this); save.setText("保存并应用"); save.setOnClickListener(v -> saveConfig()); root.addView(save);
        Button reset = new Button(this); reset.setText("恢复 DesktopGridX 修改"); reset.setOnClickListener(v -> resetConfig()); root.addView(reset);
        Button restart = new Button(this); restart.setText("强制停止桌面"); restart.setOnClickListener(v -> {
            runRoot("am force-stop com.miui.home");
            status.setText("已强制停止桌面；返回桌面后再点运行时自检");
        }); root.addView(restart);
        Button locate = new Button(this); locate.setText("自动重新定位 Hook 点"); locate.setOnClickListener(v -> autoLocate(locate)); root.addView(locate);
        Button selfCheck = new Button(this); selfCheck.setText("运行时自检"); selfCheck.setOnClickListener(v -> refreshRuntimeStatus()); root.addView(selfCheck);
        Button diagnostic = new Button(this); diagnostic.setText("一键导出诊断包"); diagnostic.setOnClickListener(v -> exportDiagnostic(diagnostic)); root.addView(diagnostic);

        runtimeStatus = text("运行时状态：尚未读取", 14);
        root.addView(runtimeStatus);
        root.addView(text("诊断会通过 Root 收集 Java Entry 状态、DesktopGridX Native 状态、LSPosed / ShadowHook / Launcher 日志、当前系统 icon_size_scale、Launcher 配置键、.gnu_debugdata 符号以及 ARM64 候选点。", 13));
        status = text("配置路径：/data/adb/desktopgridx/config.conf", 14); root.addView(status);

        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        loadConfig();
        refreshRuntimeStatus();
    }

    private TextView text(String s, float sp) { TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setPadding(0,12,0,20); return v; }
    private EditText numberField(String hint) { EditText e=new EditText(this); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER); return e; }
    private int val(EditText e, int def) { try { String s=e.getText().toString().trim(); return s.isEmpty()?def:Integer.parseInt(s); } catch(Exception x){ return def; } }

    private void saveConfig() {
        int x=val(columns,0), y=val(rows,0), h=val(hotseat,0), icon=val(iconSize,0);
        if ((x!=0 && (x<3||x>10)) || (y!=0 && (y<4||y>12)) || (h!=0 && (h<3||h>10)) || (icon!=0 && (icon<1||icon>100))) {
            status.setText("数值超出安全范围"); return;
        }
        String cfg="columns="+x+"\nrows="+y+"\nhotseat="+h+"\niconSize="+icon+"\ntracePrefs="+(tracePrefs.isChecked()?1:0)+"\n";
        String cmd="mkdir -p /data/adb/desktopgridx; printf '%s' "+shq(cfg)+" > /data/adb/desktopgridx/config.conf; " +
                "chmod 0644 /data/adb/desktopgridx/config.conf; rm -f /data/adb/desktopgridx/runtime-status.conf /data/adb/desktopgridx/runtime-status.conf.tmp; " +
                "rm -f /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf; " +
                "if [ "+icon+" -gt 0 ]; then if ! grep -q '^original_icon_size_scale=' /data/adb/desktopgridx/original.conf 2>/dev/null; then " +
                "V=$(settings get system icon_size_scale 2>/dev/null); printf 'original_icon_size_scale=%s\\n' \"$V\" >> /data/adb/desktopgridx/original.conf; chmod 0600 /data/adb/desktopgridx/original.conf; fi; settings put system icon_size_scale "+icon+"; fi";
        boolean ok=runRoot(cmd);
        if(!ok){ status.setText("保存失败：请确认 Root / su 授权"); return; }
        runtimeStatus.setText("运行时状态：已清空，等待 Launcher 下次启动重新生成");
        status.setText("配置已保存，正在解析当前 Launcher Hook 点…");
        new Thread(() -> {
            LauncherHookResolver.Outcome o=LauncherHookResolver.resolveAndPersist(this);
            runOnUiThread(() -> status.setText(o.message + "；强制停止桌面或重启后生效"));
        }, "DesktopGridX-Locator").start();
    }

    private void resetConfig() {
        String cmd="if [ -f /data/adb/desktopgridx/original.conf ]; then V=$(sed -n 's/^original_icon_size_scale=//p' /data/adb/desktopgridx/original.conf | head -n1); " +
                "if [ -n \"$V\" ] && [ \"$V\" != null ]; then settings put system icon_size_scale \"$V\"; else settings delete system icon_size_scale; fi; fi; " +
                "rm -f /data/adb/desktopgridx/config.conf /data/adb/desktopgridx/resolved.conf /data/adb/desktopgridx/original.conf /data/adb/desktopgridx/runtime-status.conf /data/adb/desktopgridx/runtime-status.conf.tmp; " +
                "rm -f /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf";
        status.setText(runRoot(cmd) ? "已恢复 DesktopGridX 修改；强制停止桌面或重启后生效" : "恢复失败");
        runtimeStatus.setText("运行时状态：已清除");
        columns.setText(""); rows.setText(""); hotseat.setText(""); iconSize.setText(""); tracePrefs.setChecked(false);
    }

    private void loadConfig() {
        try {
            Process p=new ProcessBuilder("su","-c","cat /data/adb/desktopgridx/config.conf 2>/dev/null").start();
            BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream())); String line;
            while((line=r.readLine())!=null){
                String[] a=line.split("=",2); if(a.length<2)continue;
                if(a[0].equals("columns"))columns.setText(zeroEmpty(a[1]));
                if(a[0].equals("rows"))rows.setText(zeroEmpty(a[1]));
                if(a[0].equals("hotseat"))hotseat.setText(zeroEmpty(a[1]));
                if(a[0].equals("iconSize"))iconSize.setText(zeroEmpty(a[1]));
                if(a[0].equals("tracePrefs"))tracePrefs.setChecked("1".equals(a[1]));
            }
        } catch(Exception ignored) {}
    }
    private String zeroEmpty(String s){ return "0".equals(s) ? "" : s; }

    private void refreshRuntimeStatus() {
        new Thread(() -> {
            String raw=runRootOutput(
                    "echo '===JAVA==='; J=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf /data/data/com.miui.home/cache/desktopgridx-java-runtime.conf; do if [ -f \"$F\" ]; then echo path=$F; cat \"$F\"; J=1; break; fi; done; [ -n \"$J\" ] || echo java_missing=1; " +
                    "echo '===NATIVE==='; if [ -f /data/adb/desktopgridx/runtime-status.conf ]; then cat /data/adb/desktopgridx/runtime-status.conf; else echo native_missing=1; fi");
            String summary=formatRuntime(raw);
            runOnUiThread(() -> runtimeStatus.setText(summary));
        },"DesktopGridX-SelfCheck").start();
    }

    private String formatRuntime(String raw){
        if(raw==null || raw.trim().isEmpty()) return "运行时状态：读取失败";
        String javaPart=section(raw,"===JAVA===","===NATIVE===");
        String nativePart=section(raw,"===NATIVE===",null);
        boolean javaOk=!javaPart.contains("java_missing=1") && "1".equals(find(javaPart,"desktopgridx_java_entry"));
        String jStage=find(javaPart,"desktopgridx_stage");
        String jLoad=find(javaPart,"desktopgridx_native_library_loaded");
        String jInstall=find(javaPart,"desktopgridx_native_install_result");
        String jErr=find(javaPart,"desktopgridx_error");
        if(nativePart.contains("native_missing=1")) {
            return "运行时自检：\nJava Entry="+(javaOk?"✓ 已进入":"✕ 未进入/无状态")+"\nJava stage="+jStage+"\nNative library="+jLoad+"  installResult="+jInstall+"\nJava error="+jErr+"\nNative Runtime=✕ 未生成";
        }
        String stage=find(nativePart,"stage"), nativeLoaded=find(nativePart,"native_loaded"), sh=find(nativePart,"shadowhook_init"), pref=find(nativePart,"preference_hook_installed");
        String px=find(nativePart,"pref_cell_x_hits"), py=find(nativePart,"pref_cell_y_hits"), gx=find(nativePart,"getter_x_hits"), gy=find(nativePart,"getter_y_hits"), hs=find(nativePart,"hotseat_hits");
        String method=find(nativePart,"resolver"), err=find(nativePart,"last_error"), pid=find(nativePart,"pid"), base=find(nativePart,"launcher_base");
        boolean hit=positive(px)||positive(py)||positive(gx)||positive(gy)||positive(hs);
        return "运行时自检：\nJava Entry="+(javaOk?"✓":"✕")+" stage="+jStage+"\nNative="+("1".equals(nativeLoaded)?"✓":"✕")+" PID="+pid+" base="+base+"\n"+
                "stage="+stage+" resolver="+method+"\nShadowHook="+sh+" PreferenceHook="+pref+"\n"+
                "命中：prefX="+px+" prefY="+py+" getterX="+gx+" getterY="+gy+" hotseat="+hs+"\n实际调用验证："+(hit?"✓ 已命中":"尚未命中/尚未触发")+"\nlast_error="+err;
    }
    private static String section(String raw,String start,String end){ int a=raw.indexOf(start); if(a<0)return ""; a+=start.length(); int b=end==null?raw.length():raw.indexOf(end,a); if(b<0)b=raw.length(); return raw.substring(a,b); }
    private static boolean positive(String s){ try{return Long.parseLong(s)>0;}catch(Exception e){return false;} }
    private static String find(String raw,String key){ for(String l:raw.split("\\R")){int p=l.indexOf('=');if(p>0&&l.substring(0,p).equals(key))return l.substring(p+1);}return "?"; }

    private void autoLocate(Button button) {
        button.setEnabled(false); status.setText("正在解析当前桌面的 .gnu_debugdata / ELF 符号…");
        new Thread(() -> {
            LauncherHookResolver.Outcome o=LauncherHookResolver.resolveAndPersist(this);
            runOnUiThread(() -> { button.setEnabled(true); status.setText(o.message); Toast.makeText(this,o.message,Toast.LENGTH_LONG).show(); });
        }, "DesktopGridX-Locator").start();
    }
    private void exportDiagnostic(Button button) {
        button.setEnabled(false); status.setText("正在收集诊断信息…");
        DiagnosticCollector.export(this, message -> runOnUiThread(() -> {
            button.setEnabled(true); status.setText(message); Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }));
    }
    private boolean runRoot(String command) { try { Process p=new ProcessBuilder("su","-c",command).start(); return p.waitFor()==0; } catch(Exception e){ return false; } }
    private String runRootOutput(String command){ try{ Process p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start(); BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream())); StringBuilder b=new StringBuilder(); String l; while((l=r.readLine())!=null)b.append(l).append('\n'); p.waitFor(); return b.toString(); }catch(Exception e){return "error="+e;} }
    private static String shq(String s){ return "'"+s.replace("'","'\\''")+"'"; }
}
