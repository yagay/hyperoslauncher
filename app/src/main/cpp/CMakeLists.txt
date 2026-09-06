cmake_minimum_required(VERSION 3.22.1)
project(desktopgridx LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "DesktopGridX native targets require Android NDK")
endif()

if(NOT "${ANDROID_ABI}" STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported; got '${ANDROID_ABI}'")
endif()

# HYOS runtime payload: intentionally STL-free to match known-good HyperOS 4
# native modules. No libc++ static/shared runtime is linked into this library.
add_library(desktopgridx_hyos SHARED hyos_hook.cpp hyos_stage_probe.cpp)
target_compile_features(desktopgridx_hyos PRIVATE cxx_std_17)
target_compile_options(desktopgridx_hyos PRIVATE
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -mbranch-protection=standard
    -Wall
    -Wextra
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
target_link_libraries(desktopgridx_hyos PRIVATE android log dl)

# App-side offline ELF analyzer. Also STL-free so ANDROID_STL=none can apply to
# the complete native build without reintroducing libc++ into the APK.
add_library(desktopgridx SHARED native_analyzer.cpp)
target_compile_features(desktopgridx PRIVATE cxx_std_17)
target_compile_options(desktopgridx PRIVATE
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(desktopgridx PRIVATE
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
)
