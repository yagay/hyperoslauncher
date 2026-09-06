#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace {
void read_cmdline(char* out, size_t cap) {
    if (out == nullptr || cap == 0) return;
    out[0] = '\0';
    FILE* f = fopen("/proc/self/cmdline", "re");
    if (f == nullptr) return;
    const size_t n = fread(out, 1, cap - 1, f);
    fclose(f);
    out[n] = '\0';
    for (size_t i = 0; i < n; ++i) {
        if (out[i] == '\0') out[i] = ' ';
    }
}
}

// Stage-0 probe: runs from ELF .init_array only when the HYOS loader performs
// actual dynamic-library initialization. It intentionally uses no C++ runtime.
__attribute__((constructor)) static void dgx_hyos_elf_constructor() {
    char cmdline[192]{};
    read_cmdline(cmdline, sizeof(cmdline));
    __android_log_print(ANDROID_LOG_INFO, "DesktopGridX",
                        "DGX_HYOS_CTOR pid=%d cmdline=%s",
                        static_cast<int>(getpid()),
                        cmdline[0] ? cmdline : "<unknown>");
}
