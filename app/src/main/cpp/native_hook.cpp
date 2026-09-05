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
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>
#include <cerrno>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DesktopGridX", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DesktopGridX", __VA_ARGS__)

namespace {
constexpr uintptr_t XY_DISTANCE = 0xAC;
constexpr const char *RUNTIME_PATHS[] = {
    "/data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/data/com.miui.home/cache/desktopgridx-native-runtime.conf"
};

enum class InstallState : int {
    NOT_STARTED = 0,
    INSTALLING = 1,
    WAITING_LIBRARY = 2,
    INSTALLED = 3,
    FAILED_RETRYABLE = 4,
    FAILED_PERMANENT = 5
};

struct KnownRvaProfile {
    uintptr_t x, y, hotseat, prefGetInt;
    const char *label;
};
constexpr KnownRvaProfile KNOWN_RVA_PROFILES[] = {
    {0x61BCD0, 0x61BD7C, 0x61CE14, 0x632708, "launcher-1c8ad848"},
    {0x61E1F0, 0x61E29C, 0x61F334, 0x634C28, "launcher-433232fb"},
    {0x61EB2C, 0x61EBD8, 0x61FC70, 0x635564, "launcher-1477db56"}
};

std::atomic<int> g_cols{0}, g_rows{0}, g_hotseat{0}, g_icon_size{0}, g_trace_prefs{0};
std::atomic<uintptr_t> g_hint_x{0}, g_hint_y{0}, g_hint_hotseat{0}, g_hint_pref_get_int{0};
std::atomic<InstallState> g_install_state{InstallState::NOT_STARTED};
std::atomic<bool> g_callback_registered{false};
std::atomic<bool> g_native_entry_seen{false};
std::atomic<uintptr_t> g_runtime_base{0};
std::atomic<bool> g_runtime_before_init{false};
std::atomic<int> g_state_shadowhook{0}, g_state_pref_hook{0}, g_state_x_hook{0}, g_state_y_hook{0}, g_state_hotseat_hook{0};
std::atomic<uint64_t> g_hit_pref_x{0}, g_hit_pref_y{0}, g_hit_icon{0}, g_hit_getter_x{0}, g_hit_getter_y{0}, g_hit_hotseat{0};
std::atomic<bool> g_logged_cell_x{false}, g_logged_cell_y{false}, g_logged_icon_size{false};
std::mutex g_install_mutex;
std::mutex g_status_mutex;
std::string g_runtime_method="none";
std::string g_runtime_last_error="none";
std::string g_runtime_path;

using IntFn = int (*)();
IntFn orig_x=nullptr, orig_y=nullptr, orig_hotseat=nullptr;
void *stub_x=nullptr, *stub_y=nullptr, *stub_hotseat=nullptr;

using U128 = unsigned __int128;
using PreferenceGetIntFn = U128 (*)(const char*, size_t);
PreferenceGetIntFn orig_pref_get_int=nullptr;
void *stub_pref_get_int=nullptr;
static_assert(sizeof(U128)==16, "AArch64 Rust scalar-pair bridge requires 128-bit carrier");

const char *state_name(InstallState s) {
    switch(s) {
        case InstallState::NOT_STARTED: return "not_started";
        case InstallState::INSTALLING: return "installing";
        case InstallState::WAITING_LIBRARY: return "waiting_library";
        case InstallState::INSTALLED: return "installed";
        case InstallState::FAILED_RETRYABLE: return "failed_retryable";
        case InstallState::FAILED_PERMANENT: return "failed_permanent";
    }
    return "unknown";
}

bool ensure_parent(const char *path) {
    std::string p(path ? path : "");
    auto pos=p.rfind('/');
    if(pos==std::string::npos) return false;
    std::string dir=p.substr(0,pos);
    struct stat st{};
    return stat(dir.c_str(),&st)==0 && S_ISDIR(st.st_mode);
}

void write_runtime_status_locked(const char *stage) {
    std::ostringstream o;
    o<<"version=0.9.0\n"
     <<"pid="<<getpid()<<"\n"
     <<"stage="<<(stage?stage:"unknown")<<"\n"
     <<"install_state="<<state_name(g_install_state.load())<<"\n"
     <<"native_entry_seen="<<(g_native_entry_seen.load()?1:0)<<"\n"
     <<"native_loaded=1\n"
     <<"columns="<<g_cols.load()<<"\nrows="<<g_rows.load()<<"\nhotseat="<<g_hotseat.load()
     <<"\niconSize="<<g_icon_size.load()<<"\ntracePrefs="<<g_trace_prefs.load()<<"\n"
     <<"launcher_base=0x"<<std::hex<<g_runtime_base.load()<<std::dec<<"\n"
     <<"resolver="<<g_runtime_method<<"\n"
     <<"before_init="<<(g_runtime_before_init.load()?1:0)<<"\n"
     <<"shadowhook_init="<<g_state_shadowhook.load()<<"\n"
     <<"preference_hook_installed="<<g_state_pref_hook.load()<<"\n"
     <<"x_hook_installed="<<g_state_x_hook.load()<<"\n"
     <<"y_hook_installed="<<g_state_y_hook.load()<<"\n"
     <<"hotseat_hook_installed="<<g_state_hotseat_hook.load()<<"\n"
     <<"pref_cell_x_hits="<<g_hit_pref_x.load()<<"\npref_cell_y_hits="<<g_hit_pref_y.load()
     <<"\nicon_pref_hits="<<g_hit_icon.load()<<"\ngetter_x_hits="<<g_hit_getter_x.load()
     <<"\ngetter_y_hits="<<g_hit_getter_y.load()<<"\nhotseat_hits="<<g_hit_hotseat.load()<<"\n"
     <<"last_error="<<g_runtime_last_error<<"\n";
    const std::string data=o.str();

    auto try_write=[&](const char *path)->bool {
        if(!ensure_parent(path)) return false;
        std::string tmp=std::string(path)+"."+std::to_string(getpid())+".tmp";
        FILE *f=fopen(tmp.c_str(),"we");
        if(!f) return false;
        size_t n=fwrite(data.data(),1,data.size(),f);
        fflush(f);
        int fd=fileno(f); if(fd>=0) fsync(fd);
        fclose(f);
        if(n!=data.size()) { unlink(tmp.c_str()); return false; }
        if(rename(tmp.c_str(),path)!=0) { unlink(tmp.c_str()); return false; }
        chmod(path,0600);
        g_runtime_path=path;
        return true;
    };

    if(!g_runtime_path.empty() && try_write(g_runtime_path.c_str())) return;
    for(const char *p:RUNTIME_PATHS) if(try_write(p)) return;
}

void write_runtime_status(const char *stage) {
    std::lock_guard<std::mutex> lock(g_status_mutex);
    write_runtime_status_locked(stage);
}

void set_error(const char *error, const char *stage) {
    {
        std::lock_guard<std::mutex> lock(g_status_mutex);
        g_runtime_last_error=error?error:"unknown";
        write_runtime_status_locked(stage);
    }
    LOGE("%s stage=%s", error?error:"unknown", stage?stage:"unknown");
}

inline void runtime_hit(std::atomic<uint64_t> &counter,const char *stage) {
    uint64_t n=counter.fetch_add(1)+1;
    if(n==1 || n==10 || n==100 || n==1000) write_runtime_status(stage);
}

void reset_config_values() {
    g_cols.store(0); g_rows.store(0); g_hotseat.store(0); g_icon_size.store(0); g_trace_prefs.store(0);
    g_hint_x.store(0); g_hint_y.store(0); g_hint_hotseat.store(0); g_hint_pref_get_int.store(0);
}

void read_config() {
    reset_config_values();
    char line[256];
    FILE *f=fopen("/data/adb/desktopgridx/config.conf","re");
    if(f) {
        while(fgets(line,sizeof(line),f)) {
            int v=0;
            if(sscanf(line,"columns=%d",&v)==1) g_cols.store(v);
            else if(sscanf(line,"rows=%d",&v)==1) g_rows.store(v);
            else if(sscanf(line,"hotseat=%d",&v)==1) g_hotseat.store(v);
            else if(sscanf(line,"iconSize=%d",&v)==1) g_icon_size.store(v);
            else if(sscanf(line,"tracePrefs=%d",&v)==1) g_trace_prefs.store(v);
        }
        fclose(f);
    } else LOGI("config unavailable: %s", strerror(errno));

    FILE *rf=fopen("/data/adb/desktopgridx/resolved.conf","re");
    if(rf) {
        while(fgets(line,sizeof(line),rf)) {
            unsigned long long v=0;
            if(sscanf(line,"resolved_x=0x%llx",&v)==1) g_hint_x.store((uintptr_t)v);
            else if(sscanf(line,"resolved_y=0x%llx",&v)==1) g_hint_y.store((uintptr_t)v);
            else if(sscanf(line,"resolved_hotseat=0x%llx",&v)==1) g_hint_hotseat.store((uintptr_t)v);
            else if(sscanf(line,"resolved_preference_get_int=0x%llx",&v)==1) g_hint_pref_get_int.store((uintptr_t)v);
        }
        fclose(rf);
    }
    LOGI("config x=%d y=%d hs=%d icon=%d trace=%d hints=%p/%p/%p pref=%p",
         g_cols.load(),g_rows.load(),g_hotseat.load(),g_icon_size.load(),g_trace_prefs.load(),
         (void*)g_hint_x.load(),(void*)g_hint_y.load(),(void*)g_hint_hotseat.load(),(void*)g_hint_pref_get_int.load());
}

int hook_x() {
    int v=g_cols.load();
    if(v>0) return v;
    if(orig_x) return orig_x();
    LOGE("hook_x called without override/original");
    return 0;
}
int hook_y() {
    int v=g_rows.load();
    if(v>0) return v;
    if(orig_y) return orig_y();
    LOGE("hook_y called without override/original");
    return 0;
}
int hook_hotseat() {
    int v=g_hotseat.load();
    if(v>0) return v;
    if(orig_hotseat) return orig_hotseat();
    LOGE("hook_hotseat called without override/original");
    return 0;
}

bool keyeq(const char *key,size_t len,const char *lit) {
    size_t n=strlen(lit);
    return key && len==n && memcmp(key,lit,n)==0;
}

U128 hook_preference_get_int(const char *key,size_t len) {
    int v=0;
    std::atomic<bool> *logged=nullptr;
    if(keyeq(key,len,"pref_key_cell_x")) { v=g_cols.load(); logged=&g_logged_cell_x; }
    else if(keyeq(key,len,"pref_key_cell_y")) { v=g_rows.load(); logged=&g_logged_cell_y; }
    else if(keyeq(key,len,"icon_size_scale") || keyeq(key,len,"icon_size")) { v=g_icon_size.load(); logged=&g_logged_icon_size; }

    if(v>0) {
        if(logged && !logged->exchange(true)) LOGI("preference override %.*s=%d",(int)len,key,v);
        return (static_cast<U128>(static_cast<uint32_t>(v)) << 64) | static_cast<U128>(1);
    }
    if(!orig_pref_get_int) {
        LOGE("preference proxy reached without original trampoline");
        return static_cast<U128>(0);
    }
    U128 result=orig_pref_get_int(key,len);
    if(g_trace_prefs.load() && key && len>0 && len<=80) {
        static std::atomic<uint32_t> trace_count{0};
        uint32_t n=trace_count.fetch_add(1);
        if(n<400) {
            uint64_t low=(uint64_t)result, high=(uint64_t)(result>>64);
            LOGI("pref-trace key=%.*s present=%u value=%d",(int)len,key,(unsigned)(low&1u),(int32_t)(high&0xffffffffu));
        }
    }
    return result;
}

int hook_x_status(){ runtime_hit(g_hit_getter_x,"getter-x-hit"); return hook_x(); }
int hook_y_status(){ runtime_hit(g_hit_getter_y,"getter-y-hit"); return hook_y(); }
int hook_hotseat_status(){ runtime_hit(g_hit_hotseat,"hotseat-hit"); return hook_hotseat(); }
U128 hook_preference_get_int_status(const char *key,size_t len) {
    if(key) {
        if(keyeq(key,len,"pref_key_cell_x")) runtime_hit(g_hit_pref_x,"pref-cell-x-hit");
        else if(keyeq(key,len,"pref_key_cell_y")) runtime_hit(g_hit_pref_y,"pref-cell-y-hit");
        else if(keyeq(key,len,"icon_size_scale") || keyeq(key,len,"icon_size")) runtime_hit(g_hit_icon,"icon-pref-hit");
    }
    return hook_preference_get_int(key,len);
}

struct PatternWord { uint32_t value, mask; };
constexpr PatternWord PAT_XY[] = {
    {0xd10083ff,0xffffffff},{0xa9017bfd,0xffffffff},{0x910043fd,0xffffffff},{0xd00042c8,0x9f00001f}
};
constexpr PatternWord PAT_PREF_GET_INT[] = {
    {0xd10203ff,0xffffffff},{0xa9057bfd,0xffffffff},{0xa90657f6,0xffffffff},{0xa9074ff4,0xffffffff},
    {0x910143fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xaa0103f3,0xffffffff},
    {0xc8dffd08,0xffffffff},{0xaa0003f4,0xffffffff},{0xf100091f,0xffffffff}
};
constexpr PatternWord PAT_HS[] = {
    {0xa9bf7bfd,0xffffffff},{0x910003fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},
    {0xc8dffd08,0xffffffff},{0xf100091f,0xffffffff},{0x54000121,0xffffffff},{0x90000008,0x9f00001f},
    {0xf9400100,0xffc003ff},{0x94000000,0xfc000000},{0x52800088,0xffffffff},{0x7100001f,0xffffffff},
    {0x1a881500,0xffffffff},{0xa8c17bfd,0xffffffff},{0xd65f03c0,0xffffffff}
};

bool match_pattern(const uint8_t *p,const PatternWord *pat,size_t n) {
    for(size_t i=0;i<n;i++) { uint32_t w; memcpy(&w,p+i*4,4); if((w&pat[i].mask)!=(pat[i].value&pat[i].mask)) return false; }
    return true;
}
std::vector<uintptr_t> scan_range(const uint8_t *start,size_t len,uintptr_t logical,const PatternWord *pat,size_t n) {
    std::vector<uintptr_t> out; size_t bytes=n*4; if(len<bytes) return out;
    for(size_t off=0;off+bytes<=len;off+=4) if(match_pattern(start+off,pat,n)) out.push_back(logical+off);
    return out;
}

struct LoadedImage { uintptr_t base=0; const ElfW(Phdr)* phdr=nullptr; ElfW(Half) phnum=0; };
int phdr_cb(dl_phdr_info *info,size_t,void *data) {
    if(info && info->dlpi_name && strstr(info->dlpi_name,"libapp_launcher.so")) {
        auto *d=(LoadedImage*)data; d->base=(uintptr_t)info->dlpi_addr; d->phdr=info->dlpi_phdr; d->phnum=info->dlpi_phnum; return 1;
    }
    return 0;
}
bool runtime_match_safe(const LoadedImage &img,uintptr_t addr,const PatternWord *pat,size_t n) {
    size_t need=n*4;
    for(ElfW(Half)i=0;i<img.phnum;i++) {
        const auto &p=img.phdr[i]; if(p.p_type!=PT_LOAD || !(p.p_flags&PF_X) || !(p.p_flags&PF_R)) continue;
        uintptr_t begin=img.base+p.p_vaddr,end=begin+p.p_memsz;
        if(addr>=begin && addr<=end && need<=(size_t)(end-addr)) return match_pattern((const uint8_t*)addr,pat,n);
    }
    return false;
}

struct ScanResult {
    uintptr_t x=0,y=0,hotseat=0,prefGetInt=0;
    std::vector<uintptr_t> xy,hs,pref;
    std::string method="unresolved";
    bool safe=false;
    bool prefAbiVerified=false;
};

ScanResult resolve_runtime(const LoadedImage &img) {
    ScanResult r;
    for(ElfW(Half)i=0;i<img.phnum;i++) {
        const auto &p=img.phdr[i]; if(p.p_type!=PT_LOAD || !(p.p_flags&PF_X) || !(p.p_flags&PF_R)) continue;
        auto *mem=(const uint8_t*)(img.base+p.p_vaddr);
        auto a=scan_range(mem,p.p_memsz,img.base+p.p_vaddr,PAT_XY,std::size(PAT_XY));
        auto b=scan_range(mem,p.p_memsz,img.base+p.p_vaddr,PAT_HS,std::size(PAT_HS));
        auto c=scan_range(mem,p.p_memsz,img.base+p.p_vaddr,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT));
        r.xy.insert(r.xy.end(),a.begin(),a.end()); r.hs.insert(r.hs.end(),b.begin(),b.end()); r.pref.insert(r.pref.end(),c.begin(),c.end());
    }

