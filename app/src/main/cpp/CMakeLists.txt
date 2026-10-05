cmake_minimum_required(VERSION 3.22.1)
project(qingyu_pinyin LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# AOSP PinyinIME decoder only. Android system-private service/JNI APIs are not used.
file(GLOB PINYIN_DECODER_SOURCES CONFIGURE_DEPENDS "aosp/share/*.cpp")
add_library(qingyu_pinyin SHARED qingyu_jni.cpp ${PINYIN_DECODER_SOURCES})
target_include_directories(qingyu_pinyin PRIVATE compat aosp/include)
target_compile_options(qingyu_pinyin PRIVATE -O2 -fvisibility=hidden)
if(ANDROID)
    target_link_libraries(qingyu_pinyin PRIVATE log)
    # Android 15+ supports devices using 16 KiB pages.
    target_link_options(qingyu_pinyin PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(qingyu_pinyin PRIVATE ${JNI_INCLUDE_DIRS})
endif()
