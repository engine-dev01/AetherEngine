# AetherEngine

No-root Android virtualization engine (SNAKE-parity rebuild): virtualize a real
installed app — 8 Ball Pool — inside this host app's own processes, plus an
in-process memory/IO toolchain (`libaether.so`). Flutter shell, Kotlin framework
layer, C++ native layer. **All builds happen in GitHub Actions — never locally.**

Current status: **game boots through the real guest Activity** (swap + guest
resources + `onCreate` verified on device). Last known blocker: GMS measurement
dynamite (`m7.*`) throws `SecurityException` on the main looper and the JVM exits
the process regardless of any uncaught handler — interim fix is a gated provider
skip (see [docs/CUTS.md](docs/CUTS.md)); the permanent fix is the virtual broker
(P5). Not yet: stable `onResume` + 30 s survival (L6 acceptance gate).

## Layout

```
aether-core/          Kotlin: GuestRuntime (≡ jv0 bind path), SandboxManager,
                      PackageConfParser, RemoteConfig, Engine.kt (JNI surface)
aether-android/       Kotlin: Orchestrator, VirtualAppContainer, ServiceBinderProxy,
                      GuestProcessTable/Holder, HCallbackProxy, AetherInstrumentation,
                      Proxy* components, CrashHandler, DiagLog
aether-android/
  aether-app/src/main/kotlin/com/aether/engine/proxy/   (see above)
  test-stubs/         AOSP-signature stubs → localTest (no SDK, no Robolectric)
aether-native/        C++ → libaether.so (JNI_OnLoad + RegisterNatives, 26 methods)
app/                  Flutter shell (home_screen: Play, 1234 chain-check, Diag)
reference/            Committed evidence: snake F2/F3 (T1) + jadx transcript (T2)
docs/CUTS.md          Every deliberate cut/no-op: what, why, date, proof
scripts/              All verification gates (below)
```

## Process model (manifest parity ≡ SNAKE F3)

| process | components |
|---|---|
| main `com.aether` | Flutter host, Orchestrator, service proxies |
| `:engine` | daemon + inner service, VPN service, SystemCallProvider |
| `:p0`–`:p3` | `ProxyActivity$Pn` (+ `$Pn_L` landscape), ProxyService/Job/Provider per-slot |

36 declared components (18 activity / 11 service / 6 provider / 1 receiver),
4 guest slots, provider-handshake spawn (`_Engine_|_init_process_` →
`_Engine_|_client_`, linkToDeath slot release) ≡ SNAKE `a7.m` hop 11–12.

## Run-chain (proof-anchored)

```
Play → allocate slot (a7) → ContentResolver.call(provider per-slot)
     → framework spawns :pN → handleInit p3 (jv0.P2, DIAG rebind rule)
     → dispatch ProxyActivity$P<slot> → VirtualAppContainer identity (guest)
     → HCallbackProxy install@main-looper, arm (≡ my @ mH.mCallback)
     → GuestRuntimeBridge v2: jv0.O2 bind — LoadedApk re-root, processName
       spoof (AppBindData), newApplication(guest), providers (B3 + 1 GATED)
     → finishBind() → release held transactions → recreate()
     → AetherInstrumentation.newActivity: swap stub → real guest Activity
       (guest arsc via addAssetPath, mBase, guest ActivityInfo + theme)
     → hook A at binder layer: startActivity(guest-class) → rewrite to
       P<slot> + stash EXTRA_GUEST_CLASS (≡ il0/r1 intent packing)
     → callActivityOnResume → nativeProcessPair (≡ b8:79 Native.ac)
```

Caller contract (device-proven): every binder call leaving the process carries
**host** identity — caller-slot strings are spoofed guest→host on AMS/PMS
whitelisted methods (`registerReceiver*`, `getContentProvider*`,
`getIntentSender*`, …); data queries (`getPackageInfo(guest)`) go to the real
PMS unmodified. Guest name must never face the real system (SE otherwise).

## Version support (8 Ball Pool)

