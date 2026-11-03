#define FONTSTASH_IMPLEMENTATION
#include "fontstash.h"

#include "nanovg.h"
#define NANOVG_SW_IMPLEMENTATION
#define NVGSW_QUIET_FRAME
#include "nanovg_sw.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define MINIZ_GZ_IMPLEMENTATION
#include "ulib/miniz_gzip.h"

#define PLATFORMUTIL_IMPLEMENTATION
#include "ulib/platformutil.h"

#define STRINGUTIL_IMPLEMENTATION
#include "ulib/stringutil.h"

#define FILEUTIL_IMPLEMENTATION
#include "ulib/fileutil.h"

#include "write_bridge_jni.h"
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstring>

// Stylus Labs Write core headers (from android/cpp/Write/syncscribble & submodules)
#include "document.h"
#include "page.h"
#include "strokebuilder.h"
#include "scribblepen.h"
#include "syncundo.h"
#include "ulib/painter.h"
#include "ulib/image.h"
#include "usvg/svgpainter.h"
#include "usvg/svgparser.h"
#include "usvg/svgwriter.h"

namespace orgwritenotes {

static constexpr int AUTO_EXPAND_MARGIN_PX = 120;
static constexpr int AUTO_EXPAND_STEP_PX   = 320;

static void ensureSharedBoundsCalcInitialized() {
    if (!SvgDocument::sharedBoundsCalc) {
        static Painter s_boundsPainter(Painter::PAINT_NULL | Painter::NO_TEXT);
        static SvgPainter s_boundsCalc(&s_boundsPainter);
        SvgDocument::sharedBoundsCalc = &s_boundsCalc;
    }
    Element::ERASE_IMAGES = true;
}

static bool isPointInPolygon(float px, float py, const float* xyPoints, int numFloats) {
    int n = numFloats / 2;
    if (n < 3) return false;
    bool inside = false;
    for (int i = 0, j = n - 1; i < n; j = i++) {
        float xi = xyPoints[i * 2];
        float yi = xyPoints[i * 2 + 1];
        float xj = xyPoints[j * 2];
        float yj = xyPoints[j * 2 + 1];
        bool intersect = ((yi > py) != (yj > py)) &&
            (px < (xj - xi) * (py - yi) / ((yj - yi) != 0.0f ? (yj - yi) : 1e-6f) + xi);
        if (intersect) inside = !inside;
    }
    return inside;
}

static const char* rulingStyleToName(int style) {
    switch (style) {
        case RULING_DOTTED:          return "dotted";
        case RULING_ENGINEER_GRID:   return "engineer";
        case RULING_ISOMETRIC:       return "isometric";
        case RULING_MUSIC_STAFF:     return "music";
        case RULING_CORNELL_SUMMARY: return "cornell";
        case RULING_WEEKLY_PLANNER:  return "weekly";
        case RULING_CUSTOM_DESIGNER: return "custom";
        default:                     return "standard";
    }
}

static int parseRulingStyleName(const char* name) {
    if (!name || !name[0]) return RULING_STANDARD;
    if (std::strcmp(name, "dotted") == 0) return RULING_DOTTED;
    if (std::strcmp(name, "engineer") == 0) return RULING_ENGINEER_GRID;
    if (std::strcmp(name, "isometric") == 0) return RULING_ISOMETRIC;
    if (std::strcmp(name, "music") == 0) return RULING_MUSIC_STAFF;
    if (std::strcmp(name, "cornell") == 0) return RULING_CORNELL_SUMMARY;
    if (std::strcmp(name, "weekly") == 0) return RULING_WEEKLY_PLANNER;
    if (std::strcmp(name, "custom") == 0) return RULING_CUSTOM_DESIGNER;
    return RULING_STANDARD;
}

NativeCanvasSession::NativeCanvasSession(int width, int height, int xRuling, int yRuling)
    : m_width(width),
      m_height(height),
      m_xRuling(xRuling),
      m_yRuling(yRuling),
      m_doc(std::make_unique<Document>()) {
    ensureSharedBoundsCalcInitialized();
    PageProperties props(
        static_cast<Dim>(width),
        static_cast<Dim>(height),
        static_cast<Dim>(xRuling),
        static_cast<Dim>(yRuling),
        0,
        Color::fromArgb(0xFFFFFDF8).opaque(),
        Color::fromArgb(0xFF4A90E2)
    );
    m_page = new Page(props);
    if (m_page->svgDoc) {
        m_page->svgDoc->setViewBox(Rect());
    }
    m_doc->insertPage(m_page);
}

NativeCanvasSession::~NativeCanvasSession() = default;

void NativeCanvasSession::retain() {
    m_refCount.fetch_add(1, std::memory_order_relaxed);
}

void NativeCanvasSession::release() {
    if (m_refCount.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete this;
    }
}

void NativeCanvasSession::detectRulingStyleFromLoadedPage() {
    if (!m_page || !m_page->contentNode) {
        m_rulingStyle = RULING_STANDARD;
        return;
    }
    const char* styleAttr = m_page->contentNode->getStringAttr("rulingstyle", nullptr);
    if (styleAttr && styleAttr[0]) {
        m_rulingStyle = parseRulingStyleName(styleAttr);
    } else if (m_page->isCustomRuling) {
        m_rulingStyle = RULING_CUSTOM_DESIGNER;
    } else {
        m_rulingStyle = RULING_STANDARD;
    }
}

void NativeCanvasSession::rebuildProceduralRuleLayer() {
    if (!m_page || !m_page->svgDoc) return;
    if (m_page->contentNode) {
        m_page->contentNode->setAttr("rulingstyle", rulingStyleToName(m_rulingStyle));
    }

    if (m_rulingStyle == RULING_STANDARD) {
        m_page->isCustomRuling = false;
        m_page->onPageSizeChange();
        return;
    }

    if (m_rulingStyle == RULING_CUSTOM_DESIGNER) {
        m_page->isCustomRuling = true;
        if (m_page->ruleNode) {
            SvgNode* pagerect = m_page->ruleNode->selectFirst(".pagerect");
            if (pagerect && pagerect->type() == SvgNode::RECT) {
                static_cast<SvgRect*>(pagerect)->setRect(Rect::wh(m_page->props.width, m_page->props.height));
                setSvgFillColor(pagerect, m_page->props.color);
            }
        }
        m_page->onPageSizeChange();
        return;
    }

    // Procedural custom template layer (Dotted, Engineering Grid, Isometric, Music Staff, Cornell Summary)
    m_page->isCustomRuling = true;
    SvgContainerNode* oldRuleNode = m_page->ruleNode;
    auto* newRuleNode = new SvgG;
    setSvgFillColor(newRuleNode, Color::NONE);
    setSvgStrokeColor(newRuleNode, m_page->props.ruleColor);
    newRuleNode->setAttr<float>("stroke-width", 1.1f);
    newRuleNode->setAttribute("vector-effect", "non-scaling-stroke");
    newRuleNode->addClass("ruleline");
    newRuleNode->addClass("customruling");
    newRuleNode->addClass("write-scale-down");

    SvgNode* before = m_page->svgDoc->children().empty() ? nullptr : m_page->svgDoc->children().front();
    m_page->svgDoc->addChild(newRuleNode, oldRuleNode ? oldRuleNode : before);
    new Element(newRuleNode);
    if (oldRuleNode) {
        m_page->svgDoc->removeChild(oldRuleNode);
        delete oldRuleNode;
    }
    m_page->ruleNode = newRuleNode;

    const Dim w = m_page->props.width;
    const Dim h = m_page->props.height;
    const Dim xStep = m_page->props.xRuling > 0 ? m_page->props.xRuling : 40;
    const Dim yStep = m_page->props.yRuling > 0 ? m_page->props.yRuling : 40;

    // 1. Background paper rect
    auto* bgRect = new SvgRect(Rect::wh(w, h));
    setSvgFillColor(bgRect, m_page->props.color);
    setSvgStrokeColor(bgRect, Color::NONE);
    bgRect->addClass("pagerect");
    newRuleNode->addChild(bgRect);

    if (m_rulingStyle == RULING_DOTTED) {
        // Dot Grid: crisp circular/diamond dots partitioned into vertical bands for fast dirty-rect culling
        const Dim r = 1.6f;
        const Dim bandHeight = 512.0f;
        for (Dim bandTop = 0; bandTop < h; bandTop += bandHeight) {
            const Dim bandBottom = std::min(h, bandTop + bandHeight);
            Path2D dotsPath;
            Dim firstY = std::max(yStep, std::ceil(bandTop / yStep) * yStep);
            if (firstY < bandTop && firstY > 0) firstY += yStep;
            for (Dim y = firstY; y < bandBottom; y += yStep) {
                for (Dim x = xStep; x < w; x += xStep) {
                    dotsPath.moveTo(Point(x - r, y));
                    dotsPath.lineTo(Point(x + r, y));
                    dotsPath.moveTo(Point(x, y - r));
                    dotsPath.lineTo(Point(x, y + r));
                }
            }
            if (!dotsPath.empty()) {
                auto* dotNode = new SvgPath(dotsPath);
                dotNode->setAttr<float>("stroke-width", 2.2f);
                dotNode->setAttr<int>("stroke-linecap", Painter::RoundCap);
                newRuleNode->addChild(dotNode);
            }
        }
    } else if (m_rulingStyle == RULING_ENGINEER_GRID) {
        // Engineering Graph Paper: subtle minor grid (1x) + bold major grid (5x), partitioned into bands
        Color minorColor = m_page->props.ruleColor;
        minorColor.setAlphaF(0.35f);
        Color majorColor = m_page->props.ruleColor;
        majorColor.setAlphaF(0.85f);

        const Dim bandHeight = 512.0f;
        int rowIdx = 1;
        Dim y = yStep;
        while (y < h) {
            const Dim bandLimit = y + bandHeight;
            Path2D minorRows;
            Path2D majorRows;
            for (; y < h && y < bandLimit; y += yStep, ++rowIdx) {
                if (rowIdx % 5 == 0) {
                    majorRows.addLine(Point(0, y), Point(w, y));
                } else {
                    minorRows.addLine(Point(0, y), Point(w, y));
                }
            }
            if (!minorRows.empty()) {
                auto* minorNode = new SvgPath(minorRows);
                setSvgStrokeColor(minorNode, minorColor);
                minorNode->setAttr<float>("stroke-width", 0.8f);
                newRuleNode->addChild(minorNode);
            }
            if (!majorRows.empty()) {
                auto* majorNode = new SvgPath(majorRows);
                setSvgStrokeColor(majorNode, majorColor);
                majorNode->setAttr<float>("stroke-width", 1.6f);
                newRuleNode->addChild(majorNode);
            }
        }

        const Dim colBandWidth = 512.0f;
        int colIdx = 1;
        Dim x = xStep;
        while (x < w) {
            const Dim colLimit = x + colBandWidth;
            Path2D minorCols;
            Path2D majorCols;
            for (; x < w && x < colLimit; x += xStep, ++colIdx) {
                if (colIdx % 5 == 0) {
                    majorCols.addLine(Point(x, 0), Point(x, h));
                } else {
                    minorCols.addLine(Point(x, 0), Point(x, h));
                }
            }
            if (!minorCols.empty()) {
                auto* minorNode = new SvgPath(minorCols);
                setSvgStrokeColor(minorNode, minorColor);
                minorNode->setAttr<float>("stroke-width", 0.8f);
                newRuleNode->addChild(minorNode);
            }
            if (!majorCols.empty()) {
                auto* majorNode = new SvgPath(majorCols);
                setSvgStrokeColor(majorNode, majorColor);
                majorNode->setAttr<float>("stroke-width", 1.6f);
                newRuleNode->addChild(majorNode);
            }
        }
    } else if (m_rulingStyle == RULING_ISOMETRIC) {
        // Isometric 30° / 60° triangle grid
        Path2D isoPath;
        const Dim dy = yStep;
        const Dim dx = dy * 1.7320508f; // sqrt(3) * dy for 30 deg isometric lines
        // Vertical columns every dx/2
        for (Dim x = dx * 0.5f; x < w; x += dx * 0.5f) {
            isoPath.addLine(Point(x, 0), Point(x, h));
        }
        // Diagonal +30 deg and -30 deg lines
        const int maxSteps = static_cast<int>((w + h * 2.0f) / dy) + 4;
        for (int k = -maxSteps; k <= maxSteps; ++k) {
            Dim y0 = k * dy;
            Dim y1 = y0 + (w / dx) * dy;
            isoPath.addLine(Point(0, y0), Point(w, y1));

            Dim y2 = k * dy;
            Dim y3 = y2 - (w / dx) * dy;
            isoPath.addLine(Point(0, y2), Point(w, y3));
        }
        auto* isoNode = new SvgPath(isoPath);
        Color isoColor = m_page->props.ruleColor;
        isoColor.setAlphaF(0.45f);
        setSvgStrokeColor(isoNode, isoColor);
        isoNode->setAttr<float>("stroke-width", 0.9f);
        newRuleNode->addChild(isoNode);
    } else if (m_rulingStyle == RULING_MUSIC_STAFF) {
        // 5-line Music Staves with left/right bar borders, one SvgPath per staff for fast culling
        const Dim lineGap = std::max<Dim>(10.0f, std::round(yStep * 0.3f));
        const Dim staffBlock = lineGap * 4.0f + std::max<Dim>(42.0f, yStep * 1.2f);
        const Dim leftPad = m_page->props.marginLeft > 0 ? m_page->props.marginLeft : 48.0f;
        const Dim rightPad = std::max<Dim>(leftPad + 100.0f, w - 48.0f);
        for (Dim baseY = 48.0f; baseY + lineGap * 4.0f < h - 24.0f; baseY += staffBlock) {
            Path2D staffPath;
            for (int l = 0; l < 5; ++l) {
                Dim ly = baseY + l * lineGap;
                staffPath.addLine(Point(leftPad, ly), Point(rightPad, ly));
            }
            // Left and right staff barlines
            staffPath.addLine(Point(leftPad, baseY), Point(leftPad, baseY + lineGap * 4.0f));
            staffPath.addLine(Point(rightPad, baseY), Point(rightPad, baseY + lineGap * 4.0f));
            auto* staffNode = new SvgPath(staffPath);
            staffNode->setAttr<float>("stroke-width", 1.2f);
            newRuleNode->addChild(staffNode);
        }
    } else if (m_rulingStyle == RULING_CORNELL_SUMMARY) {
        // Cornell Note-Taking Template: Header bar, Left cue column, Ruled notes area, Bottom summary box
        const Dim headerY = std::max<Dim>(yStep * 2.0f, 72.0f);
        const Dim summaryY = std::max<Dim>(headerY + yStep * 3.0f, h - std::max<Dim>(yStep * 3.0f, 120.0f));
        const Dim cueX = m_page->props.marginLeft > 0 ? m_page->props.marginLeft : 220.0f;

        Path2D notesRulePath;
        for (Dim y = headerY + yStep; y < summaryY; y += yStep) {
            notesRulePath.addLine(Point(cueX, y), Point(w, y));
        }
        auto* notesNode = new SvgPath(notesRulePath);
        newRuleNode->addChild(notesNode);

        Path2D framePath;
        framePath.addLine(Point(0, headerY), Point(w, headerY));
        framePath.addLine(Point(cueX, headerY), Point(cueX, summaryY));
        framePath.addLine(Point(0, summaryY), Point(w, summaryY));
        auto* frameNode = new SvgPath(framePath);
        setSvgStrokeColor(frameNode, Color::fromArgb(0xFFE11D48)); // Classic Cornell red/rose divider lines
        frameNode->setAttr<float>("stroke-width", 1.8f);
        newRuleNode->addChild(frameNode);
    } else if (m_rulingStyle == RULING_WEEKLY_PLANNER) {
        // Weekly planner: header bar, label column, and 7 equal day rows with light ruled lines
        const Dim headerY = std::max<Dim>(yStep * 1.5f, 60.0f);
        const Dim labelX = m_page->props.marginLeft > 0 ? m_page->props.marginLeft : 150.0f;
        const Dim rowH = (h - headerY) / 7.0f;

        Path2D linesPath;
        for (int day = 0; day < 7; ++day) {
            const Dim top = headerY + day * rowH;
            for (Dim y = top + yStep; y < top + rowH - 2.0f; y += yStep) {
                linesPath.addLine(Point(labelX, y), Point(w, y));
            }
        }
        auto* linesNode = new SvgPath(linesPath);
        Color lineColor = m_page->props.ruleColor;
        lineColor.setAlphaF(0.45f);
        setSvgStrokeColor(linesNode, lineColor);
        linesNode->setAttr<float>("stroke-width", 0.8f);
        newRuleNode->addChild(linesNode);

        Path2D framePath;
        framePath.addLine(Point(0, headerY), Point(w, headerY));
        for (int day = 1; day < 7; ++day) {
            const Dim y = headerY + day * rowH;
            framePath.addLine(Point(0, y), Point(w, y));
        }
        framePath.addLine(Point(labelX, headerY), Point(labelX, h));
        auto* frameNode = new SvgPath(framePath);
        setSvgStrokeColor(frameNode, m_page->props.ruleColor);
        frameNode->setAttr<float>("stroke-width", 1.6f);
        newRuleNode->addChild(frameNode);
    }

    if (m_page->props.marginLeft > 0 && m_rulingStyle != RULING_CORNELL_SUMMARY && m_rulingStyle != RULING_MUSIC_STAFF && m_rulingStyle != RULING_WEEKLY_PLANNER) {
        auto* marginNode = new SvgPath(
            Path2D().addLine(Point(m_page->props.marginLeft, 0), Point(m_page->props.marginLeft, h))
        );
        marginNode->setAttr<color_t>("stroke", Color::RED);
        marginNode->addClass("leftmargin");
        newRuleNode->addChild(marginNode);
    }

    m_page->onPageSizeChange();
}

bool NativeCanvasSession::loadSvgzBytes(const uint8_t* bytes, size_t length) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!bytes || length == 0) return false;
    auto stream = std::make_unique<MemStream>(
        reinterpret_cast<const char*>(bytes),
        length
    );
    auto newDoc = std::make_unique<Document>();
    Document::loadresult_t res = newDoc->load(stream.release(), /*delayload=*/false);
    if (res < 0 || newDoc->pages.empty()) {
        return false;
    }
    m_selectedElements.clear();
    m_doc = std::move(newDoc);
    m_page = m_doc->pages.front();
    m_page->ensureLoaded(false);
    for (Page* p : m_doc->pages) {
        if (p && p->svgDoc) {
            p->svgDoc->setViewBox(Rect());
        }
    }
    m_width = static_cast<int>(m_page->width());
    m_height = static_cast<int>(m_page->height());
    m_xRuling = static_cast<int>(m_page->xruling());
    m_yRuling = static_cast<int>(m_page->yruling());
    detectRulingStyleFromLoadedPage();
    if (m_rulingStyle != RULING_STANDARD && m_rulingStyle != RULING_CUSTOM_DESIGNER) {
        rebuildProceduralRuleLayer();
    }
    return true;
}

