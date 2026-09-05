    {0x90000008, 0x9f00001f}, // second adrp x8
    {0xf9400100, 0xffc003ff}, // ldr x0,[x8,#imm12]
    {0x94000000, 0xfc000000}, // bl <target>
    {0x52800088, 0xffffffff},
    {0x7100001f, 0xffffffff},
    {0x1a881500, 0xffffffff},
    {0xa8c17bfd, 0xffffffff},
    {0xd65f03c0, 0xffffffff}
};

bool match_pattern(const uint8_t *p, const PatternWord *pat, size_t n){
    for(size_t i=0;i<n;i++){
        uint32_t w; memcpy(&w,p+i*4,sizeof(w));
        if((w & pat[i].mask) != (pat[i].value & pat[i].mask)) return false;
    }
    return true;
}

std::vector<uintptr_t> scan_range(const uint8_t *start, size_t len, uintptr_t logical_base,
                                  const PatternWord *pat, size_t n){
    std::vector<uintptr_t> out;
    size_t bytes=n*4;
    if(len<bytes) return out;
    for(size_t off=0; off+bytes<=len; off+=4){
        if(match_pattern(start+off,pat,n)) out.push_back(logical_base+off);
    }
    return out;
}

struct LoadedImage {
    uintptr_t base=0;
    const ElfW(Phdr) *phdr=nullptr;
    ElfW(Half) phnum=0;
};
int phdr_cb(dl_phdr_info *info, size_t, void *data){
    if(info && info->dlpi_name && strstr(info->dlpi_name,"libapp_launcher.so")){
        auto *d=static_cast<LoadedImage*>(data);
        d->base=(uintptr_t)info->dlpi_addr; d->phdr=info->dlpi_phdr; d->phnum=info->dlpi_phnum;
        return 1;
    }
    return 0;
}

struct ScanResult {
    uintptr_t x=0,y=0,hotseat=0,prefGetInt=0;
    std::vector<uintptr_t> xyCandidates;
    std::vector<uintptr_t> hsCandidates;
    std::vector<uintptr_t> prefCandidates;
    std::string method;
    bool safe=false;
};

bool runtime_match_safe(const LoadedImage &img, uintptr_t addr, const PatternWord *pat, size_t n){
    const size_t need=n*4;
    for(ElfW(Half) i=0;i<img.phnum;i++){
        const auto &p=img.phdr[i];
        if(p.p_type!=PT_LOAD || !(p.p_flags&PF_X) || !(p.p_flags&PF_R)) continue;
        uintptr_t begin=img.base+p.p_vaddr;
        uintptr_t end=begin+(uintptr_t)p.p_memsz;
        if(addr>=begin && addr<=end && need<=(size_t)(end-addr))
            return match_pattern(reinterpret_cast<const uint8_t*>(addr),pat,n);
    }
    return false;
}

ScanResult resolve_runtime(const LoadedImage &img){
    ScanResult r;
    for(ElfW(Half) i=0;i<img.phnum;i++){
        const auto &p=img.phdr[i];
        if(p.p_type!=PT_LOAD || !(p.p_flags & PF_X) || !(p.p_flags & PF_R)) continue;
        auto *mem=reinterpret_cast<const uint8_t*>(img.base+p.p_vaddr);
        auto xy=scan_range(mem,(size_t)p.p_memsz,img.base+p.p_vaddr,PAT_XY,std::size(PAT_XY));
        auto hs=scan_range(mem,(size_t)p.p_memsz,img.base+p.p_vaddr,PAT_HS,std::size(PAT_HS));
        auto pref=scan_range(mem,(size_t)p.p_memsz,img.base+p.p_vaddr,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT));
        r.xyCandidates.insert(r.xyCandidates.end(),xy.begin(),xy.end());
        r.hsCandidates.insert(r.hsCandidates.end(),hs.begin(),hs.end());
        r.prefCandidates.insert(r.prefCandidates.end(),pref.begin(),pref.end());
    }
    uintptr_t hx=g_hint_x.load(), hy=g_hint_y.load(), hh=g_hint_hotseat.load(), hp=g_hint_pref_get_int.load();
    if(hx && hy && hh &&
       runtime_match_safe(img,img.base+hx,PAT_XY,std::size(PAT_XY)) &&
       runtime_match_safe(img,img.base+hy,PAT_XY,std::size(PAT_XY)) &&
       runtime_match_safe(img,img.base+hh,PAT_HS,std::size(PAT_HS))){
        r.x=img.base+hx; r.y=img.base+hy; r.hotseat=img.base+hh;
        if(hp && runtime_match_safe(img,img.base+hp,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT))) r.prefGetInt=img.base+hp;
        else if(r.prefCandidates.size()==1) r.prefGetInt=r.prefCandidates[0];
        r.safe=true; r.method=r.prefGetInt?"gnu-debugdata-hint+upstream-preference":"gnu-debugdata-hint+getter-fallback";
        return r;
    }

    std::vector<std::pair<uintptr_t,uintptr_t>> pairs;
    for(auto a:r.xyCandidates) for(auto b:r.xyCandidates) if(b>a && b-a==XY_DISTANCE) pairs.emplace_back(a,b);
    if(pairs.size()==1 && r.hsCandidates.size()==1){
        r.x=pairs[0].first; r.y=pairs[0].second; r.hotseat=r.hsCandidates[0];
        if(r.prefCandidates.size()==1) r.prefGetInt=r.prefCandidates[0];
        r.safe=true; r.method=r.prefGetInt?"runtime-pattern+upstream-preference":"runtime-pattern+getter-fallback";
        return r;
    }
    for(const auto &k:KNOWN_BUILDS){
        uintptr_t ax=img.base+k.x, ay=img.base+k.y, ah=img.base+k.hotseat, ap=img.base+k.prefGetInt;
        if(runtime_match_safe(img,ax,PAT_XY,std::size(PAT_XY)) &&
           runtime_match_safe(img,ay,PAT_XY,std::size(PAT_XY)) &&
           runtime_match_safe(img,ah,PAT_HS,std::size(PAT_HS))){
            r.x=ax;r.y=ay;r.hotseat=ah;
            if(runtime_match_safe(img,ap,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT))) r.prefGetInt=ap;
            r.safe=true;r.method=std::string("verified-known-rva:")+k.label+(r.prefGetInt?"+upstream-preference":"+getter-fallback"); return r;
        }
    }
    r.method="unresolved";
    return r;
}