| game | code | PGL hash dir |
|---|---|---|
| 56.23.2 | 3965 | `90d8aa15a2de2cb4` |
| 56.29.1 | 4013 | `9e75dd17d258d07f` |

PGL/oat/hidden-dex are written by the game at runtime (never fabricated by the
engine — SNAKE dumps prove it); `package.conf` = engine-written per-install
registry, refreshed whenever its embedded `apkPath` ≠ live `codePath`.

## Verification gates (run all before requesting CI assembly)

```bash
bash scripts/preflight.sh          # meta-gate (11 checks) incl. below:
  scripts/jni_parity.py            # Engine.kt ↔ RegisterNatives 1:1 + dead-fun guard
  scripts/native_chain_parity.py   # T1 F2 ↔ sig/table/call-site placement (ic/i/ac; D6/D7 WARN)
  scripts/structural_gates.py      # S1 orphan-TU · S2 manifest↔MAX_SLOTS · S3 NO-OP markers
  scripts/wire_contract_check.py   # W1 wire literals const-only · W2 bundle keys single-source
                                   # W3 Dart↔Kotlin channel type contract
  scripts/evidence_check.sh        # vendored T1 integrity + UNVERIFIED doc declarations
sh scripts/full_compile.sh         # 3 modules vs real android.jar 35 + flutter stubs
                                   # (== CI compileDebugKotlin — catches what CI would)
sh aether-android/test-stubs/run-aether-test.sh   # localTest: 4 JVM tests (seam tests included)
```

Branch CI: `ci.yml` (lint/analyze/compile/localTest) + `build-apk.yml` (release
APK → GitHub Release `apk-v1.0.0`, arm64-v8a, ~30 MB).

## On-device diagnostics (no adb needed)

- **`1234` key** → chain check: identity / sCache 11/11 / AMS=PROXY / slots /
  installed + real provider handshake on **diag slot 3** (never guest slot)
- **Diag button** → `trace.log` + logcat dump + `launch_result.json`
  (`{ok, stage, reason, identity, slot}` written at every exit path) + latest
  `crash_logs/crash_*.log` (14-section report)
- `files/root/` = virtual root (≡ SNAKE `lv0.java:72`): package.conf, proc/,
  system/, game data dirs — read-only while a game session is live

## Rules of engagement (this rebuild)

1. **CI assembles, never local builds**; push only after all local gates pass
   **and** explicit per-round approval.
2. **Tiered evidence only** (T1 = machine-rederived from `SNAKE.apk` in
   `reference/snake/` + `codes/Codes`; T2 = jadx transcripts, cited with tier
   flag; anything else = UNVERIFIED, declared in `reference/README.md`).
3. **One scope per commit**; diff reviewed with `git diff --cached` before commit.
4. **No fabricated files, no invented parity names**: framework-visible strings ≡
   SNAKE letter-for-letter; internal endpoints ours (B4); every cut recorded in
   `docs/CUTS.md` and surfaced as `NO-OP`/`WARN` in gates — never silent PASS.

## Roadmap (from the pinned blueprint)

- **P5** virtual AMS/PMS server-side + service coverage 11→48 + virtual UID →
  restore FirebaseInitProvider + kill SE at the source (permanent fix)
- **D6/D7** native hop bodies (`pjowqpxe`/`update` hidden-dex pipeline) — NO-OP
  marked, gate-visible
- **D10** hidden-API exemption path on A16 (JNI route currently denied by ART;
  sCache 11/11 still lands via existing reflection)
- L6 acceptance: guest `onResume` + >30 s alive + continuous sandbox writes

## Docs

- [DEV.md](DEV.md) — day-to-day (module map, gates, build flow, release)
- [TESTING.md](TESTING.md) — test pipeline history (L1–L3, now gate table in DEV.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/CONFIG.md](docs/CONFIG.md)
  provisioning: [docs/PROVISIONING_8BP_56.23.2.md](docs/PROVISIONING_8BP_56.23.2.md)
- [reference/README.md](reference/README.md) — evidence tiers + UNVERIFIED list
- [docs/CUTS.md](docs/CUTS.md) — every deliberate cut/gate with proof