std::vector<uint8_t> NativeCanvasSession::exportSvgzBytes() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_doc) return {};
    if (m_page && m_page->contentNode) {
        m_page->contentNode->setAttr("rulingstyle", rulingStyleToName(m_rulingStyle));
    }
    MemStream outStream(256 * 1024);
    bool saved = m_doc->saveBgz(&outStream, /*thumb=*/nullptr, Document::SAVE_FORCE | Document::SAVE_COPY);
    if (m_doc->blockStream.get() == &outStream) {
        m_doc->blockStream.release();
    }
    if (!saved) {
        return {};
    }
    const uint8_t* rawPtr = reinterpret_cast<const uint8_t*>(outStream.data());
    return std::vector<uint8_t>(rawPtr, rawPtr + outStream.size());
}

void NativeCanvasSession::setRenderTheme(bool isDark, uint32_t darkBgArgb, uint32_t darkFgArgb) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_renderDarkTheme = isDark;
    if (darkBgArgb != 0) {
        Color c = Color::fromArgb(darkBgArgb).opaque();
        int luma = c.red() * 299 + c.green() * 587 + c.blue() * 114;
        m_darkBgArgb = (luma < 128000) ? darkBgArgb : 0xFF181825;
    }
    if (darkFgArgb != 0) {
        Color c = Color::fromArgb(darkFgArgb).opaque();
        int luma = c.red() * 299 + c.green() * 587 + c.blue() * 114;
        m_darkFgArgb = (luma >= 128000) ? darkFgArgb : 0xFFCDD6F4;
    }
}

bool NativeCanvasSession::renderToPixels(
    uint8_t* dstPixels, int bmpWidth, int bmpHeight, float originX, float originY
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!dstPixels || bmpWidth <= 0 || bmpHeight <= 0 || !m_page) return false;
    if (!m_swPainter) {
        m_swPainter = std::make_unique<Painter>(
            Painter::PAINT_SW | Painter::NO_TEXT | Painter::ALIGN_SCISSOR
        );
    }

    const Color savedPagePropColor = m_page->props.color;
    const bool pageIsLight =
        (savedPagePropColor.red() * 299 + savedPagePropColor.green() * 587 + savedPagePropColor.blue() * 114) >= 128000;

    std::vector<std::pair<SvgNode*, std::vector<SvgAttr>>> savedNodeAttrs;
    auto saveAttrs = [&](SvgNode* n) {
        savedNodeAttrs.emplace_back(n, n->attrs);
    };

    if (m_renderDarkTheme) {
        const Color darkBg = Color::fromArgb(m_darkBgArgb).opaque();
        const Color darkFg = Color::fromArgb(m_darkFgArgb).opaque();
        const Color darkRule = Color::fromArgb(0xFF334155);

        if (pageIsLight) {
            m_page->props.color = darkBg;

            // 1. Adapt ruling group & paper background rect (.pagerect / .pagebg)
            if (m_page->ruleNode) {
                Color ruleGroupStroke = m_page->ruleNode->getColorAttr("stroke", Color::NONE);
                if (ruleGroupStroke != Color::NONE && ruleGroupStroke != Color::INVALID_COLOR) {
                    saveAttrs(m_page->ruleNode);
                    float rop = m_page->ruleNode->getFloatAttr("stroke-opacity", 1.0f);
                    Color adaptedRule = darkRule;
                    adaptedRule.setAlphaF(rop);
                    setSvgStrokeColor(m_page->ruleNode, adaptedRule);
                }
                for (SvgNode* rChild : m_page->ruleNode->children()) {
                    if (!rChild) continue;
                    if (rChild->hasClass("pagerect") || rChild->hasClass("pagebg")) {
                        saveAttrs(rChild);
                        setSvgFillColor(rChild, darkBg);
                    } else if (!rChild->hasClass("leftmargin") && rChild->type() == SvgNode::PATH) {
                        Color rc = rChild->getColorAttr("stroke", Color::NONE);
                        if (rc != Color::NONE && rc != Color::INVALID_COLOR && !(rc.red() > 180 && rc.green() < 80)) {
                            saveAttrs(rChild);
                            float rop = rChild->getFloatAttr("stroke-opacity", 1.0f);
                            Color adaptedRule = darkRule;
                            adaptedRule.setAlphaF(rop);
                            setSvgStrokeColor(rChild, adaptedRule);
                        }
                    }
                }
            }
        }

        // 2. Adapt standard dark ink strokes in content layer to light foreground
        auto isDarkInk = [](const Color& c, float opacity) -> bool {
            return c != Color::NONE && c != Color::INVALID_COLOR &&
                   c.alpha() > 100 && opacity > 0.4f &&
                   c.red() < 75 && c.green() < 75 && c.blue() < 85;
        };
        auto adaptContentTree = [&](auto& self, SvgNode* node) -> void {
            if (!node) return;
            if (auto* container = node->asContainerNode()) {
                for (SvgNode* child : container->children()) {
                    self(self, child);
                }
            }
            if (node == m_page->contentNode) return;
            Color fillCol = node->getColorAttr("fill", Color::NONE);
            float fillOp = node->getFloatAttr("fill-opacity", 1.0f);
            Color strokeCol = node->getColorAttr("stroke", Color::NONE);
            float strokeOp = node->getFloatAttr("stroke-opacity", 1.0f);
            bool darkFill = isDarkInk(fillCol, fillOp);
            bool darkStroke = isDarkInk(strokeCol, strokeOp);
            if (darkFill || darkStroke) {
                saveAttrs(node);
                if (darkFill) {
                    Color f = darkFg;
                    f.setAlphaF(fillOp);
                    setSvgFillColor(node, f);
                }
                if (darkStroke) {
                    Color s = darkFg;
                    s.setAlphaF(strokeOp);
                    setSvgStrokeColor(node, s);
                }
            }
        };
        if (m_page->contentNode) {
            adaptContentTree(adaptContentTree, m_page->contentNode);
        }
    } else {
        const Color lightBg = Color::fromArgb(0xFFFFFDF8).opaque();
        const Color lightFg = Color::fromArgb(0xFF1E293B).opaque();
        const Color lightRule = Color::fromArgb(0x334A90E2);

        if (!pageIsLight) {
            m_page->props.color = lightBg;
            if (m_page->ruleNode) {
                Color ruleGroupStroke = m_page->ruleNode->getColorAttr("stroke", Color::NONE);
                if (ruleGroupStroke != Color::NONE && ruleGroupStroke != Color::INVALID_COLOR) {
                    saveAttrs(m_page->ruleNode);
                    setSvgStrokeColor(m_page->ruleNode, lightRule);
                }
                for (SvgNode* rChild : m_page->ruleNode->children()) {
                    if (!rChild) continue;
                    if (rChild->hasClass("pagerect") || rChild->hasClass("pagebg")) {
                        saveAttrs(rChild);
                        setSvgFillColor(rChild, lightBg);
                    }
                }
            }
        }

        // Adapt near-white strokes drawn in dark mode so they remain visible on light paper
        auto isWhiteInk = [](const Color& c, float opacity) -> bool {
            return c != Color::NONE && c != Color::INVALID_COLOR &&
                   c.alpha() > 100 && opacity > 0.4f &&
                   c.red() > 205 && c.green() > 205 && c.blue() > 205;
        };
        auto adaptLightTree = [&](auto& self, SvgNode* node) -> void {
            if (!node) return;
            if (auto* container = node->asContainerNode()) {
                for (SvgNode* child : container->children()) {
                    self(self, child);
                }
            }
            if (node == m_page->contentNode) return;
            Color fillCol = node->getColorAttr("fill", Color::NONE);
            float fillOp = node->getFloatAttr("fill-opacity", 1.0f);
            Color strokeCol = node->getColorAttr("stroke", Color::NONE);
            float strokeOp = node->getFloatAttr("stroke-opacity", 1.0f);
            bool whiteFill = isWhiteInk(fillCol, fillOp);
            bool whiteStroke = isWhiteInk(strokeCol, strokeOp);
            if (whiteFill || whiteStroke) {
                saveAttrs(node);
                if (whiteFill) {
                    Color f = lightFg;
                    f.setAlphaF(fillOp);
                    setSvgFillColor(node, f);
                }
                if (whiteStroke) {
                    Color s = lightFg;
                    s.setAlphaF(strokeOp);
                    setSvgStrokeColor(node, s);
                }
            }
        };
        if (m_page->contentNode) {
            adaptLightTree(adaptLightTree, m_page->contentNode);
        }
    }

    const bool isTile = originX != 0.0f || originY != 0.0f;
    const float scaleX = isTile ? 1.0f : static_cast<float>(bmpWidth) / static_cast<float>(std::max(1, m_width));
    const float scaleY = isTile ? 1.0f : static_cast<float>(bmpHeight) / static_cast<float>(std::max(1, m_height));
    Image targetImg = Image::fromPixelsNoCopy(bmpWidth, bmpHeight, dstPixels);
    targetImg.fill(m_page->props.color.color);
    m_swPainter->setTarget(&targetImg);
    m_swPainter->beginFrame(1.0f);
    if (isTile) {
        m_swPainter->translate(-originX, -originY);
    } else if (std::abs(scaleX - 1.0f) > 1e-4f || std::abs(scaleY - 1.0f) > 1e-4f) {
        m_swPainter->transform(Transform2D(scaleX, 0.0f, 0.0f, scaleY, 0.0f, 0.0f));
    }
    Rect fullRect = isTile
        ? Rect::ltwh(originX, originY, bmpWidth, bmpHeight)
        : Rect::wh(static_cast<Dim>(m_width), static_cast<Dim>(m_height));
    m_page->draw(m_swPainter.get(), fullRect, /*rulelines=*/true);
    m_swPainter->endFrame();
    m_swPainter->setTarget(nullptr);
    targetImg.data = nullptr;

    m_page->props.color = savedPagePropColor;
    for (auto it = savedNodeAttrs.rbegin(); it != savedNodeAttrs.rend(); ++it) {
        it->first->attrs = std::move(it->second);
    }

    return true;
}