    uintptr_t hx=g_hint_x.load(),hy=g_hint_y.load(),hh=g_hint_hotseat.load(),hp=g_hint_pref_get_int.load();
    if(hx && hy && hh && hy>hx && hy-hx==XY_DISTANCE &&
       runtime_match_safe(img,img.base+hx,PAT_XY,std::size(PAT_XY)) &&
       runtime_match_safe(img,img.base+hy,PAT_XY,std::size(PAT_XY)) &&
       runtime_match_safe(img,img.base+hh,PAT_HS,std::size(PAT_HS))) {
        r.x=img.base+hx; r.y=img.base+hy; r.hotseat=img.base+hh;
        if(hp && runtime_match_safe(img,img.base+hp,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT))) r.prefGetInt=img.base+hp;
        else if(r.pref.size()==1) r.prefGetInt=r.pref[0];
        r.prefAbiVerified=r.prefGetInt!=0; r.safe=true;
        r.method=r.prefAbiVerified?"gnu-debugdata+live-verify+pref-abi":"gnu-debugdata+live-verify+getter";
        return r;
    }

    std::vector<std::pair<uintptr_t,uintptr_t>> pairs;
    for(auto a:r.xy) for(auto b:r.xy) if(b>a && b-a==XY_DISTANCE) pairs.emplace_back(a,b);
    if(pairs.size()==1 && r.hs.size()==1) {
        r.x=pairs[0].first; r.y=pairs[0].second; r.hotseat=r.hs[0];
        if(r.pref.size()==1) r.prefGetInt=r.pref[0];
        r.prefAbiVerified=r.prefGetInt!=0; r.safe=true;
        r.method=r.prefAbiVerified?"runtime-pattern+pref-abi":"runtime-pattern+getter";
        return r;
    }

    for(const auto &k:KNOWN_RVA_PROFILES) {
        uintptr_t ax=img.base+k.x,ay=img.base+k.y,ah=img.base+k.hotseat,ap=img.base+k.prefGetInt;
        if(runtime_match_safe(img,ax,PAT_XY,std::size(PAT_XY)) && runtime_match_safe(img,ay,PAT_XY,std::size(PAT_XY)) &&
           ay>ax && ay-ax==XY_DISTANCE && runtime_match_safe(img,ah,PAT_HS,std::size(PAT_HS))) {
            r.x=ax;r.y=ay;r.hotseat=ah;
            if(runtime_match_safe(img,ap,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT))) r.prefGetInt=ap;
            r.prefAbiVerified=r.prefGetInt!=0; r.safe=true;
            r.method=std::string("verified-rva-profile:")+k.label+(r.prefAbiVerified?"+pref-abi":"+getter");
            return r;
        }
    }
    return r;
}

