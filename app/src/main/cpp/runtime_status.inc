// Included inside the anonymous namespace after native_hook_part1.inc.
std::atomic<uint64_t> g_hit_pref_x{0}, g_hit_pref_y{0}, g_hit_icon{0}, g_hit_getter_x{0}, g_hit_getter_y{0}, g_hit_hotseat{0};
std::atomic<int> g_state_shadowhook{0}, g_state_pref_hook{0}, g_state_x_hook{0}, g_state_y_hook{0}, g_state_hotseat_hook{0};
std::atomic<uintptr_t> g_runtime_base{0};
std::string g_runtime_method="none";
std::string g_runtime_last_error="none";
std::atomic<bool> g_runtime_before_init{false};

void write_runtime_status(const char *stage){
    FILE *f=fopen("/data/adb/desktopgridx/runtime-status.conf.tmp","we");
    if(!f) return;
    fprintf(f,"version=0.7.0\n");
    fprintf(f,"pid=%d\n",getpid());
    fprintf(f,"stage=%s\n",stage?stage:"unknown");
    fprintf(f,"native_loaded=1\n");
    fprintf(f,"columns=%d\nrows=%d\nhotseat=%d\niconSize=%d\ntracePrefs=%d\n",
            g_cols.load(),g_rows.load(),g_hotseat.load(),g_icon_size.load(),g_trace_prefs.load());
    fprintf(f,"launcher_base=%p\n",(void*)g_runtime_base.load());
    fprintf(f,"resolver=%s\n",g_runtime_method.c_str());
    fprintf(f,"before_init=%d\n",g_runtime_before_init.load()?1:0);
    fprintf(f,"shadowhook_init=%d\npreference_hook_installed=%d\nx_hook_installed=%d\ny_hook_installed=%d\nhotseat_hook_installed=%d\n",
            g_state_shadowhook.load(),g_state_pref_hook.load(),g_state_x_hook.load(),g_state_y_hook.load(),g_state_hotseat_hook.load());
    fprintf(f,"pref_cell_x_hits=%llu\npref_cell_y_hits=%llu\nicon_pref_hits=%llu\ngetter_x_hits=%llu\ngetter_y_hits=%llu\nhotseat_hits=%llu\n",
            (unsigned long long)g_hit_pref_x.load(),(unsigned long long)g_hit_pref_y.load(),(unsigned long long)g_hit_icon.load(),
            (unsigned long long)g_hit_getter_x.load(),(unsigned long long)g_hit_getter_y.load(),(unsigned long long)g_hit_hotseat.load());
    fprintf(f,"last_error=%s\n",g_runtime_last_error.c_str());
    fclose(f);
    rename("/data/adb/desktopgridx/runtime-status.conf.tmp","/data/adb/desktopgridx/runtime-status.conf");
    chmod("/data/adb/desktopgridx/runtime-status.conf",0644);
}

inline void runtime_hit(std::atomic<uint64_t> &counter,const char *stage){
    uint64_t n=counter.fetch_add(1)+1;
    if(n==1 || n==10 || n==100 || n==1000) write_runtime_status(stage);
}

int hook_x_status(){ runtime_hit(g_hit_getter_x,"getter-x-hit"); return hook_x(); }
int hook_y_status(){ runtime_hit(g_hit_getter_y,"getter-y-hit"); return hook_y(); }
int hook_hotseat_status(){ runtime_hit(g_hit_hotseat,"hotseat-hit"); return hook_hotseat(); }
U128 hook_preference_get_int_status(const char *key,size_t len){
    if(key){
        const char *kx="pref_key_cell_x", *ky="pref_key_cell_y";
        if(len==strlen(kx) && memcmp(key,kx,len)==0) runtime_hit(g_hit_pref_x,"pref-cell-x-hit");
        else if(len==strlen(ky) && memcmp(key,ky,len)==0) runtime_hit(g_hit_pref_y,"pref-cell-y-hit");
        else if((len==strlen("icon_size_scale") && memcmp(key,"icon_size_scale",len)==0) ||
                (len==strlen("icon_size") && memcmp(key,"icon_size",len)==0)) runtime_hit(g_hit_icon,"icon-pref-hit");
    }
    return hook_preference_get_int(key,len);
}