bool NativeCanvasSession::renderDirtyRectToPixels(
    uint8_t* dstPixels,
    int bmpWidth,
    int bmpHeight,
    float left,
    float top,
    float right,
    float bottom
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!dstPixels || bmpWidth <= 0 || bmpHeight <= 0 || !m_page) return false;
    if (left >= right || top >= bottom) return false;

    const float scaleX = static_cast<float>(bmpWidth) / static_cast<float>(std::max(1, m_width));
    const float scaleY = static_cast<float>(bmpHeight) / static_cast<float>(std::max(1, m_height));

    const int pad = 4;
    const int x0 = std::max(0, static_cast<int>(std::floor(left * scaleX)) - pad);
    const int y0 = std::max(0, static_cast<int>(std::floor(top * scaleY)) - pad);
    const int x1 = std::min(bmpWidth, static_cast<int>(std::ceil(right * scaleX)) + pad);
    const int y1 = std::min(bmpHeight, static_cast<int>(std::ceil(bottom * scaleY)) + pad);
    const int dirtyW = x1 - x0;
    const int dirtyH = y1 - y0;
    if (dirtyW <= 0 || dirtyH <= 0) return false;

    // If the dirty area covers most of the bitmap, fall back to a full-frame render
    if (static_cast<int64_t>(dirtyW) * dirtyH > (static_cast<int64_t>(bmpWidth) * bmpHeight * 3) / 4) {
        return renderToPixels(dstPixels, bmpWidth, bmpHeight);
    }

    if (!m_swPainter) {
        m_swPainter = std::make_unique<Painter>(
            Painter::PAINT_SW | Painter::NO_TEXT | Painter::ALIGN_SCISSOR
        );
    }

    const Color savedPagePropColor = m_page->props.color;
    const bool pageIsLight =
        (savedPagePropColor.red() * 299 + savedPagePropColor.green() * 587 + savedPagePropColor.blue() * 114) >= 128000;

    std::vector<std::pair<SvgNode*, std::vector<SvgAttr>>> savedNodeAttrs;
    auto saveAttrs = [&](SvgNode* n) {
        savedNodeAttrs.emplace_back(n, n->attrs);
    };

    if (m_renderDarkTheme) {
        const Color darkBg = Color::fromArgb(m_darkBgArgb).opaque();
        const Color darkFg = Color::fromArgb(m_darkFgArgb).opaque();
        const Color darkRule = Color::fromArgb(0xFF334155);

        if (pageIsLight) {
            m_page->props.color = darkBg;
            if (m_page->ruleNode) {
                Color ruleGroupStroke = m_page->ruleNode->getColorAttr("stroke", Color::NONE);
                if (ruleGroupStroke != Color::NONE && ruleGroupStroke != Color::INVALID_COLOR) {
                    saveAttrs(m_page->ruleNode);
                    float rop = m_page->ruleNode->getFloatAttr("stroke-opacity", 1.0f);
                    Color adaptedRule = darkRule;
                    adaptedRule.setAlphaF(rop);
                    setSvgStrokeColor(m_page->ruleNode, adaptedRule);
                }
                for (SvgNode* rChild : m_page->ruleNode->children()) {
                    if (!rChild) continue;
                    if (rChild->hasClass("pagerect") || rChild->hasClass("pagebg")) {
                        saveAttrs(rChild);
                        setSvgFillColor(rChild, darkBg);
                    } else if (!rChild->hasClass("leftmargin") && rChild->type() == SvgNode::PATH) {
                        Color rc = rChild->getColorAttr("stroke", Color::NONE);
                        if (rc != Color::NONE && rc != Color::INVALID_COLOR && !(rc.red() > 180 && rc.green() < 80)) {
                            saveAttrs(rChild);
                            float rop = rChild->getFloatAttr("stroke-opacity", 1.0f);
                            Color adaptedRule = darkRule;
                            adaptedRule.setAlphaF(rop);
                            setSvgStrokeColor(rChild, adaptedRule);
                        }
                    }
                }
            }
        }

        auto isDarkInk = [](const Color& c, float opacity) -> bool {
            return c != Color::NONE && c != Color::INVALID_COLOR &&
                   c.alpha() > 100 && opacity > 0.4f &&
                   c.red() < 75 && c.green() < 75 && c.blue() < 85;
        };
        auto adaptContentTree = [&](auto& self, SvgNode* node) -> void {
            if (!node) return;
            if (auto* container = node->asContainerNode()) {
                for (SvgNode* child : container->children()) {
                    self(self, child);
                }
            }
            if (node == m_page->contentNode) return;
            Color fillCol = node->getColorAttr("fill", Color::NONE);
            float fillOp = node->getFloatAttr("fill-opacity", 1.0f);
            Color strokeCol = node->getColorAttr("stroke", Color::NONE);
            float strokeOp = node->getFloatAttr("stroke-opacity", 1.0f);
            bool darkFill = isDarkInk(fillCol, fillOp);
            bool darkStroke = isDarkInk(strokeCol, strokeOp);
            if (darkFill || darkStroke) {
                saveAttrs(node);
                if (darkFill) {
                    Color f = darkFg;
                    f.setAlphaF(fillOp);
                    setSvgFillColor(node, f);
                }
                if (darkStroke) {
                    Color s = darkFg;
                    s.setAlphaF(strokeOp);
                    setSvgStrokeColor(node, s);
                }
            }
        };
        if (m_page->contentNode) {
            adaptContentTree(adaptContentTree, m_page->contentNode);
        }
    } else if (!pageIsLight) {
        const Color lightBg = Color::fromArgb(0xFFFFFDF8).opaque();
        const Color lightFg = Color::fromArgb(0xFF1E293B).opaque();
        const Color lightRule = Color::fromArgb(0x334A90E2);
        m_page->props.color = lightBg;
        if (m_page->ruleNode) {
            Color ruleGroupStroke = m_page->ruleNode->getColorAttr("stroke", Color::NONE);
            if (ruleGroupStroke != Color::NONE && ruleGroupStroke != Color::INVALID_COLOR) {
                saveAttrs(m_page->ruleNode);
                setSvgStrokeColor(m_page->ruleNode, lightRule);
            }
            for (SvgNode* rChild : m_page->ruleNode->children()) {
                if (!rChild) continue;
                if (rChild->hasClass("pagerect") || rChild->hasClass("pagebg")) {
                    saveAttrs(rChild);
                    setSvgFillColor(rChild, lightBg);
                }
            }
        }
        auto isWhiteInk = [](const Color& c, float opacity) -> bool {
            return c != Color::NONE && c != Color::INVALID_COLOR &&
                   c.alpha() > 100 && opacity > 0.4f &&
                   c.red() > 205 && c.green() > 205 && c.blue() > 205;
        };
        auto adaptLightTree = [&](auto& self, SvgNode* node) -> void {
            if (!node) return;
            if (auto* container = node->asContainerNode()) {
                for (SvgNode* child : container->children()) {
                    self(self, child);
                }
            }
            if (node == m_page->contentNode) return;
            Color fillCol = node->getColorAttr("fill", Color::NONE);
            float fillOp = node->getFloatAttr("fill-opacity", 1.0f);
            Color strokeCol = node->getColorAttr("stroke", Color::NONE);
            float strokeOp = node->getFloatAttr("stroke-opacity", 1.0f);
            bool whiteFill = isWhiteInk(fillCol, fillOp);
            bool whiteStroke = isWhiteInk(strokeCol, strokeOp);
            if (whiteFill || whiteStroke) {
                saveAttrs(node);
                if (whiteFill) {
                    Color f = lightFg;
                    f.setAlphaF(fillOp);
                    setSvgFillColor(node, f);
                }
                if (whiteStroke) {
                    Color s = lightFg;
                    s.setAlphaF(strokeOp);
                    setSvgStrokeColor(node, s);
                }
            }
        };
        if (m_page->contentNode) {
            adaptLightTree(adaptLightTree, m_page->contentNode);
        }
    }

    // Clear only the dirty sub-rectangle rows in the target RGBA buffer
    const uint32_t bgPixel = m_page->props.color.color;
    uint32_t* pixelRows = reinterpret_cast<uint32_t*>(dstPixels);
    for (int y = y0; y < y1; ++y) {
        std::fill_n(pixelRows + static_cast<size_t>(y) * bmpWidth + x0, dirtyW, bgPixel);
    }

    Image targetImg = Image::fromPixelsNoCopy(bmpWidth, bmpHeight, dstPixels);
    m_swPainter->setTarget(&targetImg);
    m_swPainter->beginFrame(1.0f);
    if (std::abs(scaleX - 1.0f) > 1e-4f || std::abs(scaleY - 1.0f) > 1e-4f) {
        m_swPainter->transform(Transform2D(scaleX, 0.0f, 0.0f, scaleY, 0.0f, 0.0f));
    }
    Rect clipRect = Rect::ltrb(
        static_cast<Dim>(x0) / scaleX,
        static_cast<Dim>(y0) / scaleY,
        static_cast<Dim>(x1) / scaleX,
        static_cast<Dim>(y1) / scaleY
    );
    m_swPainter->setClipRect(clipRect);
    m_page->draw(m_swPainter.get(), clipRect, /*rulelines=*/true);
    m_swPainter->endFrame();
    m_swPainter->setTarget(nullptr);
    targetImg.data = nullptr;

    m_page->props.color = savedPagePropColor;
    for (auto it = savedNodeAttrs.rbegin(); it != savedNodeAttrs.rend(); ++it) {
        it->first->attrs = std::move(it->second);
    }

    return true;
}

void NativeCanvasSession::setPen(
    int colorArgb,
    float strokeWidth,
    bool pressureSensitive,
    int tipType,
    bool snapToGrid,
    bool lineDrawing,
    float chiselAngle
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_penColor = Color::fromArgb(static_cast<uint32_t>(colorArgb)).color;
    m_penWidth = strokeWidth;
    m_pressureSensitive = pressureSensitive;
    m_penTipType = tipType;
    m_snapToGrid = snapToGrid;
    m_lineDrawing = lineDrawing;
    m_chiselAngle = chiselAngle;
}

void NativeCanvasSession::snapPointIfEnabled(float* inoutX, float* inoutY) const {
    if (!m_snapToGrid || !m_page || !inoutX || !inoutY) return;
    Dim yr = m_page->yruling(true);
    Dim xr = m_page->xruling() > 0 ? m_page->xruling() : yr;
    if (xr > 0) *inoutX = std::floor(*inoutX / xr + 0.5f) * xr;
    if (yr > 0) *inoutY = std::floor(*inoutY / yr + 0.5f) * yr;
}

void NativeCanvasSession::beginStroke(
    float x, float y, float pressure, float tiltX, float tiltY, double timestampMs
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    snapPointIfEnabled(&x, &y);
    unsigned int penFlags = 0u;
    if (m_penTipType == 2) {
        penFlags |= ScribblePen::TIP_CHISEL | ScribblePen::WIDTH_DIR;
    } else if (m_penTipType == 1) {
        penFlags |= ScribblePen::TIP_ROUND;
    } else if (m_penTipType == 0) {
        penFlags |= ScribblePen::TIP_FLAT;
    }
    if (m_pressureSensitive && !m_lineDrawing && !m_snapToGrid) {
        penFlags |= ScribblePen::WIDTH_PR;
    }
    if (m_snapToGrid) {
        penFlags |= ScribblePen::SNAP_TO_GRID;
    }
    if (m_lineDrawing) {
        penFlags |= ScribblePen::LINE_DRAWING;
    }
    if (Color(m_penColor).alpha() < 255) {
        penFlags |= ScribblePen::DRAW_UNDER;
    }
    ScribblePen pen(
        Color(m_penColor),
        static_cast<Dim>(m_penWidth),
        penFlags,
        /*wr=*/0.7f,
        /*pr=*/1.0f,
        /*spd=*/0.0f,
        /*angle=*/static_cast<Dim>(m_chiselAngle)
    );
    m_activeStroke.reset(StrokeBuilder::create(pen));
    if (!m_lineDrawing && !m_snapToGrid) {
        m_activeStroke->addFilter(new SimplifyFilter(0.15f, 0.08f));
    }
    m_activeInputPoints.clear();
    m_activeStrokeSnapped = false;
    m_activeInputPoints.push_back({x, y, timestampMs});
    if (m_lineDrawing) {
        // Add two initial points for line drawing since moveStroke replaces the endpoint
        m_activeStroke->addInputPoint(StrokePoint(x, y, 0.5f, 0, 0, static_cast<Timestamp>(timestampMs)));
        m_activeInputPoints.push_back({x, y, timestampMs});
    }
    m_activeStroke->addInputPoint(StrokePoint(x, y, pressure, tiltX, tiltY, static_cast<Timestamp>(timestampMs)));
}

void NativeCanvasSession::moveStroke(
    float x, float y, float pressure, float tiltX, float tiltY, double timestampMs
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_activeStroke) return;
    if (m_activeStrokeSnapped) return; // Once hold-to-snap locked, ignore minor micro-jitter until lift
    snapPointIfEnabled(&x, &y);
    if (m_lineDrawing) {
        m_activeStroke->removePoints(1);
        if (m_activeStroke->getPath()->empty()) {
            m_activeStroke->addInputPoint(StrokePoint(x, y, 0.5f, 0, 0, static_cast<Timestamp>(timestampMs)));
        }
        m_activeStroke->addInputPoint(StrokePoint(x, y, 0.5f, 0, 0, static_cast<Timestamp>(timestampMs)));
        if (m_activeInputPoints.size() >= 2) {
            m_activeInputPoints.back() = {x, y, timestampMs};
        } else {
            m_activeInputPoints.push_back({x, y, timestampMs});
        }
    } else {
        m_activeStroke->addInputPoint(StrokePoint(x, y, pressure, tiltX, tiltY, static_cast<Timestamp>(timestampMs)));
        m_activeInputPoints.push_back({x, y, timestampMs});
    }
}

