=== F4b .mytext   addr=0x81eeac  size=0xf4  file offset 0x81eeac ===
A hand-named executable section that no compiler emits; it sits between .text and .plt.

--- JNIEnv slots referenced inside .mytext ---
  0x81eed0: [JNIEnv + 0x38] -> FromReflectedMethod

--- decoded instructions ---
  0x81eeac: ret       
  0x81eeb0: ret       
  0x81eeb4: stp       x29, x30, [sp, #-0x20]!
  0x81eeb8: stp       x20, x19, [sp, #0x10]
  0x81eebc: mov       x29, sp
  0x81eec0: ldr       x8, [x0]
  0x81eec4: mov       x1, x3
  0x81eec8: mov       x19, x2
  0x81eecc: mov       x20, x0
  0x81eed0: ldr       x8, [x8, #0x38]
  0x81eed4: blr       x8
  0x81eed8: mov       x2, x0
  0x81eedc: mov       x0, x20
  0x81eee0: mov       x1, x19
  0x81eee4: bl        #0xb01c4
  0x81eee8: adrp      x8, #0x828000
  0x81eeec: ldrsw     x8, [x8, #0xd98]
  0x81eef0: ldr       w9, [x0, x8]
  0x81eef4: orr       w10, w9, #1
  0x81eef8: cmp       w10, w9
  0x81eefc: b.eq      #0x81ef08
  0x81ef00: mov       w9, w10
  0x81ef04: str       w10, [x0, x8]
  0x81ef08: adrp      x10, #0x828000
  0x81ef0c: ldr       w10, [x10, #0xd90]
  0x81ef10: cmp       w10, #0x1d
  0x81ef14: b.lt      #0x81ef28
  0x81ef18: orr       w10, w9, #0x10000000
  0x81ef1c: cmp       w10, w9
  0x81ef20: b.eq      #0x81ef28
  0x81ef24: str       w10, [x0, x8]
  0x81ef28: ldp       x20, x19, [sp, #0x10]
  0x81ef2c: ldp       x29, x30, [sp], #0x20
  0x81ef30: ret       
  0x81ef34: stp       x29, x30, [sp, #-0x10]!
  0x81ef38: mov       x29, sp
  0x81ef3c: mov       x1, x3
  0x81ef40: bl        #0xb134c
  0x81ef44: adrp      x8, #0x828000
  0x81ef48: ldrsw     x8, [x8, #0xd98]
  0x81ef4c: ldr       w9, [x0, x8]
  0x81ef50: orr       w10, w9, #1
  0x81ef54: cmp       w10, w9
  0x81ef58: b.eq      #0x81ef64
  0x81ef5c: mov       w9, w10
  0x81ef60: str       w10, [x0, x8]
  0x81ef64: adrp      x10, #0x828000
  0x81ef68: ldr       w10, [x10, #0xd90]
  0x81ef6c: cmp       w10, #0x1d
  0x81ef70: b.lt      #0x81ef88
  0x81ef74: orr       w10, w9, #0x10000000
  0x81ef78: cmp       w10, w9
  0x81ef7c: b.eq      #0x81ef88
  0x81ef80: mov       w9, w10
  0x81ef84: str       w10, [x0, x8]
  0x81ef88: and       w10, w9, #0xffffffef
  0x81ef8c: cmp       w10, w9
  0x81ef90: b.eq      #0x81ef98
  0x81ef94: str       w10, [x0, x8]
  0x81ef98: ldp       x29, x30, [sp], #0x10
  0x81ef9c: ret       
