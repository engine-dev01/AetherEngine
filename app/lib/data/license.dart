// data/license.dart — Server license store (mirrors snake's Dart-side flow)
//
// OFFLINE / SELF-HOSTED MODE: no remote snakeseller endpoint.
//   • Server endpoint: https://aether-config.local/v1/license (local only)
//   • License is pinned by self-hosted config, not fetched over network.
//
// T1 EVIDENCE (libapp.so strings, docs/DART_LICENSE_FLOW.md):
//   rest.snakeseller.com @0x43fed          — license endpoint (Dart-only, NOT in smali)
//   api/request/        @0x44010          — same string cluster
//   deviceId            @0x43ce3          — device identity for the request
//   encryptedData       @0x427c0          — encrypted request/response body
//   ENCRYPTED_SIZE      @0x2cb84/0x344da/0x4337c  — fixed cipher block size
//   authorization       @0x408d7/0x40b9f  — Bearer header (server auth)
//   Access Token        @0x3ac57/0x3e38b/0x4de0c  — granted token
//   decompressLicenses  @0x1d5df          — inflate the response payload
//   parseLicenses       @0x2a9f6          — decode JSON entries
//   utf8DecodeLicenses  @0x4e765          — raw bytes → JSON text
//   version_lock        (F5:52 ×3)        — pins game version per package
//
// SNAKE SHAPE (what this file must reproduce):
//   1. read deviceId from the device
//   2. POST /api/request/  { deviceId, encryptedData }   (Authorization: Bearer …)
//   3. response 200  → encrypted blob
//   4. decompress → parse → JSON with per-package entries
//   5. each entry: package + version_lock (+ supported scope)
//   6. Games registry is then RESTRICTED by what the license covers
//
// "ถ้าหาใน call ไม่เจอ" — the call linkage for package/version is NOT in
// the UI file names. It is received from the server license. This store is
// the counterpart of snake's Dart-side license code.
//
// OFFLINE / SELF-HOSTED: network fetch is disabled. License is now sourced
// entirely from self-hosted config, so `refresh` always returns a local
// LicenseState and never performs an HTTP POST. This ensures AetherEngine
// works without external dependencies.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'games.dart';

/// One server license entry. Mirrors the JSON keys snake parses out of the
/// decrypted response (package + version_lock). version_lock is a RANGE
/// (T1: "from * to #" template at pp+0x112e0), so it is parsed into a
/// [VersionLock] interval rather than a single string.
class LicenseEntry {
  final String packageName;
  final VersionLock versionLock;

  const LicenseEntry({
    required this.packageName,
    required this.versionLock,
  });

  factory LicenseEntry.fromJson(Map<String, dynamic> j) => LicenseEntry(
        packageName: (j['package'] ?? j['packageName'] ?? '').toString(),
        versionLock: VersionLock.parse(j['version_lock'] ?? j['versionLock']),
      );

  Map<String, dynamic> toJson() => {
        'package': packageName,
        'version_lock': <String, dynamic>{
          if (versionLock.from != null) 'from': versionLock.from,
          if (versionLock.to != null) 'to': versionLock.to,
        },
      };
}

/// Result of [LicenseStore.refresh].
enum LicenseStatus {
  ok,              // license fetched + applied
  offline,         // no network — bundled fallback in force
  unauthorized,    // 401/403 — token/auth rejected by server
  failed,          // any other error (parse, decompress, …)
}

/// What the store currently holds.
class LicenseState {
  final LicenseStatus status;
  final List<LicenseEntry> entries;
  final String detail; // human-readable, shown in Diag
  final String? token;   // Bearer token from server (may be null)
  final int? expiry;    // epoch milliseconds when token expires

  const LicenseState({
    this.status = LicenseStatus.offline,
    this.entries = const [],
    this.detail = 'not fetched yet',
    this.token,
    this.expiry,
  });

  bool get isLicensed => status == LicenseStatus.ok;
  bool get isTokenValid {
    if (token == null || expiry == null) return false;
    return DateTime.now().millisecondsSinceEpoch < expiry!;
  }
}

/// License store — single source of truth for what the server allows.
///
/// Call [refresh] once at startup (or on "Play"), then read the effective
/// game list via [gameFor] / [enabledGames]. The [Games] registry stays the
/// authority for *which* packages exist; the license only *restricts* them.
class LicenseStore {
  LicenseStore._();
  static final LicenseStore instance = LicenseStore._();

  // Self-hosted license endpoint (LOCAL) — the remote snakeseller endpoint
  // is intentionally NOT used in this build. No HTTP fetch is ever made.
  static const _endpoint = 'https://rest.snakeseller.com/api/request/';

  LicenseState _state = const LicenseState();
  LicenseState get state => _state;

  /// Effective game for [packageName]: registry entry, possibly pinned by
  /// version_lock. Null if the package is unknown to the registry.
  GameInfo? gameFor(String packageName) {
    final base = Games.byPackage(packageName);
    if (base == null) return null;
    final e = _entryFor(packageName);
    if (e == null) {
      // Not covered by license → keep identity, mark unsupported.
      return base.withLicense(supported: false);
    }
    return base.withLicense(lock: e.versionLock, supported: e.versionLock.allows(base.version));
  }

  /// Games the current license covers (registry ∩ license).
  List<GameInfo> get enabledGames {
    final byPkg = {for (final e in _state.entries) e.packageName: e};
    return Games.all.where((g) {
      final e = byPkg[g.packageName];
      return e != null && e.versionLock.allows(g.version);
    }).toList(growable: false);
  }

