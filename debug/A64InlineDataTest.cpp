// Build: clang++ -std=c++20 -Wall -Wextra -Werror debug/A64InlineDataTest.cpp -o /tmp/a64-inline-data-test
#include "../runtime/translator/src/a64_inline_data.h"
#include <cstdio>
#include <cstdlib>

namespace {
constexpr uint64_t base = 0x7000000000;
constexpr uint32_t nop = 0xd503201f;
const uint32_t leaf[] = {0xd10043ff, 0xa9007bfd, 0xf94007e0,
                         0xa9407bfd, 0x910043ff, 0xd65f03c0};
int checks = 0;
using Code = std::vector<uint32_t>;
Code fixture(bool mov = false) {
    Code c(32, nop);
    c[0] = 0x94000008; // BL 0x20
    c[1] = mov ? 0xaa0003e1 : 0x9100e001; // MOV x1,x0 / ADD x1,x0,#0x38
    c[2] = mov ? 0x9100e021 : 0xd61f0020; // ADD x1,x1,#0x38 / BR x1
    if (mov) c[3] = 0xd61f0020;
    std::copy(std::begin(leaf), std::end(leaf), c.begin() + 8);
    c[4] = 0xd4000001; // skipped instruction-shaped data
    c[15] = 0xd4000001; // actual destination (0x3c), must remain code
    return c;
}
auto ranges(const Code& c, size_t bytes = 0) {
    return macapk::a64::lr_skipped_ranges(reinterpret_cast<const uint8_t*>(c.data()),
                                         bytes ? bytes : c.size() * 4, base);
}
bool contains(const Code& c, unsigned off) {
    for (auto r : ranges(c)) if (base + off >= r.lo && base + off < r.hi) return true;
    return false;
}
void check(bool pass, const char* name) {
    ++checks;
    if (!pass) { std::fprintf(stderr, "FAIL: %s\n", name); std::exit(1); }
}
}

int main() {
    for (bool mov : {false, true}) {
        auto c = fixture(mov);
        check(contains(c, 0x10), "skipped SVC is data");
        check(contains(c, 0x38), "skipped tail after leaf is data");
        check(!contains(c, 0x3c), "branch destination is code");
        check(!contains(c, 0x20) && !contains(c, 0x34), "called leaf is code");
        check(!contains(c, 0x08), "dispatch instructions are code");
    }
    auto c = fixture(); c[8] = nop;
    check(ranges(c).empty(), "non-matching helper rejected");
    c = fixture(true); c[1] = 0xaa0003ff;
    check(ranges(c).empty(), "MOV to XZR cannot materialize an address");
    c = fixture(); c[1] = 0x9100e01f;
    check(ranges(c).empty(), "ADD destination SP rejected");
    c = fixture(); c[1] = 0x9100e021;
    check(ranges(c).empty(), "ADD source must be returned LR");
    c = fixture(); c[2] = 0xd61f0040;
    check(ranges(c).empty(), "BR register must match ADD destination");
    c = fixture(); c[1] = 0x91000001;
    check(ranges(c).empty(), "backward destination rejected");
    c = fixture(); c[1] = 0x9100dc01;
    check(ranges(c).empty(), "unaligned destination rejected");
    c = fixture(); c[1] = 0x91080001;
    check(ranges(c).empty(), "destination beyond segment rejected");
    c = fixture();
    check(ranges(c, 0x30).empty(), "truncated helper rejected");
    check(ranges(c, 4).empty(), "truncated dispatch rejected");
    // A second visible incoming edge makes a skipped interval ambiguous.
    // B, BL, B.cond, CBZ, TBZ and ADR each target the data at 0x10.
    for (uint32_t entry : {0x17fffff4u, 0x97fffff4u, 0x54fffe80u,
                           0xb4fffe80u, 0x3607fe80u, 0x10fffe80u}) {
        c = fixture(); c[16] = entry;
        check(!contains(c, 0x10), "alternate entry keeps ambiguous interval executable");
    }
    // An incoming edge at the high endpoint is outside the skipped interval.
    c = fixture(); c[16] = 0x17ffffff;
    check(contains(c, 0x10), "incoming edge at destination is safe");
    c = fixture(); c[16] = 0x30fffe80; // ADR x0,0x11
    check(contains(c, 0x10), "unaligned data address is not an instruction entry");
    std::printf("%d inline-data checks passed\n", checks);
}
