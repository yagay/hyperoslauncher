#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>

#define DGX_PROBE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DesktopGridX", __VA_ARGS__)
#define DGX_PROBE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DesktopGridX", __VA_ARGS__)

namespace {

std::string read_text(const char *path, size_t max_bytes = 1024) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string out;
    out.resize(max_bytes);
    ssize_t n;
    do {
        n = read(fd, out.data(), out.size());
    } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return {};
    out.resize(static_cast<size_t>(n));
    while (!out.empty() && (out.back() == '\0' || out.back() == '\n' || out.back() == '\r')) out.pop_back();
    for (char &c : out) if (c == '\0') c = ' ';
    return out;
}

std::string read_exe() {
    char buf[512]{};
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    buf[n] = '\0';
    return std::string(buf);
}

bool maps_contains(const char *needle) {
    if (!needle || !*needle) return false;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[2048];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle)) { found = true; break; }
    }
    fclose(f);
    return found;
}

void write_probe_snapshot(const char *stage) {
    const pid_t pid = getpid();
    const long tid = syscall(SYS_gettid);
    const std::string cmdline = read_text("/proc/self/cmdline", 512);
    const std::string exe = read_exe();
    const std::string selinux = read_text("/proc/self/attr/current", 512);
    const bool hyos = exe.find("hyos_spawner") != std::string::npos || cmdline.find("hyos_spawner") != std::string::npos;
    const bool launcher = cmdline.find("com.miui.home") != std::string::npos;
    const bool launcher_so = maps_contains("libapp_launcher.so");
    const bool module_so = maps_contains("libdesktopgridx.so");
    const bool lsposed = maps_contains("zygisk_lsposed") || maps_contains("libframework.so") || maps_contains("lspd");

    DGX_PROBE_LOGI(
        "DGX_HYOS_PROBE stage=%s pid=%d tid=%ld exe=%s cmdline=%s selinux=%s hyos=%d launcher=%d launcher_so=%d module_so=%d lsposed=%d",
        stage ? stage : "unknown", pid, tid,
        exe.empty() ? "?" : exe.c_str(),
        cmdline.empty() ? "?" : cmdline.c_str(),
        selinux.empty() ? "?" : selinux.c_str(),
        hyos ? 1 : 0, launcher ? 1 : 0, launcher_so ? 1 : 0, module_so ? 1 : 0, lsposed ? 1 : 0);

    const char *paths[] = {
        "/data/user_de/0/com.miui.home/cache/desktopgridx-hyos-probe.conf",
        "/data/user/0/com.miui.home/cache/desktopgridx-hyos-probe.conf",
        "/data/data/com.miui.home/cache/desktopgridx-hyos-probe.conf"
    };
    char data[4096];
    int len = snprintf(data, sizeof(data),
        "version=0.12.0\nstage=%s\npid=%d\ntid=%ld\nexe=%s\ncmdline=%s\nselinux=%s\nhyos_spawner=%d\nlauncher_identity=%d\nlauncher_so_seen=%d\nmodule_so_seen=%d\nlsposed_mapping_seen=%d\n",
        stage ? stage : "unknown", pid, tid,
        exe.empty() ? "?" : exe.c_str(),
        cmdline.empty() ? "?" : cmdline.c_str(),
        selinux.empty() ? "?" : selinux.c_str(),
        hyos ? 1 : 0, launcher ? 1 : 0, launcher_so ? 1 : 0, module_so ? 1 : 0, lsposed ? 1 : 0);
    if (len <= 0) return;
    size_t bytes = static_cast<size_t>(len < static_cast<int>(sizeof(data)) ? len : static_cast<int>(sizeof(data) - 1));
    for (const char *path : paths) {
        FILE *f = fopen(path, "we");
        if (!f) continue;
        size_t n = fwrite(data, 1, bytes, f);
        fflush(f);
        int fd = fileno(f); if (fd >= 0) fsync(fd);
        fclose(f);
        if (n == bytes) { chmod(path, 0600); break; }
    }
}

__attribute__((constructor)) void dgx_hyos_probe_ctor() {
    write_probe_snapshot("elf-constructor");
}

} // namespace
