#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace macapk::a64 {

// Relocate an execution copy while leaving all PC-relative data reads pointed
// at the original bytes. Conditional branches within the page move with it.
// Short branches leaving the page use appended B veneers when necessary.
// BL sets LR in the execution copy; code that inspects LR needs separate care.
// Diagnostic only: this is not a general binary translation contract.
inline bool relocate_shadow_page(std::span<const uint32_t> words,
                                 uint64_t original, uint64_t shadow,
                                 std::vector<uint32_t>& out,
                                 const char** failure = nullptr, uint64_t* failure_pc = nullptr) {
    out.assign(words.begin(), words.end());
    size_t current = 0;
    auto fail = [&](const char* why) {
        if (failure) *failure = why;
        if (failure_pc) *failure_pc = original + current * 4;
        return false;
    };
    auto sx = [](uint32_t v, unsigned bits) -> int64_t {
        return (int64_t)(int32_t)(v << (32 - bits)) >> (32 - bits);
    };
    auto fits = [](int64_t v, unsigned bits) {
        return v >= -(int64_t(1) << (bits - 1)) && v < (int64_t(1) << (bits - 1));
    };
    auto branch = [&](uint64_t from, uint64_t to, uint32_t& insn) {
        int64_t delta = int64_t(to - from);
        if ((delta & 3) || !fits(delta / 4, 26)) return false;
        insn = 0x14000000u | (uint32_t(delta / 4) & 0x03ffffffu);
        return true;
    };
    uint32_t fallthrough;
    if (!branch(shadow + words.size_bytes(), original + words.size_bytes(), fallthrough)) return fail("fallthrough range");
    out.push_back(fallthrough);
    for (size_t i = 0; i < words.size(); ++i) {
        current = i;
        uint32_t w = words[i], changed = w;
        uint64_t oa = original + i * 4, sa = shadow + i * 4;
        if ((w & 0x1f000000u) == 0x10000000u) { // ADR/ADRP
            int64_t imm = sx(((w >> 29) & 3) | (((w >> 5) & 0x7ffff) << 2), 21);
            bool page = w >> 31;
            uint64_t target = page ? (oa & ~uint64_t(0xfff)) + imm * 4096 : oa + imm;
            int64_t delta = page ? int64_t((target & ~uint64_t(0xfff)) - (sa & ~uint64_t(0xfff))) / 4096
                                 : int64_t(target - sa);
            if (!fits(delta, 21)) return fail("ADR/ADRP range");
            uint32_t bits = uint32_t(delta) & 0x1fffff;
            changed = (w & 0x9f00001fu) | ((bits & 3) << 29) | ((bits >> 2) << 5);
        } else if ((w & 0x7c000000u) == 0x14000000u) { // B/BL
            uint64_t target = oa + sx(w & 0x03ffffffu, 26) * 4;
            uint32_t b;
            if (!branch(sa, target, b)) return fail("B/BL range");
            changed = (w & 0x80000000u) | b;
        } else {
            uint32_t hi = w >> 24;
            bool literal = hi == 0x18 || hi == 0x58 || hi == 0x98 || hi == 0xd8 ||
                           hi == 0x1c || hi == 0x5c || hi == 0x9c;
            bool tb = (w & 0x7e000000u) == 0x36000000u;
            bool cond = tb || (w & 0xff000010u) == 0x54000000u ||
                               (w & 0x7e000000u) == 0x34000000u;
            if (literal || cond) {
                unsigned bits = tb ? 14 : 19;
                uint32_t mask = (1u << bits) - 1;
                uint64_t target = oa + sx((w >> 5) & mask, bits) * 4;
                if (cond && target >= original && target < original + words.size_bytes()) continue;
                int64_t delta = int64_t(target - sa);
                if ((delta & 3) || !fits(delta / 4, bits)) {
                    if (!cond) return fail("literal range"); // Never silently keep a wrong literal address.
                    uint64_t veneer = shadow + out.size() * 4;
                    uint32_t b;
                    if (!branch(veneer, target, b)) return fail("veneer destination range");
                    delta = int64_t(veneer - sa);
                    if ((delta & 3) || !fits(delta / 4, bits)) return fail("veneer branch range");
                    out.push_back(b);
                }
                changed = (w & ~(mask << 5)) | ((uint32_t(delta / 4) & mask) << 5);
            }
        }
        out[i] = changed;
    }
    return true;
}

} // namespace macapk::a64
