// test/games_registry_test.dart — registry + UI-parity tests.
//
// The registry is a LOCAL identity table: the T1 audit found `com.miniclip.*`
// = 0 hits across smali/java_out/res_out/Dart/libapp (docs/DART_LICENSE_FLOW.md
// §1), so every entry must stay labelled [NOT-IN-POOL]. The Python gate
// (scripts/snake_ui_parity.py) enforces the label in source; this test
// enforces the same contract at runtime, plus the "all cards unlocked +
// SEVIP" directive that the home grid renders.
import 'package:flutter_test/flutter_test.dart';
import 'package:aether/data/games.dart';

void main() {
  test('registry has the three snake home-screen cards in order', () {
    expect(Games.all.map((g) => g.name).toList(),
        ['8 Ball Pool', 'Carrom Pool', 'Soccer Stars']);
  });

  test('every card is unlocked and carries the SEVIP tier', () {
    for (final g in Games.all) {
      expect(g.supported, isTrue, reason: '${g.name} must not be locked');
      expect(g.tier, 'SEVIP', reason: '${g.name} must show the SEVIP chip');
      expect(g.showVersionPill, isTrue, reason: '${g.name} must show a pill');
      expect(g.version, isNotEmpty, reason: '${g.name} needs a pill value');
    }
  });

  test('package IDs are unique and android-shaped', () {
    final pkgs = Games.all.map((g) => g.packageName).toList();
    expect(pkgs.toSet().length, pkgs.length, reason: 'duplicate package id');
    for (final p in pkgs) {
      expect(p, startsWith('com.'));
      expect(p.split('.').length, greaterThanOrEqualTo(3));
    }
  });

  test('byPackage resolves every registry entry', () {
    for (final g in Games.all) {
      expect(Games.byPackage(g.packageName)?.name, g.name);
    }
    expect(Games.byPackage('com.example.nope'), isNull);
  });

  test('defaultGame is the first card (8 Ball Pool)', () {
    expect(Games.defaultGame.name, '8 Ball Pool');
  });

  test('withLicense preserves display fields and applies the lock', () {
    const g = GameInfo(
      name: 'X',
      version: '1.0.0',
      packageName: 'com.x.y',
      tier: 'SEVIP',
    );
    final pinned = g.withLicense(lock: const VersionLock(from: '2.0.0'));
    expect(pinned.version, '1.0.0');       // version string untouched
    expect(pinned.tier, 'SEVIP');          // display fields survive
    expect(pinned.supported, isTrue);
    final blocked = g.withLicense(supported: false);
    expect(blocked.supported, isFalse);
  });
}