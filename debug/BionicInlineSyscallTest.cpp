// Generated AArch64 inline syscall fixture, exercising the real SIGTRAP path.
// Build: clang++ -std=c++20 -Wall -Wextra -Werror debug/BionicInlineSyscallTest.cpp -o /tmp/svc-test
// Run: /tmp/svc-test /absolute/path/to/libmacapk-bionic-shim.dylib
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <libkern/OSCacheControl.h>

int checks;
void check(bool pass, const char* what) {
    ++checks;
    if (!pass) { std::fprintf(stderr, "FAIL: %s (errno=%d)\n", what, errno); std::exit(1); }
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    constexpr uint32_t brk = 0xd4200000u | (0x4d41u << 5);
    // Two independent functions: getpid (172), and openat (56). No ELF/app code.
    uint32_t code[] = {0xd2801588, brk, 0xd65f03c0, 0xd2800708, brk, 0xd65f03c0};
    void* memory = mmap(nullptr, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    check(memory != MAP_FAILED, "fixture allocated");
    std::memcpy(memory, code, sizeof(code)); sys_icache_invalidate(memory, sizeof(code));
    check(mprotect(memory, 0x4000, PROT_READ | PROT_EXEC) == 0, "fixture made executable");
    auto pid = reinterpret_cast<long (*)()>(memory);
    auto openat = reinterpret_cast<long (*)(long, const char*, long, long)>(static_cast<char*>(memory) + 12);
    errno = EDOM;
    check(pid() == getpid(), "inline syscall success value");
    check(errno == EDOM, "success preserves errno");
    errno = EDOM;
    long result = openat(-1, "missing-relative-file", 0, 0);
    check(result == -9, "inline syscall failure is Linux -EBADF, not libc -1");
    check(errno == EDOM, "kernel-style failure preserves errno");
    munmap(memory, 0x4000);
    std::printf("%d inline syscall checks passed\n", checks);
}
