from pathlib import Path
import struct
b=bytearray(0x11800)
def put(o,f,*v):struct.pack_into('<'+f,b,o,*v)
def adr(r,source,target):
 d=target-source;return 0x10000000|((d&3)<<29)|(((d>>2)&0x7ffff)<<5)|r
b[:16]=b'\x7fELF\x02\x01\x01'+bytes(9)
put(16,'HHIQQQIHHHHHH',3,183,1,0,64,0x11000,0,64,56,3,64,7,6)
put(64,'IIQQQQQQ',1,5,0,0,0,0xc000,0xc000,0x4000)
put(120,'IIQQQQQQ',1,6,0x10000,0x10000,0x10000,0x1800,0x1800,0x4000)
put(176,'IIQQQQQQ',2,6,0x10600,0x10600,0x10600,7*16,7*16,8)
strings=b'\0JNI_OnLoad\0liblinked.so\0';b[0x10500:0x10500+len(strings)]=strings
put(0x10418,'IBBHQQ',1,0x12,0,1,0x200,0x50)
put(0x10300,'IIIII',1,2,1,0,0)
# Call crosses an original page; helper requires the original return PC and
# returns via an indirect branch. x16/x17 and NZCV must survive that return.
helper=[adr(9,0x4200,0x210),0xeb0903df,0x528000c0,0x72a00020,0xd65f03c0]
for i,w in enumerate(helper):put(0x4200+i*4,'I',w)
# An MRS is deliberately on a third page, to make all-pages mode relevant
# while ensuring no ordinary sparse-mode page covers the two functions.
put(0x8200,'II',0xd53bd040,0xd65f03c0)
dynamic=[(4,0x10300),(5,0x10500),(6,0x10400),(10,len(strings)),(11,24),(14,12),(0,0)]
for i,v in enumerate(dynamic):put(0x10600+i*16,'QQ',*v)
names=b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.shstrtab\0';b[0x10800:0x10800+len(names)]=names
rows=[(1,1,6,0x200,0x200,0x8008,0,0,4,0),(7,11,3,0x10400,0x10400,48,3,1,8,24),(15,3,3,0x10500,0x10500,len(strings),0,0,1,0),(23,5,3,0x10300,0x10300,20,2,0,8,4),(29,6,3,0x10600,0x10600,112,3,0,8,16),(38,3,0,0,0x10800,len(names),0,0,1,0)]
for i,v in enumerate(rows,1):put(0x11000+i*64,'IIQQQQIIQQ',*v)

# Include a host call and check that LR again names original guest code.
code=[0xa9bf7bfd,0xd2824690,0xd28acf11,0x94000000|((0x4200-0x20c)//4),0,0xd2824689,0xeb09021f,0,0xd28acf09,0xeb09023f,0,
 adr(8,0x22c,0x10000),0xf9400108,0xd63f0100,adr(9,0x238,0x238),0xeb0903df,0,0x7101241f,0,0x528000c0,0x72a00020,0xa8c17bfd,0xd65f03c0,0x52800000,0xa8c17bfd,0xd65f03c0]
for i in (4,7,10,16,18):code[i]=0x54000001|((23-i)<<5)
for i,w in enumerate(code):struct.pack_into('<I',b,0x200+4*i,w)

Path(__import__("sys").argv[1]).write_bytes(b)
