#include <cassert>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char** argv) {
    assert(argc == 2);
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!library) { puts(dlerror()); return 1; }
    using Allocate = int (*)(int, int64_t, int64_t);
    auto allocate = reinterpret_cast<Allocate>(dlsym(library, "__macapk_arm64_posix_fallocate"));
    auto allocate64 = reinterpret_cast<Allocate>(dlsym(library, "__macapk_arm64_posix_fallocate64"));
    assert(allocate && allocate64);
    char path[] = "/tmp/macapk-fallocate-XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    int readonly = open(path, O_RDONLY);
    assert(readonly >= 0);
    unlink(path);
    const char original[] = "preserve existing data";
    assert(write(fd, original, sizeof(original)) == sizeof(original));
    assert(lseek(fd, 7, SEEK_SET) == 7);
    errno = 123;
    assert(allocate(fd, 4096, 65536) == 0 && errno == 123);
    struct stat st{};
    assert(fstat(fd, &st) == 0 && st.st_size == 69632 && st.st_blocks * 512 >= 69632);
    const auto blocks = st.st_blocks;
    assert(lseek(fd, 0, SEEK_CUR) == 7);
    char bytes[sizeof(original)]{};
    assert(pread(fd, bytes, sizeof(bytes), 0) == sizeof(bytes));
    assert(memcmp(bytes, original, sizeof(bytes)) == 0);
    char last = 1;
    assert(pread(fd, &last, 1, st.st_size - 1) == 1 && last == 0);
    for (int i = 0; i < 10; ++i) assert(allocate64(fd, 0, 32768) == 0);
    assert(fstat(fd, &st) == 0 && st.st_size == 69632 && st.st_blocks == blocks);
    errno = 123;
    assert(allocate(-1, 0, 1) == 9 && errno == 123); // Linux EBADF
    assert(allocate(readonly, 0, 1) == 9 && errno == 123);
    assert(allocate(fd, -1, 1) == 22 && errno == 123);
    assert(allocate(fd, 0, 0) == 22 && errno == 123);
    assert(allocate(fd, INT64_MAX, 2) == 27 && errno == 123);
    close(readonly);
    close(fd);
    puts("PASS: allocation reserves space, preserves data/offset/errno, and rejects invalid requests");
}
