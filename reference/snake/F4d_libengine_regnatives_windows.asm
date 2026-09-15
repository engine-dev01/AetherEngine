=== F4d RegisterNatives site windows (decoded) ===

##### site @0xb0140  nMethods=1  table=sp+0x38 #####
  0xb0060: mov       x5, xzr
  0xb0064: mov       w6, #0xde
  0xb0068: blr       x20
  0xb006c: adrp      x8, #0x2c000
  0xb0070: mov       x24, x0
  0xb0074: add       x8, x8, #0xd20
  0xb0078: ldr       x9, [x8]
  0xb007c: ldrb      w8, [x8, #8]
  0xb0080: str       x9, [x0]
  0xb0084: strb      w8, [x0, #8]
  0xb0088: bl        #0x7775d8
  0xb008c: mov       x25, x0
  0xb0090: mov       w0, #0xc
  0xb0094: bl        #0x81f140
  0xb0098: mov       x8, #0xb4f4
  0xb009c: mov       x26, x0
  0xb00a0: movk      x8, #0x60b7, lsl #16
  0xb00a4: mov       x20, xzr
  0xb00a8: movk      x8, #0x1adb, lsl #32
  0xb00ac: str       wzr, [x0, #8]
  0xb00b0: movk      x8, #0x3026, lsl #48
  0xb00b4: str       x8, [x0]
  0xb00b8: ldrb      w0, [x24, x20]
  0xb00bc: mov       x1, x26
  0xb00c0: ldr       x8, [x25]
  0xb00c4: blr       x8
  0xb00c8: strb      w0, [x24, x20]
  0xb00cc: add       x20, x20, #1
  0xb00d0: cmp       x20, #8
  0xb00d4: b.ne      #0xb00b8
  0xb00d8: ldr       x0, [sp, #0x30]
  0xb00dc: mov       x1, x24
  0xb00e0: mov       w2, #8
  0xb00e4: bl        #0x81f250
  0xb00e8: cbnz      w0, #0xb0120
  0xb00ec: adrp      x8, #0x828000
  0xb00f0: ldrsw     x9, [x8, #0xd98]
  0xb00f4: ldr       w8, [x22, x9]
  0xb00f8: tbz       w8, #8, #0xb011c
  0xb00fc: adrp      x10, #0x828000
  0xb0100: ldr       w10, [x10, #0xd90]
  0xb0104: cmp       w10, #0x1b
  0xb0108: b.gt      #0xb011c
  0xb010c: and       w10, w8, #0xfff7ffff
  0xb0110: cmp       w10, w8
  0xb0114: b.eq      #0xb011c
  0xb0118: str       w10, [x22, x9]
  0xb011c: tbz       w8, #8, #0xb0190
  0xb0120: ldrsw     x8, [x27, #0xda0]
  0xb0124: add       x2, sp, #0x38
  0xb0128: mov       x0, x19
  0xb012c: mov       x1, x28
  0xb0130: mov       w3, #1
  0xb0134: ldr       x8, [x22, x8, lsl #3]
  0xb0138: str       x8, [x23]
  0xb013c: ldr       x8, [x19]
  0xb0140: ldr       x8, [x8, #0x6b8]
  0xb0144: blr       x8
  0xb0148: tbnz      w0, #0x1f, #0xb0190
  0xb014c: adrp      x8, #0x828000
  0xb0150: ldr       w8, [x8, #0xd90]
  0xb0154: and       w8, w8, #0xfffffffe
  0xb0158: cmp       w8, #0x1a
  0xb015c: b.ne      #0xb0190
  0xb0160: adrp      x8, #0x828000
  0xb0164: ldrsw     x8, [x8, #0xd98]
  0xb0168: ldr       w10, [x22, x8]
  0xb016c: orr       w9, w10, #0x80000
  0xb0170: cmp       w9, w10
  0xb0174: b.eq      #0xb0190
  0xb0178: str       w9, [x22, x8]
  0xb017c: b         #0xb0190
  0xb0180: ldr       x8, [x19]
  0xb0184: ldr       x8, [x8, #0x88]
  0xb0188: mov       x0, x19
  0xb018c: blr       x8
  0xb0190: ldr       x8, [x21, #0x28]
  0xb0194: ldur      x9, [x29, #-8]
  0xb0198: cmp       x8, x9
  0xb019c: b.ne      #0xb01c0

##### site @0xb40a8  nMethods=2  table=sp+0x20 #####
  0xb3fc8: mov       w8, #0x4ef2
  0xb3fcc: adrp      x26, #0x836000
  0xb3fd0: movk      w8, #0x338, lsl #16
  0xb3fd4: stur      x8, [x29, #-0x40]
  0xb3fd8: ldur      x8, [x29, #-0x40]
  0xb3fdc: ldr       x0, [x8]
  0xb3fe0: mov       x8, #0x35
  0xb3fe4: svc       #0
  0xb3fe8: b         #0xb3ff8
  0xb3fec: ldp       x25, x24, [sp, #0x10]
  0xb3ff0: ldp       x19, x20, [sp]
  0xb3ff4: adrp      x8, #0x10000
  0xb3ff8: stur      w27, [x29, #-0x38]
  0xb3ffc: add       x1, x19, #0xc
  0xb4000: mov       x0, x19
  0xb4004: ldr       d0, [x8, #0xc20]
  0xb4008: stur      d0, [x29, #-0x40]
  0xb400c: str       w27, [x19, #8]
  0xb4010: ldur      x8, [x29, #-0x40]
  0xb4014: str       x8, [x19]
  0xb4018: bl        #0x81ad58
  0xb401c: str       x19, [x26, #0xbf8]
  0xb4020: mov       x0, xzr
  0xb4024: mov       w1, #4
  0xb4028: mov       w2, #7
  0xb402c: mov       w3, #0x22
  0xb4030: mov       x4, #-1
  0xb4034: mov       x5, xzr
  0xb4038: mov       w6, #0xde
  0xb403c: blr       x19
  0xb4040: mov       w8, #0xdcdc
  0xb4044: mov       x21, x0
  0xb4048: movk      w8, #0xa0, lsl #16
  0xb404c: str       w8, [x0]
  0xb4050: bl        #0x777fb0
  0xb4054: mov       x22, x0
  0xb4058: mov       w0, #0xc
  0xb405c: bl        #0x81f140
  0xb4060: mov       x23, x0
  0xb4064: mov       x19, xzr
  0xb4068: str       x28, [x0]
  0xb406c: str       wzr, [x0, #8]
  0xb4070: ldrb      w0, [x21, x19]
  0xb4074: mov       x1, x23
  0xb4078: ldr       x8, [x22]
  0xb407c: blr       x8
  0xb4080: strb      w0, [x21, x19]
  0xb4084: add       x19, x19, #1
  0xb4088: cmp       x19, #3
  0xb408c: b.ne      #0xb4070
  0xb4090: ldr       x8, [x25]
  0xb4094: adrp      x9, #0x81e000
  0xb4098: add       x9, x9, #0xeb0
  0xb409c: add       x2, sp, #0x20
  0xb40a0: mov       x0, x25
  0xb40a4: mov       x1, x20
  0xb40a8: ldr       x8, [x8, #0x6b8]
  0xb40ac: mov       w3, #2
  0xb40b0: stp       x21, x9, [sp, #0x40]
  0xb40b4: blr       x8
  0xb40b8: ldr       x8, [x24, #0x28]
  0xb40bc: ldur      x9, [x29, #-8]
  0xb40c0: cmp       x8, x9
  0xb40c4: b.ne      #0xb40e8
  0xb40c8: ldp       x20, x19, [sp, #0xe0]
  0xb40cc: ldp       x22, x21, [sp, #0xd0]
  0xb40d0: ldp       x24, x23, [sp, #0xc0]
  0xb40d4: ldp       x26, x25, [sp, #0xb0]
  0xb40d8: ldp       x28, x27, [sp, #0xa0]
  0xb40dc: ldp       x29, x30, [sp, #0x90]
  0xb40e0: add       sp, sp, #0xf0
  0xb40e4: ret       
  0xb40e8: bl        #0x81eff0
  0xb40ec: sub       sp, sp, #0xe0
  0xb40f0: stp       x29, x30, [sp, #0x80]
  0xb40f4: add       x29, sp, #0x80
  0xb40f8: stp       x28, x27, [sp, #0x90]
  0xb40fc: stp       x26, x25, [sp, #0xa0]
  0xb4100: stp       x24, x23, [sp, #0xb0]
  0xb4104: stp       x22, x21, [sp, #0xc0]

##### site @0xf3a08  nMethods=10  table=0x828ee8 #####
  0xf3928: mov       x0, x19
  0xf392c: ldr       d0, [x8, #0xc20]
  0xf3930: str       w9, [sp, #0x28]
  0xf3934: str       d0, [sp, #0x20]
  0xf3938: str       w9, [x19, #8]
  0xf393c: ldr       x8, [sp, #0x20]
  0xf3940: str       x8, [x19]
  0xf3944: bl        #0x81ad58
  0xf3948: str       x19, [x20, #0xbf8]
  0xf394c: mov       x0, xzr
  0xf3950: mov       w1, #0x18
  0xf3954: mov       w2, #7
  0xf3958: mov       w3, #0x22
  0xf395c: mov       x4, #-1
  0xf3960: mov       x5, xzr
  0xf3964: mov       w6, #0xde
  0xf3968: blr       x19
  0xf396c: adrp      x8, #0x2e000
  0xf3970: mov       x20, x0
  0xf3974: add       x8, x8, #0x7d0
  0xf3978: ldr       q0, [x8]
  0xf397c: ldr       x8, [x8, #0x10]
  0xf3980: str       q0, [x0]
  0xf3984: str       x8, [x0, #0x10]
  0xf3988: bl        #0x7778a8
  0xf398c: mov       x21, x0
  0xf3990: mov       w0, #0xc
  0xf3994: bl        #0x81f140
  0xf3998: mov       x8, #0xb4f4
  0xf399c: mov       x22, x0
  0xf39a0: movk      x8, #0x60b7, lsl #16
  0xf39a4: mov       x19, xzr
  0xf39a8: movk      x8, #0x1adb, lsl #32
  0xf39ac: str       wzr, [x0, #8]
  0xf39b0: movk      x8, #0x3026, lsl #48
  0xf39b4: str       x8, [x0]
  0xf39b8: ldrb      w0, [x20, x19]
  0xf39bc: mov       x1, x22
  0xf39c0: ldr       x8, [x21]
  0xf39c4: blr       x8
  0xf39c8: strb      w0, [x20, x19]
  0xf39cc: add       x19, x19, #1
  0xf39d0: cmp       x19, #0x17
  0xf39d4: b.ne      #0xf39b8
  0xf39d8: ldr       x8, [x24]
  0xf39dc: mov       x0, x24
  0xf39e0: mov       x1, x20
  0xf39e4: ldr       x8, [x8, #0x30]
  0xf39e8: blr       x8
  0xf39ec: cbz       x0, #0xf3a1c
  0xf39f0: ldr       x8, [x24]
  0xf39f4: adrp      x2, #0x828000
  0xf39f8: mov       x1, x0
  0xf39fc: add       x2, x2, #0xee8
  0xf3a00: mov       x0, x24
  0xf3a04: mov       w3, #0xa
  0xf3a08: ldr       x8, [x8, #0x6b8]
  0xf3a0c: blr       x8
  0xf3a10: tbnz      w0, #0x1f, #0xf3a1c
  0xf3a14: mov       w0, #1
  0xf3a18: b         #0xf3a20
  0xf3a1c: mov       w0, wzr
  0xf3a20: ldr       x8, [x23, #0x28]
  0xf3a24: ldur      x9, [x29, #-8]
  0xf3a28: cmp       x8, x9
  0xf3a2c: b.ne      #0xf3a50
  0xf3a30: ldp       x20, x19, [sp, #0xb0]
  0xf3a34: ldp       x22, x21, [sp, #0xa0]
  0xf3a38: ldp       x24, x23, [sp, #0x90]
  0xf3a3c: ldp       x26, x25, [sp, #0x80]
  0xf3a40: ldp       x28, x27, [sp, #0x70]
  0xf3a44: ldp       x29, x30, [sp, #0x60]
  0xf3a48: add       sp, sp, #0xc0
  0xf3a4c: ret       
  0xf3a50: bl        #0x81eff0
  0xf3a54: sub       sp, sp, #0x50
  0xf3a58: stp       x29, x30, [sp, #0x20]
  0xf3a5c: add       x29, sp, #0x20
  0xf3a60: str       x21, [sp, #0x30]
  0xf3a64: stp       x20, x19, [sp, #0x40]
