#include <android/log.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DesktopGridX", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DesktopGridX", __VA_ARGS__)

namespace {

constexpr uintptr_t kXYDistance = 0xAC;
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kLauncherProcess[] = "com.miui.home";
constexpr char kLauncherName[] = "libapp_launcher.so";
constexpr size_t kMaxCandidates = 256;

struct NativeApiEntries {
    uint32_t version;
    void (*hook_func)(void* target, void* replacement, void** backup);
    void (*unhook_func)(void* target);
};
using NativeOnLibraryLoaded = void (*)(const char* name, void* handle);

struct Word { uint32_t value; uint32_t mask; };
constexpr Word kPatternXY[] = {
    {0xd10083ff,0xffffffff},{0xa9017bfd,0xffffffff},
    {0x910043fd,0xffffffff},{0xd00042c8,0x9f00001f}
};
constexpr Word kPatternPref[] = {
    {0xd10203ff,0xffffffff},{0xa9057bfd,0xffffffff},
    {0xa90657f6,0xffffffff},{0xa9074ff4,0xffffffff},
    {0x910143fd,0xffffffff},{0x90000008,0x9f00001f},
    {0x91000108,0xffc003ff},{0xaa0103f3,0xffffffff},
    {0xc8dffd08,0xffffffff},{0xaa0003f4,0xffffffff},
    {0xf100091f,0xffffffff}
};
constexpr Word kPatternHotseat[] = {
    {0xa9bf7bfd,0xffffffff},{0x910003fd,0xffffffff},
    {0x90000008,0x9f00001f},{0x91000108,0xffc003ff},
    {0xc8dffd08,0xffffffff},{0xf100091f,0xffffffff},
    {0x54000121,0xffffffff},{0x90000008,0x9f00001f},
    {0xf9400100,0xffc003ff},{0x94000000,0xfc000000},
    {0x52800088,0xffffffff},{0x7100001f,0xffffffff},
    {0x1a881500,0xffffffff},{0xa8c17bfd,0xffffffff},
    {0xd65f03c0,0xffffffff}
};

struct CandidateSet {
    uintptr_t values[kMaxCandidates];
    size_t count;
    bool overflow;
};

struct Image {
    uintptr_t base;
    const ElfW(Phdr)* phdr;
    ElfW(Half) count;
};

using IntFn = int (*)();
using U128 = unsigned __int128;
using PrefFn = U128 (*)(const char*, size_t);

void (*g_hook_func)(void*, void*, void**) = nullptr;
void (*g_unhook_func)(void*) = nullptr;
IntFn g_orig_x = nullptr;
IntFn g_orig_y = nullptr;
IntFn g_orig_hotseat = nullptr;
PrefFn g_orig_pref = nullptr;
uintptr_t g_hooked_x = 0;
uintptr_t g_hooked_y = 0;
uintptr_t g_hooked_hotseat = 0;
uintptr_t g_hooked_pref = 0;

volatile int g_columns = 0;
volatile int g_rows = 0;
volatile int g_hotseat = 0;
volatile int g_icon_size = 0;
volatile int g_trace_prefs = 0;
volatile uintptr_t g_hint_x = 0;
volatile uintptr_t g_hint_y = 0;
volatile uintptr_t g_hint_hotseat = 0;
volatile uintptr_t g_hint_pref = 0;
volatile uint32_t g_install_state = 0;
volatile uint32_t g_lock = 0;
volatile uint64_t g_hit_x = 0;
volatile uint64_t g_hit_y = 0;
volatile uint64_t g_hit_hotseat = 0;
volatile uint64_t g_hit_pref = 0;
uint32_t g_native_api_version = 0;

constexpr const char* kStatusPaths[] = {
    "/data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/data/com.miui.home/cache/desktopgridx-native-runtime.conf"
};

bool EndsWith(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t a = strlen(value);
    const size_t b = strlen(suffix);
    return b <= a && memcmp(value + a - b, suffix, b) == 0;
}

bool IsLauncherHyosProcess() {
    char exe[128]{};
    const ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0 || static_cast<size_t>(n) >= sizeof(exe)) return false;
    exe[n] = '\0';
    if (strcmp(exe, kSpawnerPath) != 0) return false;
    FILE* f = fopen("/proc/self/cmdline", "re");
    if (f == nullptr) return false;
    char cmd[64]{};
    const size_t r = fread(cmd, 1, sizeof(cmd) - 1, f);
    fclose(f);
    if (r == 0) return false;
    cmd[r] = '\0';
    return strcmp(cmd, kLauncherProcess) == 0;
}

