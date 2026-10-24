"""Minimal AArch64 ELF whose executable segment exceeds the old 4096-page cap."""
from pathlib import Path
import struct
import sys

page = 0x4000
pages = 4101
rx_size = pages * page
data = rx_size
b = bytearray(rx_size + page)
def put(offset, fmt, *values):
    struct.pack_into('<' + fmt, b, offset, *values)
b[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
put(16, 'HHIQQQIHHHHHH', 3, 183, 1, 0, 64, 0, 0, 64, 56, 3, 64, 0, 0)
put(64, 'IIQQQQQQ', 1, 5, 0, 0, 0, rx_size, rx_size, page)
put(120, 'IIQQQQQQ', 1, 6, data, data, data, page, page, page)
put(176, 'IIQQQQQQ', 2, 6, data+0x600, data+0x600, data+0x600, 7*16, 7*16, 8)
strings = b'\0probe\0liblarge-shadow.so\0'
b[data+0x500:data+0x500+len(strings)] = strings
put(data+0x418, 'IBBHQQ', 1, 0x12, 0, 1, 0x200, 4)
put(data+0x300, 'IIIII', 1, 2, 1, 0, 0)
for i, pair in enumerate([(4,data+0x300),(5,data+0x500),(6,data+0x400),(10,len(strings)),(11,24),(14,7),(0,0)]):
    put(data+0x600+i*16, 'QQ', *pair)
# Branch from the first page to the last page, then return to the host caller.
last = (pages-1)*page + 0x200
put(0x200, 'I', 0x14000000 | ((last-0x200)//4))
put(last, 'II', 0x52800920, 0xd65f03c0) # mov w0,#73; ret
Path(sys.argv[1]).write_bytes(b)
