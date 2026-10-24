#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <initializer_list>

// clang++ -std=c++20 debug/ProcRootPathTest.cpp -o /tmp/proc-root-path-test
// /tmp/proc-root-path-test runtime/build/bin/libmacapk-bionic-shim.dylib
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(shim);
    auto raw = reinterpret_cast<long (*)(long, long, long, long, long, long, long)>(
        dlsym(shim, "__macapk_arm64_raw_syscall"));
    assert(raw);
    for (const char* path : {"/proc/self/maps", "/./proc/self/maps",
                             "//././/proc/self/maps"}) {
        long fd = raw(56, -100, reinterpret_cast<long>(path), 0, 0, 0, 0);
        assert(fd >= 0);
        char text[128]{};
        assert(raw(63, fd, reinterpret_cast<long>(text), sizeof(text) - 1, 0, 0, 0) > 0);
        assert(std::strchr(text, '-'));
        assert(raw(57, fd, 0, 0, 0, 0, 0) == 0);
    }
    // Interior components must still be resolved by the filesystem, including
    // the requirement that any component followed by a slash is a directory.
    for (const char* path : {"/proc/self/maps/./", "/./proc/self/maps/./",
                             "/macapk-missing-root-path/../proc/self/maps"}) {
        assert(raw(56, -100, reinterpret_cast<long>(path), 0, 0, 0, 0) < 0);
    }
    std::puts("Root dot/slash aliases resolve procfs; invalid interior paths still fail");
}
