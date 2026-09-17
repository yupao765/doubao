cmake_minimum_required(VERSION 3.22.1)
project(doubao_echo C)
include(FetchContent)
FetchContent_Declare(speex
    URL https://downloads.xiph.org/releases/speex/speexdsp-1.2.1.tar.gz
    URL_HASH SHA256=8c777343e4a6399569c72abc38a95b24db56882c83dbdb6c6424a5f4aeb54d3d)
FetchContent_GetProperties(speex)
if(NOT speex_POPULATED)
    FetchContent_Populate(speex)
endif()
set(SPX ${speex_SOURCE_DIR}/libspeexdsp)
add_library(doubao_echo SHARED echo_jni.c ${SPX}/mdf.c ${SPX}/preprocess.c
    ${SPX}/fftwrap.c ${SPX}/filterbank.c ${SPX}/kiss_fft.c ${SPX}/kiss_fftr.c)
target_include_directories(doubao_echo PRIVATE ${speex_SOURCE_DIR}/include ${SPX} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(doubao_echo PRIVATE FLOATING_POINT USE_KISS_FFT EXPORT=)
target_compile_options(doubao_echo PRIVATE -O2)
target_link_options(doubao_echo PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(doubao_echo m)
