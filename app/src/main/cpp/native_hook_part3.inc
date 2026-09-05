    if(!found.safe){
        g_hook_done.store(false); // allow a later explicit process restart/version retry, but never unsafe-hook now.
        LOGE("auto locator could not uniquely verify hook points; refusing unsafe hook");
        return -30;
    }
    bool ok=true;
    bool up=false;
    // Preferred backend: intercept the launcher's own PreferenceUtils::get_int before .init/.init_array
    // whenever possible. Then GridConfig is born with our X/Y values and no getter detour is needed.
    if(found.prefGetInt && (g_cols.load()>0 || g_rows.load()>0 || g_icon_size.load()>0 || g_trace_prefs.load())){
        up=install_one(found.prefGetInt,(void*)hook_preference_get_int,(void**)&orig_pref_get_int,&stub_pref_get_int,"PreferenceUtils::get_int");
        g_upstream_active.store(up);
        LOGI("upstream GridConfig preference backend=%s timing=%s", up?"active":"failed", before_init?"pre-init":"late");
    } else {
        LOGI("upstream GridConfig preference backend unavailable; getter fallback required");
    }

    // If we installed the upstream hook before library constructors, all subsequent GridConfig reads
    // go through it. Avoid redundant X/Y inline hooks in that best-case path. If the library was already
    // initialized when the module arrived, keep getter hooks as a deterministic late-load fallback.
    const bool need_getter_fallback = !up || !before_init;
    if(need_getter_fallback){
        LOGI("workspace backend=getter-fallback reason=%s", !up?"upstream-unavailable":"late-install");
        if(g_cols.load()>0) ok &= install_one(found.x,(void*)hook_x,(void**)&orig_x,&stub_x,"DeviceConfigs::get_cell_count_x");
        if(g_rows.load()>0) ok &= install_one(found.y,(void*)hook_y,(void**)&orig_y,&stub_y,"DeviceConfigs::get_cell_count_y");
    } else {
        LOGI("workspace backend=upstream-preference-only (GridConfig-consistent)");
    }
    // No equally-proven launcher preference source for Hotseat has been established yet. Keep the
    // independently verified getter hook until a native source/key is proven by diagnostics.
    if(g_hotseat.load()>0) ok &= install_one(found.hotseat,(void*)hook_hotseat,(void**)&orig_hotseat,&stub_hotseat,"DeviceConfigs::get_hotseat_max_count");
    char *records=shadowhook_get_records(SHADOWHOOK_RECORD_ITEM_ALL);
    if(records){ LOGI("shadowhook records:\n%s", records); free(records); }
    if(!ok) g_hook_done.store(false);
    return ok?1:-50;
}

void launcher_dl_pre(dl_phdr_info *info, size_t, void *){
    if(!info || !info->dlpi_name || !strstr(info->dlpi_name,"libapp_launcher.so")) return;
    LoadedImage img;
    img.base=(uintptr_t)info->dlpi_addr; img.phdr=info->dlpi_phdr; img.phnum=info->dlpi_phnum;
    LOGI("dl_init PRE callback observed %s", info->dlpi_name);
    int rc=hook_loaded_image(img,true);
    LOGI("pre-init native install result=%d",rc);
    if(rc==1 && g_callback_registered.exchange(false)) shadowhook_unregister_dl_init_callback(launcher_dl_pre,nullptr,nullptr);
}

int install_impl(){
#if !defined(__aarch64__)
    LOGE("unsupported ABI"); return -10;
#endif
    read_config();
    if(g_cols.load()==0 && g_rows.load()==0 && g_hotseat.load()==0 && g_icon_size.load()==0 && !g_trace_prefs.load()){
        LOGI("all overrides disabled"); return 0;
    }

    int rc=shadowhook_init(SHADOWHOOK_MODE_SHARED,true);
    if(rc!=0 && rc!=SHADOWHOOK_ERRNO_OK){ LOGE("shadowhook_init failed=%d %s",rc,shadowhook_to_errmsg(rc)); return -40; }
    shadowhook_set_recordable(true);

    LoadedImage img; dl_iterate_phdr(phdr_cb,&img);
    if(img.base) return hook_loaded_image(img,false);

    if(!g_callback_registered.exchange(true)){
        int cr=shadowhook_register_dl_init_callback(launcher_dl_pre,nullptr,nullptr);
        if(cr!=0){
            g_callback_registered.store(false);
            LOGE("register dl_init callback failed=%d",cr);
            return -21;
        }
        LOGI("libapp_launcher.so not loaded yet; PRE-init hook callback registered");
    }
    return 2; // pending: hook will run when libapp_launcher.so is loaded.
}