std::string hexv(uintptr_t v){ std::ostringstream o;o<<"0x"<<std::hex<<v;return o.str(); }
std::string format_scan(const ScanResult &r,uintptr_t base) {
    std::ostringstream o;
    o<<"method="<<r.method<<"\nsafe="<<(r.safe?"true":"false")<<"\nbase="<<hexv(base)<<"\n"
     <<"xy_candidate_count="<<r.xy.size()<<"\nhotseat_candidate_count="<<r.hs.size()<<"\npreference_get_int_candidate_count="<<r.pref.size()<<"\n"
     <<"preference_abi_verified="<<(r.prefAbiVerified?"true":"false")<<"\n";
    if(r.safe) {
        o<<"cell_count_x_rva="<<hexv(r.x-base)<<"\ncell_count_y_rva="<<hexv(r.y-base)<<"\nhotseat_rva="<<hexv(r.hotseat-base)<<"\n";
        if(r.prefGetInt) o<<"preference_get_int_rva="<<hexv(r.prefGetInt-base)<<"\n";
    }
    return o.str();
}

bool install_one(uintptr_t addr,void *proxy,void **orig,void **stub,const char *name) {
    if(*stub) return true;
    *stub=shadowhook_hook_func_addr_2((void*)addr,proxy,orig,SHADOWHOOK_HOOK_WITH_UNIQUE_MODE|SHADOWHOOK_HOOK_RECORD,"libapp_launcher.so",name);
    if(!*stub || !*orig) {
        int e=shadowhook_get_errno(); LOGE("hook %s failed: %d %s orig=%p",name,e,shadowhook_to_errmsg(e),orig?*orig:nullptr);
        if(*stub) { shadowhook_unhook(*stub); *stub=nullptr; }
        if(orig) *orig=nullptr;
        return false;
    }
    LOGI("hook %s OK @ %p",name,(void*)addr); return true;
}

