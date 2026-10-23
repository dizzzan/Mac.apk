#include "../runtime/translator/src/fault_wx_pages.h"
#include <cassert>
#include <cstdio>
#include <limits>

int main() {
    macapk::FaultWxPages pages;
    constexpr uintptr_t base = 0x100000;
    constexpr size_t page = macapk::FaultWxPages::kPage;
    assert(!pages.contains(base));
    assert(!pages.add(0, page));
    assert(!pages.add(base + 1, page));
    assert(!pages.add(base, 0));
    assert(!pages.add(base, 4097 * page));
    assert(!pages.add(std::numeric_limits<uintptr_t>::max() & ~(page - 1), 2 * page));
    assert(pages.add(base, page + 1));
    assert(pages.contains(base) && pages.contains(base + 2 * page - 1));
    assert(!pages.contains(base + 2 * page));
    assert(pages.add(base + page, page)); // overlapping publication is idempotent
    pages.remove(base, page);
    assert(!pages.contains(base) && pages.contains(base + page));
    pages.remove(base + page, 1);
    assert(!pages.contains(base + page));
    assert(pages.add(base, 4096 * page));
    assert(!pages.add(base + 4096 * page, page));
    assert(!pages.contains(base + 4096 * page));
    pages.remove(base + 127 * page, page);
    assert(pages.add(base + 4096 * page, page));
    pages.remove(base, 4097 * page);
    assert(!pages.contains(base) && !pages.contains(base + 4096 * page));
    std::puts("W^X page registry bounds, overlaps, removals and capacity passed");
}
