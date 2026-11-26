cmake_minimum_required(VERSION 3.18.1)
project("deltaforce-native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Memory library
add_library(
    memory
    SHARED
    native-lib.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    memory
    ${log-lib}
    ${android-lib}
)

# UE4 Parser library
add_library(
    ue4parser
    SHARED
    ue4parser.cpp
)

target_link_libraries(
    ue4parser
    ${log-lib}
    ${android-lib}
    memory
)

# Input Injector library (uinput)
add_library(
    input_injector
    SHARED
    input_injector.cpp
)

target_link_libraries(
    input_injector
    ${log-lib}
    ${android-lib}
)