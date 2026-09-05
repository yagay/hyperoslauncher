#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <unistd.h>

namespace {
void read_cmdline(char* out, size_t cap) {
    if (!out || cap == 0) return;
    out[0] = '\0';
    FILE* f = fopen("/proc/self/cmdline", "re");
    if (!f) return;
    const size_t n = fread(out, 1, cap - 1, f);
    fclose(f);
    out[n] = '\0';
    for (size_t i = 0; i < n; ++i) {
        if (out[i] == '\0') out[i] = ' ';
    }
}
}

// Stage-0 probe: this runs from ELF .init_array if the HYOS loader performs a
// real dynamic-library initialization. It deliberately does not touch LSPosed,
// Java, app storage, or the hook engine, so it cleanly distinguishes "mapped"
// from "initialized".
__attribute__((constructor)) static void dgx_hyos_elf_constructor() {
    char cmdline[192]{};
    read_cmdline(cmdline, sizeof(cmdline));
    __android_log_print(ANDROID_LOG_INFO, "DesktopGridX",
                        "DGX_HYOS_CTOR pid=%d cmdline=%s",
                        static_cast<int>(getpid()), cmdline[0] ? cmdline : "<unknown>");
}
