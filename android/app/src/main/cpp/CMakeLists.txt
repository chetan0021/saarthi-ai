cmake_minimum_required(VERSION 3.22.1)
project(saarthi_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(saarthi_native SHARED
        saarthi_native.cpp
        neon_dot.cpp)

target_compile_options(saarthi_native PRIVATE -O3 -Wall -Wextra -fvisibility=hidden)

# The SDOT kernel needs the Armv8.2 dot-product extension; it is only called at runtime
# when HWCAP_ASIMDDP is present, so the rest of the library stays baseline Armv8-A.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set_source_files_properties(neon_dot.cpp PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
endif ()

find_library(log-lib log)
target_link_libraries(saarthi_native ${log-lib})
