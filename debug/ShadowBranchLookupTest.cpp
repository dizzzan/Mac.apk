#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>

int main(int argc, char** argv) {
    assert(argc == 2);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    assert(shim);
    auto guard = reinterpret_cast<bool (*)(uintptr_t, uintptr_t, uintptr_t, uintptr_t)>(
        dlsym(shim, "__macapk_try_set_syscall_guard_page"));
    auto set = reinterpret_cast<void (*)(uintptr_t, uintptr_t, uintptr_t)>(
        dlsym(shim, "__macapk_set_shadow_landing"));
    auto lookup = reinterpret_cast<uintptr_t (*)(uintptr_t)>(
        dlsym(shim, "__macapk_shadow_landing_for"));
    auto branch = reinterpret_cast<uintptr_t (*)(uintptr_t, uintptr_t*)>(
        dlsym(shim, "__macapk_shadow_branch_target"));
    assert(guard && set && lookup && branch);
    constexpr uintptr_t base = 0x400000000000, page = 0x4000;
    // These are lookup-only records, never dereferenced or executed.
    for (unsigned i = 0; i < 9000; ++i) {
        uintptr_t p = base + i * page;
        assert(lookup(p) == 0); // cache miss before guard publication
        assert(guard(p, p + page, p + page, 0));
        assert(lookup(p) == 0); // cache miss before landing publication
        set(p, p + 2 * page, p + 4 * page);
        assert(lookup(p + 4) == p + 2 * page + 8);
        assert(lookup(p) == p + 2 * page);
    }
    uintptr_t p = base + 8999 * page;
    uintptr_t host = reinterpret_cast<uintptr_t>(dlsym(RTLD_DEFAULT, "getpid"));
    assert(host && lookup(host) == 0);
    auto begin = std::chrono::steady_clock::now();
    for (int i = 0; i < 20000; ++i) {
        uintptr_t lr = p + 16;
        assert(branch(host, &lr) == 0);
        assert(lr == p + 4 * page + 64);
    }
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - begin).count();
    // Exercise colliding positive-cache entries and host-return entries.
    for (unsigned i : {0u, 4096u, 8192u, 0u}) {
        uintptr_t q = base + i * page, lr = q + 4;
        assert(lookup(q) == q + 2 * page);
        assert(branch(host, &lr) == 0 && lr == q + 4 * page + 16);
    }
    printf("PASS: miss invalidation, landing publication, cache collisions; 20k host lookups %.2f ms\n", ms);
}
