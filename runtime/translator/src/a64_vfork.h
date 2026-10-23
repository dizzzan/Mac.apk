#pragma once
#include <cstdint>
#include <span>
#include <algorithm>

namespace macapk::a64 {
// Old Bionic vfork clears pthread_internal_t::cached_pid_ before clone.
// Darwin has neither that TLS layout nor Linux's shared-VM vfork contract.
// Our syscall router rejects CLONE_VM|CLONE_VFORK with ENOSYS. In an execution
// shadow, omit ONLY this private cache write so the unsupported syscall can
// reach that error path without dereferencing Darwin's TPIDR_EL0 value.
// The argument setup, syscall, and caller's errno handling still execute.
// AOSP reference: platform_bionic/android-8.1.0_r1/libc/arch-arm64/bionic/vfork.S
inline size_t omit_unsupported_vfork_pid_cache(std::span<uint32_t> words) {
    constexpr uint32_t prefix[] = {0xd53bd040, 0xf9400400, 0xb900141f,
        0xd2882220, 0xaa1f03e1, 0xaa1f03e2, 0xaa1f03e3, 0xaa1f03e4, 0xd2801b88};
    constexpr uint32_t svc = 0xd4000001, brk = 0xd429a820, nop = 0xd503201f;
    size_t count = 0;
    for (size_t i = 0; i + 10 <= words.size(); ++i) {
        if (!std::equal(std::begin(prefix), std::end(prefix), words.begin() + i)
                || (words[i + 9] != svc && words[i + 9] != brk)) continue;
        std::fill_n(words.begin() + i, 3, nop);
        ++count;
    }
    return count;
}
}
