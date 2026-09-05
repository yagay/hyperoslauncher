#include <jni.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr uintptr_t XY_DISTANCE=0xAC;
struct Word{uint32_t value,mask;};
constexpr Word PAT_XY[]={{0xd10083ff,0xffffffff},{0xa9017bfd,0xffffffff},{0x910043fd,0xffffffff},{0xd00042c8,0x9f00001f}};
constexpr Word PAT_PREF[]={{0xd10203ff,0xffffffff},{0xa9057bfd,0xffffffff},{0xa90657f6,0xffffffff},{0xa9074ff4,0xffffffff},{0x910143fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xaa0103f3,0xffffffff},{0xc8dffd08,0xffffffff},{0xaa0003f4,0xffffffff},{0xf100091f,0xffffffff}};
constexpr Word PAT_HS[]={{0xa9bf7bfd,0xffffffff},{0x910003fd,0xffffffff},{0x90000008,0x9f00001f},{0x91000108,0xffc003ff},{0xc8dffd08,0xffffffff},{0xf100091f,0xffffffff},{0x54000121,0xffffffff},{0x90000008,0x9f00001f},{0xf9400100,0xffc003ff},{0x94000000,0xfc000000},{0x52800088,0xffffffff},{0x7100001f,0xffffffff},{0x1a881500,0xffffffff},{0xa8c17bfd,0xffffffff},{0xd65f03c0,0xffffffff}};
bool match(const uint8_t*p,const Word*pat,size_t n){for(size_t i=0;i<n;i++){uint32_t w;memcpy(&w,p+i*4,4);if((w&pat[i].mask)!=(pat[i].value&pat[i].mask))return false;}return true;}
std::vector<uintptr_t> scan(const uint8_t*p,size_t len,uintptr_t logical,const Word*pat,size_t n){std::vector<uintptr_t>out;size_t need=n*4;if(len<need)return out;for(size_t o=0;o+need<=len;o+=4)if(match(p+o,pat,n))out.push_back(logical+o);return out;}
std::string analyze(const char*path){int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return "error=open_failed\n";struct stat st{};if(fstat(fd,&st)!=0||st.st_size<(off_t)sizeof(Elf64_Ehdr)){close(fd);return "error=bad_file\n";}void*m=mmap(nullptr,st.st_size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(m==MAP_FAILED)return "error=mmap_failed\n";auto*base=(const uint8_t*)m;auto*eh=(const Elf64_Ehdr*)base;std::ostringstream out;if(memcmp(eh->e_ident,ELFMAG,SELFMAG)!=0||eh->e_ident[EI_CLASS]!=ELFCLASS64||eh->e_machine!=EM_AARCH64){munmap(m,st.st_size);return "error=not_arm64_elf\n";}std::vector<uintptr_t>xy,hs,pref;if(eh->e_phoff+(uint64_t)eh->e_phnum*eh->e_phentsize<=(uint64_t)st.st_size)for(uint16_t i=0;i<eh->e_phnum;i++){auto*p=(const Elf64_Phdr*)(base+eh->e_phoff+(uint64_t)i*eh->e_phentsize);if(p->p_type!=PT_LOAD||!(p->p_flags&PF_X)||p->p_offset+p->p_filesz>(uint64_t)st.st_size)continue;auto a=scan(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_XY,std::size(PAT_XY));auto h=scan(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_HS,std::size(PAT_HS));auto q=scan(base+p->p_offset,p->p_filesz,p->p_vaddr,PAT_PREF,std::size(PAT_PREF));xy.insert(xy.end(),a.begin(),a.end());hs.insert(hs.end(),h.begin(),h.end());pref.insert(pref.end(),q.begin(),q.end());}std::vector<std::pair<uintptr_t,uintptr_t>>pairs;for(auto a:xy)for(auto b:xy)if(b>a&&b-a==XY_DISTANCE)pairs.emplace_back(a,b);out<<"arch=aarch64\nxy_candidate_count="<<xy.size()<<"\nhotseat_candidate_count="<<hs.size()<<"\npreference_get_int_candidate_count="<<pref.size()<<"\n";if(pairs.size()==1&&hs.size()==1){out<<"safe=true\nmethod=file-pattern+relation\ncell_count_x_rva=0x"<<std::hex<<pairs[0].first<<"\ncell_count_y_rva=0x"<<pairs[0].second<<"\nhotseat_rva=0x"<<hs[0]<<"\n";if(pref.size()==1)out<<"preference_get_int_rva=0x"<<pref[0]<<"\n";}else out<<"safe=false\npair_count="<<std::dec<<pairs.size()<<"\n";munmap(m,st.st_size);return out.str();}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_yagay_desktopgridx_NativeBridge_analyzeElf(JNIEnv*env,jclass,jstring path){const char*p=env->GetStringUTFChars(path,nullptr);std::string r=analyze(p?p:"");if(p)env->ReleaseStringUTFChars(path,p);return env->NewStringUTF(r.c_str());}
