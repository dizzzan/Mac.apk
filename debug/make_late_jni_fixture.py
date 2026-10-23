#!/usr/bin/env python3
"""Original ELF fixture: DT_INIT renames a dynamic export to JNI_OnLoad.

Usage: script output.so [absent|static]. Translate with macapk-translator.
The absent variant exercises JNI's default; static declares the hook on disk.
"""
from pathlib import Path
import struct
import sys

b = bytearray(0x5800)


def put(offset, fmt, *args):
    struct.pack_into('<' + fmt, b, offset, *args)


def adr(rd, source, target):
    delta = target - source
    return 0x10000000 | ((delta & 3) << 29) | (((delta >> 2) & 0x7ffff) << 5) | rd


b[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
put(16, 'HHIQQQIHHHHHH', 3, 183, 1, 0, 64, 0x5000, 0, 64, 56, 3, 64, 7, 6)
put(64, 'IIQQQQQQ', 1, 5, 0, 0, 0, 0x1000, 0x1000, 0x4000)
put(120, 'IIQQQQQQ', 1, 6, 0x4000, 0x4000, 0x4000, 0x1800, 0x1800, 0x4000)
put(176, 'IIQQQQQQ', 2, 6, 0x4600, 0x4600, 0x4600, 8 * 16, 8 * 16, 8)
strings = b'\0before_load\0JNI_OnLoad\0liblate_jni.so\0'
b[0x4500:0x4500 + len(strings)] = strings
mode = sys.argv[2] if len(sys.argv) > 2 else 'late'
put(0x4400 + 24, 'IBBHQQ', 13 if mode == 'static' else 1, 0x12, 0, 1, 0x240, 12)
put(0x4300, 'IIIII', 1, 2, 1, 0, 0)  # one SysV bucket containing symbol 1
# The constructor publishes the hook using an ordinary store to the writable
# symbol table. No imported functions, app code, or protection bypasses.
put(0x200, 'IIII', adr(8, 0x200, 0x4418), 0x52800009 | (13 << 5),
    0xb9000109, 0xd65f03c0)
# JNI_VERSION_1_6, distinguishable from the absent-hook default 1.1.
put(0x240, 'III', 0x528000c0, 0x72a00020, 0xd65f03c0)
dynamic = [(4, 0x4300), (5, 0x4500), (6, 0x4400), (10, len(strings)),
           (11, 24), (14, 24)]
if mode == 'late':
    dynamic.append((12, 0x200))
dynamic.append((0, 0))
for i, pair in enumerate(dynamic):
    put(0x4600 + 16 * i, 'QQ', *pair)
names = b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.shstrtab\0'
b[0x4800:0x4800 + len(names)] = names
sections = [
    (1, 1, 6, 0x200, 0x200, 0x4c, 0, 0, 4, 0),
    (7, 11, 3, 0x4400, 0x4400, 48, 3, 1, 8, 24),
    (15, 3, 3, 0x4500, 0x4500, len(strings), 0, 0, 1, 0),
    (23, 5, 3, 0x4300, 0x4300, 20, 2, 0, 8, 4),
    (29, 6, 3, 0x4600, 0x4600, 16 * len(dynamic), 3, 0, 8, 16),
    (38, 3, 0, 0, 0x4800, len(names), 0, 0, 1, 0),
]
for i, row in enumerate(sections, 1):
    put(0x5000 + 64 * i, 'IIQQQQIIQQ', *row)
Path(sys.argv[1]).write_bytes(b)
