package com.yagay.desktopgridx;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class JavaRuntimeStatus {
    private static final String[] PATHS = {
            "/data/user_de/0/com.miui.home/cache/desktopgridx-java-runtime.conf",
            "/data/user/0/com.miui.home/cache/desktopgridx-java-runtime.conf",
            "/data/data/com.miui.home/cache/desktopgridx-java-runtime.conf"
    };
    private static final Map<String,String> STATE = new LinkedHashMap<>();
    private static String activePath;

    private JavaRuntimeStatus() {}

    static synchronized void mark(String key, Object value) {
        STATE.put("desktopgridx_" + key, String.valueOf(value));
        STATE.put("desktopgridx_timestamp_ms", String.valueOf(System.currentTimeMillis()));
        flush();
    }

    static synchronized void markError(String stage, Throwable t) {
        STATE.put("desktopgridx_stage", stage);
        STATE.put("desktopgridx_error_class", t == null ? "null" : t.getClass().getName());
        STATE.put("desktopgridx_error", t == null ? "null" : String.valueOf(t.getMessage()));
        STATE.put("desktopgridx_timestamp_ms", String.valueOf(System.currentTimeMillis()));
        flush();
    }

    private static void flush() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String,String> e : STATE.entrySet()) {
            b.append(e.getKey()).append('=').append(sanitize(e.getValue())).append('\n');
        }
        byte[] data = b.toString().getBytes(StandardCharsets.UTF_8);
        if (activePath != null && write(activePath, data)) return;
        for (String p : PATHS) {
            if (write(p, data)) {
                activePath = p;
                return;
            }
        }
    }

    private static boolean write(String path, byte[] data) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(data);
                out.flush();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
