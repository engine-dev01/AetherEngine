# AetherCore Native Engine — CMake build
# Unified engine: aether_core (JNI bridge) + L2 (process/aob/cache) + L1 (sandbox FS)
# Merged from aether-layer/l2-engine + aether-layer/l1-sandbox into libaether.so
# (single shared lib, engine-aligned loader). Compiles cleanly with NDK r26.

cmake_minimum_required(VERSION 3.22.1)
project(aether_core C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Stealth: no symbols exported except JNI_OnLoad
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# Find Android log (used only behind AETHER_DEBUG)
find_library(LOG_LIB log)

# ─── Unified source list ───
set(AETHER_SOURCES
    ${CMAKE_CURRENT_LIST_DIR}/aether_core.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/mem_reader.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/module_resolver.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/aob_scanner.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/crypto.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/config.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/stealth.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/binder.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/flagger.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/jni_hook.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/class_map.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/key_store.cpp
    ${CMAKE_CURRENT_LIST_DIR}/core/payload_store.cpp
)

# Layer 1 — Mirror Sandbox (virtual filesystem / manifest snapshot)
# audit C5: manifest_snapshot/hide_module = orphan TUs ที่ gc-sections ทิ้ง
#  - virtual_fs.cpp ต่อกับ JNI แล้ว (enableIO/addIORule/nativeResolvePath)
#  - manifest_snapshot: ผู้ผลิต package.conf ตัวจริงคือ Kotlin generatePackageConf
#    (per-install) — C++ duplicate ไม่มี call-site → ตัด (docs/CUTS.md)
#  - hide_module: เขียน /data/adb/magisk/denylist = ต้อง root + ไม่มี caller → ตัด
set(L1_SOURCES
    ${CMAKE_CURRENT_LIST_DIR}/layer/bindmount/virtual_fs.cpp
)

# Vendored compression (LZ4, Aether-aligned) — C source
set(VENDOR_SOURCES
    ${CMAKE_CURRENT_LIST_DIR}/third_party/lz4/lz4.c
)

add_library(aether SHARED
    ${AETHER_SOURCES}
    ${L1_SOURCES}
    ${VENDOR_SOURCES}
)

target_include_directories(aether PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/core
    ${CMAKE_CURRENT_LIST_DIR}/layer
    ${CMAKE_CURRENT_LIST_DIR}/layer/bindmount
    ${CMAKE_CURRENT_LIST_DIR}/layer/packageconf
    ${CMAKE_CURRENT_LIST_DIR}/layer/rootspoof
    ${CMAKE_CURRENT_LIST_DIR}/third_party/lz4
)

target_link_libraries(aether
    ${LOG_LIB}
    dl
)

# Compiler flags: small, fast, no bloat
target_compile_options(aether PRIVATE
    -O2
    -fvisibility=hidden
    -fno-rtti
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wno-unused-parameter
)

target_link_options(aether PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
