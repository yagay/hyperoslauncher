package com.yagay.desktopgridx;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

public final class MainActivity extends Activity {
    private EditText columns, rows, hotseat, iconSize;
    private CheckBox tracePrefs;
    private TextView status, runtimeStatus;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48,64,48,48);
        root.addView(text("DesktopGridX",26));
        root.addView(text("HyperOS 4 / com.miui.home · LSPosed 2.2.0-it 7869 · Modern Native API 102\n运行时只使用单一 libdesktopgridx_hyos.so，通过 LSPosed hook_func/unhook_func 安装 Hook；不再依赖 Java fallback 或 ShadowHook。",15));

        columns=numberField("桌面列数，例如 4 / 5 / 6 / 7");
        rows=numberField("桌面行数，例如 6 / 7 / 8 / 9");
        hotseat=numberField("底栏最大图标数，例如 4 / 5 / 6 / 7");
        iconSize=numberField("图标大小 1–100；留空/0 表示不修改");
        root.addView(columns);root.addView(rows);root.addView(hotseat);root.addView(iconSize);

        tracePrefs=new CheckBox(this);
        tracePrefs.setText("诊断 PreferenceUtils 整数键命中");
        root.addView(tracePrefs);

        Button save=new Button(this);save.setText("保存并应用");save.setOnClickListener(v->saveConfig());root.addView(save);
        Button reset=new Button(this);reset.setText("恢复 DesktopGridX 修改");reset.setOnClickListener(v->resetConfig());root.addView(reset);
        Button restart=new Button(this);restart.setText("强制停止桌面");restart.setOnClickListener(v->runRootAsync("am force-stop com.miui.home",ok->status.setText(ok?"已强制停止桌面；返回桌面后运行自检":"强制停止失败")));root.addView(restart);
        Button locate=new Button(this);locate.setText("自动重新定位 Hook 点");locate.setOnClickListener(v->autoLocate(locate));root.addView(locate);
        Button selfCheck=new Button(this);selfCheck.setText("运行时自检");selfCheck.setOnClickListener(v->refreshRuntimeStatus());root.addView(selfCheck);
        Button diagnostic=new Button(this);diagnostic.setText("一键导出多点诊断包");diagnostic.setOnClickListener(v->exportDiagnostic(diagnostic));root.addView(diagnostic);

        runtimeStatus=text("运行时状态：尚未读取",14);root.addView(runtimeStatus);
        status=text("配置路径：/data/adb/desktopgridx/config.conf",14);root.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        loadConfigAsync();refreshRuntimeStatus();
    }

    private TextView text(String s,float sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setPadding(0,12,0,20);return v;}
    private EditText numberField(String hint){EditText e=new EditText(this);e.setHint(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);return e;}
    private int val(EditText e,int def){try{String s=e.getText().toString().trim();return s.isEmpty()?def:Integer.parseInt(s);}catch(Exception x){return def;}}

    private void saveConfig(){
        int x=val(columns,0),y=val(rows,0),h=val(hotseat,0),icon=val(iconSize,0);boolean trace=tracePrefs.isChecked();
        if((x!=0&&(x<3||x>10))||(y!=0&&(y<4||y>12))||(h!=0&&(h<3||h>10))||(icon!=0&&(icon<1||icon>100))){status.setText("数值超出安全范围");return;}
        String cfg="columns="+x+"\nrows="+y+"\nhotseat="+h+"\niconSize="+icon+"\ntracePrefs="+(trace?1:0)+"\n";
        String clear="rm -f /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf";
        String cmd="mkdir -p /data/adb/desktopgridx; T=/data/adb/desktopgridx/config.conf.new; rm -f \"$T\"; printf '%s' "+shq(cfg)+" > \"$T\"; chmod 0644 \"$T\"; test -s \"$T\"; mv -f \"$T\" /data/adb/desktopgridx/config.conf; "+clear+"; if [ "+icon+" -gt 0 ]; then if ! grep -q '^original_icon_size_scale=' /data/adb/desktopgridx/original.conf 2>/dev/null; then V=$(settings get system icon_size_scale 2>/dev/null); printf 'original_icon_size_scale=%s\\n' \"$V\" >> /data/adb/desktopgridx/original.conf; chmod 0600 /data/adb/desktopgridx/original.conf; fi; settings put system icon_size_scale "+icon+"; fi";
        status.setText("正在保存配置…");
        new Thread(()->{RootShell.Result rr=RootShell.run(cmd,20,512*1024);if(!rr.ok()){runOnUiThread(()->status.setText("保存失败：exit="+rr.exitCode+(rr.timedOut?" timeout":"")));return;}LauncherHookResolver.Outcome o=LauncherHookResolver.resolveAndPersist(this);runOnUiThread(()->{runtimeStatus.setText("运行时状态：等待 Launcher 下次启动");status.setText(o.message+"；强制停止桌面或重启后生效");});},"DesktopGridX-Save").start();
    }

    private void resetConfig(){
        String cmd="if [ -f /data/adb/desktopgridx/original.conf ]; then V=$(sed -n 's/^original_icon_size_scale=//p' /data/adb/desktopgridx/original.conf | head -n1); if [ -n \"$V\" ] && [ \"$V\" != null ]; then settings put system icon_size_scale \"$V\"; else settings delete system icon_size_scale; fi; fi; rm -f /data/adb/desktopgridx/config.conf /data/adb/desktopgridx/resolved.conf /data/adb/desktopgridx/original.conf /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf";
        status.setText("正在恢复…");runRootAsync(cmd,ok->{status.setText(ok?"已恢复；强制停止桌面或重启后生效":"恢复失败");if(ok){runtimeStatus.setText("运行时状态：已清除");columns.setText("");rows.setText("");hotseat.setText("");iconSize.setText("");tracePrefs.setChecked(false);}});
    }

    private void loadConfigAsync(){new Thread(()->{RootShell.Result r=RootShell.run("cat /data/adb/desktopgridx/config.conf 2>/dev/null",10,128*1024);String raw=r.output;runOnUiThread(()->{for(String line:raw.split("\\R")){String[]a=line.split("=",2);if(a.length<2)continue;if(a[0].equals("columns"))columns.setText(zeroEmpty(a[1]));if(a[0].equals("rows"))rows.setText(zeroEmpty(a[1]));if(a[0].equals("hotseat"))hotseat.setText(zeroEmpty(a[1]));if(a[0].equals("iconSize"))iconSize.setText(zeroEmpty(a[1]));if(a[0].equals("tracePrefs"))tracePrefs.setChecked("1".equals(a[1]));}});},"DesktopGridX-LoadConfig").start();}
    private String zeroEmpty(String s){return "0".equals(s)?"":s;}

    private void refreshRuntimeStatus(){new Thread(()->{String cmd="N=''; for F in /data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf /data/data/com.miui.home/cache/desktopgridx-native-runtime.conf; do if [ -f \"$F\" ]; then cat \"$F\"; N=1; break; fi; done; [ -n \"$N\" ] || echo native_missing=1";String raw=RootShell.run(cmd,10,512*1024).output;runOnUiThread(()->runtimeStatus.setText(formatRuntime(raw)));},"DesktopGridX-SelfCheck").start();}
    private String formatRuntime(String raw){if(raw==null||raw.trim().isEmpty()||raw.contains("native_missing=1"))return "运行时自检：Native Entry=✕ 未生成\n请确认 LSPosed 已启用 com.miui.home scope，并导出诊断包。";String entry=find(raw,"native_entry_seen"),backend=find(raw,"backend"),install=find(raw,"install_state"),stage=find(raw,"stage"),x=find(raw,"x_hook_installed"),y=find(raw,"y_hook_installed"),h=find(raw,"hotseat_hook_installed"),p=find(raw,"preference_hook_installed"),hx=find(raw,"getter_x_hits"),hy=find(raw,"getter_y_hits"),hh=find(raw,"hotseat_hits"),hp=find(raw,"preference_hits"),err=find(raw,"last_error");return "运行时自检：\nNative Entry="+("1".equals(entry)?"✓":"✕")+" backend="+backend+"\ninstall="+install+" stage="+stage+"\nHook：X="+x+" Y="+y+" Hotseat="+h+" Pref="+p+"\n命中：X="+hx+" Y="+hy+" Hotseat="+hh+" Pref="+hp+"\nlast_error="+err;}
    private static String find(String raw,String key){for(String l:raw.split("\\R")){int p=l.indexOf('=');if(p>0&&l.substring(0,p).equals(key))return l.substring(p+1);}return "?";}

    private void autoLocate(Button button){button.setEnabled(false);status.setText("正在解析当前 Launcher .gnu_debugdata 并验证机器码…");new Thread(()->{LauncherHookResolver.Outcome o=LauncherHookResolver.resolveAndPersist(this);runOnUiThread(()->{button.setEnabled(true);status.setText(o.message);Toast.makeText(this,o.message,Toast.LENGTH_LONG).show();});},"DesktopGridX-Locator").start();}
    private void exportDiagnostic(Button button){button.setEnabled(false);status.setText("正在收集多点诊断信息…");DiagnosticCollector.export(this,message->runOnUiThread(()->{button.setEnabled(true);status.setText(message);Toast.makeText(this,message,Toast.LENGTH_LONG).show();}));}
    private interface RootCallback{void done(boolean ok);}
    private void runRootAsync(String command,RootCallback cb){new Thread(()->{RootShell.Result r=RootShell.run(command,20,512*1024);runOnUiThread(()->cb.done(r.ok()));},"DesktopGridX-Root").start();}
    private static String shq(String s){return "'"+s.replace("'","'\\''")+"'";}
}