void unhook_one(void **stub,void **orig,const char *name) {
    if(stub && *stub) {
        int rc=shadowhook_unhook(*stub);
        if(rc!=0) LOGE("rollback unhook %s failed: %d %s",name,shadowhook_get_errno(),shadowhook_to_errmsg(shadowhook_get_errno()));
        else LOGI("rollback unhook %s OK",name);
        *stub=nullptr;
    }
    if(orig) *orig=nullptr;
}

void rollback_transaction(bool prefNew,bool xNew,bool yNew,bool hsNew) {
    if(hsNew) unhook_one(&stub_hotseat,(void**)&orig_hotseat,"hotseat");
    if(yNew) unhook_one(&stub_y,(void**)&orig_y,"cell_y");
    if(xNew) unhook_one(&stub_x,(void**)&orig_x,"cell_x");
    if(prefNew) unhook_one(&stub_pref_get_int,(void**)&orig_pref_get_int,"PreferenceUtils::get_int");
    if(prefNew) g_state_pref_hook.store(0); if(xNew) g_state_x_hook.store(0); if(yNew) g_state_y_hook.store(0); if(hsNew) g_state_hotseat_hook.store(0);
}

int hook_loaded_image(const LoadedImage &img,bool before_init) {
    std::lock_guard<std::mutex> installLock(g_install_mutex);
    InstallState st=g_install_state.load();
    if(st==InstallState::INSTALLED) return 1;
    if(st==InstallState::INSTALLING) return 2;
    g_install_state.store(InstallState::INSTALLING);
    g_runtime_base.store(img.base); g_runtime_before_init.store(before_init);
    write_runtime_status("locator-start");

    ScanResult found=resolve_runtime(img);
    {
        std::lock_guard<std::mutex> s(g_status_mutex); g_runtime_method=found.method; g_runtime_last_error="none";
    }
    LOGI("auto locator:\n%s",format_scan(found,img.base).c_str());
    if(!found.safe) {
        g_install_state.store(InstallState::FAILED_PERMANENT); set_error("locator_unresolved","locator-failed"); return -30;
    }

    bool prefNew=false,xNew=false,yNew=false,hsNew=false;
    bool ok=true,up=false;
    const bool wantsPref=(g_cols.load()>0||g_rows.load()>0||g_icon_size.load()>0||g_trace_prefs.load());
    if(found.prefGetInt && found.prefAbiVerified && wantsPref) {
        prefNew=(stub_pref_get_int==nullptr);
        up=install_one(found.prefGetInt,(void*)hook_preference_get_int_status,(void**)&orig_pref_get_int,&stub_pref_get_int,"PreferenceUtils::get_int");
        g_state_pref_hook.store(up?1:-1); ok &= up;
    } else g_state_pref_hook.store(0);

    const bool needGetterFallback=!up || !before_init;
    if(needGetterFallback && g_cols.load()>0) {
        xNew=(stub_x==nullptr); bool v=install_one(found.x,(void*)hook_x_status,(void**)&orig_x,&stub_x,"DeviceConfigs::get_cell_count_x"); g_state_x_hook.store(v?1:-1); ok&=v;
    }
    if(needGetterFallback && g_rows.load()>0) {
        yNew=(stub_y==nullptr); bool v=install_one(found.y,(void*)hook_y_status,(void**)&orig_y,&stub_y,"DeviceConfigs::get_cell_count_y"); g_state_y_hook.store(v?1:-1); ok&=v;
    }
    if(g_hotseat.load()>0) {
        hsNew=(stub_hotseat==nullptr); bool v=install_one(found.hotseat,(void*)hook_hotseat_status,(void**)&orig_hotseat,&stub_hotseat,"DeviceConfigs::get_hotseat_max_count"); g_state_hotseat_hook.store(v?1:-1); ok&=v;
    }

    if(!ok) {
        rollback_transaction(prefNew,xNew,yNew,hsNew);
        g_install_state.store(InstallState::FAILED_RETRYABLE); set_error("hook_transaction_rolled_back","hook-install-failed"); return -50;
    }
    g_install_state.store(InstallState::INSTALLED);
    { std::lock_guard<std::mutex> s(g_status_mutex); g_runtime_last_error="none"; }
    write_runtime_status("hook-install-complete");
    return 1;
}