std::string hexv(uintptr_t v){ std::ostringstream o; o<<"0x"<<std::hex<<v; return o.str(); }
std::string format_scan(const ScanResult &r, uintptr_t base){
    std::ostringstream o;
    o<<"method="<<r.method<<"\nsafe="<<(r.safe?"true":"false")<<"\nbase="<<hexv(base)<<"\n";
    o<<"xy_candidate_count="<<r.xyCandidates.size()<<"\n";
    for(size_t i=0;i<r.xyCandidates.size();++i) o<<"xy_candidate["<<i<<"]="<<hexv(r.xyCandidates[i]-base)<<"\n";
    o<<"hotseat_candidate_count="<<r.hsCandidates.size()<<"\n";
    for(size_t i=0;i<r.hsCandidates.size();++i) o<<"hotseat_candidate["<<i<<"]="<<hexv(r.hsCandidates[i]-base)<<"\n";
    o<<"preference_get_int_candidate_count="<<r.prefCandidates.size()<<"\n";
    for(size_t i=0;i<r.prefCandidates.size();++i) o<<"preference_get_int_candidate["<<i<<"]="<<hexv(r.prefCandidates[i]-base)<<"\n";
    if(r.safe){
        o<<"cell_count_x_rva="<<hexv(r.x-base)<<"\ncell_count_y_rva="<<hexv(r.y-base)
         <<"\nhotseat_rva="<<hexv(r.hotseat-base)<<"\n";
        if(r.prefGetInt) o<<"preference_get_int_rva="<<hexv(r.prefGetInt-base)<<"\nupstream_grid_config=true\n";
        else o<<"upstream_grid_config=false\n";
    }
    return o.str();
}

bool install_one(uintptr_t addr, void *proxy, void **orig, void **stub, const char *name){
    *stub=shadowhook_hook_func_addr_2(reinterpret_cast<void*>(addr),proxy,orig,
        SHADOWHOOK_HOOK_WITH_SHARED_MODE|SHADOWHOOK_HOOK_RECORD,"libapp_launcher.so",name);
    if(!*stub){ int e=shadowhook_get_errno(); LOGE("hook %s failed: %d %s",name,e,shadowhook_to_errmsg(e)); return false; }
    LOGI("hook %s OK @ %p",name,(void*)addr); return true;
}

std::atomic<bool> g_hook_done{false};
std::atomic<bool> g_callback_registered{false};

#include "runtime_status.inc"

int hook_loaded_image(const LoadedImage &img, bool before_init){
    if(g_hook_done.exchange(true)){ LOGI("hook already installed/attempted, skip duplicate"); return 1; }
    LOGI("libapp_launcher.so ready, base=%p",(void*)img.base);
    ScanResult found=resolve_runtime(img);
    std::string report=format_scan(found,img.base);
    LOGI("auto locator:\n%s",report.c_str());