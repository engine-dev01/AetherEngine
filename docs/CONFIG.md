# AetherEngine Configuration

How to add new games, engines, or settings — without breaking core.

## Add new game (Phase 10: Games registry)

Edit `app/lib/data/games.dart`:

```dart
class Games {
  static const List<GameInfo> all = [
    // existing 8 Ball Pool
    GameInfo(
      name: '8 Ball Pool',
      version: '56.23.2',
      packageName: 'com.miniclip.eightballpool',
      coverAsset: null,  // optional PNG path
    ),
    // ADD NEW GAME HERE:
    GameInfo(
      name: 'Carrom Pool',
      version: '6.0.0',
      packageName: 'com.miniclop.carrompool',
      coverAsset: 'assets/games/carrom.png',  // optional
    ),
  ];
}
```

Save → `flutter run` (or push to CI for auto-rebuild) — new game appears in home screen automatically.

**No code change required** — UI reads `Games.all` list dynamically.

## Switch native engine (Phase 11: EngineLoader)

Aether supports swapping Aether's `libaether.so` with Snake's `libengine.so`:

```kotlin
import com.aether.engine.app.EngineLoader
import com.aether.engine.app.EngineLoader.EngineType

// In your code (e.g. settings panel — Phase 12):
EngineLoader.setEngineType(context, EngineType.SNAKE)
```

Or via SharedPreferences:
```kotlin
context.getSharedPreferences("aether_engine", Context.MODE_PRIVATE)
    .edit().putString("engine_type", "SNAKE").apply()
```

**Important:** Snake's `libengine.so` is NOT bundled by default (8.5 MB, OLLVM-obfuscated).
To enable Snake engine, manually deploy:
```bash
# Place libengine.so at:
app/src/main/jniLibs/arm64-v8a/libengine.so
```

Without this file, Aether auto-falls back to `libaether.so`.

## Add new app settings (Phase 10: AppSettings)

Edit `app/lib/data/app_settings.dart`:

```dart
class AppSettings {
  // existing 11 fields (showLines, keepLines, powerLock, etc.)
  
  // ADD NEW FIELD:
  final bool newFeature;
  
  const AppSettings({
    // existing defaults...
    this.newFeature = false,
  });
  
  // Don't forget copyWith()!
  AppSettings copyWith({..., bool? newFeature}) {
    return AppSettings(
      // existing fields...
      newFeature: newFeature ?? this.newFeature,
    );
  }
}
```

Then wire in home screen or settings panel (Phase 12).

## Add new MethodChannel (Kotlin ↔ Dart)

Currently 2 channels: `launchGame`, `openPlayStore`.

To add a new channel (e.g. `getEngineType`):

1. Add handler in `EngineBridge.kt`:
```kotlin
override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
        "launchGame" -> result.success(launchGame(...))
        "openPlayStore" -> { openPlayStore(); result.success(true) }
        "getEngineType" -> result.success(EngineLoader.getEngineType(ctx!!).name)  // NEW
        else -> result.notImplemented()
    }
}
```

2. Call from Dart in `app/lib/`:
```dart
final engineType = await channel.invokeMethod<String>('getEngineType');
```

**Important:** Verify Dart side actually uses the new channel (audit `flutter analyze` warnings).

## Add new JNI method (Tier 1 — careful!)

Requires:
1. Add `external fun` to `aether-core/.../Engine.kt`
2. Add `Java_com_aether_Engine_X` in `aether-native/.../aether_core.cpp`
3. Add to `JNINativeMethod m[]` table
4. Pre-flight G3 verifies 1:1 parity (will FAIL if mismatch)

**Warning:** Changing Tier 1 risks runtime crash. Test on real device before merge.