int NativeCanvasSession::snapActiveStrokeToShape(
    float* outSnappedPoints, int maxFloats, int* outNumPoints
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNumPoints) *outNumPoints = 0;
    if (!m_activeStroke || m_activeStrokeSnapped || m_activeInputPoints.size() < 5) {
        return 0;
    }

    const size_t N = m_activeInputPoints.size();
    float minX = m_activeInputPoints[0].x, maxX = minX;
    float minY = m_activeInputPoints[0].y, maxY = minY;
    float totalLen = 0.0f;

    for (size_t i = 0; i < N; ++i) {
        float px = m_activeInputPoints[i].x;
        float py = m_activeInputPoints[i].y;
        minX = std::min(minX, px);
        maxX = std::max(maxX, px);
        minY = std::min(minY, py);
        maxY = std::max(maxY, py);
        if (i > 0) {
            totalLen += std::hypot(px - m_activeInputPoints[i - 1].x,
                                   py - m_activeInputPoints[i - 1].y);
        }
    }
    if (totalLen < 36.0f) return 0;

    const float x0 = m_activeInputPoints.front().x;
    const float y0 = m_activeInputPoints.front().y;
    const float xN = m_activeInputPoints.back().x;
    const float yN = m_activeInputPoints.back().y;
    const float chordLen = std::hypot(xN - x0, yN - y0);
    const float w = maxX - minX;
    const float h = maxY - minY;

    auto perpDistToSeg = [](float px, float py, float ax, float ay, float bx, float by) -> float {
        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-4f) return std::hypot(px - ax, py - ay);
        return std::abs(dy * px - dx * py + bx * ay - by * ax) / std::sqrt(lenSq);
    };

    int detectedShape = 0; // 1=Line, 2=Arrow, 3=Rectangle, 4=Ellipse
    std::vector<std::pair<float, float>> geomPts;

    // 1. Check Straight Line
    if (chordLen >= 32.0f) {
        float maxPerp = 0.0f;
        for (size_t i = 1; i + 1 < N; ++i) {
            maxPerp = std::max(maxPerp, perpDistToSeg(m_activeInputPoints[i].x, m_activeInputPoints[i].y, x0, y0, xN, yN));
        }
        if (maxPerp / chordLen < 0.085f && (chordLen / totalLen) > 0.87f) {
            float sx = x0, sy = y0, ex = xN, ey = yN;
            float angle = std::atan2(ey - sy, ex - sx);
            // Snap to horizontal / vertical if within ~5 degrees
            if (std::abs(angle) < 0.087f || std::abs(std::abs(angle) - 3.14159265f) < 0.087f) {
                ey = sy;
            } else if (std::abs(std::abs(angle) - 1.5707963f) < 0.087f) {
                ex = sx;
            }
            snapPointIfEnabled(&sx, &sy);
            snapPointIfEnabled(&ex, &ey);
            geomPts.push_back({sx, sy});
            geomPts.push_back({ex, ey});
            detectedShape = 1;
        }
    }

    // 2. Check Single-Stroke Connector Arrow (straight shaft + hooked tip at the end)
    if (detectedShape == 0 && totalLen >= 48.0f && N >= 8) {
        size_t tipIdx = 0;
        float maxDistFromStart = 0.0f;
        for (size_t i = N / 2; i < N; ++i) {
            float d = std::hypot(m_activeInputPoints[i].x - x0, m_activeInputPoints[i].y - y0);
            if (d > maxDistFromStart) {
                maxDistFromStart = d;
                tipIdx = i;
            }
        }
        if (tipIdx >= 3 && tipIdx + 2 < N && maxDistFromStart >= 36.0f) {
            float tipX = m_activeInputPoints[tipIdx].x;
            float tipY = m_activeInputPoints[tipIdx].y;
            float maxShaftPerp = 0.0f;
            float shaftLen = 0.0f;
            for (size_t i = 1; i <= tipIdx; ++i) {
                shaftLen += std::hypot(m_activeInputPoints[i].x - m_activeInputPoints[i - 1].x,
                                       m_activeInputPoints[i].y - m_activeInputPoints[i - 1].y);
                maxShaftPerp = std::max(maxShaftPerp,
                                        perpDistToSeg(m_activeInputPoints[i].x, m_activeInputPoints[i].y, x0, y0, tipX, tipY));
            }
            float tailHookDist = std::hypot(xN - tipX, yN - tipY);
            if (maxShaftPerp / maxDistFromStart < 0.095f &&
                (maxDistFromStart / std::max(1.0f, shaftLen)) > 0.86f &&
                tailHookDist >= 10.0f && tailHookDist <= maxDistFromStart * 0.45f) {
                float sx = x0, sy = y0, tx = tipX, ty = tipY;
                float angle = std::atan2(ty - sy, tx - sx);
                float barbLen = std::clamp(maxDistFromStart * 0.18f, 14.0f, 28.0f);
                float barbAngle1 = angle + 3.14159265f - 0.48f; // ~152 deg
                float barbAngle2 = angle + 3.14159265f + 0.48f;
                float b1x = tx + barbLen * std::cos(barbAngle1);
                float b1y = ty + barbLen * std::sin(barbAngle1);
                float b2x = tx + barbLen * std::cos(barbAngle2);
                float b2y = ty + barbLen * std::sin(barbAngle2);
                geomPts.push_back({sx, sy});
                geomPts.push_back({tx, ty});
                geomPts.push_back({b1x, b1y});
                geomPts.push_back({tx, ty});
                geomPts.push_back({b2x, b2y});
                detectedShape = 2;
            }
        }
    }

    // 3. Check Closed Shapes (Rectangle or Circle/Ellipse)
    if (detectedShape == 0 && chordLen < 0.30f * totalLen && w >= 24.0f && h >= 24.0f) {
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float rx = w * 0.5f;
        float ry = h * 0.5f;
        float minDim = std::min(w, h);

        float sumBoxDist = 0.0f;
        float sumEllipseErrSq = 0.0f;
        for (size_t i = 0; i < N; ++i) {
            float px = m_activeInputPoints[i].x;
            float py = m_activeInputPoints[i].y;
            float dLeft = std::abs(px - minX);
            float dRight = std::abs(maxX - px);
            float dTop = std::abs(py - minY);
            float dBottom = std::abs(maxY - py);
            float dEdge = std::min(std::min(dLeft, dRight), std::min(dTop, dBottom));
            sumBoxDist += dEdge;

            float nx = (px - cx) / std::max(1.0f, rx);
            float ny = (py - cy) / std::max(1.0f, ry);
            float rNorm = std::hypot(nx, ny);
            float err = rNorm - 1.0f;
            sumEllipseErrSq += err * err;
        }
        float avgBoxRel = (sumBoxDist / static_cast<float>(N)) / minDim;
        float rmsEllipseErr = std::sqrt(sumEllipseErrSq / static_cast<float>(N));

        if (avgBoxRel < 0.115f && avgBoxRel < rmsEllipseErr * 0.82f) {
            // Axis-aligned Rectangle
            float l = minX, t = minY, r = maxX, b = maxY;
            snapPointIfEnabled(&l, &t);
            snapPointIfEnabled(&r, &b);
            geomPts.push_back({l, t});
            geomPts.push_back({r, t});
            geomPts.push_back({r, b});
            geomPts.push_back({l, b});
            geomPts.push_back({l, t});
            detectedShape = 3;
        } else if (rmsEllipseErr < 0.185f) {
            // Circle or Ellipse (snap to true circle if aspect ratio is close to 1:1)
            if (std::abs(rx - ry) / std::max(rx, ry) < 0.18f) {
                rx = ry = (rx + ry) * 0.5f;
            }
            const int segs = 48;
            for (int s = 0; s <= segs; ++s) {
                float theta = (2.0f * 3.14159265f * static_cast<float>(s)) / static_cast<float>(segs);
                geomPts.push_back({cx + rx * std::cos(theta), cy + ry * std::sin(theta)});
            }
            detectedShape = 4;
        }
    }

    if (detectedShape == 0 || geomPts.size() < 2) {
        return 0;
    }

    // Rebuild m_activeStroke with crisp geometric segments
    unsigned int penFlags = ScribblePen::TIP_ROUND;
    if (Color(m_penColor).alpha() < 255) {
        penFlags |= ScribblePen::DRAW_UNDER;
    }
    ScribblePen geomPen(
        Color(m_penColor),
        static_cast<Dim>(m_penWidth),
        penFlags,
        /*wr=*/0.7f,
        /*pr=*/1.0f,
        /*spd=*/0.0f,
        /*angle=*/0.0f
    );
    m_activeStroke.reset(StrokeBuilder::create(geomPen));
    double ts = m_activeInputPoints.front().ts;
    for (size_t i = 0; i < geomPts.size(); ++i) {
        // Interpolate intermediate points along long straight segments so Write's stroke mesh is sharp at corners
        if (i > 0 && detectedShape != 4) {
            float ax = geomPts[i - 1].first, ay = geomPts[i - 1].second;
            float bx = geomPts[i].first, by = geomPts[i].second;
            float segLen = std::hypot(bx - ax, by - ay);
            int steps = std::clamp(static_cast<int>(segLen / 12.0f), 2, 24);
            for (int k = 1; k <= steps; ++k) {
                float frac = static_cast<float>(k) / static_cast<float>(steps);
                float ix = ax + (bx - ax) * frac;
                float iy = ay + (by - ay) * frac;
                ts += 2.0;
                m_activeStroke->addInputPoint(StrokePoint(ix, iy, 0.5f, 0, 0, static_cast<Timestamp>(ts)));
            }
        } else {
            ts += 2.0;
            m_activeStroke->addInputPoint(
                StrokePoint(geomPts[i].first, geomPts[i].second, 0.5f, 0, 0, static_cast<Timestamp>(ts))
            );
        }
    }

    m_activeStrokeSnapped = true;

    int countPts = std::min(static_cast<int>(geomPts.size()), maxFloats / 2);
    if (outSnappedPoints && countPts > 0) {
        for (int i = 0; i < countPts; ++i) {
            outSnappedPoints[i * 2] = geomPts[i].first;
            outSnappedPoints[i * 2 + 1] = geomPts[i].second;
        }
    }
    if (outNumPoints) *outNumPoints = countPts;
    return detectedShape;
}

void NativeCanvasSession::moveStrokeBatch(const float* pointsData6, int pointCount) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_activeStroke || m_activeStrokeSnapped || !pointsData6 || pointCount <= 0) return;
    for (int i = 0; i < pointCount; ++i) {
        const float* pt = pointsData6 + (i * 6);
        float x = pt[0];
        float y = pt[1];
        float pressure = pt[2];
        float tiltX = pt[3];
        float tiltY = pt[4];
        double ts = static_cast<double>(pt[5]);
        snapPointIfEnabled(&x, &y);
        if (m_lineDrawing) {
            m_activeStroke->removePoints(1);
            if (m_activeStroke->getPath()->empty()) {
                m_activeStroke->addInputPoint(StrokePoint(x, y, 0.5f, 0, 0, static_cast<Timestamp>(ts)));
            }
            m_activeStroke->addInputPoint(StrokePoint(x, y, 0.5f, 0, 0, static_cast<Timestamp>(ts)));
            if (m_activeInputPoints.size() >= 2) {
                m_activeInputPoints.back() = {x, y, ts};
            } else {
                m_activeInputPoints.push_back({x, y, ts});
            }
        } else {
            m_activeStroke->addInputPoint(StrokePoint(x, y, pressure, tiltX, tiltY, static_cast<Timestamp>(ts)));
            m_activeInputPoints.push_back({x, y, ts});
        }
    }
}

void NativeCanvasSession::autoExpandForBounds(float maxRight, float maxBottom, int* outNewWidth, int* outNewHeight) {
    if (!m_page) return;
    bool changed = false;
    int targetW = m_width;
    int targetH = m_height;
    if (maxRight > (m_width - AUTO_EXPAND_MARGIN_PX)) {
        targetW = static_cast<int>(maxRight) + AUTO_EXPAND_STEP_PX;
        if (m_xRuling > 0) {
            targetW = ((targetW + m_xRuling - 1) / m_xRuling) * m_xRuling;
        }
        changed = true;
    }
    if (maxBottom > (m_height - AUTO_EXPAND_MARGIN_PX)) {
        targetH = static_cast<int>(maxBottom) + AUTO_EXPAND_STEP_PX;
        if (m_yRuling > 0) {
            targetH = ((targetH + m_yRuling - 1) / m_yRuling) * m_yRuling;
        }
        changed = true;
    }
    if (changed) {
        PageProperties props = m_page->getProperties();
        props.width = static_cast<Dim>(targetW);
        props.height = static_cast<Dim>(targetH);
        m_page->setProperties(&props);
        if (m_page->svgDoc) {
            m_page->svgDoc->setViewBox(Rect());
        }
        m_width = targetW;
        m_height = targetH;
        if (m_rulingStyle != RULING_STANDARD) {
            rebuildProceduralRuleLayer();
        }
    }
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
}

