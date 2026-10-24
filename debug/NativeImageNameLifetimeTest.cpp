#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <iterator>
#include <sys/mman.h>
#include <vector>

int main(int argc, char** argv) {
    assert(argc == 3);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { puts(dlerror()); return 1; }
    auto pathLoad = reinterpret_cast<void* (*)(const char*, const char*)>(
        dlsym(shim, "__macapk_arm64_native_load_from_path"));
    auto blobLoad = reinterpret_cast<void* (*)(const void*, size_t, const char*)>(
        dlsym(shim, "__macapk_arm64_native_load"));
    auto find = reinterpret_cast<void* (*)(const char*)>(
        dlsym(shim, "__macapk_arm64_native_find_loaded"));
    assert(pathLoad && blobLoad && find);
    std::ifstream input(argv[2], std::ios::binary);
    std::vector<char> elf((std::istreambuf_iterator<char>(input)), {});
    assert(!elf.empty());
    for (int mode = 0; mode < 2; ++mode) {
        const char* expected = mode ? "libowned-blob.so" : "libowned-path.so";
        auto* name = static_cast<char*>(mmap(nullptr, 16384, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANON, -1, 0));
        assert(name != MAP_FAILED);
        std::strcpy(name, expected);
        void* image = mode ? blobLoad(elf.data(), elf.size(), name) : pathLoad(argv[2], name);
        assert(image);
        // A wrapper's string literal disappears when dyld unloads that wrapper,
        // while its guest ELF remains registered. Make stale reads deterministic.
        assert(mprotect(name, 16384, PROT_NONE) == 0);
        assert(find(expected) == image);
        assert(munmap(name, 16384) == 0);
    }
    puts("PASS: registered ELF names survive both path/blob callers' storage");
}
