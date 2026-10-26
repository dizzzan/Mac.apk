#include "macapk/photo_export.h"
#import <AppKit/AppKit.h>
#import <ImageIO/ImageIO.h>
#include <cassert>
#include <cstdio>

int main() { @autoreleasepool {
    uint32_t pixels[] = {0xffff0000, 0xff00ff00, 0xff0000ff, 0x80ff0000, 0xffffffff, 0xff000000};
    auto png = macapk::PhotoExportEncodePNG(pixels, 3, 2);
    assert(!png.empty());
    NSData* bytes = [NSData dataWithBytes:png.data() length:png.size()];
    NSBitmapImageRep* image = [NSBitmapImageRep imageRepWithData:bytes];
    assert(image.pixelsWide == 3 && image.pixelsHigh == 2);
    // Read encoded samples directly; NSColor's generic-RGB conversion changes
    // primaries on color-managed displays and is not a lossless-pixel test.
    NSUInteger tl[4], tr[4], bl[4];
    [image getPixel:tl atX:0 y:0]; [image getPixel:tr atX:2 y:0]; [image getPixel:bl atX:0 y:1];
    assert(tl[0] == 255 && tl[1] == 0 && tl[2] == 0 && tl[3] == 255);
    assert(tr[0] == 0 && tr[1] == 0 && tr[2] == 255 && tr[3] == 255);
    assert(bl[0] == 255 && bl[1] == 0 && bl[2] == 0 && bl[3] == 128);
    assert(macapk::PhotoExportEncodePNG(nullptr, 3, 2).empty());
    assert(macapk::PhotoExportEncodePNG(pixels, -1, 2).empty());
    assert(macapk::PhotoExportEncodePNG(pixels, 100000, 100000).empty());
    std::puts("Photo PNG: dimensions, orientation, color, alpha and size guards passed");
} }