std::string NativeCanvasSession::endStroke(
    bool* outHeightExpanded,
    int* outNewHeight,
    float* outDirtyBounds4
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outHeightExpanded) *outHeightExpanded = false;
    if (outNewHeight) *outNewHeight = m_height;
    if (outDirtyBounds4) {
        outDirtyBounds4[0] = outDirtyBounds4[1] = outDirtyBounds4[2] = outDirtyBounds4[3] = 0.0f;
    }
    if (!m_activeStroke || !m_page) return "";

    Element* elem = m_activeStroke->finish();
    m_activeStroke.reset();
    if (!elem) return "";

    ++m_strokeSeq;
    std::string strokeId = "s_" + std::to_string(m_strokeSeq);
    elem->node->setXmlId(strokeId.c_str());

    int prevHeight = m_height;
    m_doc->history->startAction(0);
    Element* beforeElem = nullptr;
    if (((m_penColor >> 24) & 0xFF) < 0xFF) {
        for (Element* existing : m_page->children()) {
            beforeElem = existing;
            break;
        }
    }
    m_page->addStroke(elem, beforeElem);

    Rect bbox = elem->bbox().pad(m_penWidth + 4.0f);
    int newW = m_width, newH = m_height;
    autoExpandForBounds(bbox.right, bbox.bottom, &newW, &newH);
    if (newH != prevHeight) {
        if (outHeightExpanded) *outHeightExpanded = true;
        if (outNewHeight) *outNewHeight = newH;
    }
    m_doc->history->endAction();

    if (outDirtyBounds4) {
        if (!bbox.isValid()) {
            outDirtyBounds4[0] = 0.0f;
            outDirtyBounds4[1] = 0.0f;
            outDirtyBounds4[2] = static_cast<float>(m_width);
            outDirtyBounds4[3] = static_cast<float>(m_height);
        } else {
            outDirtyBounds4[0] = static_cast<float>(bbox.left);
            outDirtyBounds4[1] = static_cast<float>(bbox.top);
            outDirtyBounds4[2] = static_cast<float>(bbox.right);
            outDirtyBounds4[3] = static_cast<float>(bbox.bottom);
        }
    }

    MemStream xmlBuf;
    SvgWriter::save(elem->node, xmlBuf, "");
    return std::string(xmlBuf.data(), xmlBuf.size());
}

bool NativeCanvasSession::eraseAt(float x, float y, float radius, float* outDirtyBounds4) {
    return eraseSegment(x, y, x, y, radius, outDirtyBounds4);
}

bool NativeCanvasSession::eraseSegment(
    float prevX,
    float prevY,
    float x,
    float y,
    float radius,
    float* outDirtyBounds4
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outDirtyBounds4) {
        outDirtyBounds4[0] = outDirtyBounds4[1] = outDirtyBounds4[2] = outDirtyBounds4[3] = 0.0f;
    }
    if (!m_page) return false;
    Point p0(prevX, prevY);
    Point p1(x, y);
    Rect hitBox = Rect::corners(p0, p1).pad(radius);
    std::vector<Element*> toRemove;
    Rect dirty;
    for (Element* el : m_page->children()) {
        if (el && el->bbox().intersects(hitBox)) {
            toRemove.push_back(el);
            dirty.rectUnion(el->bbox());
        }
    }
    if (toRemove.empty()) return false;

    m_doc->history->startAction(0);
    for (Element* el : toRemove) {
        m_page->removeStroke(el);
    }
    m_doc->history->endAction();
    sanitizeSelection();
    if (outDirtyBounds4 && dirty.isValid()) {
        Rect padded = dirty.pad(6.0f);
        outDirtyBounds4[0] = static_cast<float>(padded.left);
        outDirtyBounds4[1] = static_cast<float>(padded.top);
        outDirtyBounds4[2] = static_cast<float>(padded.right);
        outDirtyBounds4[3] = static_cast<float>(padded.bottom);
    }
    return true;
}

bool NativeCanvasSession::eraseRuledLineAt(float y, float* outDirtyBounds4) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outDirtyBounds4) {
        outDirtyBounds4[0] = outDirtyBounds4[1] = outDirtyBounds4[2] = outDirtyBounds4[3] = 0.0f;
    }
    if (!m_page) return false;
    int targetLine = m_page->getLine(static_cast<Dim>(y));
    std::vector<Element*> toRemove;
    Rect dirty;
    for (Element* el : m_page->children()) {
        if (el && m_page->getLine(el) == targetLine) {
            toRemove.push_back(el);
            dirty.rectUnion(el->bbox());
        }
    }
    if (toRemove.empty()) return false;

    m_doc->history->startAction(0);
    for (Element* el : toRemove) {
        m_page->removeStroke(el);
    }
    m_doc->history->endAction();
    sanitizeSelection();
    if (outDirtyBounds4 && dirty.isValid()) {
        Rect padded = dirty.pad(6.0f);
        outDirtyBounds4[0] = static_cast<float>(padded.left);
        outDirtyBounds4[1] = static_cast<float>(padded.top);
        outDirtyBounds4[2] = static_cast<float>(padded.right);
        outDirtyBounds4[3] = static_cast<float>(padded.bottom);
    }
    return true;
}

bool NativeCanvasSession::eraseFreeSegment(
    float prevX,
    float prevY,
    float x,
    float y,
    float radius,
    float* outDirtyBounds4
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outDirtyBounds4) {
        outDirtyBounds4[0] = outDirtyBounds4[1] = outDirtyBounds4[2] = outDirtyBounds4[3] = 0.0f;
    }
    if (!m_page) return false;
    Point p0(prevX, prevY);
    Point p1(x, y);
    Rect hitBox = Rect::corners(p0, p1).pad(radius);

    struct TouchedStroke {
        Element* orig;
        Element* next;
    };
    std::vector<TouchedStroke> touchedList;
    Rect dirty;

    auto strokes = m_page->children();
    for (auto it = strokes.begin(); it != strokes.end();) {
        Element* el = *it++;
        Element* nextEl = (it != strokes.end()) ? *it : nullptr;
        if (!el || !el->bbox().intersects(hitBox)) continue;
        Rect origBBox = el->bbox();
        if (el->freeErase(p0, p1, static_cast<Dim>(radius))) {
            touchedList.push_back({el, nextEl});
            dirty.rectUnion(origBBox);
        }
    }
    if (touchedList.empty()) return false;

    m_doc->history->startAction(0);
    for (const auto& item : touchedList) {
        Element* s = item.orig;
        Element* nexts = item.next;
        if (s->isPathElement() || s->isMultiStroke()) {
            std::vector<Element*> subPaths = s->getEraseSubPaths();
            m_page->removeStroke(s);
            for (Element* sub : subPaths) {
                ++m_strokeSeq;
                std::string strokeId = "s_" + std::to_string(m_strokeSeq);
                sub->node->setXmlId(strokeId.c_str());
                m_page->addStroke(sub, nexts);
                dirty.rectUnion(sub->bbox());
            }
        }
    }
    m_doc->history->endAction();
    sanitizeSelection();
    if (outDirtyBounds4 && dirty.isValid()) {
        Rect padded = dirty.pad(6.0f);
        outDirtyBounds4[0] = static_cast<float>(padded.left);
        outDirtyBounds4[1] = static_cast<float>(padded.top);
        outDirtyBounds4[2] = static_cast<float>(padded.right);
        outDirtyBounds4[3] = static_cast<float>(padded.bottom);
    }
    return true;
}

bool NativeCanvasSession::resizeCanvas(int newWidth, int newHeight) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page) return false;
    m_doc->history->startAction(0);
    PageProperties props = m_page->getProperties();
    props.width = static_cast<Dim>(newWidth);
    props.height = static_cast<Dim>(newHeight);
    m_page->setProperties(&props);
    if (m_page->svgDoc) {
        m_page->svgDoc->setViewBox(Rect());
    }
    if (m_rulingStyle != RULING_STANDARD) {
        rebuildProceduralRuleLayer();
    }
    m_doc->history->endAction();
    m_width = newWidth;
    m_height = newHeight;
    return true;
}

bool NativeCanvasSession::trimBottomWhitespace(int paddingPx, int* outNewHeight) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page) return false;
    float maxBottom = 0.0f;
    for (Element* el : m_page->children()) {
        if (el) {
            maxBottom = std::max<float>(maxBottom, el->bbox().bottom);
        }
    }
    int targetH = std::max(280, static_cast<int>( std::ceil(maxBottom) ) + std::max(16, paddingPx));
    if (m_yRuling > 0) {
        targetH = ((targetH + m_yRuling - 1) / m_yRuling) * m_yRuling;
    }
    if (targetH == m_height) {
        return false;
    }
    m_doc->history->startAction(0);
    PageProperties props = m_page->getProperties();
    props.height = static_cast<Dim>(targetH);
    m_page->setProperties(&props);
    if (m_page->svgDoc) {
        m_page->svgDoc->setViewBox(Rect());
    }
    m_height = targetH;
    rebuildProceduralRuleLayer();
    m_doc->history->endAction();
    if (outNewHeight) *outNewHeight = m_height;
    return true;
}

bool NativeCanvasSession::setTemplateImageOpacity(float opacity) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page) return false;
    float clamped = std::clamp(opacity, 0.1f, 1.0f);
    bool updated = false;
    auto applyImgOpacity = [&](auto& self, SvgNode* node) -> void {
        if (!node) return;
        if (node->type() == SvgNode::IMAGE) {
            node->setAttr("opacity", clamped);
            node->setAttr("fill-opacity", clamped);
            updated = true;
        }
        if (auto* container = node->asContainerNode()) {
            for (SvgNode* ch : container->children()) {
                self(self, ch);
            }
        }
    };
    if (m_page->ruleNode) applyImgOpacity(applyImgOpacity, m_page->ruleNode);
    if (m_page->contentNode) applyImgOpacity(applyImgOpacity, m_page->contentNode);
    if (updated && m_page->svgDoc) {
        m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
    }
    return updated;
}

void NativeCanvasSession::sanitizeSelection() {
    if (!m_page || m_selectedElements.empty()) return;
    std::vector<Element*> valid;
    for (Element* selected : m_selectedElements) {
        for (Element* child : m_page->children()) {
            if (child == selected) {
                valid.push_back(selected);
                break;
            }
        }
    }
    m_selectedElements = std::move(valid);
}

void NativeCanvasSession::computeSelectionBounds(float* outBounds4) const {
    if (!outBounds4) return;
    if (m_selectedElements.empty()) {
        outBounds4[0] = outBounds4[1] = outBounds4[2] = outBounds4[3] = 0.0f;
        return;
    }
    Rect combined;
    for (Element* el : m_selectedElements) {
        if (el) {
            combined.rectUnion(el->bbox());
        }
    }
    if (!combined.isValid()) {
        outBounds4[0] = outBounds4[1] = outBounds4[2] = outBounds4[3] = 0.0f;
        return;
    }
    outBounds4[0] = static_cast<float>(combined.left);
    outBounds4[1] = static_cast<float>(combined.top);
    outBounds4[2] = static_cast<float>(combined.right);
    outBounds4[3] = static_cast<float>(combined.bottom);
}

int NativeCanvasSession::selectRect(float left, float top, float right, float bottom, float* outBounds4) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_selectedElements.clear();
    if (!m_page) {
        computeSelectionBounds(outBounds4);
        return 0;
    }
    float l = std::min(left, right);
    float r = std::max(left, right);
    float t = std::min(top, bottom);
    float b = std::max(top, bottom);
    Rect selRect = Rect::ltrb(l, t, r, b);
    for (Element* el : m_page->children()) {
        if (!el) continue;
        Rect eb = el->bbox();
        Point c = el->com();
        if (selRect.contains(eb) || selRect.contains(c) || selRect.intersects(eb)) {
            m_selectedElements.push_back(el);
        }
    }
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

int NativeCanvasSession::selectLasso(const float* xyPoints, int numFloats, float* outBounds4) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_selectedElements.clear();
    if (!m_page || !xyPoints || numFloats < 6) {
        computeSelectionBounds(outBounds4);
        return 0;
    }

    float lMinX = xyPoints[0], lMaxX = xyPoints[0];
    float lMinY = xyPoints[1], lMaxY = xyPoints[1];
    int nPts = numFloats / 2;
    for (int i = 0; i < nPts; ++i) {
        float x = xyPoints[i * 2];
        float y = xyPoints[i * 2 + 1];
        if (x < lMinX) lMinX = x;
        if (x > lMaxX) lMaxX = x;
        if (y < lMinY) lMinY = y;
        if (y > lMaxY) lMaxY = y;
    }
    Rect lassoBBox = Rect::ltrb(lMinX, lMinY, lMaxX, lMaxY);

    for (Element* el : m_page->children()) {
        if (!el) continue;
        Rect eb = el->bbox();
        if (!lassoBBox.intersects(eb)) continue;

        Point c = el->com();
        Point bc = eb.center();
        bool inside = isPointInPolygon(c.x, c.y, xyPoints, numFloats) ||
                      isPointInPolygon(bc.x, bc.y, xyPoints, numFloats) ||
                      isPointInPolygon(eb.left, eb.top, xyPoints, numFloats) ||
                      isPointInPolygon(eb.right, eb.bottom, xyPoints, numFloats) ||
                      isPointInPolygon(eb.left, eb.bottom, xyPoints, numFloats) ||
                      isPointInPolygon(eb.right, eb.top, xyPoints, numFloats);

        if (!inside && el->isPathElement()) {
            SvgNode* n = el->node;
            if (n && n->type() == SvgNode::PATH) {
                const Path2D* p = static_cast<SvgPath*>(n)->path();
                if (p && !p->empty()) {
                    size_t pSize = p->size();
                    size_t step = std::max<size_t>(1, pSize / 8);
                    for (size_t pi = 0; pi < pSize; pi += step) {
                        Point pt = p->point(static_cast<int>(pi));
                        if (isPointInPolygon(pt.x, pt.y, xyPoints, numFloats)) {
                            inside = true;
                            break;
                        }
                    }
                }
            }
        }

        if (inside) {
            m_selectedElements.push_back(el);
        }
    }
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

int NativeCanvasSession::selectRuled(float startY, float endY, float* outBounds4) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_selectedElements.clear();
    if (!m_page) {
        computeSelectionBounds(outBounds4);
        return 0;
    }
    int lineA = m_page->getLine(static_cast<Dim>(startY));
    int lineB = m_page->getLine(static_cast<Dim>(endY));
    int minLine = std::min(lineA, lineB);
    int maxLine = std::max(lineA, lineB);
    for (Element* el : m_page->children()) {
        if (!el) continue;
        int elLine = m_page->getLine(el);
        if (elLine >= minLine && elLine <= maxLine) {
            m_selectedElements.push_back(el);
        }
    }
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

