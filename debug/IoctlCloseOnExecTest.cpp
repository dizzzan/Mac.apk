#include <cassert>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

int main(int argc, char** argv) {
    assert(argc == 2);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    assert(shim);
    auto ioctl64 = reinterpret_cast<int (*)(int, int, void*)>(dlsym(shim, "__macapk_arm64_ioctl"));
    auto ioctl32 = reinterpret_cast<uint32_t (*)(uint32_t, uint32_t, uint32_t, uint32_t)>(
        dlsym(shim, "__macapk_shim_ioctl"));
    assert(ioctl64 && ioctl32);
    int pipefd[2];
    assert(pipe(pipefd) == 0);
    for (int mode = 0; mode < 2; ++mode) {
        auto call = [&](int fd, int op) {
            // These requests have no argument, so a garbage variadic slot must
            // not be interpreted as a guest-memory pointer.
            return mode ? static_cast<int>(ioctl32(fd, op, 0xffffffff, 0))
                        : ioctl64(fd, op, reinterpret_cast<void*>(~uintptr_t(0)));
        };
        assert(call(pipefd[0], 0x5451) == 0);
        assert(fcntl(pipefd[0], F_GETFD) & FD_CLOEXEC);
        assert(call(pipefd[0], 0x5450) == 0);
        assert(!(fcntl(pipefd[0], F_GETFD) & FD_CLOEXEC));
        errno = 0;
        assert(call(-1, 0x5451) == -1 && errno == EBADF);
    }
    close(pipefd[0]); close(pipefd[1]);
    puts("PASS: ARM32/ARM64 close-on-exec ioctl flags and invalid-fd errors");
}
