#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <thread>
#include <vector>

int main(int argc, char** argv) {
    assert(argc == 2);
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!library) { puts(dlerror()); return 1; }
    auto current = reinterpret_cast<int (*)()>(
        dlsym(library, "__macapk_arm64_sched_getcpu"));
    auto getcpu = reinterpret_cast<int (*)(unsigned*, unsigned*, void*)>(
        dlsym(library, "__macapk_arm64___getcpu"));
    auto affinity = reinterpret_cast<int (*)(int, size_t, void*)>(
        dlsym(library, "__macapk_arm64_sched_getaffinity"));
    auto syscall = reinterpret_cast<long (*)(long, long, long, long, long, long, long)>(
        dlsym(library, "__macapk_arm64_syscall_impl"));
    assert(current && getcpu && affinity && syscall);
    unsigned char mask[128]{};
    assert(affinity(0, sizeof(mask), mask) == 0);
    std::vector<std::thread> workers;
    for (int w = 0; w < 8; ++w) workers.emplace_back([&] {
        for (int i = 0; i < 1000; ++i) {
            errno = 123;
            int cpu = current();
            assert(cpu >= 0 && cpu < int(sizeof(mask) * 8));
            assert(mask[cpu / 8] & (1u << (cpu % 8)));
            assert(errno == 123);
            struct { unsigned cpu, node, guard; } result{~0u, ~0u, 0xabcdef01u};
            assert(getcpu(&result.cpu, &result.node, nullptr) == 0);
            assert(result.cpu < sizeof(mask) * 8 && result.node == 0);
            assert(mask[result.cpu / 8] & (1u << (result.cpu % 8)));
            assert(result.guard == 0xabcdef01u);
            result.cpu = result.node = ~0u;
            assert(syscall(168, reinterpret_cast<long>(&result.cpu),
                           reinterpret_cast<long>(&result.node), 0, 0, 0, 0) == 0);
            assert(result.cpu < sizeof(mask) * 8 && result.node == 0);
            assert(mask[result.cpu / 8] & (1u << (result.cpu % 8)));
            assert(result.guard == 0xabcdef01u);
            assert(getcpu(nullptr, nullptr, nullptr) == 0);
        }
    });
    for (auto& worker : workers) worker.join();
    puts("PASS: CPU queries and raw syscall agree with the guest affinity mask");
}