int NativeCanvasSession::selectAll(float* outBounds4) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_selectedElements.clear();
    if (!m_page) {
        computeSelectionBounds(outBounds4);
        return 0;
    }
    for (Element* el : m_page->children()) {
        if (el) m_selectedElements.push_back(el);
    }
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

void NativeCanvasSession::clearSelection() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    m_selectedElements.clear();
}

bool NativeCanvasSession::getSelectionBounds(float* outBounds4) const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (m_selectedElements.empty()) return false;
    computeSelectionBounds(outBounds4);
    return true;
}

bool NativeCanvasSession::translateSelection(
    float dx,
    float dy,
    float* outBounds4,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page || m_selectedElements.empty()) return false;
    if (std::abs(dx) < 0.01f && std::abs(dy) < 0.01f) {
        computeSelectionBounds(outBounds4);
        return false;
    }

    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    for (Element* el : m_selectedElements) {
        if (!el) continue;
        el->applyTransform(Transform2D::translating(static_cast<Dim>(dx), static_cast<Dim>(dy)));
        el->commitTransform();
        m_doc->history->addItem(new StrokeTranslateItem(el, m_page, static_cast<Dim>(dx), static_cast<Dim>(dy)));
        maxRight = std::max(maxRight, el->bbox().right);
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }
    autoExpandForBounds(maxRight, maxBottom, outNewWidth, outNewHeight);
    m_doc->history->endAction();
    computeSelectionBounds(outBounds4);
    return true;
}

bool NativeCanvasSession::scaleSelection(
    float sx,
    float sy,
    float pivotX,
    float pivotY,
    bool scaleWidths,
    float* outBounds4,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page || m_selectedElements.empty()) return false;

    float clampedSx = std::abs(sx) < 0.05f ? (sx < 0 ? -0.05f : 0.05f) : sx;
    float clampedSy = std::abs(sy) < 0.05f ? (sy < 0 ? -0.05f : 0.05f) : sy;

    ScribbleTransform tf(
        Transform2D(
            clampedSx, 0, 0, clampedSy,
            (1.0f - clampedSx) * pivotX,
            (1.0f - clampedSy) * pivotY
        ),
        scaleWidths ? 1.0f : 1.0f / clampedSx,
        scaleWidths ? 1.0f : 1.0f / clampedSy
    );

    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    for (Element* el : m_selectedElements) {
        if (!el) continue;
        el->applyTransform(tf);
        m_doc->history->addItem(new StrokeTransformItem(el, m_page, tf));
        el->commitTransform();
        maxRight = std::max(maxRight, el->bbox().right);
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }
    autoExpandForBounds(maxRight, maxBottom, outNewWidth, outNewHeight);
    m_doc->history->endAction();
    computeSelectionBounds(outBounds4);
    return true;
}

bool NativeCanvasSession::rotateSelection(
    float angleRad,
    float pivotX,
    float pivotY,
    float* outBounds4,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page || m_selectedElements.empty() || std::abs(angleRad) < 1e-4f) return false;

    ScribbleTransform tf;
    tf.rotate(static_cast<Dim>(angleRad), Point(pivotX, pivotY));

    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    for (Element* el : m_selectedElements) {
        if (!el) continue;
        el->applyTransform(tf);
        m_doc->history->addItem(new StrokeTransformItem(el, m_page, tf));
        el->commitTransform();
        maxRight = std::max(maxRight, el->bbox().right);
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }
    autoExpandForBounds(maxRight, maxBottom, outNewWidth, outNewHeight);
    m_doc->history->endAction();
    computeSelectionBounds(outBounds4);
    return true;
}

bool NativeCanvasSession::deleteSelection() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (!m_page || m_selectedElements.empty()) return false;
    m_doc->history->startAction(0);
    for (Element* el : m_selectedElements) {
        if (el) {
            el->setSelected(nullptr);
            m_page->removeStroke(el);
        }
    }
    m_doc->history->endAction();
    m_selectedElements.clear();
    if (m_page->svgDoc) {
        m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
    }
    m_page->dirtyCount++;
    return true;
}

int NativeCanvasSession::duplicateSelection(
    float offsetX,
    float offsetY,
    float* outBounds4,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page || m_selectedElements.empty()) return 0;

    std::vector<Element*> clones;
    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    for (Element* el : m_selectedElements) {
        if (!el) continue;
        Element* copy = el->cloneNode();
        if (!copy) continue;
        ++m_strokeSeq;
        std::string strokeId = "s_" + std::to_string(m_strokeSeq);
        copy->node->setXmlId(strokeId.c_str());
        copy->applyTransform(Transform2D::translating(static_cast<Dim>(offsetX), static_cast<Dim>(offsetY)));
        copy->commitTransform();
        m_page->addStroke(copy);
        clones.push_back(copy);
        maxRight = std::max(maxRight, copy->bbox().right);
        maxBottom = std::max(maxBottom, copy->bbox().bottom);
    }
    autoExpandForBounds(maxRight, maxBottom, outNewWidth, outNewHeight);
    m_doc->history->endAction();
    m_selectedElements = std::move(clones);
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

bool NativeCanvasSession::restyleSelection(int colorArgb, float strokeWidth) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    sanitizeSelection();
    if (!m_page || m_selectedElements.empty()) return false;

    m_doc->history->startAction(0);
    StrokeProperties newProps(Color::fromArgb(static_cast<uint32_t>(colorArgb)), static_cast<Dim>(strokeWidth));
    for (Element* el : m_selectedElements) {
        if (!el) continue;
        StrokeProperties oldProps = el->getProperties();
        if (el->setProperties(newProps)) {
            m_doc->history->addItem(new StrokeChangedItem(el, m_page, oldProps));
        }
    }
    m_doc->history->endAction();
    return true;
}

std::string NativeCanvasSession::copySelectionSvg() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page || m_selectedElements.empty()) return "";
    SvgG container;
    for (Element* el : m_selectedElements) {
        if (!el || !el->node) continue;
        container.addChild(el->node->clone());
    }
    MemStream xmlBuf;
    SvgWriter::save(&container, xmlBuf, "");
    return std::string(xmlBuf.data(), xmlBuf.size());
}

int NativeCanvasSession::pasteSelectionSvg(
    const std::string& svgFragment,
    float offsetX,
    float offsetY,
    float* outBounds4,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page || svgFragment.empty()) return 0;

    std::unique_ptr<SvgDocument> frag(SvgParser().parseFragment(svgFragment.data(), svgFragment.size()));
    if (!frag) return 0;

    std::vector<SvgNode*> sourceNodes;
    if (frag->children().size() == 1 && frag->children().front()->type() == SvgNode::G) {
        SvgContainerNode* g = frag->children().front()->asContainerNode();
        for (SvgNode* c : g->children()) {
            sourceNodes.push_back(c->clone());
        }
    } else {
        for (SvgNode* c : frag->children()) {
            sourceNodes.push_back(c->clone());
        }
    }
    if (sourceNodes.empty()) return 0;

    m_selectedElements.clear();
    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    for (SvgNode* n : sourceNodes) {
        Element* el = n->hasExt() ? static_cast<Element*>(n->ext()) : new Element(n);
        ++m_strokeSeq;
        std::string strokeId = "s_" + std::to_string(m_strokeSeq);
        el->node->setXmlId(strokeId.c_str());
        if (std::abs(offsetX) > 0.01f || std::abs(offsetY) > 0.01f) {
            el->applyTransform(Transform2D::translating(offsetX, offsetY));
            el->commitTransform();
        }
        m_page->addStroke(el);
        m_selectedElements.push_back(el);
        maxRight = std::max(maxRight, el->bbox().right);
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }
    autoExpandForBounds(maxRight, maxBottom, outNewWidth, outNewHeight);
    m_doc->history->endAction();
    computeSelectionBounds(outBounds4);
    return static_cast<int>(m_selectedElements.size());
}

bool NativeCanvasSession::insertVerticalSpace(float yThreshold, float deltaY, bool snapToRuling, int* outNewHeight) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page) return false;

    Dim dy = static_cast<Dim>(deltaY);
    if (snapToRuling) {
        Dim yr = m_page->yruling(true);
        if (yr > 0) {
            dy = std::round(dy / yr) * yr;
        }
    }
    if (std::abs(dy) < 1.0f) return false;

    std::vector<Element*> toShift;
    for (Element* el : m_page->children()) {
        if (!el) continue;
        if (el->com().y >= yThreshold || el->bbox().top >= yThreshold) {
            toShift.push_back(el);
        }
    }

    m_doc->history->startAction(0);
    Dim maxBottom = 0;
    for (Element* el : toShift) {
        el->applyTransform(Transform2D::translating(0, dy));
        el->commitTransform();
        m_doc->history->addItem(new StrokeTranslateItem(el, m_page, 0, dy));
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }

    int targetHeight = m_height;
    if (dy > 0) {
        targetHeight = std::max(m_height + static_cast<int>(dy), static_cast<int>(maxBottom) + AUTO_EXPAND_MARGIN_PX);
    } else if (dy < 0 && m_height + static_cast<int>(dy) >= 300) {
        int minRequired = static_cast<int>(m_page->getBBox().bottom) + AUTO_EXPAND_MARGIN_PX;
        targetHeight = std::max(300, std::max(minRequired, m_height + static_cast<int>(dy)));
    }
    if (m_yRuling > 0) {
        targetHeight = ((targetHeight + m_yRuling - 1) / m_yRuling) * m_yRuling;
    }
    if (targetHeight != m_height) {
        PageProperties props = m_page->getProperties();
        props.height = static_cast<Dim>(targetHeight);
        m_page->setProperties(&props);
        if (m_page->svgDoc) {
            m_page->svgDoc->setViewBox(Rect());
        }
        m_height = targetHeight;
        if (m_rulingStyle != RULING_STANDARD) {
            rebuildProceduralRuleLayer();
        }
    }
    m_doc->history->endAction();
    if (outNewHeight) *outNewHeight = m_height;
    return true;
}

bool NativeCanvasSession::insertHorizontalSpace(float xThreshold, float deltaX, bool snapToRuling, int* outNewWidth) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNewWidth) *outNewWidth = m_width;
    if (!m_page) return false;

    Dim dx = static_cast<Dim>(deltaX);
    if (snapToRuling) {
        Dim xr = m_page->xruling() > 0 ? m_page->xruling() : m_page->yruling(true);
        if (xr > 0) {
            dx = std::round(dx / xr) * xr;
        }
    }
    if (std::abs(dx) < 1.0f) return false;

    std::vector<Element*> toShift;
    for (Element* el : m_page->children()) {
        if (!el) continue;
        if (el->com().x >= xThreshold || el->bbox().left >= xThreshold) {
            toShift.push_back(el);
        }
    }

    m_doc->history->startAction(0);
    Dim maxRight = 0;
    for (Element* el : toShift) {
        el->applyTransform(Transform2D::translating(dx, 0));
        el->commitTransform();
        m_doc->history->addItem(new StrokeTranslateItem(el, m_page, dx, 0));
        maxRight = std::max(maxRight, el->bbox().right);
    }

    int targetWidth = m_width;
    if (dx > 0) {
        targetWidth = std::max(m_width + static_cast<int>(dx), static_cast<int>(maxRight) + AUTO_EXPAND_MARGIN_PX);
    } else if (dx < 0 && m_width + static_cast<int>(dx) >= 400) {
        int minRequired = static_cast<int>(m_page->getBBox().right) + AUTO_EXPAND_MARGIN_PX;
        targetWidth = std::max(400, std::max(minRequired, m_width + static_cast<int>(dx)));
    }
    if (m_xRuling > 0) {
        targetWidth = ((targetWidth + m_xRuling - 1) / m_xRuling) * m_xRuling;
    }
    if (targetWidth != m_width) {
        PageProperties props = m_page->getProperties();
        props.width = static_cast<Dim>(targetWidth);
        m_page->setProperties(&props);
        if (m_page->svgDoc) {
            m_page->svgDoc->setViewBox(Rect());
        }
        m_width = targetWidth;
        if (m_rulingStyle != RULING_STANDARD) {
            rebuildProceduralRuleLayer();
        }
    }
    m_doc->history->endAction();
    if (outNewWidth) *outNewWidth = m_width;
    return true;
}

