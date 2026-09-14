# CUTS — native/คลาสที่ถูกตัด พร้อมเหตุผล + วันที่ (audit C5-uncalled)

กติกา: ทุก []CUT] ต้องอยู่ที่นี่; gate พิมพ์ NO-OP/CUT ไม่ใช่ PASS (blueprint B3:
snake รันด้วย virtual mechanism ไม่ใช่การซ่อน/ตรวจ)

| รายการ | วันที่ | เหตุผล | หลักฐาน |
|---|---|---|---|
| Stealth.blockDebugger / EnvCheck.probe / Flagger.probeSdk calls | 2026-09-11 | anti-debug ทำให้แอพกั๊กตัวเอง (commit b5b6b24) + blueprint B3 | snake T1: flagger na/nb = 0 callers (F2) |
| env_check.cpp (ทั้ง TU) | 2026-09-14 | ทุก call ถูก [CUT] แล้ว = dead code; ตัดไฟล์ทิ้งตาม C5-uncalled | aether_core.cpp JNI_OnLoad เดิม |
| manifest_snapshot.cpp/.hpp | 2026-09-14 | orphan TU (gc-sections ทิ้ง); ผู้ผลิต package.conf จริง = Kotlin generatePackageConf (per-install) | audit C5 |
| hide_module.cpp/.hpp (rootspoof) | 2026-09-14 | ต้อง root + ไม่มี call-site; out-of-scope เครื่องที่เราจะประกอบ | audit C5 |
| Engine.kt/cpp: nativeValidate, nativeExchangeKeys, nativeWriteLog, nativeDeriveKey, nativeEntropy, setAccessible×2, decryptString, hideXposed, installNetworkHttpProbe, loadEmptyDex, nativeDecryptPayloadByHash | 2026-09-14 | body = LOG-ONLY/constant + ไม่มี Kotlin call-site (audit C3 dead 15 + C4 stubs; license/protection ตาม B3) | git history คืนได้ทุกตัว |
| execStartActivity hook (AetherInstrumentation) + rewriteToStub callers | 2026-09-14 | subclass override ของ hidden method ไม่เคย dispatch = dead โดยโครงสร้าง (audit C9); rewriteToStub คงไว้เป็นชิ้น P4 (binder-layer, @Suppress + KNOWN-PENDING) | doc ในไฟล์เดิมยอมรับ |
| license natives snake (chl/djp/ilil/awl/aior/eio) — ไม่เคย port | 2026-09-13 | ตัดตามคำสั่งผู้ใช้ (login/license) + ไม่มีใน run-chain | blueprint L4 'ตัดทิ้งตาม B3' |
| launchGame / getEngineStatus channel branches | 2026-09-14 | 0 callers ฝั่ง Dart (audit C1) | grep home_screen |
| setBinderCallingPid/UidOverride + restore×2 (kt/table/cpp) | 2026-09-14 | audit C13-binder-restore-only: set ไม่เคยถูกเรียก → restore ไล่ตามของไม่ได้ติดตั้ง = telemetry หลอก; will-return พร้อม virtual-UID mapping (D8) — binder.cpp state tracker คงอยู่ (core, ไม่มี JNI surface) | grep 0 callers |
| string_decryptor.cpp (TU) | 2026-09-14 | decryptStringJNI ถูกลบแล้ว (C3) → orphan จริง; cipher spec เก็บใน StrDecryptSmokeTest สำหรับ reimplement | C5 gate |
