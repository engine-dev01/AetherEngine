=== JNI_OnLoad @0xf3fa0 — first 420 decoded instructions ===
0xf3fa0: sub       sp, sp, #0xb0
0xf3fa4: stp       x29, x30, [sp, #0x50]
0xf3fa8: add       x29, sp, #0x50
0xf3fac: stp       x28, x27, [sp, #0x60]
0xf3fb0: stp       x26, x25, [sp, #0x70]
0xf3fb4: stp       x24, x23, [sp, #0x80]
0xf3fb8: stp       x22, x21, [sp, #0x90]
0xf3fbc: stp       x20, x19, [sp, #0xa0]
0xf3fc0: mrs       x8, tpidr_el0
0xf3fc4: mov       w0, #0x27
0xf3fc8: stp       x1, x8, [sp]
0xf3fcc: mov       w23, #-0x2be00000
0xf3fd0: ldr       x8, [x8, #0x28]
0xf3fd4: stur      x8, [x29, #-8]
0xf3fd8: bl        #0x81f110
0xf3fdc: lsr       x8, x0, #2
0xf3fe0: adrp      x26, #0x12000
0xf3fe4: cmp       x8, #1
0xf3fe8: mov       x20, x0
0xf3fec: mov       x22, xzr
0xf3ff0: csinc     x24, x8, xzr, hi
0xf3ff4: add       x25, sp, #0x10
0xf3ff8: add       x26, x26, #0x5d4
0xf3ffc: mov       x0, xzr
0xf4000: mov       x1, x20
0xf4004: mov       w2, #7
0xf4008: mov       w3, #0x22
0xf400c: mov       x4, #-1
0xf4010: mov       x5, xzr
0xf4014: mov       w8, #0xde
0xf4018: svc       #0
0xf401c: cmn       x0, #1
0xf4020: str       x0, [x25, x22, lsl #3]
0xf4024: b.eq      #0xf40b0
0xf4028: mov       x21, x0
0xf402c: cmp       x20, #4
0xf4030: b.lo      #0xf4088
0xf4034: mov       x27, xzr
0xf4038: and       x9, x27, #3
0xf403c: mov       w8, #-1
0xf4040: mov       w28, #0x14000000
0xf4044: mov       w19, #0x3ffffff
0xf4048: adr       x10, #0xf4048
0xf404c: ldrsw     x11, [x26, x9, lsl #2]
0xf4050: add       x10, x10, x11
0xf4054: br        x10
0xf4058: mov       w19, #0xffff
0xf405c: mov       w28, #-0x2be00000
0xf4060: b         #0xf406c
0xf4064: add       w28, w23, #0x3e0, lsl #12
0xf4068: mov       w19, #0xffff
0xf406c: bl        #0x81f120
0xf4070: and       w8, w0, w19
0xf4074: orr       w8, w8, w28
0xf4078: str       w8, [x21, x27, lsl #2]
0xf407c: add       x27, x27, #1
0xf4080: cmp       x24, x27
0xf4084: b.ne      #0xf4038
0xf4088: cbz       x22, #0xf40a4
0xf408c: sub       w8, w22, #1
0xf4090: mov       w10, #0x14000000
0xf4094: ldr       x8, [x25, w8, uxtw #3]
0xf4098: sub       w9, w21, w8
0xf409c: bfxil     w10, w9, #2, #0x1a
0xf40a0: str       w10, [x8]
0xf40a4: add       x1, x21, x20
0xf40a8: mov       x0, x21
0xf40ac: bl        #0x81ad58
0xf40b0: add       x22, x22, #1
0xf40b4: cmp       x22, #7
0xf40b8: b.ne      #0xf3ffc
0xf40bc: mov       x19, #0x122
0xf40c0: mov       x20, #0x91
0xf40c4: ldr       x8, [sp, #0x10]
0xf40c8: movk      x19, #0x348, lsl #16
0xf40cc: movk      x20, #0x1a4, lsl #16
0xf40d0: movk      x19, #0x1a4, lsl #32
0xf40d4: movk      x20, #0x80d2, lsl #32
0xf40d8: movk      x19, #0xa405, lsl #48
0xf40dc: movk      x20, #0x5202, lsl #48
0xf40e0: blr       x8
0xf40e4: mov       w0, #0x27
0xf40e8: bl        #0x81f110
0xf40ec: adrp      x13, #0x10000
0xf40f0: mov       w11, #0xa9
0xf40f4: mov       w12, #2
0xf40f8: mov       x1, x0
0xf40fc: mov       x0, xzr
0xf4100: mov       w8, #0xde
0xf4104: mov       x5, xzr
0xf4108: mov       x9, xzr
0xf410c: mov       x10, xzr
0xf4110: mov       w2, #7
0xf4114: mov       w3, #0x22
0xf4118: mov       x4, #-1
0xf411c: svc       #0
0xf4120: mov       x8, x0
0xf4124: ldr       q0, [x13, #0xa70]
0xf4128: dup       v1.2d, x11
0xf412c: dup       v2.2d, x12
0xf4130: cmhi      v3.2d, v1.2d, v0.2d
0xf4134: xtn       v3.2s, v3.2d
0xf4138: fmov      w12, s3
0xf413c: tbz       w12, #0, #0xf4144
0xf4140: str       x10, [x8, x9]
0xf4144: dup       v3.2d, x11
0xf4148: cmhi      v3.2d, v3.2d, v0.2d
0xf414c: xtn       v3.2s, v3.2d
0xf4150: mov       w12, v3.s[1]
0xf4154: tbz       w12, #0, #0xf4164
0xf4158: add       x12, x10, x20
0xf415c: add       x13, x8, x9
0xf4160: str       x12, [x13, #8]
0xf4164: add       v0.2d, v0.2d, v2.2d
0xf4168: add       x10, x10, x19
0xf416c: add       x9, x9, #0x10
0xf4170: cmp       x9, #0x550
0xf4174: b.ne      #0xf4130
0xf4178: adrp      x9, #0x828000
0xf417c: adrp      x10, #0x828000
0xf4180: mov       x13, #0x80d2
0xf4184: adrp      x22, #0x828000
0xf4188: movk      x13, #0xe31f, lsl #16
0xf418c: adrp      x21, #0x828000
0xf4190: ldr       x9, [x9, #0x48]
0xf4194: movk      x13, #0xd1, lsl #32
0xf4198: mov       x12, #0x702
0xf419c: mov       x4, #0x94
0xf41a0: ldr       x10, [x10, #0x50]
0xf41a4: movk      x13, #0x2240, lsl #48
0xf41a8: movk      x12, #0xa850, lsl #16
0xf41ac: movk      x4, #0xd105, lsl #16
0xf41b0: mov       x14, #0x51d5
0xf41b4: movk      x12, #0xe038, lsl #32
0xf41b8: lsl       x9, x13, x9
0xf41bc: ldr       x13, [x22, #0x60]
0xf41c0: movk      x4, #0xaa, lsl #32
0xf41c4: mov       x5, #0x9bd2
0xf41c8: movk      x14, #0x4381, lsl #16
0xf41cc: ldr       x11, [x21, #0x58]
0xf41d0: movk      x12, #0x452d, lsl #48
0xf41d4: mov       x3, #0x9a9
0xf41d8: movk      x4, #0xf403, lsl #48
0xf41dc: movk      x5, #0xa9d5, lsl #16
0xf41e0: movk      x14, #0x5ae, lsl #32
0xf41e4: orr       x12, x10, x12
0xf41e8: movk      x3, #0xfd7b, lsl #16
0xf41ec: movk      x5, #0xd1d3, lsl #32
0xf41f0: mov       x15, #0xff97
0xf41f4: movk      x14, #0xdf22, lsl #48
0xf41f8: str       x9, [x8, #0x550]
0xf41fc: lsl       x9, x4, x13
0xf4200: movk      x3, #0x8a9, lsl #32
0xf4204: movk      x5, #0xeb03, lsl #48
0xf4208: movk      x15, #0x75fc, lsl #16
0xf420c: mov       x16, #0x2d1
0xf4210: lsl       x14, x14, x11
0xf4214: adrp      x24, #0x828000
0xf4218: mov       x1, #0xb94
0xf421c: movk      x3, #0xf44f, lsl #48
0xf4220: movk      x15, #0x14aa, lsl #32
0xf4224: movk      x16, #0xff83, lsl #16
0xf4228: str       x12, [x8, #0x558]
0xf422c: orr       x12, x10, x5
0xf4230: movk      x1, #0x77fb, lsl #16
0xf4234: movk      x15, #0xe003, lsl #48
0xf4238: movk      x16, #0xc94, lsl #32
0xf423c: mov       x7, #0x13aa
0xf4240: str       x9, [x8, #0x568]
0xf4244: lsl       x9, x3, x13
0xf4248: movk      x1, #0x139, lsl #32
0xf424c: mov       x2, #0x50b5
0xf4250: movk      x16, #0xc4f2, lsl #48
0xf4254: movk      x7, #0xe003, lsl #16
0xf4258: str       x14, [x8, #0x560]
0xf425c: lsl       x14, x15, x11
0xf4260: ldr       x15, [x24, #0x68]
0xf4264: movk      x1, #0xff7f, lsl #48
0xf4268: mov       x0, #0x1a9
0xf426c: movk      x2, #0xe6e8, lsl #16
0xf4270: movk      x7, #0x91, lsl #32
0xf4274: str       x12, [x8, #0x570]
0xf4278: orr       x12, x10, x16
0xf427c: movk      x0, #0xf54f, lsl #16
0xf4280: movk      x2, #0xfe97, lsl #32
0xf4284: movk      x7, #0x94c2, lsl #48
0xf4288: mov       x25, #0x13aa
0xf428c: str       x9, [x8, #0x580]
0xf4290: lsl       x9, x1, x13
0xf4294: movk      x0, #0x1faa, lsl #32
0xf4298: movk      x2, #0xe6ed, lsl #48
0xf429c: mov       x6, #0x7ed3
0xf42a0: movk      x25, #0xe303, lsl #16
0xf42a4: str       x14, [x8, #0x578]
0xf42a8: lsl       x14, x7, x15
0xf42ac: movk      x0, #0xf603, lsl #48
0xf42b0: movk      x6, #0xc7f4, lsl #16
0xf42b4: movk      x25, #0x8052, lsl #32
0xf42b8: str       x12, [x8, #0x588]
0xf42bc: lsl       x12, x2, x11
0xf42c0: movk      x6, #0xe8ca, lsl #32
0xf42c4: movk      x25, #0x108, lsl #48
0xf42c8: str       x9, [x8, #0x598]
0xf42cc: lsl       x9, x0, x15
0xf42d0: mov       x0, #0xa9
0xf42d4: mov       x17, #0x40b9
0xf42d8: movk      x6, #0xa6e6, lsl #48
0xf42dc: str       x14, [x8, #0x590]
0xf42e0: lsl       x14, x25, x13
0xf42e4: movk      x0, #0xff7f, lsl #16
0xf42e8: movk      x17, #0xb42a, lsl #16
0xf42ec: str       x12, [x8, #0x5a0]
0xf42f0: orr       x12, x10, x6
0xf42f4: movk      x0, #0x15aa, lsl #32
0xf42f8: movk      x17, #0xd17, lsl #32
0xf42fc: movk      x0, #0xe103, lsl #48
0xf4300: movk      x17, #0xe6e8, lsl #48
0xf4304: str       x14, [x8, #0x5a8]
0xf4308: str       x9, [x8, #0x5b0]
0xf430c: lsl       x9, x0, x15
0xf4310: adrp      x14, #0x828000
0xf4314: str       x12, [x8, #0x5b8]
0xf4318: lsl       x12, x17, x11
0xf431c: adrp      x17, #0x828000
0xf4320: str       x9, [x8, #0x5c0]
0xf4324: adrp      x0, #0x828000
0xf4328: ldr       x9, [x14, #0x70]
0xf432c: and       x14, x10, x16
0xf4330: str       x12, [x8, #0x5c8]
0xf4334: ldr       x12, [x17, #0x78]
0xf4338: adrp      x17, #0x828000
0xf433c: and       x13, x13, x16
0xf4340: orr       x9, x9, x14
0xf4344: ldr       x14, [x0, #0x80]
0xf4348: and       x9, x9, x12
0xf434c: orr       x12, x12, x13
0xf4350: ldr       x13, [x17, #0x88]
0xf4354: and       x15, x15, x16
0xf4358: and       x11, x11, x16
0xf435c: and       x12, x14, x12
0xf4360: orr       x14, x14, x15
0xf4364: str       x9, [x8, #0x5d0]
0xf4368: orr       x11, x13, x11
0xf436c: and       x14, x13, x14
0xf4370: and       x9, x11, x10
0xf4374: mov       x25, #0x52b2
0xf4378: mov       x27, #0x5221
0xf437c: mov       x19, #0xefa0
0xf4380: mov       x28, #0x5343
0xf4384: mov       x20, #0x4767
0xf4388: movk      x25, #0xab6, lsl #16
0xf438c: movk      x27, #0xf33, lsl #16
0xf4390: movk      x19, #0xb1b0, lsl #16
0xf4394: movk      x28, #0x5e79, lsl #16
0xf4398: movk      x20, #0x9a9d, lsl #16
0xf439c: movk      x25, #0x6174, lsl #32
0xf43a0: movk      x27, #0x2dfd, lsl #32
0xf43a4: movk      x19, #0x7218, lsl #32
0xf43a8: movk      x28, #0x1381, lsl #32
0xf43ac: movk      x20, #0x8ba0, lsl #32
0xf43b0: mov       w26, #0x622c
0xf43b4: movk      x25, #0xe5b6, lsl #48
0xf43b8: str       x12, [x8, #0x5d8]
0xf43bc: movk      x27, #0x7f31, lsl #48
0xf43c0: str       x14, [x8, #0x5e0]
0xf43c4: movk      x19, #0x75b1, lsl #48
0xf43c8: str       x9, [x8, #0x5e8]
0xf43cc: movk      x28, #0x4adb, lsl #48
0xf43d0: movk      x20, #0x157d, lsl #48
0xf43d4: movk      w26, #0xbc72, lsl #16
0xf43d8: mov       x0, x0
0xf43dc: mov       x1, x1
0xf43e0: mov       x2, x2
0xf43e4: mov       x3, x3
0xf43e8: mov       x4, x4
0xf43ec: mov       x5, x5
0xf43f0: mov       x6, x8
0xf43f4: blr       x8
0xf43f8: cbz       x0, #0xf4430
0xf43fc: adrp      x8, #0x828000
0xf4400: str       x27, [x22, #0x60]
0xf4404: str       x25, [x24, #0x68]
0xf4408: str       x28, [x21, #0x58]
0xf440c: str       x20, [x8, #0x48]
0xf4410: adrp      x8, #0x828000
0xf4414: str       x19, [x8, #0x50]
0xf4418: str       x26, [sp, #0x10]
0xf441c: ldr       x8, [sp, #0x10]
0xf4420: ldr       x0, [x8]
0xf4424: mov       x8, #0x35
0xf4428: svc       #0
0xf442c: b         #0xf443c
0xf4430: mov       w8, #0x3445
0xf4434: mov       x27, #0xefa0
0xf4438: movk      w8, #0x9177, lsl #16
0xf443c: movk      x27, #0xb1b0, lsl #16
0xf4440: movk      x27, #0x7218, lsl #32
0xf4444: movk      x27, #0x75b1, lsl #48
0xf4448: str       x8, [sp, #0x10]
0xf444c: ldr       x8, [sp, #0x10]
0xf4450: ldr       x0, [x8]
0xf4454: mov       x8, #0x35
0xf4458: svc       #0
0xf445c: b         #0xf446c
0xf4460: ldr       x19, [sp]
0xf4464: cbz       x19, #0xf5984
0xf4468: mov       x0, x19
0xf446c: bl        #0x81f0e0
0xf4470: cmp       x0, #0xb
0xf4474: b.ne      #0xf5984
0xf4478: mov       x13, #0x2493
0xf447c: adrp      x1, #0x2e000
0xf4480: movk      x13, #0x9249, lsl #16
0xf4484: mov       w8, wzr
0xf4488: movk      x13, #0x4924, lsl #32
0xf448c: mov       x9, xzr
0xf4490: mov       x15, xzr
0xf4494: mov       w10, #1
0xf4498: mov       w11, #0x32
0xf449c: mov       w12, #0xab
0xf44a0: movk      x13, #0x2492, lsl #48
0xf44a4: mov       w14, #0x25
0xf44a8: mov       w16, #0x12
0xf44ac: mov       w17, #0xcd
0xf44b0: mov       w0, #0x34
0xf44b4: add       x1, x1, #0x890
0xf44b8: and       x2, x15, #3
0xf44bc: cmp       x2, #2
0xf44c0: b.eq      #0xf451c
0xf44c4: cmp       x2, #1
0xf44c8: b.eq      #0xf44fc
0xf44cc: cbnz      x2, #0xf4540
0xf44d0: ldr       x2, [x1, x15, lsl #3]
0xf44d4: ldrb      w4, [x19, x15]
0xf44d8: eor       x2, x9, x2
0xf44dc: eor       x2, x2, x12
0xf44e0: umulh     x3, x2, x13
0xf44e4: sub       w2, w2, w3
0xf44e8: add       w2, w3, w2, lsr #1
0xf44ec: lsr       w2, w2, #2
0xf44f0: cmp       w4, w2, uxtb
0xf44f4: b.eq      #0xf4594
0xf44f8: b         #0xf45b4
0xf44fc: ldr       x2, [x1, x15, lsl #3]
0xf4500: ldrb      w3, [x19, x15]
0xf4504: add       w2, w8, w2
0xf4508: and       w2, w2, #0xff
0xf450c: eor       w2, w2, w11
0xf4510: cmp       w2, w3
0xf4514: b.eq      #0xf4594
0xf4518: b         #0xf45b4
0xf451c: ldr       x2, [x1, x15, lsl #3]
0xf4520: add       w4, w15, #0xef
0xf4524: ldrb      w3, [x19, x15]
0xf4528: eor       w2, w2, w4
0xf452c: lsl       w4, w2, #6
0xf4530: orr       w2, w4, w2, lsr #2
0xf4534: cmp       w3, w2, uxtb
0xf4538: b.eq      #0xf4594
0xf453c: b         #0xf45b4
0xf4540: and       w2, w15, #0xff
0xf4544: ldrb      w5, [x19, x15]
0xf4548: mul       w3, w2, w14
0xf454c: mul       w2, w2, w17
0xf4550: sub       w4, w15, w3, lsr #8
0xf4554: ubfx      w4, w4, #1, #7
0xf4558: add       w3, w4, w3, lsr #8
0xf455c: lsr       w4, w2, #0xa
0xf4560: lsr       w3, w3, #2
0xf4564: lsl       w4, w4, #2
0xf4568: add       w2, w4, w2, lsr #10
0xf456c: ldr       x4, [x1, x15, lsl #3]
0xf4570: sub       w3, w3, w3, lsl #3
0xf4574: sub       w2, w15, w2
0xf4578: add       w3, w15, w3
0xf457c: lsl       w2, w0, w2
0xf4580: lsr       w3, w16, w3
0xf4584: eor       w2, w2, w3
0xf4588: eor       w2, w2, w4
0xf458c: cmp       w5, w2, uxtb
0xf4590: b.ne      #0xf45b4
0xf4594: add       x2, x15, #1
0xf4598: cmp       x15, #0xa
0xf459c: cset      w10, lo
0xf45a0: add       x9, x9, #3
0xf45a4: sub       w8, w8, #5
0xf45a8: mov       x15, x2
0xf45ac: cmp       x2, #0xb
0xf45b0: b.ne      #0xf44b8
0xf45b4: tbnz      w10, #0, #0xf5984
0xf45b8: mov       x0, x19
0xf45bc: bl        #0x81f0e0
0xf45c0: cmp       x0, #0xa
0xf45c4: b.ne      #0xf5984
0xf45c8: mov       x13, #0x2493
0xf45cc: adrp      x1, #0x2e000
0xf45d0: movk      x13, #0x9249, lsl #16
0xf45d4: mov       w8, wzr
0xf45d8: movk      x13, #0x4924, lsl #32
0xf45dc: mov       x9, xzr
0xf45e0: mov       x15, xzr
0xf45e4: mov       w10, #1
0xf45e8: mov       w11, #0x32
0xf45ec: mov       w12, #0xab
0xf45f0: movk      x13, #0x2492, lsl #48
0xf45f4: mov       w14, #0x25
0xf45f8: mov       w16, #0x12
0xf45fc: mov       w17, #0xcd
0xf4600: mov       w0, #0x34
0xf4604: add       x1, x1, #0x8e8
0xf4608: and       x2, x15, #3
0xf460c: cmp       x2, #2
0xf4610: b.eq      #0xf466c
0xf4614: cmp       x2, #1
0xf4618: b.eq      #0xf464c
0xf461c: cbnz      x2, #0xf4690
0xf4620: ldr       x2, [x1, x15, lsl #3]
0xf4624: ldrb      w4, [x19, x15]
0xf4628: eor       x2, x9, x2
0xf462c: eor       x2, x2, x12
