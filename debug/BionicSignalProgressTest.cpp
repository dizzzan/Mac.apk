// Repeated, independently serviced traps at the same site must not be
// mistaken for an unhandled signal loop (HotSpot uses this for deoptimization).
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <sys/_types/_ucontext.h>
#include <unistd.h>
#include <libkern/OSCacheControl.h>

volatile sig_atomic_t handled;
void advance(int, siginfo_t*, void* context) {
    auto* uc = static_cast<ucontext_t*>(context);
    uc->uc_mcontext->__ss.__pc += 4;
    handled = handled + 1;
}
void no_progress(int, siginfo_t*, void*) {}
int main(int argc, char** argv) {
    if (argc != 2 && argc != 3) return 2;
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    auto install = reinterpret_cast<void (*)()>(dlsym(lib, "__macapk_reinstall_sigill_handler"));
    assert(install);
    struct sigaction sa{};
    sa.sa_sigaction = argc == 3 ? no_progress : advance; sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask); assert(sigaction(SIGILL, &sa, nullptr) == 0);
    install();
    uint32_t code[] = {0xd4ade001, 0xd65f03c0}; // HotSpot deopt instruction, ret
    void* memory = mmap(nullptr, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(memory != MAP_FAILED);
    std::memcpy(memory, code, sizeof(code)); sys_icache_invalidate(memory, sizeof(code));
    assert(mprotect(memory, 0x4000, PROT_READ | PROT_EXEC) == 0);
    auto trap = reinterpret_cast<void (*)()>(memory);
    if (argc == 3) { trap(); return 1; }
    for (int i = 0; i < 8; ++i) trap();
    assert(handled == 8);
    pid_t child = fork(); assert(child >= 0);
    if (child == 0) {
        struct rlimit limit{0, 0}; setrlimit(RLIMIT_CORE, &limit);
        execl(argv[0], argv[0], argv[1], "--loop", static_cast<char*>(nullptr));
        _exit(1);
    }
    int status = 0; assert(waitpid(child, &status, 0) == child);
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGILL);
    munmap(memory, 0x4000);
    std::puts("8 independently serviced same-site signals passed; unhandled loop still terminates");
}