void Lock() {
    while (__atomic_exchange_n(&g_lock, 1u, __ATOMIC_ACQUIRE) != 0u) {
        usleep(1000);
    }
}

void Unlock() {
    __atomic_store_n(&g_lock, 0u, __ATOMIC_RELEASE);
}

bool Match(const uint8_t* p, const Word* pattern, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        uint32_t word = 0;
        memcpy(&word, p + i * sizeof(uint32_t), sizeof(word));
        if ((word & pattern[i].mask) !=
                (pattern[i].value & pattern[i].mask)) return false;
    }
    return true;
}

void AddCandidate(CandidateSet* set, uintptr_t value) {
    if (set->count >= kMaxCandidates) {
        set->overflow = true;
        return;
    }
    set->values[set->count++] = value;
}

void Scan(const uint8_t* memory, size_t length, uintptr_t logical,
          const Word* pattern, size_t pattern_count, CandidateSet* out) {
    const size_t need = pattern_count * sizeof(uint32_t);
    if (memory == nullptr || out == nullptr || length < need) return;
    for (size_t offset = 0; offset + need <= length; offset += sizeof(uint32_t)) {
        if (Match(memory + offset, pattern, pattern_count)) {
            AddCandidate(out, logical + offset);
        }
    }
}

int ImageCallback(dl_phdr_info* info, size_t, void* data) {
    if (info == nullptr || data == nullptr || info->dlpi_name == nullptr) return 0;
    if (!EndsWith(info->dlpi_name, kLauncherName)) return 0;
    Image* image = static_cast<Image*>(data);
    image->base = static_cast<uintptr_t>(info->dlpi_addr);
    image->phdr = info->dlpi_phdr;
    image->count = info->dlpi_phnum;
    return 1;
}

bool Verify(const Image& image, uintptr_t address,
            const Word* pattern, size_t pattern_count) {
    if (image.base == 0 || image.phdr == nullptr) return false;
    const size_t need = pattern_count * sizeof(uint32_t);
    for (ElfW(Half) i = 0; i < image.count; ++i) {
        const ElfW(Phdr)& ph = image.phdr[i];
        if (ph.p_type != PT_LOAD || (ph.p_flags & PF_X) == 0 ||
                (ph.p_flags & PF_R) == 0) continue;
        const uintptr_t begin = image.base + ph.p_vaddr;
        const uintptr_t end = begin + ph.p_memsz;
        if (address >= begin && address <= end && end - address >= need) {
            return Match(reinterpret_cast<const uint8_t*>(address),
                         pattern, pattern_count);
        }
    }
    return false;
}

void ReadConfig() {
    g_columns = 0;
    g_rows = 0;
    g_hotseat = 0;
    g_icon_size = 0;
    g_trace_prefs = 0;
    g_hint_x = g_hint_y = g_hint_hotseat = g_hint_pref = 0;

    char line[256]{};
    FILE* f = fopen("/data/adb/desktopgridx/config.conf", "re");
    if (f != nullptr) {
        while (fgets(line, sizeof(line), f) != nullptr) {
            int value = 0;
            if (sscanf(line, "columns=%d", &value) == 1) g_columns = value;
            else if (sscanf(line, "rows=%d", &value) == 1) g_rows = value;
            else if (sscanf(line, "hotseat=%d", &value) == 1) g_hotseat = value;
            else if (sscanf(line, "iconSize=%d", &value) == 1) g_icon_size = value;
            else if (sscanf(line, "tracePrefs=%d", &value) == 1) g_trace_prefs = value;
        }
        fclose(f);
    }

    f = fopen("/data/adb/desktopgridx/resolved.conf", "re");
    if (f != nullptr) {
        while (fgets(line, sizeof(line), f) != nullptr) {
            unsigned long long value = 0;
            if (sscanf(line, "resolved_x=0x%llx", &value) == 1)
                g_hint_x = static_cast<uintptr_t>(value);
            else if (sscanf(line, "resolved_y=0x%llx", &value) == 1)
                g_hint_y = static_cast<uintptr_t>(value);
            else if (sscanf(line, "resolved_hotseat=0x%llx", &value) == 1)
                g_hint_hotseat = static_cast<uintptr_t>(value);
            else if (sscanf(line, "resolved_preference_get_int=0x%llx", &value) == 1)
                g_hint_pref = static_cast<uintptr_t>(value);
        }
        fclose(f);
    }
}

