// Usage: test <shim.dylib> <lib_native/libdlsym_fixture.so>
// The fixture's function returns its own ADR-derived address. A wrapper
// trampoline can call it, but is not that symbol's Android address.
#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <cstdint>

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    auto open = reinterpret_cast<void* (*)(const char*, int)>(
        dlsym(shim, "__macapk_arm64_dlopen"));
    auto symbol = reinterpret_cast<void* (*)(void*, const char*)>(
        dlsym(shim, "__macapk_arm64_dlsym"));
    auto address = reinterpret_cast<int (*)(const void*, Dl_info*)>(
        dlsym(shim, "__macapk_arm64_dladdr"));
    assert(open && symbol && address);
    void* handle = open(argv[2], 2); // Bionic LP64 RTLD_NOW.
    assert(handle);
    void* entry = symbol(handle, "fixture_self");
    assert(entry);
    void* actual = reinterpret_cast<void* (*)()>(entry)();
    if (entry != actual) {
        std::fprintf(stderr, "guest dlsym returned wrapper %p; ELF symbol is %p\n", entry, actual);
        return 1;
    }
    Dl_info info{};
    assert(address(entry, &info) == 1);
    assert(std::memcmp(info.dli_fbase, "\177ELF", 4) == 0);
    assert(std::strstr(info.dli_fname, "libdlsym_fixture.so"));
    assert(info.dli_saddr == entry && std::strcmp(info.dli_sname, "fixture_self") == 0);
    auto* data = static_cast<uint64_t*>(symbol(handle, "fixture_data"));
    assert(data && *data == UINT64_C(0x123456789abcdef0));
    assert(symbol(handle, "no_such_fixture_symbol") == nullptr);
    void* host = dlopen("/usr/lib/libSystem.B.dylib", RTLD_NOW | RTLD_LOCAL);
    assert(host && symbol(host, "strlen") == dlsym(host, "strlen"));
    std::puts("guest symbol identity, ELF metadata, data exports, misses and host fallback passed");
}
