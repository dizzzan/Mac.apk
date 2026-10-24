#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <new>

namespace macapk {

struct SyscallGuard {
    uintptr_t lo = 0, hi = 0, shadow = 0, wrapper = 0;
    bool dynamic = false;
    std::atomic<bool> live{false};
    std::atomic<uintptr_t> landing{0}, host_return{0};
};

// Readers include signal handlers: growing a vector would invalidate their
// records and lookup caches. Append to permanent blocks instead. Publication
// uses only lock-free atomics; readers never allocate or take the writer lock.
// Removed mappings become inactive, but their records stay valid for readers
// already holding a pointer. Blocks intentionally live until process exit.
class SyscallGuardRegistry {
    static constexpr size_t kBlockSize = 256;
    struct Block {
        SyscallGuard entries[kBlockSize];
        std::atomic<size_t> count{0};
        std::atomic<Block*> next{nullptr};
    };
    static_assert(std::atomic<size_t>::is_always_lock_free);
    static_assert(std::atomic<Block*>::is_always_lock_free);
    Block first_;
    Block* tail_ = &first_;
    size_t size_ = 0;

public:
    class Iterator {
        Block* block_;
        size_t index_ = 0, count_ = 0;
        void advance_block() {
            while (block_) {
                count_ = block_->count.load(std::memory_order_acquire);
                if (index_ < count_) return;
                block_ = count_ == kBlockSize ? block_->next.load(std::memory_order_acquire) : nullptr;
                index_ = 0;
            }
        }
    public:
        explicit Iterator(Block* block) : block_(block) { advance_block(); }
        SyscallGuard& operator*() const { return block_->entries[index_]; }
        Iterator& operator++() {
            if (++index_ == count_) {
                // A partially published tail ends this scan. Following a
                // newly linked block could otherwise skip entries appended
                // to that tail after we took its count snapshot.
                block_ = count_ == kBlockSize ? block_->next.load(std::memory_order_acquire) : nullptr;
                index_ = 0;
                advance_block();
            }
            return *this;
        }
        bool operator!=(const Iterator& other) const { return block_ != other.block_ || index_ != other.index_; }
    };
    Iterator begin() { return Iterator(&first_); }
    Iterator end() { return Iterator(nullptr); }

    // The caller serializes writers, including duplicate detection. No slot
    // is reused: stale cached pointers must never acquire a different mapping.
    SyscallGuard* append(uintptr_t lo, uintptr_t hi, uintptr_t shadow, uintptr_t wrapper, bool dynamic) {
        size_t index = tail_->count.load(std::memory_order_relaxed);
        Block* block = tail_;
        if (index == kBlockSize) {
            block = new (std::nothrow) Block;
            if (!block) return nullptr;
            index = 0;
        }
        auto& entry = block->entries[index];
        entry.lo = lo; entry.hi = hi; entry.shadow = shadow;
        entry.wrapper = wrapper; entry.dynamic = dynamic;
        entry.live.store(true, std::memory_order_relaxed);
        block->count.store(index + 1, std::memory_order_release);
        if (block != tail_) {
            tail_->next.store(block, std::memory_order_release);
            tail_ = block;
        }
        ++size_;
        return &entry;
    }
    size_t size() const { return size_; } // writer lock required
};

} // namespace macapk
