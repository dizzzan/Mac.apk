#pragma once
#include <cstddef>
#include <cstdint>
#include <span>

namespace macapk::a64 {
// Bionic's generic syscall register shuffle, followed by a known return form.
// Generated code can copy this wrapper after the ELF loader's initial scan.
// Replacing only SVC preserves its own Linux-to-libc errno adapter.
inline size_t rewrite_dynamic_syscalls(std::span<uint32_t> words) {
    constexpr uint32_t shuffle[] = {0xaa0003e8, 0xaa0103e0, 0xaa0203e1,
        0xaa0303e2, 0xaa0403e3, 0xaa0503e4, 0xaa0603e5};
    size_t count = 0;
    for (size_t i = 0; i + 8 < words.size(); ++i) {
        size_t j = 0;
        while (j < 7 && words[i + j] == shuffle[j]) ++j;
        if (j != 7 || words[i + 7] != 0xd4000001) continue;
        uint32_t tail = words[i + 8];
        if (tail != 0xd65f03c0 && tail != 0xb13ffc1f && tail != 0xb140041f)
            continue; // ret, cmn x0,#4095 or cmn x0,#4096; no guessed adapter
        words[i + 7] = 0xd429a820; // BRK #0x4d41: arguments survive XNU entry.
        ++count;
        i += 7;
    }
    return count;
}
} // namespace macapk::a64
