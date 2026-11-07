cmake_minimum_required(VERSION 3.22.1)
project("org_writenotes_kindle" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(KINDLE_STATIC_LINK "Link C/C++ runtime statically for standalone Kindle Scribe deployment" OFF)
option(KINDLE_BUILD_TESTS "Build Kindle Scribe unit and protocol golden tests" ON)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(WRITE_ROOT "${REPO_ROOT}/android/cpp/Write")
set(BRIDGE_ROOT "${REPO_ROOT}/android/app/src/main/cpp")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${BRIDGE_ROOT}
    ${WRITE_ROOT}
    ${WRITE_ROOT}/syncscribble
    ${WRITE_ROOT}/ulib
    ${WRITE_ROOT}/usvg
    ${WRITE_ROOT}/nanovgXC/src
    ${WRITE_ROOT}/pugixml/src
    ${WRITE_ROOT}/miniz
    ${WRITE_ROOT}/stb
)

set(WRITE_CORE_SOURCES
    ${WRITE_ROOT}/syncscribble/basics.cpp
    ${WRITE_ROOT}/syncscribble/document.cpp
    ${WRITE_ROOT}/syncscribble/element.cpp
    ${WRITE_ROOT}/syncscribble/page.cpp
    ${WRITE_ROOT}/syncscribble/strokebuilder.cpp
    ${WRITE_ROOT}/syncscribble/syncundo.cpp
    ${WRITE_ROOT}/usvg/svgnode.cpp
    ${WRITE_ROOT}/usvg/svgpainter.cpp
    ${WRITE_ROOT}/usvg/svgparser.cpp
    ${WRITE_ROOT}/usvg/svgstyleparser.cpp
    ${WRITE_ROOT}/usvg/cssparser.cpp
    ${WRITE_ROOT}/usvg/svgwriter.cpp
    ${WRITE_ROOT}/ulib/geom.cpp
    ${WRITE_ROOT}/ulib/image.cpp
    ${WRITE_ROOT}/ulib/path2d.cpp
    ${WRITE_ROOT}/ulib/painter.cpp
    ${WRITE_ROOT}/nanovgXC/src/nanovg.c
    ${WRITE_ROOT}/pugixml/src/pugixml.cpp
    ${WRITE_ROOT}/miniz/miniz.c
    ${WRITE_ROOT}/miniz/miniz_tdef.c
    ${WRITE_ROOT}/miniz/miniz_tinfl.c
)

add_compile_definitions(
    PUGIXML_NO_XPATH
    PUGIXML_NO_EXCEPTIONS
    NO_PAINTER_GL
    NO_PAINTER_SWU
    ORGWRITENOTES_NO_JNI=1
    SCRIBBLE_REV_NUMBER="orgwritenotes-kindle"
)

add_library(
    write_engine_kindle
    STATIC
    ${BRIDGE_ROOT}/write_bridge_jni.cpp
    ${WRITE_CORE_SOURCES}
)

target_compile_options(
    write_engine_kindle
    PRIVATE
    -O3
    -DNDEBUG
    -ffast-math
    -fno-math-errno
)

add_library(
    kindle_scribe_core
    STATIC
    src/eink_fb.cpp
    src/scribe_input.cpp
    src/ws_protocol.cpp
    src/scribe_app.cpp
)

target_link_libraries(
    kindle_scribe_core
    PUBLIC
    write_engine_kindle
)

add_executable(
    org-writenotes-kindle
    src/main.cpp
)

find_package(Threads REQUIRED)
target_link_libraries(
    org-writenotes-kindle
    PRIVATE
    kindle_scribe_core
    Threads::Threads
    m
)

if(KINDLE_STATIC_LINK)
    target_link_options(
        org-writenotes-kindle
        PRIVATE
        -static-libstdc++
        -static-libgcc
    )
endif()

if(KINDLE_BUILD_TESTS)
    enable_testing()
    add_executable(
        kindle_tests
        tests/kindle_tests.cpp
    )
    target_link_libraries(
        kindle_tests
        PRIVATE
        kindle_scribe_core
        Threads::Threads
        m
    )
    add_test(
        NAME KindleScribeUnitAndProtocolTests
        COMMAND kindle_tests "${REPO_ROOT}/test-fixtures/protocol-golden.json"
    )
endif()

