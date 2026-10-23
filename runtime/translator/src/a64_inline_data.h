#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>

namespace macapk::a64 {

struct InlineDataRange { uint64_t lo, hi; };

// Recognize a bounded, forward branch whose destination is materialized from
// BL's return address. The called leaf saves/restores FP/LR and returns LR in
// x0. Bytes skipped by the computed BR can include instruction-shaped data.
// Keep the called leaf executable, and decline intervals with another visible
// branch/address entry. This deliberately does not infer arbitrary indirect
// branch targets; callers currently enable it only for diagnostic experiments.
inline std::vector<InlineDataRange> lr_skipped_ranges(const uint8_t* bytes,
                                                     size_t size, uint64_t base) {
    auto word = [&](size_t off) {
        uint32_t w; std::memcpy(&w, bytes + off, 4); return w;
    };
    auto sext = [](uint32_t value, unsigned bits) -> int64_t {
        return (int64_t)(int32_t)(value << (32 - bits)) >> (32 - bits);
    };
    std::vector<uint64_t> entries;
    std::vector<InlineDataRange> candidates;
    for (size_t off = 0; off + 4 <= size; off += 4) {
        uint32_t w = word(off);
        uint64_t pc = base + off;
        if ((w & 0x7c000000u) == 0x14000000u)
            entries.push_back(pc + sext(w & 0x03ffffffu, 26) * 4);
        else if ((w & 0xff000010u) == 0x54000000u || (w & 0x7e000000u) == 0x34000000u)
            entries.push_back(pc + sext((w >> 5) & 0x7ffffu, 19) * 4);
        else if ((w & 0x7e000000u) == 0x36000000u)
            entries.push_back(pc + sext((w >> 5) & 0x3fffu, 14) * 4);
        else if ((w & 0x9f000000u) == 0x10000000u)
            entries.push_back(pc + sext(((w >> 29) & 3u) | (((w >> 5) & 0x7ffffu) << 2), 21));

        if ((w & 0xfc000000u) != 0x94000000u || off + 12 > size) continue;
        uint64_t helper = pc + sext(w & 0x03ffffffu, 26) * 4;
        if (helper < base || helper - base > size || size - (helper - base) < 24) continue;
        size_t h = helper - base;
        const uint32_t leaf[] = {0xd10043ff, 0xa9007bfd, 0xf94007e0,
                                 0xa9407bfd, 0x910043ff, 0xd65f03c0};
        if (std::memcmp(bytes + h, leaf, sizeof(leaf))) continue;
        size_t add = off + 4;
        unsigned source = 0;
        uint32_t next = word(add);
        if ((next & 0xffffffe0u) == 0xaa0003e0u) {
            source = next & 31u;
            if (source == 31) continue; // MOV discards x0; ADD would read SP.
            add += 4;
        }
        if (add + 8 > size) continue;
        uint32_t a = word(add);
        unsigned dest = a & 31u;
        if ((a & 0xffc00000u) != 0x91000000u || ((a >> 5) & 31u) != source || dest == 31) continue;
        if (word(add + 4) != (0xd61f0000u | (dest << 5))) continue;
        uint64_t target = pc + 4 + ((a >> 10) & 0xfffu);
        uint64_t lo = base + add + 8;
        if ((target & 3u) || target <= lo || target > base + size || target - lo > 256) continue;
        // The BL leaf itself is reached even when it lies in the skipped span.
        if (helper >= lo && helper < target) {
            if (lo < helper) candidates.push_back({lo, helper});
            if (helper + sizeof(leaf) < target) candidates.push_back({helper + sizeof(leaf), target});
        } else candidates.push_back({lo, target});
    }
    std::sort(entries.begin(), entries.end());
    std::vector<InlineDataRange> result;
    for (auto r : candidates) {
        auto entry = std::lower_bound(entries.begin(), entries.end(), r.lo);
        if (entry == entries.end() || *entry >= r.hi) result.push_back(r);
    }
    return result;
}

} // namespace macapk::a64
