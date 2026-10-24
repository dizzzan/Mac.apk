#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace macapk {
// Observe decoded media or a dedicated TextureView producer, before Android
// window composition. Raw decoded sources take priority over rendered surfaces.
uint64_t StreamRecorderObserve(uint64_t source, int width, int height, bool renderedSurface = false);
void StreamRecorderVideo(uint64_t ticket, std::vector<uint32_t> bgra,
                         int width, int height, double hostSeconds);
double StreamRecorderHostSeconds();
void StreamRecorderAudio(void* pcmBuffer, void* audioTime);
bool StreamRecorderStart(const std::string& path, std::string& error);
void StreamRecorderStop();
// 0 idle, 1 recording, 2 finalizing. Error/saved path are safe to read anywhere.
int StreamRecorderState();
std::string StreamRecorderMessage();
void StreamRecorderInstallTitlebar(void* window);
void StreamRecorderFinishOnExit();
}
