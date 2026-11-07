#include "eink_fb.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>

#ifdef __ANDROID__
// Ensure ELF PT_TLS segment alignment is >= 32 bytes for static ARM binaries
alignas(64) thread_local int g_kindleBionicTlsAlign = 1;

extern "C" int __android_log_print(int /*prio*/, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    std::fprintf(stderr, "[%s] ", tag ? tag : "kindle");
    int res = std::vfprintf(stderr, fmt, args);
    std::fprintf(stderr, "\n");
    va_end(args);
    return res + g_kindleBionicTlsAlign - 1;
}
#endif

#if defined(__linux__)
#include <fcntl.h>
#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif

namespace orgwritenotes::kindle {

namespace {

// ============================================================================
// Kindle / Freescale / MediaTek EPDC (mxcfb) Kernel ioctl Definitions
// Compatible with Kindle Oasis / PW5 / Kindle Scribe (MT8183 / Bellatrix4)
// ============================================================================
struct mxcfb_rect {
    uint32_t top;
    uint32_t left;
    uint32_t width;
    uint32_t height;
};

struct mxcfb_alt_buffer_data {
    uint32_t phys_addr;
    uint32_t width;
    uint32_t height;
    struct mxcfb_rect alt_update_region;
};

struct mxcfb_swipe_data {
    uint32_t direction;
    uint32_t steps;
};

// MediaTek Bellatrix3 / Kindle Scribe (hwtcon_v2) 96-byte update structure
struct mxcfb_update_data_mtk {
    struct mxcfb_rect update_region;
    uint32_t waveform_mode;
    uint32_t update_mode;
    uint32_t update_marker;
    int temp;
    unsigned int flags;
    int dither_mode;
    int quant_bit;
    struct mxcfb_alt_buffer_data alt_buffer_data;
    struct mxcfb_swipe_data swipe_data;
    uint32_t hist_bw_waveform_mode;
    uint32_t hist_gray_waveform_mode;
    uint32_t ts_pxp;
    uint32_t ts_epdc;
};

struct mxcfb_update_data {
    struct mxcfb_rect update_region;
    uint32_t waveform_mode;
    uint32_t update_mode;
    uint32_t update_marker;
    int temp;
    unsigned int flags;
    struct mxcfb_alt_buffer_data alt_buffer_data;
};

struct mxcfb_update_marker_data {
    uint32_t update_marker;
    uint32_t collision_test;
};

static constexpr unsigned long kMxcfbSendUpdateMtk    = 1080051246UL; // _IOW('F', 0x2E, struct mxcfb_update_data_mtk)
static constexpr unsigned long kMxcfbSendUpdateLegacy = 1078478382UL;

constexpr uint32_t UPDATE_MODE_PARTIAL = 0x0;
constexpr uint32_t UPDATE_MODE_FULL    = 0x1;
constexpr int TEMP_USE_AMBIENT         = 0x1000;

// MediaTek (Kindle Scribe / PW5) waveform IDs from mxcfb_kindle_h.lua
static uint32_t toMtkWaveform(EinkWaveform wf) {
    switch (wf) {
        case EinkWaveform::INIT:      return 0; // MTK_WAVEFORM_MODE_INIT
        case EinkWaveform::DU:        return 1; // MTK_WAVEFORM_MODE_DU (fast 1-bit monochrome pen trace)
        case EinkWaveform::GC16:      return 2; // MTK_WAVEFORM_MODE_GC16
        case EinkWaveform::GC16_FAST: return 4; // MTK_WAVEFORM_MODE_GLR16 / REAGL (clean 16-level stroke settle)
        case EinkWaveform::A2:        return 6; // MTK_WAVEFORM_MODE_A2
        case EinkWaveform::GL16:      return 3; // MTK_WAVEFORM_MODE_GL16
    }
    return 2;
}

// 4x4 Ordered Bayer matrix (scaled 0..255) for crisp DU 1-bit stroke previews
static constexpr uint8_t kBayer4x4[4][4] = {
    {  15, 195,  60, 240 },
    { 135,  75, 180, 120 },
    {  45, 225,  30, 210 },
    { 165, 105, 150,  90 }
};

// Compact 8x8 monochrome bitmap font for printable ASCII (0x20..0x7E)
static const uint8_t* glyph8x8(char c) {
    static const uint8_t kSpace[8] = {0,0,0,0,0,0,0,0};
    static const uint8_t kFont[95][8] = {
        {0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00}, // ' '
        {0x18,0x3C,0x3C,0x18,0x18,0x00,0x18,0x00}, // '!'
        {0x6C,0x6C,0x24,0x00,0x00,0x00,0x00,0x00}, // '"'
        {0x6C,0x6C,0xFE,0x6C,0xFE,0x6C,0x6C,0x00}, // '#'
        {0x18,0x3E,0x60,0x3C,0x06,0x7C,0x18,0x00}, // '$'
        {0x00,0xC6,0xCC,0x18,0x30,0x66,0xC6,0x00}, // '%'
        {0x38,0x6C,0x38,0x76,0xDC,0xCC,0x76,0x00}, // '&'
        {0x18,0x18,0x30,0x00,0x00,0x00,0x00,0x00}, // '''
        {0x0C,0x18,0x30,0x30,0x30,0x18,0x0C,0x00}, // '('
        {0x30,0x18,0x0C,0x0C,0x0C,0x18,0x30,0x00}, // ')'
        {0x00,0x66,0x3C,0xFF,0x3C,0x66,0x00,0x00}, // '*'
        {0x00,0x18,0x18,0x7E,0x18,0x18,0x00,0x00}, // '+'
        {0x00,0x00,0x00,0x00,0x00,0x18,0x18,0x30}, // ','
        {0x00,0x00,0x00,0x7E,0x00,0x00,0x00,0x00}, // '-'
        {0x00,0x00,0x00,0x00,0x00,0x18,0x18,0x00}, // '.'
        {0x06,0x0C,0x18,0x30,0x60,0xC0,0x80,0x00}, // '/'
        {0x3C,0x66,0x6E,0x76,0x66,0x66,0x3C,0x00}, // '0'
        {0x18,0x38,0x18,0x18,0x18,0x18,0x7E,0x00}, // '1'
        {0x3C,0x66,0x06,0x1C,0x30,0x66,0x7E,0x00}, // '2'
        {0x3C,0x66,0x06,0x1C,0x06,0x66,0x3C,0x00}, // '3'
        {0x1C,0x3C,0x6C,0xCC,0xFE,0x0C,0x1E,0x00}, // '4'
        {0x7E,0x60,0x7C,0x06,0x06,0x66,0x3C,0x00}, // '5'
        {0x1C,0x30,0x60,0x7C,0x66,0x66,0x3C,0x00}, // '6'
        {0x7E,0x66,0x06,0x0C,0x18,0x18,0x18,0x00}, // '7'
        {0x3C,0x66,0x66,0x3C,0x66,0x66,0x3C,0x00}, // '8'
        {0x3C,0x66,0x66,0x3E,0x06,0x0C,0x38,0x00}, // '9'
        {0x00,0x18,0x18,0x00,0x00,0x18,0x18,0x00}, // ':'
        {0x00,0x18,0x18,0x00,0x00,0x18,0x18,0x30}, // ';'
        {0x0C,0x18,0x30,0x60,0x30,0x18,0x0C,0x00}, // '<'
        {0x00,0x00,0x7E,0x00,0x00,0x7E,0x00,0x00}, // '='
        {0x30,0x18,0x0C,0x06,0x0C,0x18,0x30,0x00}, // '>'
        {0x3C,0x66,0x06,0x0C,0x18,0x00,0x18,0x00}, // '?'
        {0x3C,0x66,0x6E,0x6E,0x60,0x62,0x3C,0x00}, // '@'
        {0x18,0x3C,0x66,0x66,0x7E,0x66,0x66,0x00}, // 'A'
        {0x7C,0x66,0x66,0x7C,0x66,0x66,0x7C,0x00}, // 'B'
        {0x3C,0x66,0x60,0x60,0x60,0x66,0x3C,0x00}, // 'C'
        {0x78,0x6C,0x66,0x66,0x66,0x6C,0x78,0x00}, // 'D'
        {0x7E,0x60,0x60,0x78,0x60,0x60,0x7E,0x00}, // 'E'
        {0x7E,0x60,0x60,0x78,0x60,0x60,0x60,0x00}, // 'F'
        {0x3C,0x66,0x60,0x6E,0x66,0x66,0x3E,0x00}, // 'G'
        {0x66,0x66,0x66,0x7E,0x66,0x66,0x66,0x00}, // 'H'
        {0x3C,0x18,0x18,0x18,0x18,0x18,0x3C,0x00}, // 'I'
        {0x1E,0x0C,0x0C,0x0C,0xCC,0xCC,0x78,0x00}, // 'J'
        {0x66,0x6C,0x78,0x70,0x78,0x6C,0x66,0x00}, // 'K'
        {0x60,0x60,0x60,0x60,0x60,0x66,0x7E,0x00}, // 'L'
        {0xC6,0xEE,0xFE,0xD6,0xC6,0xC6,0xC6,0x00}, // 'M'
        {0x66,0x76,0x7E,0x7E,0x6E,0x66,0x66,0x00}, // 'N'
        {0x3C,0x66,0x66,0x66,0x66,0x66,0x3C,0x00}, // 'O'
        {0x7C,0x66,0x66,0x7C,0x60,0x60,0x60,0x00}, // 'P'
        {0x3C,0x66,0x66,0x66,0x6A,0x6C,0x36,0x00}, // 'Q'
        {0x7C,0x66,0x66,0x7C,0x6C,0x66,0x66,0x00}, // 'R'
        {0x3C,0x66,0x60,0x3C,0x06,0x66,0x3C,0x00}, // 'S'
        {0x7E,0x18,0x18,0x18,0x18,0x18,0x18,0x00}, // 'T'
        {0x66,0x66,0x66,0x66,0x66,0x66,0x3C,0x00}, // 'U'
        {0x66,0x66,0x66,0x66,0x66,0x3C,0x18,0x00}, // 'V'
        {0xC6,0xC6,0xC6,0xD6,0xFE,0xEE,0xC6,0x00}, // 'W'
        {0x66,0x66,0x3C,0x18,0x3C,0x66,0x66,0x00}, // 'X'
        {0x66,0x66,0x66,0x3C,0x18,0x18,0x18,0x00}, // 'Y'
        {0x7E,0x06,0x0C,0x18,0x30,0x60,0x7E,0x00}, // 'Z'
        {0x3C,0x30,0x30,0x30,0x30,0x30,0x3C,0x00}, // '['
        {0xC0,0x60,0x30,0x18,0x0C,0x06,0x02,0x00}, // '\'
        {0x3C,0x0C,0x0C,0x0C,0x0C,0x0C,0x3C,0x00}, // ']'
        {0x10,0x38,0x6C,0xC6,0x00,0x00,0x00,0x00}, // '^'
        {0x00,0x00,0x00,0x00,0x00,0x00,0x00,0xFF}, // '_'
        {0x30,0x18,0x0C,0x00,0x00,0x00,0x00,0x00}, // '`'
        {0x00,0x00,0x3C,0x06,0x3E,0x66,0x3E,0x00}, // 'a'
        {0x60,0x60,0x7C,0x66,0x66,0x66,0x7C,0x00}, // 'b'
        {0x00,0x00,0x3C,0x60,0x60,0x66,0x3C,0x00}, // 'c'
        {0x06,0x06,0x3E,0x66,0x66,0x66,0x3E,0x00}, // 'd'
        {0x00,0x00,0x3C,0x66,0x7E,0x60,0x3C,0x00}, // 'e'
        {0x1C,0x36,0x30,0x78,0x30,0x30,0x30,0x00}, // 'f'
        {0x00,0x00,0x3E,0x66,0x66,0x3E,0x06,0x3C}, // 'g'
        {0x60,0x60,0x7C,0x66,0x66,0x66,0x66,0x00}, // 'h'
        {0x18,0x00,0x38,0x18,0x18,0x18,0x3C,0x00}, // 'i'
        {0x06,0x00,0x06,0x06,0x06,0x66,0x66,0x3C}, // 'j'
        {0x60,0x60,0x66,0x6C,0x78,0x6C,0x66,0x00}, // 'k'
        {0x38,0x18,0x18,0x18,0x18,0x18,0x3C,0x00}, // 'l'
        {0x00,0x00,0xEC,0xFE,0xD6,0xC6,0xC6,0x00}, // 'm'
        {0x00,0x00,0x7C,0x66,0x66,0x66,0x66,0x00}, // 'n'
        {0x00,0x00,0x3C,0x66,0x66,0x66,0x3C,0x00}, // 'o'
        {0x00,0x00,0x7C,0x66,0x66,0x7C,0x60,0x60}, // 'p'
        {0x00,0x00,0x3E,0x66,0x66,0x3E,0x06,0x06}, // 'q'
        {0x00,0x00,0x7C,0x66,0x60,0x60,0x60,0x00}, // 'r'
        {0x00,0x00,0x3E,0x60,0x3C,0x06,0x7C,0x00}, // 's'
        {0x18,0x18,0x7E,0x18,0x18,0x18,0x0E,0x00}, // 't'
        {0x00,0x00,0x66,0x66,0x66,0x66,0x3E,0x00}, // 'u'
        {0x00,0x00,0x66,0x66,0x66,0x3C,0x18,0x00}, // 'v'
        {0x00,0x00,0xC6,0xC6,0xD6,0xFE,0x6C,0x00}, // 'w'
        {0x00,0x00,0x66,0x3C,0x18,0x3C,0x66,0x00}, // 'x'
        {0x00,0x00,0x66,0x66,0x66,0x3E,0x06,0x3C}, // 'y'
        {0x00,0x00,0x7E,0x0C,0x18,0x30,0x7E,0x00}, // 'z'
        {0x0E,0x18,0x18,0x70,0x18,0x18,0x0E,0x00}, // '{'
        {0x18,0x18,0x18,0x00,0x18,0x18,0x18,0x00}, // '|'
        {0x70,0x18,0x18,0x0E,0x18,0x18,0x70,0x00}, // '}'
        {0x76,0xDC,0x00,0x00,0x00,0x00,0x00,0x00}  // '~'
    };
    unsigned char uc = static_cast<unsigned char>(c);
    if (uc < 32 || uc > 126) return kSpace;
    return kFont[uc - 32];
}

} // namespace

EinkFramebuffer::EinkFramebuffer() = default;

EinkFramebuffer::~EinkFramebuffer() {
    closeDevice();
}

bool EinkFramebuffer::openDevice(const char* fbPath, int fallbackWidth, int fallbackHeight) {
    closeDevice();

#if defined(__linux__)
    if (fbPath && fbPath[0] != '\0') {
        int fd = open(fbPath, O_RDWR | O_CLOEXEC);
        if (fd >= 0) {
            struct fb_var_screeninfo vinfo{};
            struct fb_fix_screeninfo finfo{};
            if (ioctl(fd, FBIOGET_VSCREENINFO, &vinfo) == 0 &&
                ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == 0 &&
                vinfo.xres > 0 && vinfo.yres > 0) {
                size_t mapLen = finfo.smem_len > 0
                    ? static_cast<size_t>(finfo.smem_len)
                    : static_cast<size_t>(finfo.line_length) * vinfo.yres;
                void* mapped = mmap(nullptr, mapLen, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                if (mapped != MAP_FAILED) {
                    m_fd         = fd;
                    m_width      = static_cast<int>(vinfo.xres);
                    m_height     = static_cast<int>(vinfo.yres);
                    m_bpp        = static_cast<int>(vinfo.bits_per_pixel);
                    m_stride     = finfo.line_length > 0
                        ? static_cast<int>(finfo.line_length)
                        : m_width * (m_bpp / 8);
                    m_mapSize    = mapLen;
                    m_fbPtr      = static_cast<uint8_t*>(mapped);
                    m_isHardware = true;
                    return true;
                }
            }
            close(fd);
        }
    }
#endif

    // Simulated 8-bit grayscale E-Ink framebuffer (1860 x 2480 Kindle Scribe default)
    m_width      = std::max(64, fallbackWidth);
    m_height     = std::max(64, fallbackHeight);
    m_bpp        = 8;
    m_stride     = m_width;
    m_isHardware = false;
    m_simBuffer.assign(static_cast<size_t>(m_stride) * m_height, 0xFF);
    m_fbPtr      = m_simBuffer.data();
    return true;
}

void EinkFramebuffer::closeDevice() {
#if defined(__linux__)
    if (m_isHardware && m_fbPtr && m_mapSize > 0) {
        munmap(m_fbPtr, m_mapSize);
    }
    if (m_fd >= 0) {
        close(m_fd);
    }
#endif
    m_fd         = -1;
    m_fbPtr      = nullptr;
    m_mapSize    = 0;
    m_isHardware = false;
    m_simBuffer.clear();
}

void EinkFramebuffer::clear(uint8_t grayLevel) {
    if (!m_fbPtr) return;
    if (m_bpp == 8) {
        std::memset(m_fbPtr, grayLevel, static_cast<size_t>(m_stride) * m_height);
    } else {
        fillRect(0, 0, m_width, m_height, grayLevel);
    }
}

void EinkFramebuffer::setPixelGray(int x, int y, uint8_t grayLevel) {
    if (!m_fbPtr || x < 0 || y < 0 || x >= m_width || y >= m_height) return;
    uint8_t* row = m_fbPtr + static_cast<size_t>(y) * m_stride;
    if (m_bpp == 8) {
        row[x] = grayLevel;
    } else if (m_bpp == 16) {
        // RGB565
        uint16_t r5 = (grayLevel >> 3) & 0x1F;
        uint16_t g6 = (grayLevel >> 2) & 0x3F;
        uint16_t b5 = (grayLevel >> 3) & 0x1F;
        reinterpret_cast<uint16_t*>(row)[x] = static_cast<uint16_t>((r5 << 11) | (g6 << 5) | b5);
    } else if (m_bpp == 32) {
        uint8_t* px = row + x * 4;
        px[0] = grayLevel;
        px[1] = grayLevel;
        px[2] = grayLevel;
        px[3] = 0xFF;
    }
}

uint8_t EinkFramebuffer::getPixelGray(int x, int y) const {
    if (!m_fbPtr || x < 0 || y < 0 || x >= m_width || y >= m_height) return 0xFF;
    const uint8_t* row = m_fbPtr + static_cast<size_t>(y) * m_stride;
    if (m_bpp == 8) {
        return row[x];
    } else if (m_bpp == 16) {
        uint16_t v = reinterpret_cast<const uint16_t*>(row)[x];
        uint8_t g6 = (v >> 5) & 0x3F;
        return static_cast<uint8_t>((g6 << 2) | (g6 >> 4));
    } else if (m_bpp == 32) {
        const uint8_t* px = row + x * 4;
        return static_cast<uint8_t>((77 * px[2] + 150 * px[1] + 29 * px[0]) >> 8);
    }
    return 0xFF;
}

void EinkFramebuffer::blitRgbaRegion(
    const uint8_t* srcRgba,
    int srcWidth,
    int srcHeight,
    int srcX,
    int srcY,
    int dstX,
    int dstY,
    int rectWidth,
    int rectHeight,
    bool dither1Bit
) {
    if (!m_fbPtr || !srcRgba || rectWidth <= 0 || rectHeight <= 0) return;

    for (int row = 0; row < rectHeight; ++row) {
        int sy = srcY + row;
        int dy = dstY + row;
        if (sy < 0 || sy >= srcHeight || dy < 0 || dy >= m_height) continue;

        const uint8_t* srcRow = srcRgba + (static_cast<size_t>(sy) * srcWidth) * 4;
        for (int col = 0; col < rectWidth; ++col) {
            int sx = srcX + col;
            int dx = dstX + col;
            if (sx < 0 || sx >= srcWidth || dx < 0 || dx >= m_width) continue;

            const uint8_t* p = srcRow + sx * 4;
            uint8_t r = p[0];
            uint8_t g = p[1];
            uint8_t b = p[2];
            uint8_t a = p[3];

            // Composite translucent pixels over white paper (0xFF)
            if (a < 255) {
                r = static_cast<uint8_t>((r * a + 255 * (255 - a)) / 255);
                g = static_cast<uint8_t>((g * a + 255 * (255 - a)) / 255);
                b = static_cast<uint8_t>((b * a + 255 * (255 - a)) / 255);
            }

            uint8_t luma = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
            if (dither1Bit) {
                uint8_t thresh = kBayer4x4[dy & 3][dx & 3];
                luma = (luma <= thresh) ? 0x00 : 0xFF;
            }
            setPixelGray(dx, dy, luma);
        }
    }
}

void EinkFramebuffer::fillRect(int x, int y, int w, int h, uint8_t grayLevel) {
    if (!m_fbPtr || w <= 0 || h <= 0) return;
    int x0 = std::max(0, x);
    int y0 = std::max(0, y);
    int x1 = std::min(m_width, x + w);
    int y1 = std::min(m_height, y + h);
    if (x0 >= x1 || y0 >= y1) return;

    if (m_bpp == 8) {
        size_t span = static_cast<size_t>(x1 - x0);
        for (int py = y0; py < y1; ++py) {
            std::memset(m_fbPtr + static_cast<size_t>(py) * m_stride + x0, grayLevel, span);
        }
    } else {
        for (int py = y0; py < y1; ++py) {
            for (int px = x0; px < x1; ++px) {
                setPixelGray(px, py, grayLevel);
            }
        }
    }
}

void EinkFramebuffer::drawRectBorder(int x, int y, int w, int h, int thickness, uint8_t grayLevel) {
    if (w <= 0 || h <= 0 || thickness <= 0) return;
    fillRect(x, y, w, thickness, grayLevel);
    fillRect(x, y + h - thickness, w, thickness, grayLevel);
    fillRect(x, y, thickness, h, grayLevel);
    fillRect(x + w - thickness, y, thickness, h, grayLevel);
}

void EinkFramebuffer::drawHLine(int x, int y, int w, uint8_t grayLevel) {
    fillRect(x, y, w, 1, grayLevel);
}

void EinkFramebuffer::drawText(int x, int y, const std::string& text, uint8_t fgGray, uint8_t bgGray, int scale) {
    if (!m_fbPtr || scale <= 0) return;
    int cursorX = x;
    const int charStep = 8 * scale;
    for (char c : text) {
        const uint8_t* rows = glyph8x8(c);
        for (int gy = 0; gy < 8; ++gy) {
            uint8_t bits = rows[gy];
            for (int gx = 0; gx < 8; ++gx) {
                bool lit = (bits & (0x80 >> gx)) != 0;
                uint8_t color = lit ? fgGray : bgGray;
                if (!lit && bgGray == fgGray) continue; // transparent background if equal
                for (int sy = 0; sy < scale; ++sy) {
                    for (int sx = 0; sx < scale; ++sx) {
                        setPixelGray(cursorX + gx * scale + sx, y + gy * scale + sy, color);
                    }
                }
            }
        }
        cursorX += charStep;
    }
}

uint32_t EinkFramebuffer::refreshRect(const EinkRect& rect, EinkWaveform waveform, bool fullFlash) {
    int x0 = std::clamp(rect.left, 0, m_width);
    int y0 = std::clamp(rect.top, 0, m_height);
    int x1 = std::clamp(rect.left + rect.width, 0, m_width);
    int y1 = std::clamp(rect.top + rect.height, 0, m_height);
    if (x1 <= x0 || y1 <= y0) return 0;

    EinkRect clamped{ x0, y0, x1 - x0, y1 - y0 };
    uint32_t marker = m_nextMarker++;

#if defined(__linux__)
    if (m_isHardware && m_fd >= 0) {
        struct mxcfb_update_data_mtk updMtk{};
        updMtk.update_region.left   = static_cast<uint32_t>(clamped.left);
        updMtk.update_region.top    = static_cast<uint32_t>(clamped.top);
        updMtk.update_region.width  = static_cast<uint32_t>(clamped.width);
        updMtk.update_region.height = static_cast<uint32_t>(clamped.height);
        updMtk.waveform_mode        = toMtkWaveform(waveform);
        updMtk.update_mode          = fullFlash ? UPDATE_MODE_FULL : UPDATE_MODE_PARTIAL;
        updMtk.update_marker        = marker;
        updMtk.temp                 = TEMP_USE_AMBIENT;
        updMtk.flags                = 0;
        if (ioctl(m_fd, kMxcfbSendUpdateMtk, &updMtk) < 0) {
            struct mxcfb_update_data updLegacy{};
            updLegacy.update_region = updMtk.update_region;
            updLegacy.waveform_mode = updMtk.waveform_mode;
            updLegacy.update_mode   = updMtk.update_mode;
            updLegacy.update_marker = marker;
            updLegacy.temp          = TEMP_USE_AMBIENT;
            (void)ioctl(m_fd, kMxcfbSendUpdateLegacy, &updLegacy);
        }
    }
#endif

    if (m_updateHistory.size() >= 256) {
        m_updateHistory.erase(m_updateHistory.begin());
    }
    m_updateHistory.push_back(EinkUpdateRecord{ clamped, waveform, fullFlash, marker });
    return marker;
}

uint32_t EinkFramebuffer::refreshFullScreen(EinkWaveform waveform, bool fullFlash) {
    return refreshRect(EinkRect{ 0, 0, m_width, m_height }, waveform, fullFlash);
}

bool EinkFramebuffer::savePgm(const std::string& filePath) const {
    if (!m_fbPtr || m_width <= 0 || m_height <= 0) return false;
    FILE* f = std::fopen(filePath.c_str(), "wb");
    if (!f) return false;
    std::fprintf(f, "P5\n%d %d\n255\n", m_width, m_height);
    std::vector<uint8_t> rowBuf(static_cast<size_t>(m_width));
    for (int y = 0; y < m_height; ++y) {
        for (int x = 0; x < m_width; ++x) {
            rowBuf[static_cast<size_t>(x)] = getPixelGray(x, y);
        }
        std::fwrite(rowBuf.data(), 1, rowBuf.size(), f);
    }
    std::fclose(f);
    return true;
}

} // namespace orgwritenotes::kindle
