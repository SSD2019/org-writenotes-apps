#ifndef KINDLE_EINK_FB_H
#define KINDLE_EINK_FB_H

#include <cstdint>
#include <string>
#include <vector>

namespace orgwritenotes::kindle {

// E-Ink EPDC waveform modes used on Kindle Scribe (MediaTek MT8183 / mxcfb compatible)
enum class EinkWaveform : int {
    INIT      = 0, // Full clear initialization
    DU        = 1, // Direct Update: 1-bit B&W, ~15ms latency, non-flashing (active pen strokes)
    GC16      = 2, // 16-level grayscale, high quality (full page / node load)
    GC16_FAST = 3, // Fast 16-level grayscale, non-flashing partial (stroke settle on pen-up)
    A2        = 4, // Fast 2-level animation / UI selection highlight
    GL16      = 5  // Ghost-less 16-level grayscale for text & ruled backgrounds
};

struct EinkRect {
    int left   = 0;
    int top    = 0;
    int width  = 0;
    int height = 0;

    bool isEmpty() const { return width <= 0 || height <= 0; }
};

struct EinkUpdateRecord {
    EinkRect rect;
    EinkWaveform waveform = EinkWaveform::DU;
    bool fullRefresh      = false;
    uint32_t marker       = 0;
};

class EinkFramebuffer {
public:
    EinkFramebuffer();
    ~EinkFramebuffer();

    EinkFramebuffer(const EinkFramebuffer&) = delete;
    EinkFramebuffer& operator=(const EinkFramebuffer&) = delete;

    // Opens /dev/fb0 if available, or initializes an in-memory simulated Scribe framebuffer
    // (default 1860 x 2480 @ 300 ppi for Kindle Scribe).
    bool openDevice(const char* fbPath = "/dev/fb0", int fallbackWidth = 1860, int fallbackHeight = 2480);
    void closeDevice();

    int width() const { return m_width; }
    int height() const { return m_height; }
    int strideBytes() const { return m_stride; }
    int bitsPerPixel() const { return m_bpp; }
    bool isHardwareFb() const { return m_isHardware; }

    // Clears the entire framebuffer to a grayscale level (0x00 = black, 0xFF = white)
    void clear(uint8_t grayLevel = 0xFF);

    // Converts an RGBA8888 buffer (or sub-rectangle) into the E-Ink framebuffer at (dstX, dstY).
    // When dither1Bit is true (used during DU/A2 fast pen trace), applies ordered Bayer 4x4 threshold
    // so mid-tone ink strokes appear crisp immediately before the GC16 pen-up settle.
    void blitRgbaRegion(
        const uint8_t* srcRgba,
        int srcWidth,
        int srcHeight,
        int srcX,
        int srcY,
        int dstX,
        int dstY,
        int rectWidth,
        int rectHeight,
        bool dither1Bit = false
    );

    // Drawing primitives for the E-Ink Top Bar, Status Pill, and Outline/Agenda Sidebar
    void fillRect(int x, int y, int w, int h, uint8_t grayLevel);
    void drawRectBorder(int x, int y, int w, int h, int thickness, uint8_t grayLevel);
    void drawHLine(int x, int y, int w, uint8_t grayLevel);
    void drawText(int x, int y, const std::string& text, uint8_t fgGray, uint8_t bgGray, int scale = 2);

    // Triggers an EPDC screen refresh on the given rectangle using MXCFB_SEND_UPDATE ioctl
    uint32_t refreshRect(const EinkRect& rect, EinkWaveform waveform, bool fullFlash = false);
    uint32_t refreshFullScreen(EinkWaveform waveform = EinkWaveform::GC16, bool fullFlash = true);

    // Read pixel luminance (0..255) at (x, y) for diagnostics & testing
    uint8_t getPixelGray(int x, int y) const;
    void setPixelGray(int x, int y, uint8_t grayLevel);

    // Export current grayscale framebuffer to a portable PGM (P5) file (for simulator / debugging)
    bool savePgm(const std::string& filePath) const;

    const std::vector<EinkUpdateRecord>& updateHistory() const { return m_updateHistory; }
    void clearUpdateHistory() { m_updateHistory.clear(); }

private:
    int m_fd             = -1;
    int m_width          = 0;
    int m_height         = 0;
    int m_stride         = 0;
    int m_bpp            = 8;
    size_t m_mapSize     = 0;
    uint8_t* m_fbPtr     = nullptr;
    bool m_isHardware    = false;
    uint32_t m_nextMarker = 1;

    std::vector<uint8_t> m_simBuffer;
    std::vector<EinkUpdateRecord> m_updateHistory;
};

} // namespace orgwritenotes::kindle

#endif // KINDLE_EINK_FB_H

