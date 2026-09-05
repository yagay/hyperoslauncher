cmake_minimum_required(VERSION 3.22.1)
project(desktopgridx)

set(CMAKE_ANDROID_STL_TYPE c++_static)

# The only LSPosed/HYOS runtime payload. It talks directly to NativeAPIEntries
# supplied by LSPosed 2.2.0-it 7869 and has no ShadowHook dependency.
# hyos_stage_probe.cpp contributes only a constructor/logcat stage marker so we
# can distinguish APK-backed mmap from a fully initialized ELF.
add_library(desktopgridx_hyos SHARED hyos_hook.cpp hyos_stage_probe.cpp)
target_compile_options(desktopgridx_hyos PRIVATE
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(desktopgridx_hyos PRIVATE
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports-hyos.map
)
target_link_libraries(desktopgridx_hyos log dl)

# Small JNI-only offline verifier used by the configuration app. It is not an
# Xposed entry and contains no hook/runtime code.
add_library(desktopgridx SHARED native_analyzer.cpp)
target_compile_options(desktopgridx PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(desktopgridx PRIVATE -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,relro -Wl,-z,now)
