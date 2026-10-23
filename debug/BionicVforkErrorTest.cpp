#include "../runtime/translator/src/a64_vfork.h"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <sys/mman.h>
#include <libkern/OSCacheControl.h>
#include <vector>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    std::vector<uint32_t> code = {0xd53bd040,0xf9400400,0xb900141f,0xd2882220,
        0xaa1f03e1,0xaa1f03e2,0xaa1f03e3,0xaa1f03e4,0xd2801b88,0xd429a820,0xd65f03c0};
    auto negative = code; negative[3] ^= 0x20;
    assert(macapk::a64::omit_unsupported_vfork_pid_cache(negative) == 0);
    assert(macapk::a64::omit_unsupported_vfork_pid_cache(std::span(code).first(9)) == 0);
    assert(macapk::a64::omit_unsupported_vfork_pid_cache(code) == 1);
    assert(code[3] == 0xd2882220 && code[9] == 0xd429a820);
    void* memory = mmap(nullptr, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(memory != MAP_FAILED); std::memcpy(memory, code.data(), code.size() * 4);
    sys_icache_invalidate(memory, code.size() * 4);
    assert(mprotect(memory, 0x4000, PROT_READ | PROT_EXEC) == 0);
    errno = EDOM;
    long result = reinterpret_cast<long (*)()>(memory)();
    assert(result == -38 && errno == EDOM); // Raw Linux ENOSYS; no clone/fork occurred.
    munmap(memory, 0x4000);
    std::puts("Bionic vfork fixture reached honest -ENOSYS without accessing host TLS");
}