void launcher_dl_pre(dl_phdr_info *info,size_t,void*) {
    if(!info || !info->dlpi_name || !strstr(info->dlpi_name,"libapp_launcher.so")) return;
    LoadedImage img{(uintptr_t)info->dlpi_addr,info->dlpi_phdr,info->dlpi_phnum};
    int rc=hook_loaded_image(img,true);
    LOGI("ShadowHook pre-init callback install result=%d",rc);
    if((rc==1 || rc==-30) && g_callback_registered.exchange(false)) shadowhook_unregister_dl_init_callback(launcher_dl_pre,nullptr,nullptr);
}

int install_impl() {
#if !defined(__aarch64__)
    g_install_state.store(InstallState::FAILED_PERMANENT); set_error("unsupported_abi","unsupported-abi"); return -10;
#endif
    InstallState state=g_install_state.load();
    if(state==InstallState::INSTALLED) return 1;
    read_config();
    write_runtime_status("config-read");
    if(g_cols.load()==0&&g_rows.load()==0&&g_hotseat.load()==0&&g_icon_size.load()==0&&!g_trace_prefs.load()) {
        g_install_state.store(InstallState::NOT_STARTED); write_runtime_status("all-overrides-disabled"); return 0;
    }

    int rc=shadowhook_init(SHADOWHOOK_MODE_UNIQUE,true);
    if(rc!=SHADOWHOOK_ERRNO_OK) {
        g_state_shadowhook.store(-1); g_install_state.store(InstallState::FAILED_RETRYABLE); set_error("shadowhook_init_failed","shadowhook-init-failed"); return -40;
    }
    g_state_shadowhook.store(1); shadowhook_set_recordable(true); write_runtime_status("shadowhook-ready");

    LoadedImage img; dl_iterate_phdr(phdr_cb,&img);
    if(img.base) return hook_loaded_image(img,false);

    if(!g_callback_registered.exchange(true)) {
        int cr=shadowhook_register_dl_init_callback(launcher_dl_pre,nullptr,nullptr);
        if(cr!=0) {
            g_callback_registered.store(false); g_install_state.store(InstallState::FAILED_RETRYABLE); set_error("dl_init_callback_failed","dl-init-callback-failed"); return -21;
        }
    }
    g_install_state.store(InstallState::WAITING_LIBRARY); write_runtime_status("waiting-for-libapp-launcher");
    return 2;
}

