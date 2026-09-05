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
    private TextView status;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        root.addView(text("DesktopGridX", 26));
        root.addView(text(
                "HyperOS 4 / com.miui.home · LSPosed API 102\n" +
                "v0.6 优先在 libapp_launcher.so 初始化前从桌面原生 GridConfig 配置链注入网格；" +
                "cell 宽度/横向间距由 Launcher 根据列数原生重算。图标大小使用 Launcher 自己的 Settings.System: icon_size_scale，" +
                "不做 View 缩放。修改后强制停止桌面或重启手机生效。", 15));

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
        Button restart = new Button(this); restart.setText("强制停止桌面"); restart.setOnClickListener(v -> runRoot("am force-stop com.miui.home")); root.addView(restart);
        Button locate = new Button(this); locate.setText("自动重新定位 Hook 点"); locate.setOnClickListener(v -> autoLocate(locate)); root.addView(locate);
        Button diagnostic = new Button(this); diagnostic.setText("一键导出诊断包"); diagnostic.setOnClickListener(v -> exportDiagnostic(diagnostic)); root.addView(diagnostic);
        root.addView(text("诊断会通过 Root 收集 DesktopGridX / LSPosed / Launcher 日志、当前系统 icon_size_scale、Launcher 配置键、" +
                ".gnu_debugdata 符号以及 ARM64 候选点，导出到 Downloads/DesktopGridX。", 13));
        status = text("配置路径：/data/adb/desktopgridx/config.conf", 14); root.addView(status);

        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        loadConfig();
    }

    private TextView text(String s, float sp) { TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setPadding(0,12,0,20); return v; }
    private EditText numberField(String hint) { EditText e=new EditText(this); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER); return e; }
    private int val(EditText e, int def) { try { String s=e.getText().toString().trim(); return s.isEmpty()?def:Integer.parseInt(s); } catch(Exception x){ return def; } }

    private void saveConfig() {
        int x=val(columns,0), y=val(rows,0), h=val(hotseat,0), icon=val(iconSize,0);
        if ((x!=0 && (x<3||x>10)) || (y!=0 && (y<4||y>12)) || (h!=0 && (h<3||h>10)) ||
                (icon!=0 && (icon<1||icon>100))) {
            status.setText("数值超出安全范围"); return;
        }
        String cfg="columns="+x+"\nrows="+y+"\nhotseat="+h+"\niconSize="+icon+"\ntracePrefs="+(tracePrefs.isChecked()?1:0)+"\n";
        String cmd="mkdir -p /data/adb/desktopgridx; printf '%s' "+shq(cfg)+" > /data/adb/desktopgridx/config.conf; " +
                "chmod 0644 /data/adb/desktopgridx/config.conf; " +
                "if [ "+icon+" -gt 0 ]; then " +
                "if ! grep -q '^original_icon_size_scale=' /data/adb/desktopgridx/original.conf 2>/dev/null; then " +
                "V=$(settings get system icon_size_scale 2>/dev/null); printf 'original_icon_size_scale=%s\\n' \"$V\" >> /data/adb/desktopgridx/original.conf; chmod 0600 /data/adb/desktopgridx/original.conf; fi; " +
                "settings put system icon_size_scale "+icon+"; fi";
        boolean ok=runRoot(cmd);
        if(!ok){ status.setText("保存失败：请确认 Root / su 授权"); return; }
        status.setText("配置已保存，正在解析当前 Launcher Hook 点…");
        new Thread(() -> {
            LauncherHookResolver.Outcome o=LauncherHookResolver.resolveAndPersist(this);
            runOnUiThread(() -> status.setText(o.message + "；强制停止桌面或重启后生效"));
        }, "DesktopGridX-Locator").start();
    }

    private void resetConfig() {
        String cmd=
                "if [ -f /data/adb/desktopgridx/original.conf ]; then " +
                "V=$(sed -n 's/^original_icon_size_scale=//p' /data/adb/desktopgridx/original.conf | head -n1); " +
                "if [ -n \"$V\" ] && [ \"$V\" != null ]; then settings put system icon_size_scale \"$V\"; else settings delete system icon_size_scale; fi; fi; " +
                "rm -f /data/adb/desktopgridx/config.conf /data/adb/desktopgridx/resolved.conf /data/adb/desktopgridx/original.conf";
        status.setText(runRoot(cmd) ? "已恢复 DesktopGridX 修改；强制停止桌面或重启后生效" : "恢复失败");
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
    private static String shq(String s){ return "'"+s.replace("'","'\\''")+"'"; }
}
