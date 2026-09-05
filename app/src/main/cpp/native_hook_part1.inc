#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>
#include <link.h>
#include <elf.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>
#include <iterator>
#include <cerrno>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DesktopGridX", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DesktopGridX", __VA_ARGS__)

namespace {
constexpr uintptr_t XY_DISTANCE = 0xAC; // observed in both analyzed HyperOS 4 builds; only an auxiliary relation.
struct KnownOffsets { uintptr_t x,y,hotseat,prefGetInt; const char *label; };
constexpr KnownOffsets KNOWN_BUILDS[] = {
    {0x61BCD0, 0x61BD7C, 0x61CE14, 0x632708, "launcher-sha1c8ad848"},
    {0x61E1F0, 0x61E29C, 0x61F334, 0x634C28, "launcher-sha433232fb"}
};
std::atomic<uintptr_t> g_hint_x{0}, g_hint_y{0}, g_hint_hotseat{0}, g_hint_pref_get_int{0};

std::atomic<int> g_cols{0}, g_rows{0}, g_hotseat{0}, g_icon_size{0}, g_trace_prefs{0};
using IntFn = int (*)();
IntFn orig_x = nullptr, orig_y = nullptr, orig_hotseat = nullptr;
void *stub_x=nullptr, *stub_y=nullptr, *stub_hotseat=nullptr;
using U128 = unsigned __int128;
using PreferenceGetIntFn = U128 (*)(const char*, size_t);
PreferenceGetIntFn orig_pref_get_int = nullptr;
void *stub_pref_get_int = nullptr;
std::atomic<bool> g_upstream_active{false};
std::atomic<bool> g_logged_cell_x{false}, g_logged_cell_y{false}, g_logged_icon_size{false};
static_assert(sizeof(U128)==16, "AArch64 Rust Option<i32> bridge requires 128-bit return");

int hook_x(){ int v=g_cols.load(); return v>0 ? v : (orig_x?orig_x():5); }
int hook_y(){ int v=g_rows.load(); return v>0 ? v : (orig_y?orig_y():-1); }
int hook_hotseat(){ int v=g_hotseat.load(); return v>0 ? v : (orig_hotseat?orig_hotseat():5); }

U128 hook_preference_get_int(const char *key, size_t len){
    auto keyeq=[&](const char *lit)->bool{
        const size_t n=strlen(lit);
        return key && len==n && memcmp(key,lit,n)==0;
    };

    int v=0;
    std::atomic<bool> *logged=nullptr;
    if(keyeq("pref_key_cell_x")) { v=g_cols.load(); logged=&g_logged_cell_x; }
    else if(keyeq("pref_key_cell_y")) { v=g_rows.load(); logged=&g_logged_cell_y; }
    // icon_size_scale is also written through Settings.System by the module app. This upstream
    // preference override is intentionally only a compatibility assist for Launcher code paths
    // that mirror the same key through PreferenceUtils. Unknown keys always pass through untouched.
    else if(keyeq("icon_size_scale") || keyeq("icon_size")) { v=g_icon_size.load(); logged=&g_logged_icon_size; }

    if(v>0){
        if(logged && !logged->exchange(true))
            LOGI("upstream preference override %.*s=%d", (int)len, key, v);
        // Verified from the supplied launcher machine code: get_int returns w0=present flag and
        // w1=i32 value. Clang's AArch64 ABI returns unsigned __int128 in x0/x1, mapping to that
        // observed scalar-pair ABI (low x0=1, low x1=value).
        return (static_cast<U128>(static_cast<uint32_t>(v)) << 64) | static_cast<U128>(1);
    }

    U128 result = orig_pref_get_int ? orig_pref_get_int(key,len) : static_cast<U128>(0);
    if(g_trace_prefs.load() && key && len>0 && len<=80){
        static std::atomic<uint32_t> trace_count{0};
        uint32_t n=trace_count.fetch_add(1);
        if(n<400){
            uint64_t low=static_cast<uint64_t>(result);
            uint64_t high=static_cast<uint64_t>(result>>64);
            LOGI("pref-trace key=%.*s present=%u value=%d", (int)len,key,
                 static_cast<unsigned>(low&1u), static_cast<int32_t>(high&0xffffffffu));
        }
    }
    return result;
}

void read_config(){
    FILE *f=fopen("/data/adb/desktopgridx/config.conf","re");
    char line[128];
    if(f){
        while(fgets(line,sizeof(line),f)){
            int v=0;
            if(sscanf(line,"columns=%d",&v)==1) g_cols.store(v);
            else if(sscanf(line,"rows=%d",&v)==1) g_rows.store(v);
            else if(sscanf(line,"hotseat=%d",&v)==1) g_hotseat.store(v);
            else if(sscanf(line,"iconSize=%d",&v)==1) g_icon_size.store(v);
            else if(sscanf(line,"tracePrefs=%d",&v)==1) g_trace_prefs.store(v);
        }
        fclose(f);
    } else LOGI("no config, default layout kept");

    FILE *rf=fopen("/data/adb/desktopgridx/resolved.conf","re");
    if(rf){
        while(fgets(line,sizeof(line),rf)){
            unsigned long long v=0;
            if(sscanf(line,"resolved_x=0x%llx",&v)==1) g_hint_x.store((uintptr_t)v);
            else if(sscanf(line,"resolved_y=0x%llx",&v)==1) g_hint_y.store((uintptr_t)v);
            else if(sscanf(line,"resolved_hotseat=0x%llx",&v)==1) g_hint_hotseat.store((uintptr_t)v);
            else if(sscanf(line,"resolved_preference_get_int=0x%llx",&v)==1) g_hint_pref_get_int.store((uintptr_t)v);
        }
        fclose(rf);
    }
    LOGI("config columns=%d rows=%d hotseat=%d iconSize=%d tracePrefs=%d hints=%p/%p/%p prefGetInt=%p",
         g_cols.load(),g_rows.load(),g_hotseat.load(),g_icon_size.load(),g_trace_prefs.load(),
         (void*)g_hint_x.load(),(void*)g_hint_y.load(),(void*)g_hint_hotseat.load(),(void*)g_hint_pref_get_int.load());
}

struct PatternWord { uint32_t value; uint32_t mask; };
// ADRP immediates move between builds, so only verify the opcode/register bits there.
constexpr PatternWord PAT_XY[] = {
    {0xd10083ff, 0xffffffff}, // sub sp, sp, #0x20
    {0xa9017bfd, 0xffffffff},
    {0x910043fd, 0xffffffff},
    {0xd00042c8, 0x9f00001f}  // adrp x8, ... (ignore page immediate)
};

constexpr PatternWord PAT_PREF_GET_INT[] = {
    {0xd10203ff, 0xffffffff}, // sub sp,sp,#0x80
    {0xa9057bfd, 0xffffffff},
    {0xa90657f6, 0xffffffff},
    {0xa9074ff4, 0xffffffff},
    {0x910143fd, 0xffffffff},
    {0x90000008, 0x9f00001f}, // adrp x8,...
    {0x91000108, 0xffc003ff}, // add x8,x8,#imm12
    {0xaa0103f3, 0xffffffff}, // mov x19,x1 (str len)
    {0xc8dffd08, 0xffffffff}, // ldar x8,[x8]
    {0xaa0003f4, 0xffffffff}, // mov x20,x0 (str ptr)
    {0xf100091f, 0xffffffff}  // cmp x8,#2
};

constexpr PatternWord PAT_HS[] = {
    {0xa9bf7bfd, 0xffffffff}, // stp x29,x30,[sp,#-16]!
    {0x910003fd, 0xffffffff}, // mov x29,sp
    {0x90000008, 0x9f00001f}, // adrp x8, page
    {0x91000108, 0xffc003ff}, // add x8,x8,#imm12
    {0xc8dffd08, 0xffffffff},
    {0xf100091f, 0xffffffff},
    {0x54000121, 0xffffffff},