std::string analyze_elf_file(const char *path){
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if(fd<0) return std::string("error=open_failed errno=")+std::to_string(errno)+"\n";
    struct stat st{}; if(fstat(fd,&st)!=0 || st.st_size<(off_t)sizeof(Elf64_Ehdr)){ close(fd); return "error=bad_file\n"; }
    void *map=mmap(nullptr,(size_t)st.st_size,PROT_READ,MAP_PRIVATE,fd,0); close(fd);
    if(map==MAP_FAILED) return std::string("error=mmap_failed errno=")+std::to_string(errno)+"\n";
    auto *base=reinterpret_cast<const uint8_t*>(map);
    auto *eh=reinterpret_cast<const Elf64_Ehdr*>(base);
    std::ostringstream out;
    if(memcmp(eh->e_ident,ELFMAG,SELFMAG)!=0 || eh->e_ident[EI_CLASS]!=ELFCLASS64 || eh->e_machine!=EM_AARCH64){
        munmap(map,(size_t)st.st_size); return "error=not_arm64_elf\n";
    }
    std::vector<uintptr_t> xy,hs,pref;
    if(eh->e_phoff + (uint64_t)eh->e_phnum*eh->e_phentsize <= (uint64_t)st.st_size){
        for(uint16_t i=0;i<eh->e_phnum;i++){
            auto *p=reinterpret_cast<const Elf64_Phdr*>(base+eh->e_phoff+(uint64_t)i*eh->e_phentsize);
            if(p->p_type!=PT_LOAD || !(p->p_flags&PF_X) || p->p_offset+p->p_filesz>(uint64_t)st.st_size) continue;
            auto a=scan_range(base+p->p_offset,(size_t)p->p_filesz,(uintptr_t)p->p_vaddr,PAT_XY,std::size(PAT_XY));
            auto b=scan_range(base+p->p_offset,(size_t)p->p_filesz,(uintptr_t)p->p_vaddr,PAT_HS,std::size(PAT_HS));
            auto c=scan_range(base+p->p_offset,(size_t)p->p_filesz,(uintptr_t)p->p_vaddr,PAT_PREF_GET_INT,std::size(PAT_PREF_GET_INT));
            xy.insert(xy.end(),a.begin(),a.end()); hs.insert(hs.end(),b.begin(),b.end()); pref.insert(pref.end(),c.begin(),c.end());
        }
    }
    out<<"file="<<path<<"\nsize="<<st.st_size<<"\narch=aarch64\n";
    out<<"xy_candidate_count="<<xy.size()<<"\n"; for(size_t i=0;i<xy.size();i++) out<<"xy_candidate["<<i<<"]="<<hexv(xy[i])<<"\n";
    out<<"hotseat_candidate_count="<<hs.size()<<"\n"; for(size_t i=0;i<hs.size();i++) out<<"hotseat_candidate["<<i<<"]="<<hexv(hs[i])<<"\n";
    out<<"preference_get_int_candidate_count="<<pref.size()<<"\n"; for(size_t i=0;i<pref.size();i++) out<<"preference_get_int_candidate["<<i<<"]="<<hexv(pref[i])<<"\n";
    std::vector<std::pair<uintptr_t,uintptr_t>> pairs;
    for(auto a:xy) for(auto b:xy) if(b>a && b-a==XY_DISTANCE) pairs.emplace_back(a,b);
    if(pairs.size()==1 && hs.size()==1){
        out<<"safe=true\nmethod=file-pattern+relation\ncell_count_x_rva="<<hexv(pairs[0].first)
           <<"\ncell_count_y_rva="<<hexv(pairs[0].second)<<"\nhotseat_rva="<<hexv(hs[0])<<"\n";
        if(pref.size()==1) out<<"preference_get_int_rva="<<hexv(pref[0])<<"\nupstream_grid_config=true\n";
        else out<<"upstream_grid_config=false\n";
    } else {
        out<<"safe=false\nmethod=unresolved\npair_count="<<pairs.size()<<"\n";
    }
    munmap(map,(size_t)st.st_size); return out.str();
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yagay_desktopgridx_NativeBridge_install(JNIEnv*, jclass){ return install_impl(); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_yagay_desktopgridx_NativeBridge_analyzeElf(JNIEnv *env, jclass, jstring path){
    const char *p=env->GetStringUTFChars(path,nullptr);
    std::string r=analyze_elf_file(p?p:"");
    if(p) env->ReleaseStringUTFChars(path,p);
    return env->NewStringUTF(r.c_str());
}
