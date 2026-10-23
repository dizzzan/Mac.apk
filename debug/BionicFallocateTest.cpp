// Build: clang++ -std=c++20 -Wall -Wextra -Werror debug/BionicFallocateTest.cpp -o /tmp/fallocate-test
// Run: /tmp/fallocate-test /absolute/path/to/libmacapk-bionic-shim.dylib
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>

int checks;
void check(bool pass, const char* name) {
    ++checks;
    if (!pass) { std::fprintf(stderr, "FAIL: %s (errno=%d)\n", name, errno); std::exit(1); }
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    using Alloc = int (*)(int, int, int64_t, int64_t);
    auto alloc = reinterpret_cast<Alloc>(dlsym(lib, "__macapk_arm64_fallocate"));
    auto alloc64 = reinterpret_cast<Alloc>(dlsym(lib, "__macapk_arm64_fallocate64"));
    check(alloc && alloc64, "both Bionic entry points exported");
    char path[] = "/tmp/macapk-fallocate-XXXXXX";
    int fd = mkstemp(path), ro = open(path, O_RDONLY);
    unlink(path);
    check(fd >= 0 && ro >= 0, "temporary regular file opened");
    check(write(fd, "contents", 8) == 8, "seed contents written");
    struct stat before{}, after{}; fstat(fd, &before);
    check(alloc(fd, 0, 0, 1048576) == -1 && errno == 95, "unsupported allocation returns Linux EOPNOTSUPP");
    check(alloc64(fd, 1, 0, 1048576) == -1 && errno == 95, "64-bit KEEP_SIZE form returns same failure");
    fstat(fd, &after);
    check(before.st_size == after.st_size && before.st_blocks == after.st_blocks, "failure reserves no space and preserves size");
    check(lseek(fd, 0, SEEK_CUR) == 8, "file offset preserved");
    char contents[8]; check(pread(fd, contents, 8, 0) == 8 && !std::memcmp(contents, "contents", 8), "file contents preserved");
    check(alloc(-1, 0, 0, 1) == -1 && errno == 9, "bad descriptor reports EBADF");
    check(alloc(ro, 0, 0, 1) == -1 && errno == 9, "read-only descriptor reports EBADF");
    check(alloc(fd, 0, -1, 1) == -1 && errno == 22, "negative offset reports EINVAL");
    check(alloc(fd, 0, 0, 0) == -1 && errno == 22, "empty range reports EINVAL");
    check(alloc(fd, 0, std::numeric_limits<int64_t>::max(), 1) == -1 && errno == 27, "overflow reports EFBIG");
    close(fd); close(ro);
    std::printf("%d fallocate checks passed\n", checks);
}
