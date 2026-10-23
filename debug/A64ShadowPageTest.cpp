// Build: clang++ -std=c++20 -Wall -Wextra -Werror debug/A64ShadowPageTest.cpp -o /tmp/a64-shadow-test
#include "../runtime/translator/src/a64_shadow_page.h"
#include <cstdio>
#include <cstdlib>

namespace {
constexpr uint64_t original = 0x7000000000, shadow = original + 0x60000;
int checks = 0;
void check(bool pass, const char* name) {
    ++checks;
    if (!pass) { std::fprintf(stderr, "FAIL: %s\n", name); std::exit(1); }
}
int64_t sx(uint32_t v, unsigned bits) {
    return int64_t(int32_t(v << (32 - bits))) >> (32 - bits);
}
uint64_t immediate_target(uint32_t w, uint64_t pc, unsigned bits) {
    return pc + sx((w >> 5) & ((1u << bits) - 1), bits) * 4;
}
uint64_t branch_target(uint32_t w, uint64_t pc) { return pc + sx(w & 0x3ffffff, 26) * 4; }
uint32_t cond(uint32_t op, unsigned bits, uint64_t pc, uint64_t target) {
    return op | ((uint32_t(int64_t(target - pc) / 4) & ((1u << bits) - 1)) << 5);
}
uint64_t adr_target(uint32_t w, uint64_t pc) {
    auto imm = sx(((w >> 29) & 3) | (((w >> 5) & 0x7ffff) << 2), 21);
    return (w >> 31) ? (pc & ~uint64_t(0xfff)) + imm * 4096 : pc + imm;
}
}

int main() {
    std::vector<uint32_t> in(0x4000 / 4, 0xd503201f), out;
    in[0] = 0x10000800; // ADR x0, original+0x100
    in[1] = 0xf0000001; // ADRP x1, original+0x3000
    in[2] = cond(0x58000002, 19, original + 8, original + 0x100); // LDR x2,literal
    in[3] = 0x94000000 | (0x8000 - 12) / 4; // BL outside the page
    in[4] = cond(0x54000000, 19, original + 16, original + 0x100); // local B.eq
    in[5] = cond(0x36000003, 14, original + 20, original + 0x6000); // off-page TBZ
    in[6] = cond(0xb4000004, 19, original + 24, original + 0x8000); // off-page CBZ
    in[7] = cond(0x58000005, 19, original + 28, original - 0x40000); // negative literal
    auto saved = in;
    check(macapk::a64::relocate_shadow_page(in, original, shadow, out), "page relocates");
    check(in == saved, "source bytes are unchanged");
    check(adr_target(out[0], shadow) == adr_target(in[0], original), "ADR preserves data address");
    check(adr_target(out[1], shadow + 4) == adr_target(in[1], original + 4), "ADRP preserves data page");
    for (unsigned i : {2u, 7u})
        check(immediate_target(out[i], shadow + i * 4, 19) == immediate_target(in[i], original + i * 4, 19),
              "literal reads original bytes");
    check(branch_target(out[3], shadow + 12) == original + 0x8000, "BL preserves destination");
    check((out[3] & 0x80000000) != 0, "BL preserves link operation");
    check(out[4] == in[4], "local conditional branch moves with page");
    uint64_t veneer = immediate_target(out[5], shadow + 20, 14);
    check(veneer >= shadow + in.size() * 4, "short branch reaches a veneer");
    check(branch_target(out[(veneer - shadow) / 4], veneer) == original + 0x6000,
          "veneer reaches original off-page target");
    check(immediate_target(out[6], shadow + 24, 19) == original + 0x8000, "off-page CBZ reaches original");
    check(branch_target(out[in.size()], shadow + in.size() * 4) == original + in.size() * 4,
          "sequential fallthrough reaches next original page");
    check(out[100] == in[100], "ordinary instructions unchanged");
    in.assign(in.size(), 0xd503201f);
    in[0] = cond(0x58000000, 19, original, original - 0xf0000);
    check(!macapk::a64::relocate_shadow_page(in, original, shadow, out), "unreachable literal fails closed");
    in[0] = 0x10800000; // ADR with -1MB displacement
    check(!macapk::a64::relocate_shadow_page(in, original, shadow, out), "unreachable ADR fails closed");
    // Relocation must work on either side of the original mapping.
    in = saved;
    check(macapk::a64::relocate_shadow_page(in, original, original - 0x60000, out), "negative placement relocates");
    check(adr_target(out[0], original - 0x60000) == original + 0x100, "negative placement preserves ADR");
    std::printf("%d shadow-page checks passed\n", checks);
}
