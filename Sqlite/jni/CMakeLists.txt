cmake_minimum_required(VERSION 3.10.0)

set(CMAKE_CXX_FLAGS "-std=c++14 -DANDROID")
set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1")
set(CMAKE_ASM_FLAGS "${CFLAGS} -x assembler-with-cpp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--exclude-libs,libbuffer.a,libwrapper.a")


if (${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
else()
    enable_language(ASM_NASM)
endif()

if (${ANDROID_ABI} STREQUAL "x86")
    set(CMAKE_ASM_NASM_COMPILER_ARG1 "${CMAKE_ASM_NASM_COMPILER_ARG1} -DPIC")
elseif (${ANDROID_ABI} STREQUAL "x86_64")
    set(CMAKE_ASM_NASM_COMPILER_ARG1 "${CMAKE_ASM_NASM_COMPILER_ARG1} -DPIC")
    set(CMAKE_ASM_NASM_FLAGS "${CMAKE_ASM_NASM_FLAGS} -DELF")
    set(CMAKE_ASM_NASM_DEBUG_FORMAT "dwarf2")
    set(CMAKE_ASM_NASM_FLAGS "${CMAKE_ASM_NASM_FLAGS} -D__x86_64__")
endif ()

add_library(buffer STATIC
        BuffersStorage.cpp
        ByteArray.cpp
        ByteStream.cpp
        FileLog.cpp
        NativeByteBuffer.cpp)
target_compile_options(buffer PUBLIC
        -Wall -frtti -finline-functions -ffast-math -Os)
set_target_properties(buffer PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(buffer PUBLIC
        HAVE_PTHREAD)


#sqlite
add_library(wrapper STATIC
        sqlite/sqlite3.c)

target_compile_options(wrapper PUBLIC
        -fno-strict-aliasing -Os)
set_target_properties(wrapper PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(wrapper PUBLIC
        NULL=0 SOCKLEN_T=socklen_t LOCALE_NOT_USED ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE HAVE_STRCHRNUL=0 ANDROID_ARMV6_IDCT)

set(NATIVE_LIB "sqlite")

add_library(${NATIVE_LIB} SHARED
        jni.c
        SqliteWrapper.cpp)

target_compile_options(${NATIVE_LIB} PUBLIC
        -ffast-math -Os -funroll-loops -ffast-math -fno-strict-aliasing -fno-math-errno)

target_compile_definitions(${NATIVE_LIB} PUBLIC
        BSD=1 NULL=0 SOCKLEN_T=socklen_t ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE __STDC_CONSTANT_MACROS ANDROID_ARMV6_IDCT OPUS_BUILD FIXED_POINT USE_ALLOCA restrict= __EMX__ LOCALE_NOT_USED HAVE_LRINT HAVE_LRINTF)

if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set_target_properties(${NATIVE_LIB} PROPERTIES
            ANDROID_ARM_NEON FALSE
            ANDROID_ARM_MODE arm)
    target_compile_definitions(${NATIVE_LIB} PUBLIC
            ANDROID_ARM_NEON=false)
else()
    set_target_properties(${NATIVE_LIB} PROPERTIES
            ANDROID_ARM_NEON FALSE
            ANDROID_ARM_MODE arm)
endif()

if (${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(${NATIVE_LIB} PUBLIC
            LIBYUV_NEON OPUS_HAVE_RTCD OPUS_ARM_ASM)


elseif(${ANDROID_ABI} STREQUAL "x86")
    target_compile_definitions(${NATIVE_LIB} PUBLIC
            x86fix)
endif()

target_link_libraries(${NATIVE_LIB}
        -Wl,--no-whole-archive
        buffer
        wrapper
        jnigraphics
        log
        z
        GLESv2
        android
        OpenSLES
        cpufeatures)

if (${ANDROID_ABI} STREQUAL "x86" OR ${ANDROID_ABI} STREQUAL "x86_64")
    target_link_libraries(${NATIVE_LIB}
            -Wl,--no-whole-archive)
endif()

#if (${ANDROID_ABI} STREQUAL "x86" OR ${ANDROID_ABI} STREQUAL "x86_64")
#    target_link_libraries(${NATIVE_LIB}
#            -Wl,--whole-archive vpxasm -Wl,--no-whole-archive
#            c)
#endif()

include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()