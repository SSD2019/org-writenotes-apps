// C++ unity translation unit for Stylus Labs Write core (syncscribble + usvg + ulib + pugixml)
#define ORGWRITENOTES_NO_JNI 1
#define PUGIXML_NO_XPATH 1
#define PUGIXML_NO_EXCEPTIONS 1
#define NO_PAINTER_GL 1
#define NO_PAINTER_SWU 1
#ifndef SCRIBBLE_REV_NUMBER
#define SCRIBBLE_REV_NUMBER "orgwritenotes-ios"
#endif

#include "shared_headers/Write/syncscribble/basics.cpp"
#include "shared_headers/Write/syncscribble/document.cpp"
#include "shared_headers/Write/syncscribble/element.cpp"
#include "shared_headers/Write/syncscribble/page.cpp"
#include "shared_headers/Write/syncscribble/strokebuilder.cpp"
#include "shared_headers/Write/syncscribble/syncundo.cpp"
#include "shared_headers/Write/usvg/svgnode.cpp"
#include "shared_headers/Write/usvg/svgpainter.cpp"
#include "shared_headers/Write/usvg/svgparser.cpp"
#include "shared_headers/Write/usvg/svgstyleparser.cpp"
#include "shared_headers/Write/usvg/cssparser.cpp"
#include "shared_headers/Write/usvg/svgwriter.cpp"
#include "shared_headers/Write/ulib/geom.cpp"
#include "shared_headers/Write/ulib/image.cpp"
#include "shared_headers/Write/ulib/path2d.cpp"
#include "shared_headers/Write/ulib/painter.cpp"
#include "shared_headers/Write/pugixml/src/pugixml.cpp"