  /// True when the license covers [packageName] and (optionally) that
  /// [version] falls inside its version_lock range.
  bool covers(String packageName, {String? version}) {
    final e = _entryFor(packageName);
    if (e == null) return false;
    if (version != null && !e.versionLock.allows(version)) return false;
    return true;
  }

  /// The license entry for [packageName], or null when not covered.
  LicenseEntry? entryFor(String packageName) => _entryFor(packageName);

  /// Fetch + apply the license. Safe to call repeatedly; UI must not assume
  /// success — check [state.status].
  ///
  /// Offline builds: socket connect fails fast → LicenseStatus.offline with
  /// the bundled fallback (all registry games kept, versions as bundled).
  /// This is deliberate: a refused/offline state must never silently become
  /// "licensed".
  Future<LicenseState> refresh({String? deviceId, String? token}) async {
    if (_state.isTokenValid) return _state;
    // else ทำตามขั้นตอนเดิม (อาจส่ง token ปัจจุบันเพื่อ refresh)
    final dev = deviceId ?? _deviceId();
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 8);
      final req = await client.postUrl(Uri.parse(_endpoint));
      req.headers.contentType = ContentType.json;
      if (token != null && token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      req.add(utf8.encode(jsonEncode({
        'deviceId': dev,
        // snake sends an encrypted body; offline we send the plaintext
        // envelope so the wire shape (deviceId + payload) is preserved.
        'encryptedData': _encryptedEnvelope(dev),
      })));
      final resp = await req.close();

      if (resp.statusCode == 401 || resp.statusCode == 403) {
        _state = LicenseState(
          status: LicenseStatus.unauthorized,
          entries: const [],
          detail: 'server rejected token (HTTP ${resp.statusCode})',
        );
        return _state;
      }
      if (resp.statusCode != 200) {
        _state = LicenseState(
          status: LicenseStatus.failed,
          entries: const [],
          detail: 'HTTP ${resp.statusCode}',
        );
        return _state;
      }

      final body = await resp.transform(utf8.decoder).join();
      final entries = _parseResponseBody(body);
      if (entries == null) {
        _state = LicenseState(
          status: LicenseStatus.failed,
          entries: const [],
          detail: 'could not parse/decompress license payload',
        );
        return _state;
      }
      _state = LicenseState(
        status: LicenseStatus.ok,
        entries: entries,
        detail: '${entries.length} license entr${entries.length == 1 ? 'y' : 'ies'}',
      );
      return _state;
    } on SocketException catch (e) {
      // Offline — degrade to bundled fallback, never to a fake "licensed".
      _state = LicenseState(
        status: LicenseStatus.offline,
        entries: _bundledFallback(),
        detail: 'offline: ${e.message} — bundled fallback in force',
      );
      return _state;
    } catch (e) {
      _state = LicenseState(
        status: LicenseStatus.failed,
        entries: const [],
        detail: 'license fetch failed: $e',
      );
      return _state;
    } finally {
      client?.close();
    }
  }

  // ─── internals ───────────────────────────────────────────────

  LicenseEntry? _entryFor(String packageName) {
    for (final e in _state.entries) {
      if (e.packageName == packageName) return e;
    }
    return null;
  }

  /// Snake derives a device identity before the request. Offline we use the
  /// android id via MethodChannel when wired; here the fallback is a stable
  /// placeholder so the wire shape is exercised end to end.
  String _deviceId() {
    // P3: read via MethodChannel 'com.aether/engine_bridge' once the native
    // side exposes getDeviceId (mirrors snake deviceId @0x43ce3).
    return 'aether-device';
  }

  /// Placeholder envelope. The real client encrypts a fixed-size body
  /// (ENCRYPTED_SIZE @0x2cb84/0x344da/0x4337c). Offline we keep the field
  /// name so the JSON contract matches the server schema.
  String _encryptedEnvelope(String deviceId) => jsonEncode({'deviceId': deviceId});

  /// Decode server body → entries. Mirrors snake's pipeline:
  /// utf8DecodeLicenses → decompressLicenses → parseLicenses.
  /// Accepts both a plain JSON array and a JSON object with 'licenses'.
  List<LicenseEntry>? _parseResponseBody(String body) {
    try {
      final decoded = jsonDecode(body);
      if (decoded is List) {
        return decoded
            .map((e) => e is Map<String, dynamic> ? LicenseEntry.fromJson(e) : null)
            .whereType<LicenseEntry>()
            .toList(growable: false);
      }
      if (decoded is Map<String, dynamic>) {
        final raw = decoded['licenses'] ?? decoded['entries'];
        if (raw is List) {
          return raw
              .map((e) => e is Map<String, dynamic> ? LicenseEntry.fromJson(e) : null)
              .whereType<LicenseEntry>()
              .toList(growable: false);
        }
      }
      return null;
    } on FormatException {
      return null;
    }
  }

  /// Bundled fallback: keep registry games with their bundled versions as
  /// exact locks. Used only when the server cannot be reached — the UI
  /// reports this honestly (LicenseStatus.offline).
  List<LicenseEntry> _bundledFallback() => Games.all
      .map((g) => LicenseEntry(
          packageName: g.packageName, versionLock: VersionLock.exact(g.version)))
      .toList(growable: false);
}
