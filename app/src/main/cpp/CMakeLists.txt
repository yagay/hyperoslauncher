cmake_minimum_required(VERSION 3.22.1)
project(desktopgridx LANGUAGES CXX)

find_package(shadowhook REQUIRED CONFIG)

# Existing JNI/offline-analysis engine used by the DesktopGridX app itself.
# It is intentionally NOT registered as the HYOS native entry.
add_library(desktopgridx SHARED native_hook.cpp hyos_probe.cpp)
target_link_libraries(desktopgridx PRIVATE log android shadowhook::shadowhook)

# LSPosed IT 7869 HYOS entry. Keep this structurally aligned with known-good
# HyperOS 4 native modules: one entry, static C++ runtime supplied by Gradle,
# no ShadowHook dependency, hidden-by-default symbols, 16 KiB ELF alignment,
# and a version script exporting only native_init.
add_library(desktopgridx_hyos SHARED hyos_minimal_probe.cpp)
target_compile_features(desktopgridx_hyos PRIVATE cxx_std_17)
target_compile_options(desktopgridx_hyos PRIVATE
    -fno-exceptions
    -fno-rtti
    -fno-threadsafe-statics
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -mbranch-protection=standard
    -Wall
    -Wextra
    -Werror
)
target_link_options(desktopgridx_hyos PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports-hyos.map"
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
target_link_libraries(desktopgridx_hyos PRIVATE log dl)
set_target_properties(desktopgridx_hyos PROPERTIES
    CXX_EXTENSIONS OFF
    OUTPUT_NAME "desktopgridx_hyos"
)