void WriteStatus(const char* stage, const char* error = "none", uintptr_t base = 0) {
    char buffer[2048]{};
    const int n = snprintf(buffer, sizeof(buffer),
        "version=0.26.0\n"
        "pid=%d\n"
        "stage=%s\n"
        "libxposed_api=102\n"
        "native_api_version=%u\n"
        "native_entry_seen=1\n"
        "backend=lsposed_native_api_stl_none\n"
        "install_state=%u\n"
        "columns=%d\nrows=%d\nhotseat=%d\niconSize=%d\n"
        "launcher_base=0x%lx\n"
        "x_hook_installed=%d\ny_hook_installed=%d\n"
        "hotseat_hook_installed=%d\npreference_hook_installed=%d\n"
        "getter_x_hits=%llu\ngetter_y_hits=%llu\nhotseat_hits=%llu\n"
        "preference_hits=%llu\nlast_error=%s\n",
        static_cast<int>(getpid()), stage, g_native_api_version,
        __atomic_load_n(&g_install_state, __ATOMIC_ACQUIRE),
        g_columns, g_rows, g_hotseat, g_icon_size,
        static_cast<unsigned long>(base),
        g_hooked_x ? 1 : 0, g_hooked_y ? 1 : 0,
        g_hooked_hotseat ? 1 : 0, g_hooked_pref ? 1 : 0,
        static_cast<unsigned long long>(__atomic_load_n(&g_hit_x, __ATOMIC_RELAXED)),
        static_cast<unsigned long long>(__atomic_load_n(&g_hit_y, __ATOMIC_RELAXED)),
        static_cast<unsigned long long>(__atomic_load_n(&g_hit_hotseat, __ATOMIC_RELAXED)),
        static_cast<unsigned long long>(__atomic_load_n(&g_hit_pref, __ATOMIC_RELAXED)),
        error);
    if (n <= 0) return;
    const size_t length = static_cast<size_t>(n) < sizeof(buffer)
            ? static_cast<size_t>(n) : sizeof(buffer) - 1;
    for (const char* path : kStatusPaths) {
        FILE* f = fopen(path, "we");
        if (f == nullptr) continue;
        fwrite(buffer, 1, length, f);
        fclose(f);
        chmod(path, 0600);
        break;
    }
}

int HookX() {
    __atomic_add_fetch(&g_hit_x, uint64_t{1}, __ATOMIC_RELAXED);
    return g_columns > 0 ? g_columns : (g_orig_x ? g_orig_x() : 0);
}

int HookY() {
    __atomic_add_fetch(&g_hit_y, uint64_t{1}, __ATOMIC_RELAXED);
    return g_rows > 0 ? g_rows : (g_orig_y ? g_orig_y() : 0);
}

int HookHotseat() {
    __atomic_add_fetch(&g_hit_hotseat, uint64_t{1}, __ATOMIC_RELAXED);
    return g_hotseat > 0 ? g_hotseat :
            (g_orig_hotseat ? g_orig_hotseat() : 0);
}

bool KeyEquals(const char* key, size_t length, const char* expected) {
    return key != nullptr && expected != nullptr &&
            length == strlen(expected) && memcmp(key, expected, length) == 0;
}

U128 HookPref(const char* key, size_t length) {
    __atomic_add_fetch(&g_hit_pref, uint64_t{1}, __ATOMIC_RELAXED);
    int value = 0;
    if (KeyEquals(key, length, "pref_key_cell_x")) value = g_columns;
    else if (KeyEquals(key, length, "pref_key_cell_y")) value = g_rows;
    else if (KeyEquals(key, length, "icon_size_scale") ||
             KeyEquals(key, length, "icon_size")) value = g_icon_size;
    if (value > 0) {
        return (static_cast<U128>(static_cast<uint32_t>(value)) << 64) |
               static_cast<U128>(1);
    }
    return g_orig_pref ? g_orig_pref(key, length) : static_cast<U128>(0);
}