bool NativeCanvasSession::insertRuledSpace(
    float startX,
    float startY,
    float endX,
    float endY,
    bool enableReflow,
    int* outNewWidth,
    int* outNewHeight
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    if (!m_page) return false;

    const Dim yr = m_page->yruling(true);
    const int startLine = m_page->getLine(static_cast<Dim>(startY));
    const int endLine = m_page->getLine(static_cast<Dim>(endY));
    const int dLine = endLine - startLine;
    const Dim dy = dLine * yr;
    const Dim dx = (startX > m_page->marginLeft()) ? static_cast<Dim>(endX - startX) : 0.0f;

    if (std::abs(dx) < 2.0f && dLine == 0) return false;

    std::vector<Element*> firstLineStrokes;
    std::vector<Element*> belowLineStrokes;

    for (Element* el : m_page->children()) {
        if (!el) continue;
        int line = m_page->getLine(el);
        if (line == startLine && el->com().x >= startX) {
            firstLineStrokes.push_back(el);
        } else if (line > startLine) {
            belowLineStrokes.push_back(el);
        }
    }

    // Sort first-line strokes left-to-right for intra-line shift & word reflow
    std::sort(firstLineStrokes.begin(), firstLineStrokes.end(), [](const Element* a, const Element* b) {
        return a->bbox().left < b->bbox().left;
    });

    m_doc->history->startAction(0);
    Dim maxRight = 0, maxBottom = 0;
    Dim extraReflowDy = 0.0f;

    const Dim rightLimit = m_page->width() - 24.0f;
    const Dim leftStart = m_page->marginLeft() > 0 ? m_page->marginLeft() + 16.0f : 32.0f;
    const Dim minWordGap = yr * 0.45f;

    // Check if shifting firstLineStrokes by dx causes word overflow past right edge
    size_t wrapStartIndex = firstLineStrokes.size();
    if (enableReflow && dx > 0 && !firstLineStrokes.empty()) {
        Dim prevRight = -1e9f;
        size_t lastWordBreak = 0;
        for (size_t i = 0; i < firstLineStrokes.size(); ++i) {
            Rect b = firstLineStrokes[i]->bbox();
            if (i > 0 && (b.left - prevRight) >= minWordGap) {
                lastWordBreak = i;
            }
            prevRight = std::max(prevRight, b.right);
            if (b.right + dx > rightLimit) {
                wrapStartIndex = lastWordBreak;
                break;
            }
        }
    }

    Dim wrapLeftOffset = 0.0f;
    if (wrapStartIndex < firstLineStrokes.size()) {
        wrapLeftOffset = leftStart - firstLineStrokes[wrapStartIndex]->bbox().left;
        extraReflowDy = yr;
    }

    for (size_t i = 0; i < firstLineStrokes.size(); ++i) {
        Element* el = firstLineStrokes[i];
        Dim sdx = (i < wrapStartIndex) ? dx : wrapLeftOffset;
        Dim sdy = (i < wrapStartIndex) ? dy : (dy + yr);
        if (std::abs(sdx) > 0.01f || std::abs(sdy) > 0.01f) {
            el->applyTransform(Transform2D::translating(sdx, sdy));
            el->commitTransform();
            m_doc->history->addItem(new StrokeTranslateItem(el, m_page, sdx, sdy));
        }
        maxRight = std::max(maxRight, el->bbox().right);
        maxBottom = std::max(maxBottom, el->bbox().bottom);
    }

    const Dim totalBelowDy = dy + extraReflowDy;
    if (std::abs(totalBelowDy) > 0.01f) {
        for (Element* el : belowLineStrokes) {
            el->applyTransform(Transform2D::translating(0, totalBelowDy));
            el->commitTransform();
            m_doc->history->addItem(new StrokeTranslateItem(el, m_page, 0, totalBelowDy));
            maxRight = std::max(maxRight, el->bbox().right);
            maxBottom = std::max(maxBottom, el->bbox().bottom);
        }
    }

    int targetHeight = m_height;
    if (totalBelowDy > 0 || maxBottom > m_height - AUTO_EXPAND_MARGIN_PX) {
        targetHeight = std::max(
            m_height + static_cast<int>(totalBelowDy),
            static_cast<int>(maxBottom) + AUTO_EXPAND_MARGIN_PX
        );
    } else if (totalBelowDy < 0 && m_height + static_cast<int>(totalBelowDy) >= 300) {
        int minRequired = static_cast<int>(m_page->getBBox().bottom) + AUTO_EXPAND_MARGIN_PX;
        targetHeight = std::max(300, std::max(minRequired, m_height + static_cast<int>(totalBelowDy)));
    }
    if (m_yRuling > 0) {
        targetHeight = ((targetHeight + m_yRuling - 1) / m_yRuling) * m_yRuling;
    }
    if (targetHeight != m_height) {
        PageProperties props = m_page->getProperties();
        props.height = static_cast<Dim>(targetHeight);
        m_page->setProperties(&props);
        if (m_page->svgDoc) {
            m_page->svgDoc->setViewBox(Rect());
        }
        m_height = targetHeight;
        if (m_rulingStyle != RULING_STANDARD) {
            rebuildProceduralRuleLayer();
        }
    }

    m_doc->history->endAction();
    if (outNewWidth) *outNewWidth = m_width;
    if (outNewHeight) *outNewHeight = m_height;
    return true;
}

bool NativeCanvasSession::setPageSetup(
    int width,
    int height,
    int xRuling,
    int yRuling,
    int marginLeft,
    int paperColorArgb,
    int ruleColorArgb,
    int rulingStyle
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page) return false;
    m_doc->history->startAction(0);
    PageProperties props = m_page->getProperties();
    if (width > 0) props.width = static_cast<Dim>(width);
    if (height > 0) props.height = static_cast<Dim>(height);
    props.xRuling = static_cast<Dim>(std::max(0, xRuling));
    props.yRuling = static_cast<Dim>(std::max(0, yRuling));
    props.marginLeft = static_cast<Dim>(std::max(0, marginLeft));
    props.color = Color::fromArgb(static_cast<uint32_t>(paperColorArgb)).opaque();
    props.ruleColor = Color::fromArgb(static_cast<uint32_t>(ruleColorArgb));

    if (rulingStyle >= 0) {
        m_rulingStyle = rulingStyle;
    }
    if (m_rulingStyle == RULING_STANDARD) {
        m_page->isCustomRuling = false;
        if (m_page->contentNode) {
            m_page->contentNode->setAttr("rulingstyle", rulingStyleToName(m_rulingStyle));
        }
    }
    m_page->setProperties(&props);
    if (m_page->svgDoc) {
        m_page->svgDoc->setViewBox(Rect());
    }
    if (m_rulingStyle != RULING_STANDARD) {
        rebuildProceduralRuleLayer();
    }
    m_doc->history->endAction();

    m_width = static_cast<int>(m_page->width());
    m_height = static_cast<int>(m_page->height());
    m_xRuling = static_cast<int>(m_page->xruling());
    m_yRuling = static_cast<int>(m_page->yruling());
    return true;
}

bool NativeCanvasSession::lockContentAsTemplate() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page || !m_page->contentNode || m_page->contentNode->children().empty()) {
        return false;
    }
    clearSelection();
    if (!m_page->ruleNode) {
        m_page->generateRuleLayer(m_page->props.color, m_page->props.width, m_page->props.height);
    }
    m_page->ruleNode->removeClass("write-std-ruling");
    m_page->ruleNode->addClass("customruling");
    m_page->isCustomRuling = true;
    m_rulingStyle = RULING_CUSTOM_DESIGNER;

    std::vector<SvgNode*> toMove;
    for (SvgNode* child : m_page->contentNode->children()) {
        toMove.push_back(child);
    }
    for (SvgNode* child : toMove) {
        if (child->hasExt()) {
            m_page->onRemoveStroke(static_cast<Element*>(child->ext()));
        }
        m_page->contentNode->removeChild(child);
        m_page->ruleNode->addChild(child);
    }
    if (m_doc) {
        delete m_doc->history;
        m_doc->history = new UndoHistory;
    }
    m_page->contentNode->setAttr("rulingstyle", "custom");
    m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
    return true;
}

bool NativeCanvasSession::unlockTemplateToContent() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page || !m_page->ruleNode) return false;
    clearSelection();

    std::vector<SvgNode*> customNodes;
    for (SvgNode* child : m_page->ruleNode->children()) {
        if (!child->hasClass("pagerect")) {
            customNodes.push_back(child);
        }
    }
    if (customNodes.empty()) return false;

    SvgNode* firstContent = m_page->contentNode->children().empty()
        ? nullptr
        : m_page->contentNode->children().front();

    for (SvgNode* node : customNodes) {
        m_page->ruleNode->removeChild(node);
        if (!node->hasExt()) {
            new Element(node);
        }
        m_page->contentNode->addChild(node, firstContent);
        m_page->onAddStroke(static_cast<Element*>(node->ext()));
    }
    if (m_doc) {
        delete m_doc->history;
        m_doc->history = new UndoHistory;
    }

    m_rulingStyle = RULING_STANDARD;
    m_page->isCustomRuling = false;
    rebuildProceduralRuleLayer();
    m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
    return true;
}

bool NativeCanvasSession::clearCustomTemplate() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page) return false;
    if (m_doc) {
        delete m_doc->history;
        m_doc->history = new UndoHistory;
    }
    m_rulingStyle = RULING_STANDARD;
    m_page->isCustomRuling = false;
    rebuildProceduralRuleLayer();
    m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
    return true;
}

bool NativeCanvasSession::insertImageBytes(
    const uint8_t* bytes,
    size_t length,
    bool asBackground,
    float* outBounds4
) {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page || !bytes || length < 16) return false;
    Image decoded = Image::decodeBuffer(bytes, length);
    if (decoded.isNull() || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
        return false;
    }

    const Dim pageW = m_page->width();
    const Dim pageH = m_page->height();
    const Dim imgW = static_cast<Dim>(decoded.getWidth());
    const Dim imgH = static_cast<Dim>(decoded.getHeight());

    if (asBackground) {
        // Place image into the locked custom background template layer
        if (!m_page->ruleNode) {
            m_page->generateRuleLayer(m_page->props.color, pageW, pageH);
        }
        m_page->ruleNode->removeClass("write-std-ruling");
        m_page->ruleNode->addClass("customruling");
        m_page->isCustomRuling = true;
        m_rulingStyle = RULING_CUSTOM_DESIGNER;
        m_page->contentNode->setAttr("rulingstyle", "custom");

        // Fit to page width and anchor at top (y=0) so the background template fills the canvas
        // rather than floating zoomed-out in the vertical middle of an oversized page.
        Dim scale = pageW / imgW;
        Dim dstW = pageW;
        Dim dstH = imgH * scale;
        Dim dstX = 0.0f;
        Dim dstY = 0.0f;
        if (dstH > pageH) {
            PageProperties props = m_page->getProperties();
            props.width = pageW;
            props.height = dstH;
            m_page->setProperties(&props);
            if (m_page->svgDoc) {
                m_page->svgDoc->setViewBox(Rect());
            }
        }
        Rect bbox = Rect::ltwh(dstX, dstY, dstW, dstH);

        auto* svgImg = new SvgImage(std::move(decoded), bbox);
        new Element(svgImg);
        // Insert right after .pagerect so rule lines or custom template strokes stay visible
        SvgNode* pagerect = m_page->ruleNode->selectFirst(".pagerect");
        SvgNode* afterRect = nullptr;
        if (pagerect) {
            auto& ch = m_page->ruleNode->children();
            auto it = std::find(ch.begin(), ch.end(), pagerect);
            if (it != ch.end() && ++it != ch.end()) {
                afterRect = *it;
            }
        }
        m_page->ruleNode->addChild(svgImg, afterRect);
        m_page->svgDoc->setDirty(SvgNode::PIXELS_DIRTY);
        if (outBounds4) {
            outBounds4[0] = outBounds4[1] = outBounds4[2] = outBounds4[3] = 0.0f;
        }
        return true;
    } else {
        // Insert as an interactive, selectable, movable, scalable, rotatable Element on the canvas
        Dim scale = std::min<Dim>(1.0f, std::min((pageW * 0.65f) / imgW, (pageH * 0.65f) / imgH));
        Dim dstW = imgW * scale;
        Dim dstH = imgH * scale;
        Dim dstX = std::max<Dim>(24.0f, (pageW - dstW) * 0.5f);
        Dim dstY = std::max<Dim>(24.0f, std::min<Dim>(120.0f, (pageH - dstH) * 0.25f));
        Rect bbox = Rect::ltwh(dstX, dstY, dstW, dstH);

        auto* svgImg = new SvgImage(std::move(decoded), bbox);
        ++m_strokeSeq;
        std::string strokeId = "img_" + std::to_string(m_strokeSeq);
        svgImg->setXmlId(strokeId.c_str());

        auto* elem = new Element(svgImg);
        m_doc->history->startAction(0);
        m_page->addStroke(elem);
        m_doc->history->endAction();

        m_selectedElements.clear();
        m_selectedElements.push_back(elem);
        computeSelectionBounds(outBounds4);
        return true;
    }
}

bool NativeCanvasSession::hasCustomTemplateNodes() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_page || !m_page->ruleNode) return false;
    return m_page->isCustomRuling && m_page->ruleNode->children().size() > 1;
}

int NativeCanvasSession::paperColorArgb() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_page ? static_cast<int>(m_page->color().argb()) : static_cast<int>(0xFFFFFDF8);
}

int NativeCanvasSession::ruleColorArgb() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_page ? static_cast<int>(m_page->props.ruleColor.argb()) : static_cast<int>(0xFF4A90E2);
}

int NativeCanvasSession::marginLeft() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_page ? static_cast<int>(m_page->marginLeft()) : 0;
}

int NativeCanvasSession::strokeCount() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_page ? m_page->strokeCount() : 0;
}

bool NativeCanvasSession::undo() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_doc || !m_doc->history || !m_doc->history->canUndo()) return false;
    m_doc->history->undo();
    if (m_page) {
        m_width = static_cast<int>(m_page->width());
        m_height = static_cast<int>(m_page->height());
        m_xRuling = static_cast<int>(m_page->xruling());
        m_yRuling = static_cast<int>(m_page->yruling());
        rebuildProceduralRuleLayer();
    }
    sanitizeSelection();
    return true;
}

bool NativeCanvasSession::redo() {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    if (!m_doc || !m_doc->history || !m_doc->history->canRedo()) return false;
    m_doc->history->redo();
    if (m_page) {
        m_width = static_cast<int>(m_page->width());
        m_height = static_cast<int>(m_page->height());
        m_xRuling = static_cast<int>(m_page->xruling());
        m_yRuling = static_cast<int>(m_page->yruling());
        rebuildProceduralRuleLayer();
    }
    sanitizeSelection();
    return true;
}

bool NativeCanvasSession::canUndo() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_doc && m_doc->history && m_doc->history->canUndo();
}

bool NativeCanvasSession::canRedo() const {
    std::lock_guard<std::recursive_mutex> lock(m_mutex);
    return m_doc && m_doc->history && m_doc->history->canRedo();
}

} // namespace orgwritenotes

