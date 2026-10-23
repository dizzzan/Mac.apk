// Usage: test shim.dylib lib_native/libfixture.so [absent]
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>

int main(int argc, char** argv) {
    assert(argc == 3 || argc == 4);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto open = reinterpret_cast<void* (*)(const char*, int)>(
        dlsym(shim, "__macapk_arm64_dlopen"));
    auto symbol = reinterpret_cast<void* (*)(void*, const char*)>(
        dlsym(shim, "__macapk_arm64_dlsym"));
    assert(open && symbol);
    void* wrapper = open(argv[2], 2);
    if (!wrapper) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto onload = reinterpret_cast<int (*)(void*, void*)>(dlsym(wrapper, "JNI_OnLoad"));
    if (!onload) { std::fputs("missing JNI startup trampoline\n", stderr); return 1; }
    assert(onload(nullptr, nullptr) == (argc == 4 ? 0x00010001 : 0x00010006));
    auto onunload = reinterpret_cast<void (*)(void*, void*)>(dlsym(wrapper, "JNI_OnUnload"));
    assert(onunload);
    onunload(nullptr, nullptr);
    assert((symbol(wrapper, "JNI_OnLoad") == nullptr) == (argc == 4));
    assert(symbol(wrapper, "JNI_OnUnload") == nullptr);
    assert(symbol(wrapper, "missing_fixture_symbol") == nullptr);
    std::puts(argc == 4 ? "absent JNI hooks retain JNI defaults" : "constructor-published JNI startup hook executes");
}