bool InstallOne(uintptr_t address, void* replacement, void** backup,
                uintptr_t* slot, const char* name) {
    if (*slot != 0) return true;
    if (g_hook_func == nullptr || backup == nullptr) {
        LOGE("hook api unavailable for %s", name);
        return false;
    }
    *backup = nullptr;
    g_hook_func(reinterpret_cast<void*>(address), replacement, backup);
    if (*backup == nullptr) {
        LOGE("LSPosed hook failed %s backup=null", name);
        return false;
    }
    *slot = address;
    LOGI("LSPosed hook OK %s @ %p", name, reinterpret_cast<void*>(address));
    return true;
}

void UnhookOne(uintptr_t* slot, void** backup) {
    if (slot != nullptr && *slot != 0 && g_unhook_func != nullptr) {
        g_unhook_func(reinterpret_cast<void*>(*slot));
    }
    if (slot != nullptr) *slot = 0;
    if (backup != nullptr) *backup = nullptr;
}

bool ResolveTargets(const Image& image, uintptr_t* x, uintptr_t* y,
                    uintptr_t* hotseat, uintptr_t* pref) {
    CandidateSet xy{};
    CandidateSet hs{};
    CandidateSet pr{};

    for (ElfW(Half) i = 0; i < image.count; ++i) {
        const ElfW(Phdr)& ph = image.phdr[i];
        if (ph.p_type != PT_LOAD || (ph.p_flags & PF_X) == 0 ||
                (ph.p_flags & PF_R) == 0) continue;
        const uint8_t* memory = reinterpret_cast<const uint8_t*>(
                image.base + ph.p_vaddr);
        Scan(memory, ph.p_memsz, image.base + ph.p_vaddr,
             kPatternXY, sizeof(kPatternXY) / sizeof(kPatternXY[0]), &xy);
        Scan(memory, ph.p_memsz, image.base + ph.p_vaddr,
             kPatternHotseat, sizeof(kPatternHotseat) / sizeof(kPatternHotseat[0]), &hs);
        Scan(memory, ph.p_memsz, image.base + ph.p_vaddr,
             kPatternPref, sizeof(kPatternPref) / sizeof(kPatternPref[0]), &pr);
    }

    const uintptr_t hx = g_hint_x;
    const uintptr_t hy = g_hint_y;
    const uintptr_t hh = g_hint_hotseat;
    const uintptr_t hp = g_hint_pref;
    if (hx != 0 && hy != 0 && hh != 0 && hy > hx && hy - hx == kXYDistance &&
        Verify(image, image.base + hx, kPatternXY,
               sizeof(kPatternXY) / sizeof(kPatternXY[0])) &&
        Verify(image, image.base + hy, kPatternXY,
               sizeof(kPatternXY) / sizeof(kPatternXY[0])) &&
        Verify(image, image.base + hh, kPatternHotseat,
               sizeof(kPatternHotseat) / sizeof(kPatternHotseat[0]))) {
        *x = image.base + hx;
        *y = image.base + hy;
        *hotseat = image.base + hh;
        if (hp != 0 && Verify(image, image.base + hp, kPatternPref,
                sizeof(kPatternPref) / sizeof(kPatternPref[0]))) {
            *pref = image.base + hp;
        }
        return true;
    }

    if (xy.overflow || hs.overflow || pr.overflow || hs.count != 1) return false;
    size_t pair_count = 0;
    uintptr_t pair_x = 0;
    uintptr_t pair_y = 0;
    for (size_t a = 0; a < xy.count; ++a) {
        for (size_t b = 0; b < xy.count; ++b) {
            if (xy.values[b] > xy.values[a] &&
                    xy.values[b] - xy.values[a] == kXYDistance) {
                pair_x = xy.values[a];
                pair_y = xy.values[b];
                ++pair_count;
                if (pair_count > 1) return false;
            }
        }
    }
    if (pair_count != 1) return false;
    *x = pair_x;
    *y = pair_y;
    *hotseat = hs.values[0];
    if (pr.count == 1) *pref = pr.values[0];
    return true;
}