// ----------------------------------------------------------------------------
// JNI Exports for `com.orgwritenotes.ink.WriteNativeBridge`
// ----------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeCreateSession(
    JNIEnv*, jobject, jint width, jint height, jint xRuling, jint yRuling
) {
    auto* session = new orgwritenotes::NativeCanvasSession(width, height, xRuling, yRuling);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRetainSession(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) session->retain();
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeDestroySession(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) session->release();
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeLoadSvgz(
    JNIEnv* env, jobject, jlong handle, jbyteArray svgzBytes
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !svgzBytes) return JNI_FALSE;
    jsize len = env->GetArrayLength(svgzBytes);
    jbyte* ptr = env->GetByteArrayElements(svgzBytes, nullptr);
    bool ok = session->loadSvgzBytes(reinterpret_cast<const uint8_t*>(ptr), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(svgzBytes, ptr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeExportSvgz(
    JNIEnv* env, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return nullptr;
    std::vector<uint8_t> bytes = session->exportSvgzBytes();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!bytes.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSetRenderTheme(
    JNIEnv*, jobject, jlong handle, jboolean isDark, jint darkBgArgb, jint darkFgArgb
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) {
        session->setRenderTheme(isDark == JNI_TRUE, static_cast<uint32_t>(darkBgArgb), static_cast<uint32_t>(darkFgArgb));
    }
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRenderToBitmap(
    JNIEnv* env, jobject, jlong handle, jobject bitmap
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !bitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return JNI_FALSE;
    }
    bool ok = session->renderToPixels(
        reinterpret_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height)
    );
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRenderTileToBitmap(
    JNIEnv* env, jobject, jlong handle, jobject bitmap, jfloat originX, jfloat originY
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !bitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return JNI_FALSE;
    }
    bool ok = session->renderToPixels(
        reinterpret_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        originX,
        originY
    );
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRenderDirtyRectToBitmap(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap,
    jfloat left,
    jfloat top,
    jfloat right,
    jfloat bottom
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !bitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return JNI_FALSE;
    }
    bool ok = session->renderDirtyRectToPixels(
        reinterpret_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        left,
        top,
        right,
        bottom
    );
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSetPen(
    JNIEnv*,
    jobject,
    jlong handle,
    jint colorArgb,
    jfloat strokeWidth,
    jboolean pressureSensitive,
    jint tipType,
    jboolean snapToGrid,
    jboolean lineDrawing,
    jfloat chiselAngle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) {
        session->setPen(
            colorArgb,
            strokeWidth,
            pressureSensitive == JNI_TRUE,
            tipType,
            snapToGrid == JNI_TRUE,
            lineDrawing == JNI_TRUE,
            chiselAngle
        );
    }
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeBeginStroke(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y, jfloat pressure, jfloat tiltX, jfloat tiltY, jdouble timestampMs
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) session->beginStroke(x, y, pressure, tiltX, tiltY, timestampMs);
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeMoveStroke(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y, jfloat pressure, jfloat tiltX, jfloat tiltY, jdouble timestampMs
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) session->moveStroke(x, y, pressure, tiltX, tiltY, timestampMs);
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeMoveStrokeBatch(
    JNIEnv* env, jobject, jlong handle, jfloatArray pointsData6, jint pointCount
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !pointsData6 || pointCount <= 0) return;
    const jsize totalFloats = pointCount * 6;
    if (env->GetArrayLength(pointsData6) < totalFloats) return;

    constexpr int STACK_MAX_POINTS = 64;
    if (pointCount <= STACK_MAX_POINTS) {
        jfloat stackBuf[STACK_MAX_POINTS * 6];
        env->GetFloatArrayRegion(pointsData6, 0, totalFloats, stackBuf);
        session->moveStrokeBatch(stackBuf, pointCount);
    } else {
        std::vector<jfloat> heapBuf(static_cast<size_t>(totalFloats));
        env->GetFloatArrayRegion(pointsData6, 0, totalFloats, heapBuf.data());
        session->moveStrokeBatch(heapBuf.data(), pointCount);
    }
}

JNIEXPORT jstring JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeEndStroke(
    JNIEnv* env, jobject, jlong handle, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return env->NewStringUTF("");
    bool expanded = false;
    int newHeight = session->height();
    float dirtyBounds[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    std::string pathXml = session->endStroke(&expanded, &newHeight, dirtyBounds);
    if (outNewDimensions) {
        const jsize len = env->GetArrayLength(outNewDimensions);
        if (len >= 6) {
            jint dimsAndDirty[6] = {
                session->width(),
                newHeight,
                static_cast<jint>(std::floor(dirtyBounds[0])),
                static_cast<jint>(std::floor(dirtyBounds[1])),
                static_cast<jint>(std::ceil(dirtyBounds[2])),
                static_cast<jint>(std::ceil(dirtyBounds[3]))
            };
            env->SetIntArrayRegion(outNewDimensions, 0, 6, dimsAndDirty);
        } else if (len >= 2) {
            jint dims[2] = { session->width(), newHeight };
            env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
        }
    }
    return env->NewStringUTF(pathXml.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeEraseAt(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y, jfloat radius
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->eraseAt(x, y, radius)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeEraseRuledLineAt(
    JNIEnv*, jobject, jlong handle, jfloat y
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->eraseRuledLineAt(y)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeEraseFreeSegment(
    JNIEnv*, jobject, jlong handle, jfloat prevX, jfloat prevY, jfloat x, jfloat y, jfloat radius
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->eraseFreeSegment(prevX, prevY, x, y, radius)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeEraseSegmentDirty(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat prevX,
    jfloat prevY,
    jfloat x,
    jfloat y,
    jfloat radius,
    jint eraseMode,
    jfloatArray outDirty4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    float dirty[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    bool erased = false;
    if (eraseMode == 1) {
        erased = session->eraseRuledLineAt(y, dirty);
    } else if (eraseMode == 2) {
        erased = session->eraseFreeSegment(prevX, prevY, x, y, radius, dirty);
    } else {
        erased = session->eraseSegment(prevX, prevY, x, y, radius, dirty);
    }
    if (erased && outDirty4 && env->GetArrayLength(outDirty4) >= 4) {
        env->SetFloatArrayRegion(outDirty4, 0, 4, dirty);
    }
    return erased ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSelectRect(
    JNIEnv* env, jobject, jlong handle, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloatArray outBounds4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return 0;
    float bounds[4] = {0, 0, 0, 0};
    int count = session->selectRect(left, top, right, bottom, bounds);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    return count;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSelectLasso(
    JNIEnv* env, jobject, jlong handle, jfloatArray xyPoints, jfloatArray outBounds4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !xyPoints) return 0;
    jsize len = env->GetArrayLength(xyPoints);
    jfloat* pts = env->GetFloatArrayElements(xyPoints, nullptr);
    float bounds[4] = {0, 0, 0, 0};
    int count = session->selectLasso(pts, static_cast<int>(len), bounds);
    env->ReleaseFloatArrayElements(xyPoints, pts, JNI_ABORT);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    return count;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSelectRuled(
    JNIEnv* env, jobject, jlong handle, jfloat startY, jfloat endY, jfloatArray outBounds4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return 0;
    float bounds[4] = {0, 0, 0, 0};
    int count = session->selectRuled(startY, endY, bounds);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    return count;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSelectAll(
    JNIEnv* env, jobject, jlong handle, jfloatArray outBounds4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return 0;
    float bounds[4] = {0, 0, 0, 0};
    int count = session->selectAll(bounds);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    return count;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeClearSelection(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (session) session->clearSelection();
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeTranslateSelection(
    JNIEnv* env, jobject, jlong handle, jfloat dx, jfloat dy, jfloatArray outBounds4, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    float bounds[4] = {0, 0, 0, 0};
    int newW = session->width();
    int newH = session->height();
    bool ok = session->translateSelection(dx, dy, bounds, &newW, &newH);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeScaleSelection(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat sx,
    jfloat sy,
    jfloat pivotX,
    jfloat pivotY,
    jboolean scaleWidths,
    jfloatArray outBounds4,
    jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    float bounds[4] = {0, 0, 0, 0};
    int newW = session->width();
    int newH = session->height();
    bool ok = session->scaleSelection(sx, sy, pivotX, pivotY, scaleWidths == JNI_TRUE, bounds, &newW, &newH);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRotateSelection(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat angleRad,
    jfloat pivotX,
    jfloat pivotY,
    jfloatArray outBounds4,
    jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    float bounds[4] = {0, 0, 0, 0};
    int newW = session->width();
    int newH = session->height();
    bool ok = session->rotateSelection(angleRad, pivotX, pivotY, bounds, &newW, &newH);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeDeleteSelection(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->deleteSelection()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeDuplicateSelection(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat offsetX,
    jfloat offsetY,
    jfloatArray outBounds4,
    jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return 0;
    float bounds[4] = {0, 0, 0, 0};
    int newW = session->width();
    int newH = session->height();
    int count = session->duplicateSelection(offsetX, offsetY, bounds, &newW, &newH);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return count;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRestyleSelection(
    JNIEnv*, jobject, jlong handle, jint colorArgb, jfloat strokeWidth
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->restyleSelection(colorArgb, strokeWidth)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeCopySelectionSvg(
    JNIEnv* env, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return env->NewStringUTF("");
    std::string xml = session->copySelectionSvg();
    return env->NewStringUTF(xml.c_str());
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativePasteSelectionSvg(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring svgFragment,
    jfloat offsetX,
    jfloat offsetY,
    jfloatArray outBounds4,
    jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !svgFragment) return 0;
    const char* rawStr = env->GetStringUTFChars(svgFragment, nullptr);
    std::string xml(rawStr ? rawStr : "");
    if (rawStr) env->ReleaseStringUTFChars(svgFragment, rawStr);

    float bounds[4] = {0, 0, 0, 0};
    int newW = session->width();
    int newH = session->height();
    int count = session->pasteSelectionSvg(xml, offsetX, offsetY, bounds, &newW, &newH);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return count;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeInsertVerticalSpace(
    JNIEnv* env, jobject, jlong handle, jfloat yThreshold, jfloat deltaY, jboolean snapToRuling, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    int newH = session->height();
    bool ok = session->insertVerticalSpace(yThreshold, deltaY, snapToRuling == JNI_TRUE, &newH);
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { session->width(), newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeInsertHorizontalSpace(
    JNIEnv* env, jobject, jlong handle, jfloat xThreshold, jfloat deltaX, jboolean snapToRuling, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    int newW = session->width();
    bool ok = session->insertHorizontalSpace(xThreshold, deltaX, snapToRuling == JNI_TRUE, &newW);
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, session->height() };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeInsertRuledSpace(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat startX,
    jfloat startY,
    jfloat endX,
    jfloat endY,
    jboolean enableReflow,
    jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    int newW = session->width();
    int newH = session->height();
    bool ok = session->insertRuledSpace(startX, startY, endX, endY, enableReflow == JNI_TRUE, &newW, &newH);
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { newW, newH };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSetPageSetup(
    JNIEnv*,
    jobject,
    jlong handle,
    jint width,
    jint height,
    jint xRuling,
    jint yRuling,
    jint marginLeft,
    jint paperColorArgb,
    jint ruleColorArgb,
    jint rulingStyle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    return session->setPageSetup(
        width, height, xRuling, yRuling, marginLeft, paperColorArgb, ruleColorArgb, rulingStyle
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeLockContentAsTemplate(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->lockContentAsTemplate()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeUnlockTemplateToContent(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->unlockTemplateToContent()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeClearCustomTemplate(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->clearCustomTemplate()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeInsertImageBytes(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray imageBytes,
    jboolean asBackground,
    jfloatArray outBounds4
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !imageBytes) return JNI_FALSE;
    jsize len = env->GetArrayLength(imageBytes);
    jbyte* ptr = env->GetByteArrayElements(imageBytes, nullptr);
    float bounds[4] = {0, 0, 0, 0};
    bool ok = session->insertImageBytes(
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len),
        asBackground == JNI_TRUE,
        bounds
    );
    env->ReleaseByteArrayElements(imageBytes, ptr, JNI_ABORT);
    if (outBounds4 && env->GetArrayLength(outBounds4) >= 4) {
        env->SetFloatArrayRegion(outBounds4, 0, 4, bounds);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeGetPageInfo(
    JNIEnv* env, jobject, jlong handle, jintArray outInfo10
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !outInfo10 || env->GetArrayLength(outInfo10) < 8) return;
    jint info[10] = {
        session->width(),
        session->height(),
        session->xRuling(),
        session->yRuling(),
        session->marginLeft(),
        session->paperColorArgb(),
        session->ruleColorArgb(),
        session->strokeCount(),
        session->rulingStyle(),
        session->hasCustomTemplateNodes() ? 1 : 0
    };
    jsize len = std::min<jsize>(10, env->GetArrayLength(outInfo10));
    env->SetIntArrayRegion(outInfo10, 0, len, info);
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeUndo(
    JNIEnv* env, jobject, jlong handle, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !session->undo()) return JNI_FALSE;
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { session->width(), session->height() };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeRedo(
    JNIEnv* env, jobject, jlong handle, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session || !session->redo()) return JNI_FALSE;
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { session->width(), session->height() };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeCanUndo(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->canUndo()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeCanRedo(
    JNIEnv*, jobject, jlong handle
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->canRedo()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeResizeCanvas(
    JNIEnv*, jobject, jlong handle, jint newWidth, jint newHeight
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->resizeCanvas(newWidth, newHeight)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSnapActiveStrokeToShape(
    JNIEnv* env, jobject, jlong handle, jfloatArray outSnappedPoints, jintArray outNumPoints1
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return 0;
    float localPts[256] = {0};
    int numPts = 0;
    int shapeType = session->snapActiveStrokeToShape(localPts, 256, &numPts);
    if (shapeType > 0 && outSnappedPoints && numPts > 0) {
        jsize maxFloats = std::min<jsize>(env->GetArrayLength(outSnappedPoints), numPts * 2);
        env->SetFloatArrayRegion(outSnappedPoints, 0, maxFloats, localPts);
    }
    if (outNumPoints1 && env->GetArrayLength(outNumPoints1) >= 1) {
        jint n = numPts;
        env->SetIntArrayRegion(outNumPoints1, 0, 1, &n);
    }
    return shapeType;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeTrimWhitespace(
    JNIEnv* env, jobject, jlong handle, jint paddingPx, jintArray outNewDimensions
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    if (!session) return JNI_FALSE;
    int newHeight = session->height();
    bool ok = session->trimBottomWhitespace(paddingPx, &newHeight);
    if (outNewDimensions && env->GetArrayLength(outNewDimensions) >= 2) {
        jint dims[2] = { session->width(), newHeight };
        env->SetIntArrayRegion(outNewDimensions, 0, 2, dims);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orgwritenotes_ink_WriteNativeBridge_nativeSetTemplateImageOpacity(
    JNIEnv*, jobject, jlong handle, jfloat opacity
) {
    auto* session = reinterpret_cast<orgwritenotes::NativeCanvasSession*>(handle);
    return (session && session->setTemplateImageOpacity(opacity)) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
