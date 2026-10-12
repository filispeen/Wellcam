cmake_minimum_required(VERSION 3.21)
project(wincam_client LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(FFMPEG REQUIRED)
find_path(LIBUSB_INCLUDE_DIR libusb.h PATH_SUFFIXES libusb-1.0 REQUIRED)
find_library(LIBUSB_LIBRARY NAMES libusb-1.0 usb-1.0 REQUIRED)

add_executable(wincam_client
    src/main.cpp
    src/protocol.cpp
    src/tcp_transport.cpp
    src/aoa_transport.cpp
    src/decoder.cpp
    src/renderer.cpp
    src/vcam.cpp)

target_include_directories(wincam_client PRIVATE
    src
    bridge
    ${FFMPEG_INCLUDE_DIRS}
    ${LIBUSB_INCLUDE_DIR})

target_link_directories(wincam_client PRIVATE ${FFMPEG_LIBRARY_DIRS})

target_link_libraries(wincam_client PRIVATE
    ${FFMPEG_LIBRARIES}
    ${LIBUSB_LIBRARY}
    ws2_32
    advapi32
    ole32
    mfplat
    mf
    mfuuid
    mfsensorgroup)

target_compile_definitions(wincam_client PRIVATE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    _CRT_SECURE_NO_WARNINGS)

if(MSVC)
    target_compile_options(wincam_client PRIVATE /W3 /utf-8)
endif()
