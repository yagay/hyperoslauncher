#include <android/log.h>
#include <link.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DesktopGridX", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DesktopGridX", __VA_ARGS__)

using HookFunType = int (*)(void *, void *, void **);
using UnhookFunType = int (*)(void *);
using NativeOnModuleLoaded = void (*)(const char *, void *);
struct NativeAPIEntries { uint32_t version; HookFunType hook_func; UnhookFunType unhook_func; };

namespace {
constexpr uintptr_t XY_DISTANCE = 0xAC;
constexpr const char *STATUS_PATHS[] = {
    "/data/user_de/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/user/0/com.miui.home/cache/desktopgridx-native-runtime.conf",
    "/data/data/com.miui.home/cache/desktopgridx-native-runtime.conf"
};
struct Word { uint32_t value, mask; };
constexpr Word PAT_XY[] = {{0xd10083ff,0xffffffff},{0xa9017bfd,0xffffffff},{0x910043fd,0xffffffff},{0xd00042c8,0x9f00001f}};
constexpr Word PAT_PREF[] = {{0xd10203ff,0xffffffff},{0xa9057bfd,0xffffffff},{0xa90657f6,0xffffffff},{0xa9074ff4,0xffffffff},{0x910143fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xaa0103f3,0xffffffff},{0xc8dffd08,0xffffffff},{0xaa0003f4,0xffffffff},{0xf100091f,0xffffffff}};
constexpr Word PAT_HS[] = {{0xa9bf7bfd,0xffffffff},{0x910003fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xc8dffd08,0xffffffff},{0xf100091f,0xffffffff},{0x54000121,0xffffffff},{0x90000008,0x9f00001f},{0xf9400100,0xffc003ff},{0x94000000,0xfc000000},{0x52800088,0xffffffff},{0x7100001f,0xffffffff},{0x1a881500,0xffffffff},{0xa8c17bfd,0xffffffff},{0xd65f03c0,0xffffffff}};

std::atomic<int> cols{0}, rows{0}, hotseat{0}, iconSize{0}, tracePrefs{0};
std::atomic<uintptr_t> hintX{0}, hintY{0}, hintHs{0}, hintPref{0};
std::atomic<bool> installed{false};
std::atomic<uint64_t> hitX{0}, hitY{0}, hitHs{0}, hitPref{0};
HookFunType hookFun=nullptr;
UnhookFunType unhookFun=nullptr;
std::mutex lock;
using IntFn=int(*)(); IntFn origX=nullptr, origY=nullptr, origHs=nullptr;
using U128=unsigned __int128; using PrefFn=U128(*)(const char*,size_t); PrefFn origPref=nullptr;
uintptr_t hookedX=0, hookedY=0, hookedHs=0, hookedPref=0;

bool match(const uint8_t *p,const Word *pat,size_t n){for(size_t i=0;i<n;i++){uint32_t w;memcpy(&w,p+i*4,4);if((w&pat[i].mask)!=(pat[i].value&pat[i].mask))return false;}return true;}
std::vector<uintptr_t> scan(const uint8_t *p,size_t len,uintptr_t logical,const Word *pat,size_t n){std::vector<uintptr_t> out;size_t need=n*4;if(len<need)return out;for(size_t o=0;o+need<=len;o+=4)if(match(p+o,pat,n))out.push_back(logical+o);return out;}
struct Image{uintptr_t base=0;const ElfW(Phdr)*phdr=nullptr;ElfW(Half)num=0;};
int phdrCb(dl_phdr_info *i,size_t,void *d){if(i&&i->dlpi_name&&strstr(i->dlpi_name,"libapp_launcher.so")){auto*x=(Image*)d;x->base=(uintptr_t)i->dlpi_addr;x->phdr=i->dlpi_phdr;x->num=i->dlpi_phnum;return 1;}return 0;}
bool verify(const Image&i,uintptr_t addr,const Word*pat,size_t n){for(ElfW(Half)x=0;x<i.num;x++){auto&p=i.phdr[x];if(p.p_type!=PT_LOAD||!(p.p_flags&PF_X)||!(p.p_flags&PF_R))continue;uintptr_t b=i.base+p.p_vaddr,e=b+p.p_memsz;if(addr>=b&&addr+n*4<=e)return match((const uint8_t*)addr,pat,n);}return false;}

void readConfig(){cols=rows=hotseat=iconSize=tracePrefs=0;hintX=hintY=hintHs=hintPref=0;char line[256];if(FILE*f=fopen("/data/adb/desktopgridx/config.conf","re")){while(fgets(line,sizeof(line),f)){int v;if(sscanf(line,"columns=%d",&v)==1)cols=v;else if(sscanf(line,"rows=%d",&v)==1)rows=v;else if(sscanf(line,"hotseat=%d",&v)==1)hotseat=v;else if(sscanf(line,"iconSize=%d",&v)==1)iconSize=v;else if(sscanf(line,"tracePrefs=%d",&v)==1)tracePrefs=v;}fclose(f);}if(FILE*f=fopen("/data/adb/desktopgridx/resolved.conf","re")){while(fgets(line,sizeof(line),f)){unsigned long long v;if(sscanf(line,"resolved_x=0x%llx",&v)==1)hintX=(uintptr_t)v;else if(sscanf(line,"resolved_y=0x%llx",&v)==1)hintY=(uintptr_t)v;else if(sscanf(line,"resolved_hotseat=0x%llx",&v)==1)hintHs=(uintptr_t)v;else if(sscanf(line,"resolved_preference_get_int=0x%llx",&v)==1)hintPref=(uintptr_t)v;}fclose(f);}}
void status(const char*stage,const char*err="none",uintptr_t base=0){char b[2048];int n=snprintf(b,sizeof(b),"version=0.21.0\npid=%d\nstage=%s\nlsposed_api=102\nnative_entry_seen=1\nbackend=lsposed_native_api\ninstall_state=%s\ncolumns=%d\nrows=%d\nhotseat=%d\niconSize=%d\nlauncher_base=0x%lx\nx_hook_installed=%d\ny_hook_installed=%d\nhotseat_hook_installed=%d\npreference_hook_installed=%d\ngetter_x_hits=%llu\ngetter_y_hits=%llu\nhotseat_hits=%llu\npreference_hits=%llu\nlast_error=%s\n",(int)getpid(),stage,installed.load()?"installed":"pending",cols.load(),rows.load(),hotseat.load(),iconSize.load(),(unsigned long)base,hookedX?1:0,hookedY?1:0,hookedHs?1:0,hookedPref?1:0,(unsigned long long)hitX.load(),(unsigned long long)hitY.load(),(unsigned long long)hitHs.load(),(unsigned long long)hitPref.load(),err);for(auto*p:STATUS_PATHS){FILE*f=fopen(p,"we");if(!f)continue;fwrite(b,1,n,f);fclose(f);chmod(p,0600);break;}}
int hookX(){hitX++;return cols>0?cols.load():(origX?origX():0);}int hookY(){hitY++;return rows>0?rows.load():(origY?origY():0);}int hookHs(){hitHs++;return hotseat>0?hotseat.load():(origHs?origHs():0);}
bool keyeq(const char*k,size_t n,const char*s){return k&&n==strlen(s)&&memcmp(k,s,n)==0;}
U128 hookPref(const char*k,size_t n){hitPref++;int v=0;if(keyeq(k,n,"pref_key_cell_x"))v=cols;else if(keyeq(k,n,"pref_key_cell_y"))v=rows;else if(keyeq(k,n,"icon_size_scale")||keyeq(k,n,"icon_size"))v=iconSize;if(v>0)return (static_cast<U128>(static_cast<uint32_t>(v))<<64)|static_cast<U128>(1);return origPref?origPref(k,n):static_cast<U128>(0);}

bool installOne(uintptr_t addr,void*proxy,void**backup,uintptr_t&slot,const char*name){if(slot)return true;if(!hookFun){LOGE("hook api unavailable");return false;}int rc=hookFun((void*)addr,proxy,backup);if(rc!=0||!*backup){LOGE("LSPosed hook failed %s rc=%d backup=%p",name,rc,backup?*backup:nullptr);return false;}slot=addr;LOGI("LSPosed hook OK %s @ %p",name,(void*)addr);return true;}
void unhookOne(uintptr_t&slot,void**backup){if(slot&&unhookFun)unhookFun((void*)slot);slot=0;if(backup)*backup=nullptr;}

int installImage(const Image&i){std::lock_guard<std::mutex>g(lock);if(installed)return 1;readConfig();if(cols==0&&rows==0&&hotseat==0&&iconSize==0&&!tracePrefs){status("disabled","none",i.base);return 0;}std::vector<uintptr_t>xy,hs,pref;for(ElfW(Half)x=0;x<i.num;x++){auto&p=i.phdr[x];if(p.p_type!=PT_LOAD||!(p.p_flags&PF_X)||!(p.p_flags&PF_R))continue;auto*m=(const uint8_t*)(i.base+p.p_vaddr);auto a=scan(m,p.p_memsz,i.base+p.p_vaddr,PAT_XY,std::size(PAT_XY));auto h=scan(m,p.p_memsz,i.base+p.p_vaddr,PAT_HS,std::size(PAT_HS));auto q=scan(m,p.p_memsz,i.base+p.p_vaddr,PAT_PREF,std::size(PAT_PREF));xy.insert(xy.end(),a.begin(),a.end());hs.insert(hs.end(),h.begin(),h.end());pref.insert(pref.end(),q.begin(),q.end());}
uintptr_t x=0,y=0,h=0,p=0;uintptr_t hx=hintX,hy=hintY,hh=hintHs,hp=hintPref;if(hx&&hy&&hh&&hy-hx==XY_DISTANCE&&verify(i,i.base+hx,PAT_XY,std::size(PAT_XY))&&verify(i,i.base+hy,PAT_XY,std::size(PAT_XY))&&verify(i,i.base+hh,PAT_HS,std::size(PAT_HS))){x=i.base+hx;y=i.base+hy;h=i.base+hh;if(hp&&verify(i,i.base+hp,PAT_PREF,std::size(PAT_PREF)))p=i.base+hp;}else{std::vector<std::pair<uintptr_t,uintptr_t>>pairs;for(auto a:xy)for(auto b:xy)if(b>a&&b-a==XY_DISTANCE)pairs.emplace_back(a,b);if(pairs.size()==1&&hs.size()==1){x=pairs[0].first;y=pairs[0].second;h=hs[0];if(pref.size()==1)p=pref[0];}}
if(!x||!y||!h){status("resolver-failed","locator_unresolved",i.base);return -30;}bool ok=true;if((cols>0||rows>0||iconSize>0||tracePrefs)&&p)ok&=installOne(p,(void*)hookPref,(void**)&origPref,hookedPref,"PreferenceUtils::get_int");if(cols>0)ok&=installOne(x,(void*)hookX,(void**)&origX,hookedX,"get_cell_count_x");if(rows>0)ok&=installOne(y,(void*)hookY,(void**)&origY,hookedY,"get_cell_count_y");if(hotseat>0)ok&=installOne(h,(void*)hookHs,(void**)&origHs,hookedHs,"get_hotseat_max_count");if(!ok){unhookOne(hookedHs,(void**)&origHs);unhookOne(hookedY,(void**)&origY);unhookOne(hookedX,(void**)&origX);unhookOne(hookedPref,(void**)&origPref);status("hook-failed","hook_transaction_rolled_back",i.base);return -50;}installed=true;status("hook-install-complete","none",i.base);return 1;}

void onLibrary(const char*name,void*){if(!name||!strstr(name,"libapp_launcher.so"))return;Image i;dl_iterate_phdr(phdrCb,&i);if(i.base){int rc=installImage(i);LOGI("libapp_launcher callback install=%d",rc);}}
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]] NativeOnModuleLoaded native_init(const NativeAPIEntries*e){if(!e||!e->hook_func){LOGE("DGX_NATIVE_ENTRY invalid API table");return onLibrary;}hookFun=e->hook_func;unhookFun=e->unhook_func;LOGI("DGX_NATIVE_ENTRY api=%u hook=%p unhook=%p",e->version,(void*)hookFun,(void*)unhookFun);status("native-entry");Image i;dl_iterate_phdr(phdrCb,&i);if(i.base)installImage(i);return onLibrary;}
