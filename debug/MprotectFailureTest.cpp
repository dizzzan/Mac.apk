// Run in a separate process: a leaked write-window lock must fail by timeout.
#include <atomic>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(shim);
    auto protect = reinterpret_cast<int (*)(void*, size_t, int)>(
        dlsym(shim, "__macapk_arm64_mprotect"));
    assert(protect);
    size_t page = size_t(sysconf(_SC_PAGESIZE));
    void* memory = mmap(nullptr, page, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(memory != MAP_FAILED);
    // Both RWX and the runtime's RW fallback must reject an unaligned address.
    errno = 0;
    assert(protect(static_cast<char*>(memory) + 1, page - 1,
                   PROT_READ | PROT_WRITE | PROT_EXEC) == -1);
    assert(errno == EINVAL);
    std::atomic<bool> finished{false};
    std::thread worker([&] {
        int rc = protect(memory, page, PROT_READ | PROT_WRITE | PROT_EXEC);
        if (rc == 0) assert(protect(memory, page, PROT_READ | PROT_EXEC) == 0);
        finished.store(true);
    });
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!finished.load() && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (!finished.load()) {
        std::fputs("FAIL: failed mprotect left another thread blocked\n", stderr);
        std::_Exit(1);
    }
    worker.join();
    assert(munmap(memory, page) == 0);
    std::puts("failed mprotect preserves EINVAL and releases the write-window lock");
}
