#!/usr/bin/env python3
"""Build an original AArch64 PIE fixture without an Android NDK linker.

Usage: script output.so. Apple's clang assembles NativeExecGuest.S; this script
adds the ELF load/dynamic tables and import relocations used by the runtime.
"""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

output = Path(sys.argv[1])
with tempfile.TemporaryDirectory() as tmp:
    objpath = Path(tmp) / 'guest.o'
    subprocess.run(['clang', '--target=aarch64-linux-android29', '-c',
                    str(Path(__file__).with_name('NativeExecGuest.S')), '-o', str(objpath)], check=True)
    obj = objpath.read_bytes()
shoff = struct.unpack_from('<Q', obj, 40)[0]
shnum = struct.unpack_from('<H', obj, 60)[0]
text = None
for i in range(shnum):
    section = struct.unpack_from('<IIQQQQIIQQ', obj, shoff + i * 64)
    if section[1] == 1 and section[2] & 4:
        text = obj[section[4]:section[4] + section[5]]
        break
assert text is not None
b = bytearray(0x1800)
def put(offset, fmt, *args):
    struct.pack_into('<' + fmt, b, offset, *args)
b[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
put(16, 'HHIQQQIHHHHHH', 3, 183, 1, 0x200, 64, 0x1000, 0, 64, 56, 2, 64, 8, 7)
put(64, 'IIQQQQQQ', 1, 5, 0, 0, 0, len(b), len(b), 0x4000)
put(120, 'IIQQQQQQ', 2, 4, 0xd00, 0xd00, 0xd00, 176, 176, 8)
b[0x200:0x200 + len(text)] = text
symbols = ['__libc_init', 'strlen', 'write', 'getenv', 'getcwd']
strings = b'\0'
offsets = []
for symbol in symbols:
    offsets.append(len(strings))
    strings += symbol.encode() + b'\0'
b[0xb00:0xb00 + len(strings)] = strings
for i, offset in enumerate(offsets, 1):
    put(0xa00 + 24 * i, 'IBBHQQ', offset, 0x12, 0, 0, 0, 0)
    put(0xc00 + 24 * (i - 1), 'QQq', 0x800 + 8 * (i - 1), (i << 32) | 1025, 0)
put(0x900, 'IIIIIIIII', 1, 6, 0, 0, 0, 0, 0, 0, 0)
dynamic = [(4, 0x900), (5, 0xb00), (6, 0xa00), (10, len(strings)), (11, 24),
           (7, 0xc00), (8, 120), (9, 24), (0x6ffffffb, 0x8000000), (21, 0), (0, 0)]
for i, pair in enumerate(dynamic):
    put(0xd00 + 16 * i, 'QQ', *pair)
names = b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.rela.dyn\0.shstrtab\0'
b[0xe00:0xe00 + len(names)] = names
sections = [
    (1, 1, 6, 0x200, 0x200, len(text), 0, 0, 4, 0),
    (7, 11, 2, 0xa00, 0xa00, 144, 3, 1, 8, 24),
    (15, 3, 2, 0xb00, 0xb00, len(strings), 0, 0, 1, 0),
    (23, 5, 2, 0x900, 0x900, 36, 2, 0, 8, 4),
    (29, 6, 2, 0xd00, 0xd00, 176, 3, 0, 8, 16),
    (38, 4, 2, 0xc00, 0xc00, 120, 2, 1, 8, 24),
    (48, 3, 0, 0, 0xe00, len(names), 0, 0, 1, 0),
]
for i, row in enumerate(sections, 1):
    put(0x1000 + 64 * i, 'IIQQQQIIQQ', *row)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(b)
output.chmod(0o755)
