cmake_minimum_required(VERSION 3.10)
project(yolov8ncnn)

set(CMAKE_CXX_STANDARD 11)

set(NCNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)

add_library(yolov8ncnn SHARED yolov8ncnn.cpp)

target_include_directories(yolov8ncnn PRIVATE ${NCNN_ROOT}/include/ncnn)

find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)

# Vulkan support (optional, for GPU acceleration)
find_library(vulkan-lib vulkan)

# Link ncnn static library
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_ROOT}/lib/libncnn.a)

target_link_libraries(yolov8ncnn
    ncnn
    ${jnigraphics-lib}
    ${log-lib}
    ${vulkan-lib})
