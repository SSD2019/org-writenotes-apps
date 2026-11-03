cmake_minimum_required(VERSION 3.22.1)
project("orgwritenotes_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to the cloned Stylus Labs Write repository
set(WRITE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../cpp/Write")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WRITE_ROOT}
    ${WRITE_ROOT}/syncscribble
    ${WRITE_ROOT}/ulib
    ${WRITE_ROOT}/usvg
    ${WRITE_ROOT}/nanovgXC/src
    ${WRITE_ROOT}/pugixml/src
    ${WRITE_ROOT}/miniz
    ${WRITE_ROOT}/stb
)

# Core Stylus Labs Write document, page, stroke builder, and SVG/BGZ engine sources
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
    SCRIBBLE_REV_NUMBER="orgwritenotes"
)

add_library(
    orgwritenotes_jni
    SHARED
    write_bridge_jni.cpp
    ${WRITE_CORE_SOURCES}
)

if(ANDROID)
    find_library(log-lib log)
    find_library(android-lib android)
    find_library(jnigraphics-lib jnigraphics)
    find_library(gles3-lib GLESv3)

    target_link_libraries(
        orgwritenotes_jni
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${gles3-lib}
    )
endif()