std::string analyze_elf_file(const char *path) {
    int fd=open(path,O_RDONLY|O_CLOEXEC); if(fd<0) return std::string("error=open_failed errno=")+std::to_string(errno)+"\n";
    struct stat st{}; if(fstat(fd,&st)!=0||st.st_size<(off_t)sizeof(Elf64_Ehdr)){close(fd);return "error=bad_file\n";}
    void *map=mmap(nullptr,st.st_size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(map==MAP_FAILED)return "error=mmap_failed\n";
    auto *base=(const uint8_t*)map; auto *eh=(const Elf64_Ehdr*)base; std::ostringstream out;
    if(memcmp(eh->e_ident,ELFMAG,SELFMAG)!=0||eh->e_ident[EI_CLASS]!=ELFCLASS64||eh->e_machine!=EM_AARCH64){munmap(map,st.st_size);return "error=not_arm64_elf\n";}
    std::vector<uintptr_t> xy,hs,pref;
    if(eh->e_phoff+(uint64_t)eh->e_phnum*eh->e_phentsize<=(uint64_t)st.st_size) for(uint16_t i=0;i<eh->e_phnum;i++) {
        auto *p=(const Elf64_Phdr*)(base+eh->e_phoff+(uint64_t)i*eh->e_phentsize);
        if(p->p_type!=PT_LOAD||!(p->p_flags&PF_X)||p->p_offset+p->p_filesz>(uint64_t)st.st_size)continue;
        auto a=scan_range(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_XY,std::size(PAT_XY));
        auto b=scan_range(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_HS,std::size(PAT_HS));
        auto c=scan_range(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT));
        xy.insert(xy.end(),a.begin(),a.end());hs.insert(hs.end(),b.begin(),b.end());pref.insert(pref.end(),c.begin(),c.end());
    }
    std::vector<std::pair<uintptr_t,uintptr_t>> pairs;for(auto a:xy)for(auto b:xy)if(b>a&&b-a==XY_DISTANCE)pairs.emplace_back(a,b);
    out<<"file="<<path<<"\nsize="<<st.st_size<<"\narch=aarch64\nxy_candidate_count="<<xy.size()<<"\nhotseat_candidate_count="<<hs.size()<<"\npreference_get_int_candidate_count="<<pref.size()<<"\n";
    if(pairs.size()==1&&hs.size()==1){out<<"safe=true\nmethod=file-pattern+relation\ncell_count_x_rva="<<hexv(pairs[0].first)<<"\ncell_count_y_rva="<<hexv(pairs[0].second)<<"\nhotseat_rva="<<hexv(hs[0])<<"\n";if(pref.size()==1)out<<"preference_get_int_rva="<<hexv(pref[0])<<"\npreference_abi_verified=true\n";}
    else out<<"safe=false\nmethod=unresolved\npair_count="<<pairs.size()<<"\n";
    munmap(map,st.st_size);return out.str();
}

} // namespace

