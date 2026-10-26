#pragma once
#include <jni.h>
#include <cstdint>
#include <vector>

namespace macapk {
void RegisterPhotoExportMethods(JNIEnv* env);
void PhotoExportInstallTitlebar(void* window);
// Straight-alpha ARGB integers, top row first; lossless PNG at the bitmap's dimensions.
std::vector<uint8_t> PhotoExportEncodePNG(const uint32_t* pixels, int width, int height);
}
