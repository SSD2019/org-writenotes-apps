// C dependencies for WriteEngineC (nanovg + miniz)
#define PUGIXML_NO_XPATH 1
#define PUGIXML_NO_EXCEPTIONS 1
#define NO_PAINTER_GL 1
#define NO_PAINTER_SWU 1

#include "shared_headers/Write/nanovgXC/src/nanovg.c"
#include "shared_headers/Write/miniz/miniz.c"
#include "shared_headers/Write/miniz/miniz_tdef.c"
#include "shared_headers/Write/miniz/miniz_tinfl.c"
