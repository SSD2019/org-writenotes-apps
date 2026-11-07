// C++ unity translation unit for Stylus Labs Write core (syncscribble + usvg + ulib + pugixml)
#define ORGWRITENOTES_NO_JNI 1
#define PUGIXML_NO_XPATH 1
#define PUGIXML_NO_EXCEPTIONS 1
#define NO_PAINTER_GL 1
#define NO_PAINTER_SWU 1
#ifndef SCRIBBLE_REV_NUMBER
#define SCRIBBLE_REV_NUMBER "orgwritenotes-ios"
#endif

#include "../../../android/cpp/Write/syncscribble/basics.cpp"
#include "../../../android/cpp/Write/syncscribble/document.cpp"
#include "../../../android/cpp/Write/syncscribble/element.cpp"
#include "../../../android/cpp/Write/syncscribble/page.cpp"
#include "../../../android/cpp/Write/syncscribble/strokebuilder.cpp"
#include "../../../android/cpp/Write/syncscribble/syncundo.cpp"
#include "../../../android/cpp/Write/usvg/svgnode.cpp"
#include "../../../android/cpp/Write/usvg/svgpainter.cpp"
#include "../../../android/cpp/Write/usvg/svgparser.cpp"
#include "../../../android/cpp/Write/usvg/svgstyleparser.cpp"
#include "../../../android/cpp/Write/usvg/cssparser.cpp"
#include "../../../android/cpp/Write/usvg/svgwriter.cpp"
#include "../../../android/cpp/Write/ulib/geom.cpp"
#include "../../../android/cpp/Write/ulib/image.cpp"
#include "../../../android/cpp/Write/ulib/path2d.cpp"
#include "../../../android/cpp/Write/ulib/painter.cpp"
#include "../../../android/cpp/Write/pugixml/src/pugixml.cpp"
