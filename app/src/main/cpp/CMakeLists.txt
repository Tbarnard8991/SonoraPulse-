cmake_minimum_required(VERSION 3.22.1)
project("sonorapulse")

find_package(oboe REQUIRED CONFIG)

add_library(sonorapulse SHARED
    AudioEngine.cpp
    native-lib.cpp
)

# Belt-and-braces 16 KB page alignment (NDK r28+ already does this by default).
target_link_options(sonorapulse PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(sonorapulse
    oboe::oboe
    log
)
