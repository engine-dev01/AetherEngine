# AetherEngine Troubleshooting

Common build/runtime issues + solutions.

## Build fails: "package= attribute deprecated"

**Cause:** AGP 8.x removed support for `package=` in AndroidManifest.xml
**Fix:** Remove `package="..."` from `<manifest>` tag + set `namespace` in `build.gradle.kts`

Pre-flight G1.5 catches this automatically.

## Build fails: "Cannot find a parameter with this name"

**Cause:** Data class field removed but constructor call still passes it (or vice versa)
**Fix:** Either:
- Add the field back to data class, or
- Remove the parameter from constructor call

**Example (Y1 fix):** V3 removed `artHookCount` field from `EngineStats`, but caller still passed `artHookCount = 0`. Fix: remove from caller.

## Build fails: "Unresolved reference: X"

**Cause:** Deleted public API in V3 cleanup but caller still uses it
**Fix:** Either:
- Restore the API in EngineBridge/Kotlin layer, or
- Remove the caller in AetherHostActivity/AetherApp/etc.

**Example:** V3 removed `setModeCallback` but AetherHostActivity called it. Fix: remove from AetherHostActivity.

## Build takes 21+ min (cold cache)

**Cause:** Android SDK + NDK downloaded fresh
**Fix:** Build cache in `~/.android/sdk` (already enabled). First build is slow, subsequent are 6-9 min.

## Build APK timed out

**Cause:** Runner class congested (rare), or NDK download slow
**Fix:** Re-run via `gh workflow run build-apk.yml` (uses same commit but new runner)

## App crashes on launch

**Cause:** JNI symbol mismatch (35 methods in Kotlin, but C++ doesn't export them)
**Fix:** Pre-flight G3 catches this. Run `bash scripts/preflight.sh` before push.

## App crashes on runtime (after launch)

**Cause:** Native hook installed in wrong order, or game process not found
**Fix:** Test on real device with `adb logcat -d *:E | grep Aether`
(Aether does NOT support sandbox — testing in `adb logcat` requires real device)

## Asset size mismatch on release

**Cause:** APK not built (compile error upstream) but release script tries to upload
**Fix:** Check step 9 (Pre-build validation) for compile errors

## Concurrency: previous run cancelled

**Cause:** New commit pushed before previous build finished
**Effect:** Saves runner minutes (~5 min saved per cancelled run)
**To opt-out:** Add `[skip-cancel]` to commit message

## Flutter analyze warnings (unused imports)

**Cause:** Imports added during refactor, not removed when code was deleted
**Fix:** Remove unused imports — caught by pre-flight G2 (brace balance) and Flutter analyzer

## Pre-flight gate fails

**Cause:** 7 gates (G1-G7) — YAML, brace, JNI, refs, etc.
**Fix:** Run `bash scripts/preflight.sh` — shows which gate failed + why
**To skip:** Add `[skip-preflight]` to commit message (NOT recommended)
