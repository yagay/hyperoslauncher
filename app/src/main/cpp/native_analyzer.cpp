#include <jni.h>
#include <elf.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
constexpr uintptr_t kXYDistance = 0xAC;
constexpr size_t kMaxCandidates = 256;
struct Word { uint32_t value; uint32_t mask; };
constexpr Word kPatternXY[]={{0xd10083ff,0xffffffff},{0xa9017bfd,0xffffffff},{0x910043fd,0xffffffff},{0xd00042c8,0x9f00001f}};
constexpr Word kPatternPref[]={{0xd10203ff,0xffffffff},{0xa9057bfd,0xffffffff},{0xa90657f6,0xffffffff},{0xa9074ff4,0xffffffff},{0x910143fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xaa0103f3,0xffffffff},{0xc8dffd08,0xffffffff},{0xaa0003f4,0xffffffff},{0xf100091f,0xffffffff}};
constexpr Word kPatternHotseat[]={{0xa9bf7bfd,0xffffffff},{0x910003fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xc8dffd08,0xffffffff},{0xf100091f,0xffffffff},{0x54000121,0xffffffff},{0x90000008,0x9f00001f},{0xf9400100,0xffc003ff},{0x94000000,0xfc000000},{0x52800088,0xffffffff},{0x7100001f,0xffffffff},{0x1a881500,0xffffffff},{0xa8c17bfd,0xffffffff},{0xd65f03c0,0xffffffff}};

struct CandidateSet { uintptr_t values[kMaxCandidates]; size_t count; bool overflow; };

bool Match(const uint8_t* p,const Word* pattern,size_t count){
    for(size_t i=0;i<count;i++){
        uint32_t word=0;
        memcpy(&word,p+i*4,4);
        if((word&pattern[i].mask)!=(pattern[i].value&pattern[i].mask)) return false;
    }
    return true;
}

void Add(CandidateSet* set,uintptr_t value){
    if(set->count>=kMaxCandidates){set->overflow=true;return;}
    set->values[set->count++]=value;
}

void Scan(const uint8_t* p,size_t len,uintptr_t logical,const Word* pattern,size_t count,CandidateSet* out){
    const size_t need=count*4;
    if(len<need) return;
    for(size_t offset=0;offset+need<=len;offset+=4){
        if(Match(p+offset,pattern,count)) Add(out,logical+offset);
    }
}

void Append(char* out,size_t cap,size_t* used,const char* fmt,...){
    if(*used>=cap) return;
    va_list args;
    va_start(args,fmt);
    const int n=vsnprintf(out+*used,cap-*used,fmt,args);
    va_end(args);
    if(n<=0) return;
    const size_t wrote=static_cast<size_t>(n);
    *used += wrote < cap-*used ? wrote : cap-*used-1;
}

void Analyze(const char* path,char* out,size_t cap){
    size_t used=0;
    if(cap==0) return;
    out[0]='\0';
    const int fd=open(path,O_RDONLY|O_CLOEXEC);
    if(fd<0){Append(out,cap,&used,"error=open_failed\n");return;}
    struct stat st{};
    if(fstat(fd,&st)!=0||st.st_size<static_cast<off_t>(sizeof(Elf64_Ehdr))){
        close(fd);Append(out,cap,&used,"error=bad_file\n");return;
    }
    void* mapping=mmap(nullptr,static_cast<size_t>(st.st_size),PROT_READ,MAP_PRIVATE,fd,0);
    close(fd);
    if(mapping==MAP_FAILED){Append(out,cap,&used,"error=mmap_failed\n");return;}
    const uint8_t* base=static_cast<const uint8_t*>(mapping);
    const Elf64_Ehdr* eh=reinterpret_cast<const Elf64_Ehdr*>(base);
    if(memcmp(eh->e_ident,ELFMAG,SELFMAG)!=0||eh->e_ident[EI_CLASS]!=ELFCLASS64||eh->e_machine!=EM_AARCH64){
        munmap(mapping,static_cast<size_t>(st.st_size));
        Append(out,cap,&used,"error=not_arm64_elf\n");return;
    }

    CandidateSet xy{};
    CandidateSet hs{};
    CandidateSet pref{};
    const uint64_t ph_end=eh->e_phoff+static_cast<uint64_t>(eh->e_phnum)*eh->e_phentsize;
    if(ph_end<=static_cast<uint64_t>(st.st_size)){
        for(uint16_t i=0;i<eh->e_phnum;i++){
            const Elf64_Phdr* ph=reinterpret_cast<const Elf64_Phdr*>(base+eh->e_phoff+static_cast<uint64_t>(i)*eh->e_phentsize);
            if(ph->p_type!=PT_LOAD||(ph->p_flags&PF_X)==0||ph->p_offset+ph->p_filesz>static_cast<uint64_t>(st.st_size)) continue;
            Scan(base+ph->p_offset,ph->p_filesz,ph->p_vaddr,kPatternXY,sizeof(kPatternXY)/sizeof(kPatternXY[0]),&xy);
            Scan(base+ph->p_offset,ph->p_filesz,ph->p_vaddr,kPatternHotseat,sizeof(kPatternHotseat)/sizeof(kPatternHotseat[0]),&hs);
            Scan(base+ph->p_offset,ph->p_filesz,ph->p_vaddr,kPatternPref,sizeof(kPatternPref)/sizeof(kPatternPref[0]),&pref);
        }
    }

    size_t pair_count=0;
    uintptr_t pair_x=0,pair_y=0;
    for(size_t a=0;a<xy.count;a++){
        for(size_t b=0;b<xy.count;b++){
            if(xy.values[b]>xy.values[a]&&xy.values[b]-xy.values[a]==kXYDistance){
                pair_x=xy.values[a];pair_y=xy.values[b];pair_count++;
            }
        }
    }

    Append(out,cap,&used,"arch=aarch64\nxy_candidate_count=%zu\nhotseat_candidate_count=%zu\npreference_get_int_candidate_count=%zu\n",xy.count,hs.count,pref.count);
    if(!xy.overflow&&!hs.overflow&&!pref.overflow&&pair_count==1&&hs.count==1){
        Append(out,cap,&used,"safe=true\nmethod=file-pattern+relation\ncell_count_x_rva=0x%llx\ncell_count_y_rva=0x%llx\nhotseat_rva=0x%llx\n",
               static_cast<unsigned long long>(pair_x),static_cast<unsigned long long>(pair_y),static_cast<unsigned long long>(hs.values[0]));
        if(pref.count==1) Append(out,cap,&used,"preference_get_int_rva=0x%llx\n",static_cast<unsigned long long>(pref.values[0]));
    }else{
        Append(out,cap,&used,"safe=false\npair_count=%zu\n",pair_count);
    }
    munmap(mapping,static_cast<size_t>(st.st_size));
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yagay_desktopgridx_NativeBridge_analyzeElf(JNIEnv* env,jclass,jstring path){
    const char* p=env->GetStringUTFChars(path,nullptr);
    char result[2048]{};
    Analyze(p?p:"",result,sizeof(result));
    if(p) env->ReleaseStringUTFChars(path,p);
    return env->NewStringUTF(result);
}
