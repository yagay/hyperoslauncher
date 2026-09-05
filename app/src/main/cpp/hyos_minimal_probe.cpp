#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

#define LOG_TAG "DesktopGridX-MinProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using HookFunType = int (*)(void *func, void *replace, void **backup);
using UnhookFunType = int (*)(void *func);
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

struct NativeAPIEntries {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
};

static void write_probe(const char *stage, const NativeAPIEntries *entries) {
    char buf[1024];
    int n = snprintf(buf, sizeof(buf),
        "stage=%s\npid=%d\napi=%u\nhook_func=%p\nunhook_func=%p\n",
        stage ? stage : "unknown", (int)getpid(), entries ? entries->version : 0,
        entries ? reinterpret_cast<void *>(entries->hook_func) : nullptr,
        entries ? reinterpret_cast<void *>(entries->unhook_func) : nullptr);

    const char *paths[] = {
        "/data/user_de/0/com.miui.home/cache/desktopgridx-minimal-probe.conf",
        "/data/user/0/com.miui.home/cache/desktopgridx-minimal-probe.conf",
        "/data/data/com.miui.home/cache/desktopgridx-minimal-probe.conf"
    };
    for (const char *p : paths) {
        int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) {
            if (n > 0) (void)write(fd, buf, static_cast<size_t>(n));
            close(fd);
            break;
        }
    }
}

static void on_library_loaded(const char *name, void *handle) {
    (void)handle;
    if (name && strstr(name, "libapp_launcher.so")) {
        LOGI("DGX_MIN_PROBE libapp_launcher seen: %s", name);
        write_probe("libapp-launcher-seen", nullptr);
    }
}

__attribute__((constructor)) static void ctor() {
    LOGI("DGX_MIN_PROBE constructor pid=%d", (int)getpid());
    write_probe("constructor", nullptr);
}

extern "C" __attribute__((visibility("default"))) __attribute__((used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    LOGI("DGX_MIN_PROBE native_init pid=%d api=%u hook=%p", (int)getpid(), entries ? entries->version : 0,
         entries ? reinterpret_cast<void *>(entries->hook_func) : nullptr);
    write_probe("native-init", entries);
    return on_library_loaded;
}
