#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

// clang++ -std=c++20 debug/DataParentPathTest.cpp -o /tmp/data-parent-test
// /tmp/data-parent-test runtime/build/bin/libmacapk-bionic-shim.dylib
int main(int argc, char** argv) {
    assert(argc == 2);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(shim);
    auto raw = reinterpret_cast<long (*)(long, long, long, long, long, long, long)>(
        dlsym(shim, "__macapk_arm64_raw_syscall"));
    auto setRoot = reinterpret_cast<void (*)(const char*)>(dlsym(shim, "__macapk_set_android_root"));
    auto setData = reinterpret_cast<void (*)(const char*)>(dlsym(shim, "__macapk_set_data_subdir"));
    assert(raw && setRoot && setData);
    char temp[] = "/tmp/macapk-data-parent-XXXXXX";
    assert(mkdtemp(temp));
    std::string root = temp;
    std::filesystem::create_directories(root + "/data/data/example_1");
    std::filesystem::create_directories(root + "/data/app");
    setRoot(root.c_str());
    setData("example_1");
    const char* paths[] = {"/data", "/data/", "/data/data", "/data/data/",
                          "/data/app", "/data/app/", "/data/data/example",
                          "/data/data/example/"};
    const char* targets[] = {"/data", "/data", "/data/data", "/data/data",
                            "/data/app", "/data/app", "/data/data/example_1",
                            "/data/data/example_1"};
    for (unsigned i = 0; i < sizeof(paths) / sizeof(paths[0]); ++i) {
        long fd = raw(56, -100, reinterpret_cast<long>(paths[i]), 0200000, 0, 0, 0);
        if (fd < 0) std::fprintf(stderr, "open(%s) = %ld\n", paths[i], fd);
        assert(fd >= 0);
        struct stat actual{}, expected{};
        assert(fstat(static_cast<int>(fd), &actual) == 0);
        assert(stat((root + targets[i]).c_str(), &expected) == 0);
        assert(actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino);
        assert(S_ISDIR(actual.st_mode));
        close(static_cast<int>(fd));
    }
    std::filesystem::remove_all(root);
    std::puts("PASS: data parent directories resolve to their own inodes");
}
