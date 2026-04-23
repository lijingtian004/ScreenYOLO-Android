cmake_minimum_required(VERSION 3.10)
project(yolov8ncnn)

set(CMAKE_CXX_STANDARD 11)

set(NCNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)

# Use ncnn cmake config if available
set(ncnn_DIR ${NCNN_ROOT}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(yolov8ncnn SHARED yolov8ncnn.cpp)

target_include_directories(yolov8ncnn PRIVATE ${NCNN_ROOT}/include)

find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)

target_link_libraries(yolov8ncnn 
    ncnn
    ${jnigraphics-lib}
    ${log-lib})
