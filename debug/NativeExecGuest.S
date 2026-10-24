// Original minimal AArch64 child used by native_exec_preservation.sh.
.text
_start:
 mov x0, sp
 mov x1, #0
 adr x2, main
 mov x3, #0
 ldr x16, init_slot
 br x16
.org 0x40
main:
 stp x29, x30, [sp, #-32]!
 stp x19, x20, [sp, #16]
 mov x29, sp
 mov x19, x1
 ldr x0, [x19]
 bl print
 ldr x0, [x19, #8]
 bl print
 adr x0, env_name
 ldr x16, env_slot
 blr x16
 bl print
 sub sp, sp, #1024
 mov x0, sp
 mov x1, #1024
 ldr x16, cwd_slot
 blr x16
 bl print
 add sp, sp, #1024
 mov w0, #73
 ldp x19, x20, [sp, #16]
 ldp x29, x30, [sp], #32
 ret
print:
 stp x19, x30, [sp, #-16]!
 mov x19, x0
 ldr x16, strlen_slot
 blr x16
 mov x2, x0
 mov x1, x19
 mov x0, #1
 ldr x16, write_slot
 blr x16
 mov x0, #1
 adr x1, newline
 mov x2, #1
 ldr x16, write_slot
 blr x16
 ldp x19, x30, [sp], #16
 ret
newline: .byte 10
env_name: .asciz "EXEC_FIXTURE_VALUE"
.org 0x600
init_slot: .quad 0
strlen_slot: .quad 0
write_slot: .quad 0
env_slot: .quad 0
cwd_slot: .quad 0
