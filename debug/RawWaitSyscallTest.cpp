#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <dlfcn.h>
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <time.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(shim);
    auto raw = reinterpret_cast<long (*)(long, long, long, long, long, long, long)>(
        dlsym(shim, "__macapk_arm64_raw_syscall"));
    assert(raw);
    timespec timeout{0, 10000000};
    errno = EDOM;
    auto start = std::chrono::steady_clock::now();
    assert(raw(72, 0, 0, 0, 0, reinterpret_cast<long>(&timeout), 0) == 0);
    assert(std::chrono::steady_clock::now() - start >= std::chrono::milliseconds(8));
    assert(timeout.tv_sec == 0 && timeout.tv_nsec == 0 && errno == EDOM);
    int fds[2]; assert(pipe(fds) == 0);
    assert(write(fds[1], "x", 1) == 1);
    fd_set readable; FD_ZERO(&readable); FD_SET(fds[0], &readable);
    timeout = {1, 0};
    assert(raw(72, fds[0] + 1, reinterpret_cast<long>(&readable), 0, 0,
               reinterpret_cast<long>(&timeout), 0) == 1);
    assert(FD_ISSET(fds[0], &readable) && timeout.tv_sec == 0 && timeout.tv_nsec > 0);
    close(fds[0]); close(fds[1]);
    assert(raw(72, -1, 0, 0, 0, 0, 0) == -22);
    timeout = {-1, 0};
    assert(raw(72, 0, 0, 0, 0, reinterpret_cast<long>(&timeout), 0) == -22);
    uint64_t mask = 0; uintptr_t maskarg[] = {uintptr_t(&mask), 8};
    assert(raw(72, 0, 0, 0, 0, 0, reinterpret_cast<long>(maskarg)) == -95);
    assert(errno == EDOM);
    void* memory = mmap(nullptr, 0x4000, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(memory != MAP_FAILED);
    assert(raw(215, reinterpret_cast<long>(memory), 0x4000, 0, 0, 0, 0) == 0);
    char byte = 0; mach_vm_size_t copied = 0;
    assert(mach_vm_read_overwrite(mach_task_self(), uintptr_t(memory), 1,
        reinterpret_cast<mach_vm_address_t>(&byte), &copied) != KERN_SUCCESS);
    std::puts("raw pselect timeout, readiness, remaining time, errors and actual munmap passed");
}
