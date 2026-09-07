// data/games.dart — Game metadata registry (Phase 10: extensible)
//
// AetherEngine supports multiple games, but only 8 Ball Pool is enabled
// in this build. To add a new game:
//   1. Add a GameInfo entry to `Games.all` (below)
//   2. (Optional) Add a cover image to app/assets/games/
//   3. Rebuild — the home screen will pick it up automatically
//
// Snake supports 16 games (GameButton1..16); we start with 1 and grow.
// To preserve "หัวใจหลักทำงานได้สมบูรณ์ก่อน" (core engine first),
// we only enable games that have been verified against a real device.
//
// packageName: target identity (used to virtualize via launchInSandbox). The
// engine virtualizes the target IN-PROCESS via stub+swap (Scaffold-4), NOT
// via an external Intent to the installed app. Pressing Play runs the guest
// inside com.aether's :p0 process; the "Play Store" button is the only path
// that dispatches an external Intent (OPT-IN).
class GameInfo {
  final String name;
  final String version;
  final String packageName;
  final String? coverAsset;  // e.g. 'assets/games/8ball.png' (nullable for fallback)
  final bool supported;        // false = show ⚠️ icon (e.g. not installed on device)

  const GameInfo({
    required this.name,
    required this.version,
    required this.packageName,
    this.coverAsset,
    this.supported = true,
  });
}

/// Game registry — single source of truth for home screen list
///
/// To add a new game (e.g. Carrom Pool), append a new GameInfo here:
//   GameInfo(
///     name: 'Carrom Pool',
///     version: '6.0.0',
///     packageName: 'com.miniclop.carrompool',
///     coverAsset: 'assets/games/carrom.png',
///   ),
class Games {
  /// All supported games (currently 1: 8 Ball Pool).
  /// packageName = IDENTITY only (display + tracking). Engine runs
  /// in-process; no Intent is dispatched to this package.
  static const List<GameInfo> all = [
    GameInfo(
      name: '8 Ball Pool',
      version: '56.23.2',
      packageName: 'com.miniclip.eightballpool',  // identity only, not launched
      coverAsset: null,  // placeholder icon (gradient + sports_baseball)
    ),
    // Example (disabled — uncomment to enable):
    // GameInfo(name: 'Carrom Pool', version: '6.0.0', packageName: 'com.miniclop.carrompool'),
    // GameInfo(name: 'Soccer Stars', version: '4.0.0', packageName: 'com.miniclop.soccerstars'),
  ];

  /// First (default) game — used by home screen's "Play" button
  static GameInfo get defaultGame => all[0];

  /// Find game by package name (returns null if not found)
  static GameInfo? byPackage(String packageName) {
    for (final g in all) {
      if (g.packageName == packageName) return g;
    }
    return null;
  }

  /// Find game by name (returns null if not found)
  static GameInfo? byName(String name) {
    for (final g in all) {
      if (g.name == name) return g;
    }
    return null;
  }
}
