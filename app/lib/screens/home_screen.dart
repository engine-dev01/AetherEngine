// screens/home_screen.dart — Aether Engine home (Phase 3 UI)
//
// Phase 3 UI:
//   - Engine Stats panel (status, PID, module, uptime, readCount, scanCount)
//   - In-process capability buttons (Read mem, Scan AOB, Compute, Compress)
//   - "Launch installed app" text field (opt-in external Intent)
//
// All in-process calls go through MethodChannel 'com.aether/engine_bridge'
// → EngineBridge → libaether.so (within com.aether process).
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../i18n/strings.dart';
import '../widgets/banner_carousel.dart';
import '../widgets/paywall_button.dart';
import '../data/games.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _channel = MethodChannel('com.aether/engine_bridge');
  int _bottomIndex = 0;
  bool _busy = false;

  // Engine stats (default values, refreshed by user)
  String _engineStatus = 'STOPPED';
  int _targetPid = -1;
  String _targetModule = '';
  int _uptimeMs = 0;
  int _readCount = 0;
  int _scanCount = 0;
  int _svcProxies = 0;

  // Virtual app state (Phase 3.5.D)
  String _realPackage = 'com.aether';
  String _fakePackage = 'com.aether';
  int _redirectCount = 0;
  String _fakeDataDir = '';
  String _fakeNativeLibDir = '';
  String _vfsTest = '';
  bool _virtualTarget = false;
  int _classRuleCount = 0;

  // Last operation results
  String _lastMemHex = '';
  String _lastScanAddr = '';
  String _lastCompute = '';
  String _lastPayload = '';
  String _lastOp = '';

  final _pkgController = TextEditingController(text: 'com.miniclip.eightballpool');

  GameInfo get _game => Games.defaultGame;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _refreshStats());
  }

  @override
  void dispose() {
    _pkgController.dispose();
    super.dispose();
  }

  // ══════════════════════════════════════════
  //  Helpers
  // ══════════════════════════════════════════

  Future<void> _snack(String msg) async {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
  }

  Future<void> _busyWrap(Future<void> Function() task) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await task();
    } on PlatformException catch (e) {
      await _snack('Bridge error: ${e.message ?? e.code}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  // ══════════════════════════════════════════
  //  Engine actions (call into EngineBridge)
  // ══════════════════════════════════════════

  Future<void> _refreshStats() async {
    await _busyWrap(() async {
      final s = await _channel.invokeMethod<Map<Object?, Object?>>('getEngineStats');
      if (s == null || !mounted) return;
      final attached = s['attached'] == true;
      final running = s['running'] == true;
      final initialized = s['initialized'] == true;
      final pid = s['targetPid'];
      final mod = s['targetModule']?.toString() ?? '';
      final uptime = s['uptimeMs'] ?? 0;
      final rc = s['readCount'] ?? 0;
      final sc = s['scanCount'] ?? 0;
      final spc = s['serviceProxyCount'] ?? 0;
      final status = running
          ? 'RUNNING'
          : attached
              ? 'ATTACHED'
              : initialized
                  ? 'INITIALIZED'
                  : 'STOPPED';
      setState(() {
        _engineStatus = status;
        _targetPid = pid is int ? pid : -1;
        _targetModule = mod;
        _uptimeMs = uptime is int ? uptime : 0;
        _readCount = rc is int ? rc : 0;
        _scanCount = sc is int ? sc : 0;
        _svcProxies = spc is int ? spc : 0;
        _lastOp = 'refreshed';
      });
      // Phase 3.5.D: also refresh virtual app status
      await _refreshVirtualApp();
    });
  }

  Future<void> _refreshVirtualApp() async {
    try {
      final s = await _channel.invokeMethod<Map<Object?, Object?>>('getVirtualAppStatus');
      if (s == null || !mounted) return;
      setState(() {
        _realPackage = s['realPackage']?.toString() ?? 'com.aether';
        _fakePackage = s['fakePackage']?.toString() ?? 'com.aether';
        _redirectCount = s['redirectCount'] is int ? s['redirectCount'] as int : 0;
        _fakeDataDir = s['fakeDataDir']?.toString() ?? '';
        _fakeNativeLibDir = s['fakeNativeLibDir']?.toString() ?? '';
        _virtualTarget = s['virtualTarget'] == true;
        _classRuleCount = s['classRuleCount'] is int ? s['classRuleCount'] as int : 0;
      });
    } catch (e) {
      // Ignore — virtual app status is optional
    }
  }

  Future<void> _testVirtualFS() async {
    await _busyWrap(() async {
      final r = await _channel.invokeMethod<String>('testVirtualFS');
      if (!mounted) return;
      setState(() {
        _vfsTest = r ?? 'unknown';
        _lastOp = 'vfs test: $_vfsTest';
      });
    });
  }

  Future<void> _launchGameInProcess() async {
    // Play = Virtualize (in-process, no external Intent).
    // Precheck: refuse to virtualize a target that isn't installed on this device,
    // so we never silently fail and never auto-dispatch an external Intent.
    final pkg = _game.packageName;
    if (pkg.isEmpty) {
      await _snack('No target package configured');
      return;
    }
    await _busyWrap(() async {
      final installed = await _channel.invokeMethod<bool>(
        'isTargetInstalled',
        {'packageName': pkg},
      );
      if (installed != true) {
        await _snack('$pkg not installed — install it first, then press Play');
        return;
      }
      final r = await _channel.invokeMethod<Map<Object?, Object?>>(
        'launchInSandbox',
        {'packageName': pkg},
      ) ?? const {};
      final ok = r['ok'] == true;
      final stage = r['stage'] ?? '?';
      final reason = r['reason'] ?? '';
      await _snack(ok
          ? 'Virtualizing $pkg (in-process, stage=$stage) — see Diag'
          : 'Virtualize FAILED at hop [$stage]: $reason');
      await _refreshVirtualApp();
      await _refreshStats();
    });
  }

  Future<void> _readMemory() async {
    await _busyWrap(() async {
      final r = await _channel.invokeMethod<Map<Object?, Object?>>(
        'readMemory',
        {'address': 0, 'size': 32},
      );
      if (r == null) {
        await _snack('readMemory: not attached or base=0');
        return;
      }
      final hex = r['hex']?.toString() ?? '';
      final addr = r['address'];
      setState(() {
        _lastMemHex = hex;
        _lastOp = 'readMemory: 32B @ 0x${addr is int ? addr.toRadixString(16) : '?'}';
      });
    });
  }

  Future<void> _scanAOB() async {
    await _busyWrap(() async {
      // ELF magic — likely at offset 0 of any .so
      final addr = await _channel.invokeMethod<int>(
        'scanAOB',
        {'hex': '7f454c46', 'mask': 'xxxx'},
      );
      if (addr == null || addr == 0) {
        await _snack('scanAOB: no match (ELF magic not in .text range)');
        return;
      }
      setState(() {
        _lastScanAddr = '0x${addr.toRadixString(16)}';
        _lastOp = 'scanAOB ELF magic at $_lastScanAddr';
      });
    });
  }

  Future<void> _runNativeCompute() async {
    await _busyWrap(() async {
      final hex = await _channel.invokeMethod<String>(
        'nativeCompute',
        {'input': 0x12345678},
      );
      if (hex == null || hex.isEmpty) {
        await _snack('nativeCompute failed');
        return;
      }
      setState(() {
        _lastCompute = hex;
        _lastOp = 'nativeCompute(0x12345678) = $hex';
      });
    });
  }

  Future<void> _compressPayload() async {
    await _busyWrap(() async {
      const input = '48656c6c6f2057656261';  // "Hello Weba"
      final hex = await _channel.invokeMethod<String>(
        'compressPayload',
        {'hex': input},
      );
      if (hex == null || hex.isEmpty) {
        await _snack('compressPayload failed');
        return;
      }
      setState(() {
        _lastPayload = 'deflate($input) = $hex';
        _lastOp = 'payload: ${hex.length ~/ 2}B compressed';
      });
    });
  }

  Future<void> _launchApp() async {
    final pkg = _pkgController.text.trim();
    if (pkg.isEmpty) {
      await _snack('Enter a package name (e.g. com.miniclip.eightballpool)');
      return;
    }
    await _busyWrap(() async {
      final ok = await _channel.invokeMethod<bool>(
        'launchApp',
        {'packageName': pkg},
      );
      await _snack(ok == true
          ? 'Launched $pkg'
          : '$pkg not installed or not launchable');
    });
  }

  Future<void> _launchInSandbox() async {
    final pkg = _pkgController.text.trim();
    if (pkg.isEmpty) {
      await _snack('Enter a package name (e.g. com.miniclip.eightballpool)');
      return;
    }
    await _busyWrap(() async {
      final installed = await _channel.invokeMethod<bool>(
        'isTargetInstalled',
        {'packageName': pkg},
      );
      if (installed != true) {
        await _snack('$pkg not installed — install it first, then try again');
        return;
      }
      final r = await _channel.invokeMethod<Map<Object?, Object?>>(
        'launchInSandbox',
        {'packageName': pkg},
      ) ?? const {};
      final okB = r['ok'] == true;
      final stageS = (r['stage'] ?? '?').toString();
      final reasonS = (r['reason'] ?? '').toString();
      await _refreshVirtualApp();
      await _snack(okB
          ? 'Virtualized $pkg (in-process, stage=$stageS)'
          : 'FAILED [$stageS]: $reasonS');
    });
  }

  Future<void> _showDiag() async {
    await _busyWrap(() async {
      final diag = await _channel.invokeMethod<String>('readDiag') ?? '(empty)';
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF11151A),
          title: const Text('Diagnostics (in-app trace)',
              style: TextStyle(color: Colors.white, fontSize: 14)),
          content: SizedBox(
            width: double.maxFinite,
            child: SingleChildScrollView(
              child: SelectableText(
                diag,
                style: const TextStyle(
                    color: Color(0xFF9CCC65), fontSize: 10, fontFamily: 'monospace'),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Close'),
            ),
          ],
        ),
      );
    });
  }

  // ─── "1234" test key — จำลองปุ่มเริ่มเกม: ตรวจ call stack ทีละ hop ───
  // ปุ่ม Play จริง: isTargetInstalled → launchInSandbox → Orchestrator →
  // VirtualAppContainer → ServiceBinderProxy(sCache) → GuestProcessTable →
  // ProxyContentProvider.call("_Engine_|_init_process_") → child holder
  // คีย์ 1234 เรียก chainCheck(hop 1-6 ในเครื่อง) + handshakeStatus (provider call
  // จริงข้าม :p0) แล้วแสดงผลเป็นข้อความ — ไม่ต้อง launch เกมก็เห็นสอดคล้องกัน
  Future<void> _chainCheck1234() async {
    await _busyWrap(() async {
      final pkg = _game.packageName;
      final hops = await _channel.invokeMethod<String>(
        'chainCheck', {'packageName': pkg});
      final hs = await _channel.invokeMethod<String>('handshakeStatus');
      if (!mounted) return;
      final report = '═══ KEY 1234 — call-stack chain check ═══\n'
          '${hops ?? "(null)"}\n'
          '── provider handshake (a7.m simulation, diag slot 3) ──\n'
          '${hs ?? "(null)"}\n\n'
          'PASS = ทุก hop ตอบสอดคล้อง; "REAL"/"✗" = จุดที่ chain ขาด';
      setState(() => _lastOp = 'chainCheck done');
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF11151A),
          title: const Text('Chain check — key 1234',
              style: TextStyle(color: Colors.white, fontSize: 14)),
          content: SizedBox(
            width: double.maxFinite,
            child: SingleChildScrollView(
              child: SelectableText(
                report,
                style: const TextStyle(
                    color: Color(0xFF9CCC65), fontSize: 10, fontFamily: 'monospace'),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Close'),
            ),
          ],
        ),
      );
    });
  }

  void _readMore() => _snack('Coming soon');
  void _getSubscription() => _snack('Subscription API not available in offline build');

  // ══════════════════════════════════════════
  //  Build UI
  // ══════════════════════════════════════════

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context).languageCode;
    return Scaffold(
      backgroundColor: const Color(0xFF101418),
      body: SafeArea(
        child: Column(
          children: [
            _buildAppBar(locale),
            BannerCarousel(onReadMore: _readMore),
            _buildEnginePanel(),
            const SizedBox(height: 8),
            _buildCapabilityButtons(),
            const SizedBox(height: 8),
            _buildLaunchAppRow(),
            const Spacer(),
            PaywallButton(onTap: _getSubscription),
            const SizedBox(height: 8),
            _buildBottomNav(),
          ],
        ),
      ),
    );
  }

  Widget _buildAppBar(String locale) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: const Color(0xFF1F2329),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.person_outline, color: Colors.white, size: 18),
                const SizedBox(width: 8),
                Text(
                  S.defaultUserId,
                  style: const TextStyle(
                    color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0xFF00C853),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: const Text(
                    S.defaultVipBadge,
                    style: TextStyle(
                      color: Colors.white, fontSize: 10, fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const Spacer(),
          IconButton(
            icon: const Icon(Icons.refresh, color: Colors.white70),
            onPressed: _busy ? null : _refreshStats,
            tooltip: 'Refresh engine stats',
          ),
        ],
      ),
    );
  }

  Widget _buildEnginePanel() {
    Color statusColor() {
      switch (_engineStatus) {
        case 'RUNNING': return const Color(0xFF00C853);
        case 'ATTACHED': return const Color(0xFFFFB300);
        case 'INITIALIZED': return const Color(0xFFFF6F00);
        default: return const Color(0xFF6B7280);
      }
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFF1A1E25),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0xFF2A2F3A), width: 0.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(width: 10, height: 10,
                  decoration: BoxDecoration(color: statusColor(), shape: BoxShape.circle)),
                const SizedBox(width: 8),
                Text(
                  'ENGINE $_engineStatus',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.0,
                  ),
                ),
                const Spacer(),
                Text('PID=$_targetPid', style: const TextStyle(color: Colors.white60, fontSize: 11)),
              ],
            ),
            const SizedBox(height: 6),
            Text('module: ${_targetModule.isEmpty ? '—' : _targetModule}',
                style: const TextStyle(color: Colors.white70, fontSize: 11)),
            const SizedBox(height: 4),
            Wrap(spacing: 12, runSpacing: 4, children: [
              _stat('uptime', '$_uptimeMs ms'),
              _stat('reads', '$_readCount'),
              _stat('scans', '$_scanCount'),
              _stat('svc proxies', '$_svcProxies'),
            ]),
            if (_lastOp.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(_lastOp,
                  style: const TextStyle(color: Color(0xFF80D8FF), fontSize: 10)),
            ],
            if (_lastMemHex.isNotEmpty)
              Text('mem: ${_lastMemHex.length > 48 ? '${_lastMemHex.substring(0, 48)}…' : _lastMemHex}',
                  style: const TextStyle(color: Color(0xFFA5D6A7), fontSize: 10, fontFamily: 'monospace')),
            if (_lastScanAddr.isNotEmpty)
              Text('scan: $_lastScanAddr',
                  style: const TextStyle(color: Color(0xFFFFCC80), fontSize: 10)),
            if (_lastCompute.isNotEmpty)
              Text('compute: $_lastCompute',
                  style: const TextStyle(color: Color(0xFFCE93D8), fontSize: 10, fontFamily: 'monospace')),
            if (_lastPayload.isNotEmpty)
              Text(_lastPayload,
                  style: const TextStyle(color: Color(0xFFB39DDB), fontSize: 10, fontFamily: 'monospace')),
            if (_fakePackage != _realPackage) ...[
              const SizedBox(height: 4),
              Text('vapp: $_realPackage → $_fakePackage (redir=$_redirectCount)',
                  style: const TextStyle(color: Color(0xFF80CBC4), fontSize: 10)),
            ],
            if (_virtualTarget)
              Text('mode: virtual-target · classRules=$_classRuleCount',
                  style: const TextStyle(color: Color(0xFF80CBC4), fontSize: 10)),
            if (_fakeDataDir.isNotEmpty)
              Text('dataDir: $_fakeDataDir',
                  style: const TextStyle(color: Color(0xFF80CBC4), fontSize: 10, fontFamily: 'monospace')),
            if (_fakeNativeLibDir.isNotEmpty)
              Text('libDir: $_fakeNativeLibDir',
                  style: const TextStyle(color: Color(0xFF80CBC4), fontSize: 10, fontFamily: 'monospace')),
            if (_vfsTest.isNotEmpty)
              Text('vfs: $_vfsTest',
                  style: const TextStyle(color: Color(0xFF4FC3F7), fontSize: 10, fontFamily: 'monospace')),
          ],
        ),
      ),
    );
  }

  Widget _stat(String label, String value) {
    return Text('$label: $value',
        style: const TextStyle(color: Colors.white60, fontSize: 11));
  }

  Widget _buildCapabilityButtons() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Wrap(spacing: 8, runSpacing: 8, children: [
        _capBtn(Icons.play_circle_outline, 'Virtualize', _launchGameInProcess, const Color(0xFF00C853)),
        _capBtn(Icons.memory, 'Read mem', _readMemory, const Color(0xFF64B5F6)),
        _capBtn(Icons.search, 'Scan AOB', _scanAOB, const Color(0xFFFFB74D)),
        _capBtn(Icons.calculate, 'Compute', _runNativeCompute, const Color(0xFFBA68C8)),
        _capBtn(Icons.compress, 'Compress', _compressPayload, const Color(0xFF4DB6AC)),
        _capBtn(Icons.folder_special, 'Virtual FS', _testVirtualFS, const Color(0xFF4FC3F7)),
        _capBtn(Icons.bug_report, 'Diag', _showDiag, const Color(0xFFFF8A65)),
        _capBtn(Icons.key, '1234 chain-check', _chainCheck1234, const Color(0xFFFFD54F)),
      ]),
    );
  }

  Widget _capBtn(IconData icon, String label, Future<void> Function() onPressed, Color color) {
    // Use Color.fromARGB to avoid Color.withValues (Flutter 3.27+)
    final bg = Color.fromARGB((0.15 * 255).round(), color.red, color.green, color.blue);
    final border = Color.fromARGB((0.5 * 255).round(), color.red, color.green, color.blue);
    return ElevatedButton.icon(
      onPressed: _busy ? null : onPressed,
      icon: Icon(icon, size: 16),
      label: Text(label, style: const TextStyle(fontSize: 12)),
      style: ElevatedButton.styleFrom(
        backgroundColor: bg,
        foregroundColor: color,
        side: BorderSide(color: border),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    );
  }

  Widget _buildLaunchAppRow() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Run app — Sandbox (virtual, in-process) or Launch (external Intent)',
              style: TextStyle(color: Colors.white60, fontSize: 11)),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _pkgController,
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                  decoration: InputDecoration(
                    isDense: true,
                    filled: true,
                    fillColor: const Color(0xFF1A1E25),
                    hintText: 'com.miniclip.eightballpool',
                    hintStyle: const TextStyle(color: Colors.white38, fontSize: 12),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(8),
                      borderSide: const BorderSide(color: Color(0xFF2A2F3A)),
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(8),
                      borderSide: const BorderSide(color: Color(0xFF2A2F3A)),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: _busy ? null : _launchInSandbox,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF00897B),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                ),
                child: const Text('Sandbox', style: TextStyle(fontSize: 12)),
              ),
              const SizedBox(width: 6),
              ElevatedButton(
                onPressed: _busy ? null : _launchApp,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF7E57C2),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                ),
                child: const Text('Play Store', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildBottomNav() {
    return Container(
      height: 56,
      decoration: const BoxDecoration(
        color: Color(0xFF181C22),
        border: Border(top: BorderSide(color: Color(0xFF2A2F3A), width: 0.5)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _navItem(Icons.add_circle_outline, 0),
          _navItem(Icons.shopping_cart_outlined, 1),
          _navItem(Icons.chat_bubble_outline, 2),
          _navItem(Icons.settings_outlined, 3),
        ],
      ),
    );
  }

  Widget _navItem(IconData icon, int index) {
    final active = _bottomIndex == index;
    final color = active ? const Color(0xFF00C853) : Colors.white38;
    return InkWell(
      onTap: () {
        if (index == 0) return;
        setState(() => _bottomIndex = index);
        _snack('${S.navAdd} / ${S.navCart} / ${S.navChat} / ${S.navSettings}: not ported (offline)');
      },
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Icon(icon, color: color, size: 24),
      ),
    );
  }
}
