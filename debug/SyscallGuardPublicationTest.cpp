#include "../runtime/translator/src/syscall_guard_registry.h"
#include <cassert>
#include <cstdio>
#include <thread>

int main() {
    macapk::SyscallGuardRegistry registry;
    constexpr size_t records = 8193;
    constexpr uintptr_t base = 0x400000000, page = 0x4000;
    auto* first = registry.append(base, base+page, base+0x100000000, 0, false);
    assert(first);
    std::atomic<bool> done{false};
    std::atomic<size_t> scans{0};
    std::thread reader([&] {
        do {
            size_t seen = 0;
            for (const auto& g : registry) {
                assert(g.lo == base+seen*page);
                assert(g.hi == g.lo+page && g.shadow == g.lo+0x100000000);
                assert(!g.dynamic && g.wrapper == 0);
                ++seen;
            }
            assert(seen > 0 && seen <= records);
            assert(first->lo == base); // cached pointer survives block growth
            scans.fetch_add(1);
        } while (!done.load(std::memory_order_acquire));
    });
    while (!scans.load()) std::this_thread::yield();
    for (size_t i = 1; i < records; ++i) {
        uintptr_t lo = base+i*page;
        assert(registry.append(lo, lo+page, lo+0x100000000, 0, false));
    }
    done.store(true, std::memory_order_release);
    reader.join();
    assert(registry.size() == records);
    size_t seen = 0;
    for (const auto& g : registry) { assert(g.lo == base+seen*page); ++seen; }
    assert(seen == records);
    first->live.store(false);
    auto* replacement = registry.append(base, base+page, base+0x200000000, 0, false);
    assert(replacement && replacement != first && replacement->live.load());
    assert(!first->live.load() && first->shadow == base+0x100000000);
    puts("Concurrent guard publication, stable cached records, and inactive record lifetime PASS");
}
