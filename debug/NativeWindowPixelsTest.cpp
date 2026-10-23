#include "../runtime/translator/src/native_window_pixels.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <vector>
using namespace macapk::native_window;
int main() {
    int checks = 0;
    auto check = [&](bool ok) { assert(ok); ++checks; };
    Layout l;
    check(!layout(0, 2, 1, l));
    check(!layout(INT32_MAX, INT32_MAX, 1, l));
    check(!layout(3, 2, yv12, l));
    check(!layout(2, 3, yv12, l));
    check(!layout(2, 2, 35, l));
    check(layout(18, 2, yv12, l) && l.stride == 32 && l.chroma_stride == 16 && l.bytes == 96);
    std::vector<uint8_t> pixels(l.bytes, 128);
    std::vector<uint32_t> argb(36);
    std::fill(pixels.begin(), pixels.begin() + 64, 16);
    to_argb(l, pixels.data(), argb.data());
    check(std::all_of(argb.begin(), argb.end(), [](auto p) { return p == 0xff000000; }));
    std::fill(pixels.begin(), pixels.begin() + 64, 235);
    to_argb(l, pixels.data(), argb.data());
    check(std::all_of(argb.begin(), argb.end(), [](auto p) { return p == 0xffffffff; }));
    // BT.601 limited-range red: verifies Cr/Cb plane order and row padding.
    std::fill(pixels.begin(), pixels.begin() + 64, 81);
    std::fill(pixels.begin() + 64, pixels.begin() + 80, 240);
    std::fill(pixels.begin() + 80, pixels.end(), 90);
    to_argb(l, pixels.data(), argb.data());
    check(argb.front() == 0xffff0000 && argb.back() == 0xffff0000);
    check(layout(1, 1, 1, l));
    uint8_t rgba[] = {10, 20, 30, 40}; uint32_t out;
    to_argb(l, rgba, &out); check(out == 0x280a141e);
    check(layout(1, 1, 2, l));
    to_argb(l, rgba, &out); check(out == 0xff0a141e);
    check(layout(1, 1, 5, l));
    to_argb(l, rgba, &out); check(out == 0x281e140a);
    check(layout(1, 1, 3, l) && l.bytes == 3);
    to_argb(l, rgba, &out); check(out == 0xff0a141e);
    check(layout(1, 1, 4, l) && l.bytes == 2);
    uint8_t rgb565[] = {0xe0, 0x07};
    to_argb(l, rgb565, &out); check(out == 0xff00ff00);
    std::printf("%d native window pixel checks passed\n", checks);
}
