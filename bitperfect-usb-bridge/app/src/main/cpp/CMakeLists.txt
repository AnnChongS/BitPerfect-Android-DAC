cmake_minimum_required(VERSION 3.22.1)
project(bitperfect_usb_audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ------------------------------------------------------------------------------
# 1. Integrate libusb-1.0 (static build for Android usbfs backend)
#    - Uses local app/src/main/cpp/third_party/libusb if present (offline/vendored)
#    - Otherwise fetches official libusb v1.0.27 automatically via FetchContent
# ------------------------------------------------------------------------------
set(LOCAL_LIBUSB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb")

if(EXISTS "${LOCAL_LIBUSB_DIR}/libusb/core.c")
    message(STATUS "BitPerfect: Using local vendored libusb at ${LOCAL_LIBUSB_DIR}")
    set(LIBUSB_SRC_DIR "${LOCAL_LIBUSB_DIR}")
else()
    message(STATUS "BitPerfect: Fetching official libusb v1.0.27 via FetchContent")
    include(FetchContent)
    FetchContent_Declare(
        libusb_upstream
        GIT_REPOSITORY https://github.com/libusb/libusb.git
        GIT_TAG        v1.0.27
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(libusb_upstream)
    set(LIBUSB_SRC_DIR "${libusb_upstream_SOURCE_DIR}")
endif()

# Exact source list mirroring upstream libusb/android/jni/libusb.mk
set(LIBUSB_SOURCES
    ${LIBUSB_SRC_DIR}/libusb/core.c
    ${LIBUSB_SRC_DIR}/libusb/descriptor.c
    ${LIBUSB_SRC_DIR}/libusb/hotplug.c
    ${LIBUSB_SRC_DIR}/libusb/io.c
    ${LIBUSB_SRC_DIR}/libusb/sync.c
    ${LIBUSB_SRC_DIR}/libusb/strerror.c
    ${LIBUSB_SRC_DIR}/libusb/os/events_posix.c
    ${LIBUSB_SRC_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_SRC_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_SRC_DIR}/libusb/os/linux_netlink.c
)

add_library(usb-1.0 STATIC ${LIBUSB_SOURCES})

target_compile_definitions(usb-1.0 PRIVATE
    HAVE_CONFIG_H
)

target_include_directories(usb-1.0
    PRIVATE
        ${LIBUSB_SRC_DIR}/android      # Pre-configured Android config.h
        ${LIBUSB_SRC_DIR}/libusb/os
    PUBLIC
        ${LIBUSB_SRC_DIR}/libusb       # Exposes <libusb.h>
)

target_compile_options(usb-1.0 PRIVATE
    $<$<CONFIG:Debug>:-O2>
    $<$<CONFIG:Release>:-O3>
    -fPIC
    -Wno-unused-parameter
    -Wno-sign-compare
)

find_library(ANDROID_LOG_LIB log)
target_link_libraries(usb-1.0 PRIVATE ${ANDROID_LOG_LIB})

# ------------------------------------------------------------------------------
# 2. BitPerfect Native USB Audio Engine
#    Phase 1: Wrap FD + UAC1/UAC2 Parser
#    Phase 2: SPSC RingBuffer + Clock Switch + Async/Adaptive ISO OUT + HW Volume
# ------------------------------------------------------------------------------
add_library(bitperfect_usb_audio SHARED
    src/uac_parser.cpp
    src/usb_iso_streamer.cpp
    src/usb_dac_engine.cpp
    src/jni_bridge.cpp
)

target_include_directories(bitperfect_usb_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(bitperfect_usb_audio PRIVATE
    $<$<CONFIG:Debug>:-O2>
    $<$<CONFIG:Release>:-O3>
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror=return-type
)

# Guarantee 16KB ELF segment alignment for Android 15-17 devices
target_link_options(bitperfect_usb_audio PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(bitperfect_usb_audio
    PRIVATE
        usb-1.0
        ${ANDROID_LOG_LIB}
)
