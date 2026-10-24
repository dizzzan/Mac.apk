from pathlib import Path
s=['.text','.p2align 2','.globl _shadow_register_probe','_shadow_register_probe:','sub sp, sp, #2048']
# Save host state, inputs and all ABI-preserved registers outside snapshots.
s += ['str x0, [sp, #1600]','str x1, [sp, #1608]','str x2, [sp, #1616]','str x3, [sp, #1624]']
for r in range(18,31): s += [f'str x{r}, [sp, #{1664+(r-18)*8}]']
for r in range(8,16): s += [f'str q{r}, [sp, #{1792+(r-8)*16}]']
s += ['mrs x9, fpcr','str x9, [sp, #1920]','mrs x9, fpsr','str x9, [sp, #1928]']
for r in range(31): s += [f'mov x{r}, #{0x100+r}']
for r in range(32): s += [f'movi v{r}.16b, #{r+1}']
s += ['mov x9, #0x400000','msr fpcr, x9','mov x9, #1','movk x9, #0x0800, lsl #16','msr fpsr, x9','mov x9, #0xa0000000','msr nzcv, x9','mov x9, #0x109']
def snapshot(base):
 for r in range(31):s.append(f'str x{r}, [sp, #{base+r*8}]')
 for r in range(32):s.append(f'str q{r}, [sp, #{base+256+r*16}]')
 for j,n in enumerate(('nzcv','fpcr','fpsr')):s.extend([f'mrs x9, {n}',f'str x9, [sp, #{base+768+j*8}]'])
snapshot(0)
s += ['mov x9, #0x109','ldr x16, [sp, #1624]', 'ldr x30, [sp, #1600]', 'stp x16, x30, [sp, #-16]!', 'blr x30', 'add sp, sp, #16']
snapshot(800)
s += ['ldr x0, [sp, #1608]','ldr x1, [sp, #1616]']
for i in range(0,800,16):s += [f'ldp x2,x3,[sp,#{i}]',f'stp x2,x3,[x0,#{i}]',f'ldp x2,x3,[sp,#{800+i}]',f'stp x2,x3,[x1,#{i}]'] if i<=496 else [f'ldr x2,[sp,#{i}]',f'str x2,[x0,#{i}]',f'ldr x2,[sp,#{i+8}]',f'str x2,[x0,#{i+8}]',f'ldr x2,[sp,#{800+i}]',f'str x2,[x1,#{i}]',f'ldr x2,[sp,#{808+i}]',f'str x2,[x1,#{i+8}]']
# ldp immediate has a 504-byte limit, including the source offset for after.
s=[line for line in s]
s2=[]
import re
for line in s:
 m=re.fullmatch(r'ldp x2,x3,\[sp,#(\d+)\]',line)
 if m and int(m[1])>504:s2.extend([f'ldr x2,[sp,#{m[1]}]',f'ldr x3,[sp,#{int(m[1])+8}]'])
 else:s2.append(line)
s=s2
s += ['ldr x9, [sp, #1920]','msr fpcr, x9','ldr x9, [sp, #1928]','msr fpsr, x9']
for r in range(18,31):s += [f'ldr x{r}, [sp, #{1664+(r-18)*8}]']
for r in range(8,16):s += [f'ldr q{r}, [sp, #{1792+(r-8)*16}]']
s += ['add sp, sp, #2048','ret']
Path(__import__('sys').argv[1]).write_text('\n'.join(s)+'\n')
