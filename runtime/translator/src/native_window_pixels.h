#pragma once
#include <cstddef>
#include <cstdint>
#include <limits>

namespace macapk::native_window {
constexpr int yv12 = 0x32315659;
struct Layout {
    int width = 0, height = 0, stride = 0, format = 0, chroma_stride = 0;
    size_t bytes = 0;
};
inline bool layout(int width, int height, int format, Layout& out) {
    if (width <= 0 || height <= 0 || uint64_t(width) * height > INT32_MAX) return false;
    Layout l{width, height, width, format, 0, 0};
    size_t bytes_per_pixel;
    switch (format) {
        case 1: case 2: case 5: bytes_per_pixel = 4; break;
        case 3: bytes_per_pixel = 3; break;
        case 4: bytes_per_pixel = 2; break;
        case yv12:
            // AOSP graphics.h: Y, Cr, Cb planes; both strides aligned to 16.
            if ((width & 1) || (height & 1) || width > INT32_MAX - 31) return false;
            l.stride = (width + 15) & ~15;
            l.chroma_stride = ((l.stride / 2) + 15) & ~15;
            l.bytes = size_t(l.stride) * height + size_t(l.chroma_stride) * height;
            out = l;
            return true;
        default: return false;
    }
    if (size_t(width) > std::numeric_limits<size_t>::max() / size_t(height) / bytes_per_pixel)
        return false;
    l.bytes = size_t(width) * height * bytes_per_pixel;
    out = l;
    return true;
}
inline uint8_t clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : uint8_t(v); }
inline void to_argb(const Layout& l, const uint8_t* src, uint32_t* dst) {
    const size_t y_size = size_t(l.stride) * l.height;
    const size_t c_size = size_t(l.chroma_stride) * (l.height / 2);
    for (int y = 0; y < l.height; ++y) {
        for (int x = 0; x < l.width; ++x) {
            uint8_t r, g, b, a = 255;
            const size_t p = size_t(y) * l.stride + x;
            switch (l.format) {
                case yv12: {
                    const size_t cp = size_t(y / 2) * l.chroma_stride + x / 2;
                    int yy = int(src[p]) - 16;
                    int v = int(src[y_size + cp]) - 128;
                    int u = int(src[y_size + c_size + cp]) - 128;
                    r = clamp((298 * yy + 409 * v + 128) >> 8);
                    g = clamp((298 * yy - 100 * u - 208 * v + 128) >> 8);
                    b = clamp((298 * yy + 516 * u + 128) >> 8);
                    break;
                }
                case 4: {
                    const uint16_t v = uint16_t(src[p * 2]) | uint16_t(src[p * 2 + 1]) << 8;
                    r = uint8_t(((v >> 11) * 255 + 15) / 31);
                    g = uint8_t((((v >> 5) & 63) * 255 + 31) / 63);
                    b = uint8_t(((v & 31) * 255 + 15) / 31);
                    break;
                }
                case 3: r = src[p * 3]; g = src[p * 3 + 1]; b = src[p * 3 + 2]; break;
                case 5: b = src[p * 4]; g = src[p * 4 + 1]; r = src[p * 4 + 2]; a = src[p * 4 + 3]; break;
                default: r = src[p * 4]; g = src[p * 4 + 1]; b = src[p * 4 + 2];
                         if (l.format == 1) a = src[p * 4 + 3]; break;
            }
            dst[size_t(y) * l.width + x] = uint32_t(a) << 24 | uint32_t(r) << 16 | uint32_t(g) << 8 | b;
        }
    }
}
}