// Modern LSPosed native entry. The library name is declared in META-INF/xposed/native_init.list.
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct { uint32_t version; HookFunType hook_func; UnhookFunType unhook_func; } NativeAPIEntries;

static void dgx_native_library_loaded(const char *name,void*) {
    if(name && strstr(name,"libapp_launcher.so")) {
        LOGI("LSPosed native callback observed %s",name);
        // Post-load fallback. install_impl() detects the already-loaded image and installs safely.
        install_impl();
    }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    (void)entries;
    g_native_entry_seen.store(true);
    LOGI("DGX_NATIVE_ENTRY api=%u", entries?entries->version:0);
    write_runtime_status("native-entry");
    int rc=install_impl();
    LOGI("DGX_NATIVE_ENTRY install=%d",rc);
    return dgx_native_library_loaded;
}

extern "C" JNIEXPORT jint JNICALL Java_com_yagay_desktopgridx_NativeBridge_install(JNIEnv*,jclass){ return install_impl(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_yagay_desktopgridx_NativeBridge_analyzeElf(JNIEnv *env,jclass,jstring path){const char *p=env->GetStringUTFChars(path,nullptr);std::string r=analyze_elf_file(p?p:"");if(p)env->ReleaseStringUTFChars(path,p);return env->NewStringUTF(r.c_str());}
