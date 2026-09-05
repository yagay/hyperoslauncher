cmake_minimum_required(VERSION 3.22.1)
project(desktopgridx)
find_package(shadowhook REQUIRED CONFIG)

# Full module: existing implementation, includes ShadowHook and current resolver/hook logic.
add_library(desktopgridx SHARED native_hook.cpp hyos_probe.cpp)
target_link_libraries(desktopgridx log android shadowhook::shadowhook)

# Minimal HYOS A/B probe: deliberately has no ShadowHook/JNI/android dependency.
# If this loads while libdesktopgridx.so does not, the failure is in the full ELF/dependency path.
add_library(desktopgridx_minprobe SHARED hyos_minimal_probe.cpp)
target_compile_options(desktopgridx_minprobe PRIVATE
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(desktopgridx_minprobe PRIVATE
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
)
target_link_libraries(desktopgridx_minprobe log dl)
