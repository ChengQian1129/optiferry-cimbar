cmake_minimum_required(VERSION 3.22.1)

project(optiferry-cimbar LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(DISABLE_TESTS TRUE CACHE BOOL "Do not build libcimbar's host tests in the Android app")

# The OpenCV Android AAR publishes its headers and libopencv_java4.so through
# Prefab. The same imported target is used by the native CFC decoder and by
# the small JNI adapter below.
find_package(OpenCV CONFIG REQUIRED)
set(OPENCV_LIBS OpenCV::opencv_java4)
find_library(log-lib log)

# libcimbar is vendored as a group of static subdirectories.  Those targets
# link OpenCV only through the final CFC target, so the Prefab imported target's
# usage requirements do not reach their own compile steps automatically.
get_target_property(OPENCV_INCLUDE_DIRS OpenCV::opencv_java4 INTERFACE_INCLUDE_DIRECTORIES)
if (NOT OPENCV_INCLUDE_DIRS)
    message(FATAL_ERROR "OpenCV::opencv_java4 does not expose its Prefab include directory")
endif()
include_directories(SYSTEM ${OPENCV_INCLUDE_DIRS})

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libcimbar/src/lib
    ${CMAKE_CURRENT_SOURCE_DIR}/libcimbar/src/third_party_lib
)

add_subdirectory(libcimbar)
add_subdirectory(concurrent)
add_subdirectory(cfc-cpp)

add_library(cimbar-native SHARED CimbarNative.cpp)
target_compile_options(cimbar-native PRIVATE -fexceptions -frtti)
target_link_libraries(cimbar-native PRIVATE cfc-cpp ${OPENCV_LIBS} ${log-lib})
