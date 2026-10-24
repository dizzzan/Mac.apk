#pragma once

#include <cstdint>
#include <span>
#include <vector>
#include <functional>

namespace macapk::a64 {

// Relocate an execution copy while leaving all PC-relative data reads pointed
// at the original bytes. Conditional branches within the page move with it.
// Short branches leaving the page use appended B veneers when necessary.
// BL sets LR in the execution copy; code that inspects LR needs separate care.
// Diagnostic only: this is not a general binary translation contract.
inline bool relocate_shadow_page(std::span<const uint32_t> words,
                                 uint64_t original, uint64_t shadow,
                                 std::vector<uint32_t>& out,
                                 const char** failure = nullptr, uint64_t* failure_pc = nullptr,
                                 bool far_literals = false, bool original_lr = false,
                                 const std::function<uint64_t(uint64_t)>& code_target = {}) {
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
    auto resolve = [&](uint64_t pc) { return code_target ? code_target(pc) : pc; };
    uint32_t fallthrough;
    if (!branch(shadow + words.size_bytes(), resolve(original + words.size_bytes()), fallthrough)) {
        if (!far_literals) return fail("fallthrough range");
        fallthrough = 0xd4200000u | (0x4da1u << 5); // registered-page continuation
    }
    out.push_back(fallthrough);
    for (size_t i = 0; i < words.size(); ++i) {
        current = i;
        uint32_t w = words[i], changed = w;
        uint64_t oa = original + i * 4, sa = shadow + i * 4;
        if (original_lr && ((w & 0xfc000000u) == 0x94000000u ||
                            (w & 0xfffffc1fu) == 0xd63f0000u)) {
            uint64_t thunk = shadow + out.size() * 4;
            uint32_t enter, transfer;
            if (!branch(sa, thunk, enter)) return fail("call thunk range");
            if ((w & 0xfc000000u) == 0x94000000u) {
                uint64_t target = oa + sx(w & 0x03ffffffu, 26) * 4;
                // A local call can stay in the execution copy while its LR
                // still names the original return address.
                if (target >= original && target < original + words.size_bytes())
                    target = shadow + (target - original);
                else target = resolve(target);
                if (!branch(thunk + 4, target, transfer)) {
                    out[i] = 0xd4200000u | (0x4da0u << 5); // original B/BL destination and LR
                    continue;
                }
            } else {
                if (((w >> 5) & 31) == 30) return fail("BLR LR needs preserved destination");
                transfer = 0xd61f0000u | (w & (31u << 5)); // BR Xn; LR set separately
            }
            out.push_back(0x5800005eu); // LDR LR, literal at +8
            out.push_back(transfer);
            out.push_back(uint32_t(oa + 4));
            out.push_back(uint32_t((oa + 4) >> 32));
            out[i] = enter;
            continue;
        } else if ((w & 0x1f000000u) == 0x10000000u) { // ADR/ADRP
            int64_t imm = sx(((w >> 29) & 3) | (((w >> 5) & 0x7ffff) << 2), 21);
            bool page = w >> 31;
            uint64_t target = page ? (oa & ~uint64_t(0xfff)) + imm * 4096 : oa + imm;
            int64_t delta = page ? int64_t((target & ~uint64_t(0xfff)) - (sa & ~uint64_t(0xfff))) / 4096
                                 : int64_t(target - sa);
            if (!fits(delta, 21)) {
                if (!far_literals) return fail("ADR/ADRP range");
                uint64_t thunk = shadow + out.size() * 4;
                uint32_t enter, leave;
                if (!branch(sa, thunk, enter) || !branch(thunk + 4, sa + 4, leave)) return fail("ADR thunk range");
                out.push_back(0x58000040u | (w & 31)); // ldr Xd, literal at +8
                out.push_back(leave);
                out.push_back(uint32_t(target));
                out.push_back(uint32_t(target >> 32));
                out[i] = enter;
                continue;
            }
            uint32_t bits = uint32_t(delta) & 0x1fffff;
            changed = (w & 0x9f00001fu) | ((bits & 3) << 29) | ((bits >> 2) << 5);
        } else if ((w & 0x7c000000u) == 0x14000000u) { // B/BL
            uint64_t target = oa + sx(w & 0x03ffffffu, 26) * 4;
            if (far_literals && !(w & 0x80000000u) &&
                target >= original && target < original + words.size_bytes()) continue;
            target = resolve(target);
            uint32_t b;
            if (!branch(sa, target, b)) {
                if (!far_literals) return fail("B/BL range");
                out[i] = 0xd4200000u | (0x4da0u << 5);
                continue;
            }
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
                if (cond) target = resolve(target);
                int64_t delta = int64_t(target - sa);
                if ((delta & 3) || !fits(delta / 4, bits)) {
                    if (!cond) {
                        if (!far_literals) return fail("literal range");
                        unsigned rt = w & 31;
                        unsigned scratch = ((hi == 0x18 || hi == 0x58 || hi == 0x98) && rt == 16) ? 17 : 16;
                        uint32_t load = hi == 0x18 ? 0xb9400000u : hi == 0x58 ? 0xf9400000u :
                            hi == 0x98 ? 0xb9800000u : hi == 0xd8 ? 0xf9800000u :
                            hi == 0x1c ? 0xbd400000u : hi == 0x5c ? 0xfd400000u : 0x3dc00000u;
                        uint64_t thunk = shadow + out.size() * 4;
                        uint32_t enter, leave;
                        if (!branch(sa, thunk, enter) || !branch(thunk + 16, sa + 4, leave)) return fail("literal thunk range");
                        out.push_back(0xf81f0fe0u | scratch); // str Xscratch,[sp,#-16]!
                        out.push_back(0x58000080u | scratch); // ldr Xscratch,literal at +16
                        out.push_back(load | (scratch << 5) | rt);
                        out.push_back(0xf84107e0u | scratch); // ldr Xscratch,[sp],#16
                        out.push_back(leave);
                        out.push_back(uint32_t(target));
                        out.push_back(uint32_t(target >> 32));
                        out[i] = enter;
                        continue;
                    }
                    uint64_t veneer = shadow + out.size() * 4;
                    uint32_t b;
                    bool far_transfer = !branch(veneer, target, b);
                    if (far_transfer) {
                        if (!far_literals) return fail("veneer destination range");
                        b = 0xd4200000u | (0x4da2u << 5);
                    }
                    delta = int64_t(veneer - sa);
                    if ((delta & 3) || !fits(delta / 4, bits)) return fail("veneer branch range");
                    out.push_back(b);
                    if (far_transfer) {
                        out.push_back(uint32_t(target));
                        out.push_back(uint32_t(target >> 32));
                    }
                }
                changed = (w & ~(mask << 5)) | ((uint32_t(delta / 4) & mask) << 5);
            }
        }
        out[i] = changed;
    }
    return true;
}

} // namespace macapk::a64
