#include "../runtime/translator/src/a64_dynamic_syscall.h"
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <sys/mman.h>
#include <time.h>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 2 && argc != 3) return 2;
    const std::vector<uint32_t> original = {0xaa0003e8, 0xaa0103e0, 0xaa0203e1,
        0xaa0303e2, 0xaa0403e3, 0xaa0503e4, 0xaa0603e5, 0xd4000001, 0xd65f03c0};
    auto negative = original; negative[5] ^= 0x20;
    assert(macapk::a64::rewrite_dynamic_syscalls(negative) == 0);
    assert(macapk::a64::rewrite_dynamic_syscalls(std::span(negative).first(8)) == 0);
    negative = original; negative[8] = 0xd503201f;
    assert(macapk::a64::rewrite_dynamic_syscalls(negative) == 0);
    auto adapted = original; adapted[8] = 0xb140041f;
    assert(macapk::a64::rewrite_dynamic_syscalls(adapted) == 1);
    assert(adapted[8] == 0xb140041f); // libc errno adapter stays intact

    setenv("MACAPK_DYNAMIC_SVC_BRK", argc == 3 ? "0" : "1", 1);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    auto protect = reinterpret_cast<int (*)(void*, size_t, int)>(
        dlsym(shim, "__macapk_arm64_mprotect"));
    assert(protect);
    void* mem = mmap(nullptr, 0x4000, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(mem != MAP_FAILED);
    std::memcpy(mem, original.data(), original.size() * 4);
    assert(protect(mem, 0x4000, PROT_READ | PROT_EXEC) == 0);
    if (argc == 3) {
        assert(std::memcmp(mem, original.data(), original.size() * 4) == 0);
        munmap(mem, 0x4000);
        std::puts("disabled generated-syscall path preserves code bytes");
        return 0;
    }
    assert(static_cast<uint32_t*>(mem)[7] == 0xd429a820);
    auto syscall = reinterpret_cast<long (*)(long, long, long)>(mem);
    timespec request{0, 10 * 1000 * 1000};
    errno = EDOM;
    auto start = std::chrono::steady_clock::now();
    assert(syscall(101, reinterpret_cast<long>(&request), 0) == 0);
    assert(std::chrono::steady_clock::now() - start >= std::chrono::milliseconds(8));
    assert(errno == EDOM);
    request.tv_nsec = 1000000000;
    assert(syscall(101, reinterpret_cast<long>(&request), 0) == -22);
    assert(errno == EDOM);
    munmap(mem, 0x4000);
    std::puts("generated syscall wrapper sleeps, returns Linux errors and preserves errno");
}
