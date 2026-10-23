#!/usr/bin/env python3
"""Generate a tiny original AArch64 ELF for GuestDlsymIdentityTest.

Pass an output path ending in lib_native/libdlsym_fixture.so, then translate
it to the neighboring libdlsym_fixture.dylib with macapk-translator.
"""
from pathlib import Path
import struct
import sys

b = bytearray(0x1800)


def put(offset, fmt, *args):
    struct.pack_into('<' + fmt, b, offset, *args)


b[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
put(16, 'HHIQQQIHHHHHH', 3, 183, 1, 0, 64, 0x1000, 0, 64, 56, 2, 64, 7, 6)
put(64, 'IIQQQQQQ', 1, 5, 0, 0, 0, len(b), len(b), 0x4000)
put(120, 'IIQQQQQQ', 2, 4, 0x600, 0x600, 0x600, 7 * 16, 7 * 16, 8)
put(0x200, 'II', 0x10000000, 0xd65f03c0)  # adr x0, self; ret
put(0x210, 'Q', 0x123456789abcdef0)
strings = b'\0fixture_self\0fixture_data\0libdlsym_fixture.so\0'
b[0x500:0x500 + len(strings)] = strings
put(0x400 + 24, 'IBBHQQ', 1, 0x12, 0, 1, 0x200, 8)
put(0x400 + 48, 'IBBHQQ', 14, 0x11, 0, 1, 0x210, 8)
put(0x300, 'IIIIII', 1, 3, 1, 0, 2, 0)  # SysV hash: one bucket, two exports
dynamic = [(4, 0x300), (5, 0x500), (6, 0x400), (10, len(strings)),
           (11, 24), (14, 27), (0, 0)]
for i, pair in enumerate(dynamic):
    put(0x600 + 16 * i, 'QQ', *pair)
names = b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.shstrtab\0'
b[0x800:0x800 + len(names)] = names
sections = [
    (1, 1, 6, 0x200, 0x200, 0x18, 0, 0, 4, 0),
    (7, 11, 2, 0x400, 0x400, 72, 3, 1, 8, 24),
    (15, 3, 2, 0x500, 0x500, len(strings), 0, 0, 1, 0),
    (23, 5, 2, 0x300, 0x300, 24, 2, 0, 8, 4),
    (29, 6, 2, 0x600, 0x600, 112, 3, 0, 8, 16),
    (38, 3, 0, 0, 0x800, len(names), 0, 0, 1, 0),
]
for i, row in enumerate(sections, 1):
    put(0x1000 + 64 * i, 'IIQQQQIIQQ', *row)
Path(sys.argv[1]).write_bytes(b)
