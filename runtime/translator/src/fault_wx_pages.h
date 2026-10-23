#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace macapk {
// Only normal mmap/mprotect/munmap calls mutate this registry. Fault handlers
// perform lock-free membership reads; they never allocate or acquire its mutex.
class FaultWxPages {
    static constexpr size_t kCapacity = 4096;
    std::array<std::atomic<uintptr_t>, kCapacity> pages_{};
    std::mutex mutex_;
public:
    static constexpr size_t kPage = 16384;
    bool contains(uintptr_t address) const {
        uintptr_t page = address & ~(uintptr_t(kPage) - 1);
        for (const auto& entry : pages_)
            if (entry.load(std::memory_order_acquire) == page && page) return true;
        return false;
    }
    bool add(uintptr_t address, size_t length) {
        if (!address || (address & (kPage - 1)) || !length
                || length > kCapacity * kPage || address + length < address) return false;
        size_t count = (length + kPage - 1) / kPage;
        std::lock_guard<std::mutex> lock(mutex_);
        size_t needed = 0, available = 0;
        for (size_t i = 0; i < count; ++i)
            if (!contains(address + i * kPage)) ++needed;
        for (auto& entry : pages_) if (!entry.load()) ++available;
        if (needed > available) return false; // no partial publication
        for (size_t i = 0; i < count; ++i) {
            uintptr_t page = address + i * kPage;
            if (contains(page)) continue;
            for (auto& entry : pages_) {
                if (!entry.load()) { entry.store(page, std::memory_order_release); break; }
            }
        }
        return true;
    }
    void remove(uintptr_t address, size_t length) {
        if (!length || address + length < address) return;
        uintptr_t lo = address & ~(uintptr_t(kPage) - 1);
        uintptr_t hi = address + length;
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& entry : pages_) {
            uintptr_t page = entry.load();
            if (page >= lo && page < hi) entry.store(0, std::memory_order_release);
        }
    }
};
static_assert(std::atomic<uintptr_t>::is_always_lock_free);
}
