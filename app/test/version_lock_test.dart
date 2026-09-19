// test/version_lock_test.dart — unit tests for the version_lock range model.
//
// Grounds: T1 evidence of snake's "This game version is not supported.
// please install other version . from * to #" template (pp+0x112e0), which is
// why a license pins an INTERVAL and not a single version. These tests prove
// the interval semantics (open bounds, inclusive edges, numeric ordering)
// before that logic is relied on to gate a launch.
import 'package:flutter_test/flutter_test.dart';
import 'package:aether/data/games.dart';

void main() {
  group('VersionLock.parse', () {
    test('exact version string', () {
      final l = VersionLock.parse('56.23.2');
      expect(l.from, '56.23.2');
      expect(l.to, '56.23.2');
      expect(l.isConstrained, isTrue);
    });

    test('range string "from-to"', () {
      final l = VersionLock.parse('56.0.0-56.99.99');
      expect(l.from, '56.0.0');
      expect(l.to, '56.99.99');
    });

    test('JSON object form', () {
      final l = VersionLock.parse({'from': '1.0.0', 'to': '2.0.0'});
      expect(l.from, '1.0.0');
      expect(l.to, '2.0.0');
    });

    test('empty / unknown input is unconstrained', () {
      expect(VersionLock.parse('').isConstrained, isFalse);
      expect(VersionLock.parse(null).isConstrained, isFalse);
      expect(VersionLock.parse(42).isConstrained, isFalse);
    });
  });

  group('VersionLock.allows', () {
    test('inclusive at both edges', () {
      const l = VersionLock(from: '56.0.0', to: '56.9.9');
      expect(l.allows('56.0.0'), isTrue);
      expect(l.allows('56.9.9'), isTrue);
      expect(l.allows('56.4.1'), isTrue);
    });

    test('rejects outside the interval', () {
      const l = VersionLock(from: '56.0.0', to: '56.9.9');
      expect(l.allows('55.9.9'), isFalse);
      expect(l.allows('57.0.0'), isFalse);
    });

    test('open lower bound accepts anything below', () {
      const l = VersionLock(to: '56.9.9');
      expect(l.allows('1.0.0'), isTrue);
      expect(l.allows('56.9.9'), isTrue);
      expect(l.allows('57.0.0'), isFalse);
    });

    test('open upper bound accepts anything above', () {
      const l = VersionLock(from: '56.0.0');
      expect(l.allows('56.0.0'), isTrue);
      expect(l.allows('99.0.0'), isTrue);
      expect(l.allows('55.9.9'), isFalse);
    });

    test('unconstrained lock allows every version', () {
      const l = VersionLock();
      expect(l.allows('0.0.1'), isTrue);
      expect(l.allows('999.0.0'), isTrue);
    });
  });

  group('VersionLock.compareVersions', () {
    test('numeric comparison, not lexicographic', () {
      // lexicographic would put "9" > "10" — the numeric path must not.
      expect(VersionLock.compareVersions('56.9.0', '56.10.0'), lessThan(0));
      expect(VersionLock.compareVersions('56.10.0', '56.9.0'), greaterThan(0));
    });

    test('unequal segment counts are zero-padded', () {
      expect(VersionLock.compareVersions('1.0', '1.0.0'), 0);
      expect(VersionLock.compareVersions('1.0.1', '1.0'), greaterThan(0));
    });
  });

  group('VersionLock.rangeLabel', () {
    test('mirrors the "from * to #" template', () {
      const l = VersionLock(from: '56.0.0', to: '56.99.99');
      expect(l.rangeLabel, '56.0.0 to 56.99.99');
    });
    test('single bound wording', () {
      expect(const VersionLock(from: '1.0.0').rangeLabel, '1.0.0 or newer');
      expect(const VersionLock(to: '1.0.0').rangeLabel, '1.0.0 or older');
      expect(const VersionLock().rangeLabel, 'any');
    });
  });
}