int InstallImage(const Image& image) {
    if (image.base == 0 || image.phdr == nullptr) return -10;
    if (__atomic_load_n(&g_install_state, __ATOMIC_ACQUIRE) == 2u) return 1;

    Lock();
    if (__atomic_load_n(&g_install_state, __ATOMIC_ACQUIRE) == 2u) {
        Unlock();
        return 1;
    }
    __atomic_store_n(&g_install_state, 1u, __ATOMIC_RELEASE);

    ReadConfig();
    if (g_columns == 0 && g_rows == 0 && g_hotseat == 0 &&
            g_icon_size == 0 && g_trace_prefs == 0) {
        __atomic_store_n(&g_install_state, 0u, __ATOMIC_RELEASE);
        WriteStatus("disabled", "none", image.base);
        Unlock();
        return 0;
    }

    uintptr_t x = 0;
    uintptr_t y = 0;
    uintptr_t hs = 0;
    uintptr_t pref = 0;
    if (!ResolveTargets(image, &x, &y, &hs, &pref)) {
        __atomic_store_n(&g_install_state, 3u, __ATOMIC_RELEASE);
        WriteStatus("resolver-failed", "locator_unresolved", image.base);
        Unlock();
        return -30;
    }

    bool ok = true;
    if ((g_columns > 0 || g_rows > 0 || g_icon_size > 0 || g_trace_prefs > 0) &&
            pref != 0) {
        ok = InstallOne(pref, reinterpret_cast<void*>(HookPref),
                        reinterpret_cast<void**>(&g_orig_pref), &g_hooked_pref,
                        "PreferenceUtils::get_int") && ok;
    }
    if (g_columns > 0) {
        ok = InstallOne(x, reinterpret_cast<void*>(HookX),
                        reinterpret_cast<void**>(&g_orig_x), &g_hooked_x,
                        "get_cell_count_x") && ok;
    }
    if (g_rows > 0) {
        ok = InstallOne(y, reinterpret_cast<void*>(HookY),
                        reinterpret_cast<void**>(&g_orig_y), &g_hooked_y,
                        "get_cell_count_y") && ok;
    }
    if (g_hotseat > 0) {
        ok = InstallOne(hs, reinterpret_cast<void*>(HookHotseat),
                        reinterpret_cast<void**>(&g_orig_hotseat),
                        &g_hooked_hotseat, "get_hotseat_max_count") && ok;
    }

    if (!ok) {
        UnhookOne(&g_hooked_hotseat, reinterpret_cast<void**>(&g_orig_hotseat));
        UnhookOne(&g_hooked_y, reinterpret_cast<void**>(&g_orig_y));
        UnhookOne(&g_hooked_x, reinterpret_cast<void**>(&g_orig_x));
        UnhookOne(&g_hooked_pref, reinterpret_cast<void**>(&g_orig_pref));
        __atomic_store_n(&g_install_state, 3u, __ATOMIC_RELEASE);
        WriteStatus("hook-failed", "hook_transaction_rolled_back", image.base);
        Unlock();
        return -50;
    }

    __atomic_store_n(&g_install_state, 2u, __ATOMIC_RELEASE);
    WriteStatus("hook-install-complete", "none", image.base);
    Unlock();
    return 1;
}

void TryInstallLauncher() {
    Image image{};
    dl_iterate_phdr(ImageCallback, &image);
    if (image.base != 0) {
        const int rc = InstallImage(image);
        LOGI("libapp_launcher install=%d", rc);
    }
}

void OnLibraryLoaded(const char* name, void*) {
    if (name == nullptr || !IsLauncherHyosProcess()) return;
    if (EndsWith(name, kLauncherName)) {
        LOGI("libapp_launcher callback received: %s", name);
        TryInstallLauncher();
    }
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
NativeOnLibraryLoaded native_init(const NativeApiEntries* entries) {
    if (entries == nullptr || entries->hook_func == nullptr ||
            entries->unhook_func == nullptr || !IsLauncherHyosProcess()) {
        LOGE("DGX_NATIVE_ENTRY rejected entries=%p", entries);
        return nullptr;
    }
    g_native_api_version = entries->version;
    g_hook_func = entries->hook_func;
    g_unhook_func = entries->unhook_func;
    LOGI("DGX_NATIVE_ENTRY api=%u hook=%p unhook=%p",
         entries->version,
         reinterpret_cast<void*>(entries->hook_func),
         reinterpret_cast<void*>(entries->unhook_func));
    WriteStatus("native-entry");
    TryInstallLauncher();
    return OnLibraryLoaded;
}
