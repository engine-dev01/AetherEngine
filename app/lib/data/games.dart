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
// ─── packageName: where it comes from (T1 evidence, docs/DART_LICENSE_FLOW.md)
// The registry here is the FALLBACK. package + version are ultimately
// governed by the server license that Dart fetches from
// https://rest.snakeseller.com/api/request/ (deviceId → encryptedData →
// Access Token → version_lock).
//
// SELF-HOSTED MODE (NEW):
//   - Remote snakeseller endpoint is NOT used.
//   - License is sourced from self-hosted config.
//   - This file remains the registry of names/versions; version constraints
//     are managed entirely via LicenseStore's offline/local license model.
//
// Virtualization is IN-PROCESS via stub+swap (Scaffold-4), NOT via an
// external Intent to the installed app. Pressing Play runs the guest
// inside com.aether's :p0 process; the "Play Store" button is the only path
// that dispatches an external Intent (OPT-IN).
class GameInfo {
  final String name;
  final String version;
  final String packageName;
  final String? coverAsset;  // e.g. 'assets/games/8ball.png' (nullable for fallback)
  final bool supported;        // false = show ⚠️ icon (license not covering it)

  // ── screenshot-anchored display fields (T2: user-supplied UI captures) ──
  // tier == null → no yellow chip on the card. Observed value: 'SEVIP'.
  final String? tier;
  // showVersionPill controls whether the purple version pill is drawn over
  // the cover. In the capture, Soccer Stars has NO pill (only the ⚠️), so
  // its registry entry sets this false even though a version string exists.
  final bool showVersionPill;

  const GameInfo({
    required this.name,
    required this.version,
    required this.packageName,
    this.coverAsset,
    this.supported = true,
    this.tier,
    this.showVersionPill = true,
  });

  /// Copy with license-driven overrides (version_lock / supported flag).
  /// Called only from LicenseStore — never from UI directly. Preserves the
  /// display-only fields (tier / showVersionPill) unchanged.
  GameInfo withLicense({VersionLock? lock, bool? supported}) => GameInfo(
        name: name,
        version: lock?.effectiveFor(version) ?? version,
        packageName: packageName,
        coverAsset: coverAsset,
        supported: supported ?? this.supported,
        tier: tier,
        showVersionPill: showVersionPill,
      );
}

/// version_lock as a RANGE, mirroring snake's "from * to #" template
/// (T1: pp+0x112e0 — "This game version is not supported. please install
/// other version . from * to #"). The license pins an allowed interval,
/// not a single value; a null bound means open-ended.
class VersionLock {
  final String? from; // lowest supported version (inclusive), null = no floor
  final String? to;   // highest supported version (inclusive), null = no ceiling
  const VersionLock({this.from, this.to});

  const VersionLock.exact(String v) : from = v, to = v;

  /// Parse snake's server representation. Accepts:
  ///   "56.23.2"            → exact
  ///   "56.0.0-56.99.99"    → range
  ///   {"from":…,"to":…}    → range (JSON object form)
  factory VersionLock.parse(dynamic v) {
    if (v is String) {
      final s = v.trim();
      if (s.isEmpty) return const VersionLock();
      final dash = s.indexOf('-');
      if (dash > 0) {
        return VersionLock(from: s.substring(0, dash).trim(), to: s.substring(dash + 1).trim());
      }
      return VersionLock.exact(s);
    }
    if (v is Map) {
      return VersionLock(
        from: v['from']?.toString(),
        to: v['to']?.toString(),
      );
    }
    return const VersionLock();
  }

  /// True when the lock actually constrains anything.
  bool get isConstrained => from != null || to != null;

  /// Effective version for display when the installed [current] is allowed.
  String effectiveFor(String current) => current;

  /// Semver-ish compare: "56.23.2" vs "56.23.2". Numeric per dot segment.
  static int compareVersions(String a, String b) {
    final pa = a.split('.'), pb = b.split('.');
    final n = pa.length > pb.length ? pa.length : pb.length;
    for (var i = 0; i < n; i++) {
      final x = int.tryParse(pa.length > i ? pa[i] : '0') ?? 0;
      final y = int.tryParse(pb.length > i ? pb[i] : '0') ?? 0;
      if (x != y) return x < y ? -1 : 1;
    }
    return 0;
  }

  /// True when [version] falls inside [from, to] (inclusive).
  bool allows(String version) {
    if (from != null && compareVersions(version, from!) < 0) return false;
    if (to != null && compareVersions(version, to!) > 0) return false;
    return true;
  }

  /// Human range for the "from * to #" template, e.g. "56.0.0 to 56.99.99".
  String get rangeLabel {
    if (from != null && to != null) return '$from to $to';
    if (from != null) return '$from or newer';
    if (to != null) return '$to or older';
    return 'any';
  }
}

/// Game registry — single source of truth for home screen list
///
/// To add a new game (e.g. Carrom Pool), append a new GameInfo here:
//   GameInfo(
///     name: 'Carrom Pool',
///     version: '6.0.0',
///     packageName: 'com.miniclip.carrompool',
///     coverAsset: 'assets/games/carrom.png',
///   ),
class Games {
  /// Supported games — order mirrors the home-screen grid (left→right):
  ///   8 Ball Pool · Carrom Pool · Soccer Stars
  /// packageName = IDENTITY only (display + tracking). Engine runs
  /// in-process; no Intent is dispatched to this package.
  ///
  /// version / tier / showVersionPill are anchored to the UI captures
  /// (T2 evidence): pills read 56.30.0 and 19.4.0; Soccer Stars shows a
  /// ⚠️ overlay and NO pill → supported:false, showVersionPill:false.
  ///
  /// NOTE: version is still the FALLBACK; the server license may pin it
  /// (version_lock). Use Games.byPackage(...).version, not this list
  /// directly, when displaying the effective version.
  // UNLOCKED MODE (user directive): every game shows the SEVIP chip and is
  // tappable — no greyed/⚠️ locked state. Versions kept as displayed in the
  // captures; Soccer Stars gets a real version so its pill renders too.
  //
  // [NOT-IN-POOL] package IDs below are NOT evidence-derived: the T1 audit
  // found `com.miniclip.*` = 0 hits across smali/java_out/res_out/Dart/libapp
  // (docs/DART_LICENSE_FLOW.md §1, reference/snake/*). snake resolves the
  // target package from the server license at runtime; the registry is our
  // local identity table and must stay labelled as such.
  static const List<GameInfo> all = [
    GameInfo(
      name: '8 Ball Pool',
      version: '56.30.0',
      packageName: 'com.miniclip.eightballpool',  // [NOT-IN-POOL] local identity
      coverAsset: null,
      tier: 'SEVIP',
      showVersionPill: true,
      supported: true,
    ),
    GameInfo(
      name: 'Carrom Pool',
      version: '19.4.0',
      packageName: 'com.miniclip.carrom',         // [NOT-IN-POOL] local identity
      coverAsset: null,
      tier: 'SEVIP',
      showVersionPill: true,
      supported: true,
    ),
    GameInfo(
      name: 'Soccer Stars',
      version: '19.4.0',
      packageName: 'com.miniclip.soccerstars',    // [NOT-IN-POOL] local identity
      coverAsset: null,
      tier: 'SEVIP',
      showVersionPill: true,
      supported: true,
    ),
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
