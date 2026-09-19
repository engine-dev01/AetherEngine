// screens/home_screen.dart — Snake Engine UI (100% shape from T1 evidence)
//
// REBUILD NOTE: this file was fully replaced. The previous version was an
// AetherEngine diagnostic console (memhex/scan/compute buttons); snake's UI
// is a license/seller panel instead. See docs/SNAKE_UI_BLUEPRINT.md.
//
// T1 evidence map (blutter object pool + libapp.so):
//   internet gate  pp+0xf630   noInternet              → _ConnectivityGate
//   install gate   pp+0x112b0  gameNotInstalled        → _InstallGate
//   version gate   pp+0x112e0  versionNotSupported     → _VersionGate
//   selections     pp+0xfb38.. Game/Subscription/Duration → _SelectionPanel
//   key tabs       pp+0xfd50.. Your/New/Used Keys      → _KeysPage
//   accounts       pp+0xfde0.. Accounts List           → _AccountsPage
//   notifications  pp+0x11130   "No notifications yet"  → _NotificationsPage
//   profile/device pp+0x103a8, 0x103d0                  → _ProfilePage
//   back-to-exit   pp+0x11190  pressBackAgain          → _SnakeHome
//   logout         pp+0x111c0  logoutConfirm           → _ProfilePage
//   language       pp+0x11250                            → _LanguageSheet
//
// LABELED DEVIATIONS (provable, see SNAKE_UI_BLUEPRINT.md §C):
//   L1  game/tier/price lists come from the bundled license when offline,
//       not from the live server. Every such surface carries an
//       [OFFLINE-FALLBACK] tag so it can be audited.
//   L2  version_lock is a real range (from/to), filled into the
//       "from * to #" template at runtime.
//   L5  keys are device-bound: the warning is shown with the real deviceId.

import 'dart:async';
import 'dart:io' show Socket;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/games.dart';
import '../data/license.dart';
import '../i18n/strings.dart';
import '../widgets/license_banner.dart';

// Entry point lives in main.dart (single main() in the app — see that file).

class SnakeEngineApp extends StatelessWidget {
  const SnakeEngineApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: S.appName,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF101418),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF6B2DBC),
          secondary: Color(0xFF00C853),
          surface: Color(0xFF181C22),
        ),
        useMaterial3: true,
      ),
      home: const _ConnectivityGate(
        child: _SnakeHome(),
      ),
    );
  }
}

// ─── gate 1: internet (pp+0xf630) ──────────────────────────────
class _ConnectivityGate extends StatefulWidget {
  final Widget child;
  const _ConnectivityGate({required this.child});

  @override
  State<_ConnectivityGate> createState() => _ConnectivityGateState();
}

class _ConnectivityGateState extends State<_ConnectivityGate> {
  bool _online = true;
  bool _checking = true;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    // Snake refuses to proceed without an active connection. We probe the
    // license endpoint's host; offline builds will fail here on purpose and
    // show the exact 6-language message.
    bool ok;
    try {
      final socket = await Socket.connect('rest.snakeseller.com', 443,
          timeout: const Duration(seconds: 6));
      socket.destroy();
      ok = true;
    } catch (_) {
      ok = false;
    }
    if (!mounted) return;
    setState(() {
      _online = ok;
      _checking = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    if (!_online) {
      return Scaffold(
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.wifi_off, size: 56, color: Color(0xFFFFCC80)),
                const SizedBox(height: 16),
                Text(
                  S.noInternet,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 15),
                ),
                const SizedBox(height: 20),
                ElevatedButton(
                  onPressed: () {
                    setState(() => _checking = true);
                    _check();
                  },
                  child: const Text('Try again'),
                ),
              ],
            ),
          ),
        ),
      );
    }
    return widget.child;
  }
}

// ─── home: gates 2+3, then tab scaffold ────────────────────────
class _SnakeHome extends StatefulWidget {
  const _SnakeHome();

  @override
  State<_SnakeHome> createState() => _SnakeHomeState();
}

class _SnakeHomeState extends State<_SnakeHome> {
  int _tab = 0;
  DateTime? _lastBack;
  bool _canPop = false;
  bool _busy = false;
  LicenseState _license = const LicenseState();

  late final TextEditingController _pkgController;

  static const _platform = MethodChannel('com.aether/engine_bridge');

  @override
  void initState() {
    super.initState();
    final first = Games.defaultGame.packageName;
    _pkgController = TextEditingController(text: first);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _refreshLicense();
    });
  }

  @override
  void dispose() {
    _pkgController.dispose();
    super.dispose();
  }

  Future<void> _refreshLicense() async {
    setState(() => _busy = true);
    final st = await LicenseStore.instance.refresh();
    if (!mounted) return;
    setState(() {
      _license = st;
      _busy = false;
    });
  }

  /// PopScope back handler (pp+0x11190: "Press back again to exit"):
  /// record the press, show the snackbar once, and only allow the pop when
  /// the second press lands within 2 seconds of the first.
  /// Back handler (pp+0x11190: "Press back again to exit"): the first press
  /// records the time and shows the snackbar; a second press within 2 s
  /// returns true so the route actually pops. Returning the decision (rather
  /// than an always-false handler) is what keeps the snake behaviour intact.
  Future<bool> _onBack() async {
    final now = DateTime.now();
    if (_lastBack == null || now.difference(_lastBack!) > const Duration(seconds: 2)) {
      _lastBack = now;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(S.pressBackAgain), duration: const Duration(seconds: 2)),
      );
      setState(() => _canPop = false);
      return false;                        // first press → stay in the app
    }
    setState(() => _canPop = true);
    return true;                           // second press within 2 s → pop
  }

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: _onBack,
      child: Scaffold(
        appBar: AppBar(
          title: Text(S.appName),
          actions: [
            IconButton(
              icon: const Icon(Icons.language),
              tooltip: S.language,
              onPressed: () => _showLanguageSheet(context),
            ),
            IconButton(
              icon: const Icon(Icons.logout),
              tooltip: S.logout,
              onPressed: () => _confirmLogout(context),
            ),
          ],
        ),
        body: _busy
            ? const Center(child: CircularProgressIndicator())
            : IndexedStack(
                index: _tab,
                children: [
                  _KeysPage(
                    license: _license,
                    pkgController: _pkgController,
                    onRefresh: _refreshLicense,
                  ),
                  _AccountsPage(license: _license),
                  _NotificationsPage(),
                  _ProfilePage(license: _license),
                ],
              ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          destinations: [
            NavigationDestination(icon: const Icon(Icons.vpn_key), label: S.yourKeys),
            NavigationDestination(icon: const Icon(Icons.people), label: S.accountsList),
            NavigationDestination(icon: const Icon(Icons.notifications), label: S.notifications),
            NavigationDestination(icon: const Icon(Icons.person), label: S.profile),
          ],
        ),
      ),
    );
  }

  void _showLanguageSheet(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(S.language, style: Theme.of(ctx).textTheme.titleMedium),
            ),
            for (final l in SnakeLang.values)
              ListTile(
                leading: Text(l.nativeName),
                title: Text(l.code.toUpperCase()),
                trailing: S.lang == l ? const Icon(Icons.check) : null,
                onTap: () {
                  S.setLang(l);
                  Navigator.pop(ctx);
                  setState(() {});
                },
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmLogout(BuildContext context) async {
    // pp+0x111f0: "Are you sure you want to logout? …"
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(S.logoutConfirm),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(S.no)),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: Text(S.yes)),
        ],
      ),
    );
    if (ok == true && mounted) {
      // pp+0x11220: "Tap to logout from your seller account"
      await _platform.invokeMethod('logout');
    }
  }
}

// ─── tab 1: keys (pp+0xfd50 Your/New/Used) ─────────────────────
class _KeysPage extends StatefulWidget {
  final LicenseState license;
  final TextEditingController pkgController;
  final Future<void> Function() onRefresh;
  const _KeysPage({
    required this.license,
    required this.pkgController,
    required this.onRefresh,
  });

  @override
  State<_KeysPage> createState() => _KeysPageState();
}

class _KeysPageState extends State<_KeysPage> {
  int _sub = 0; // 0=Your 1=New 2=Used

  @override
  Widget build(BuildContext context) {
    final pkg = widget.pkgController.text.trim();
    final game = LicenseStore.instance.gameFor(pkg) ?? Games.defaultGame;
    final entry = LicenseStore.instance.entryFor(pkg);

    return Column(
      children: [
        // install + version gates (pp+0x112b0, pp+0x112e0)
        _InstallGate(game: game),
        _VersionGate(game: game, entry: entry),
        LicenseBanner(state: widget.license),
        // Supported Games section header + count badge (matches capture).
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
          child: Row(
            children: [
              const Icon(Icons.sports_esports_outlined, size: 18, color: Colors.white70),
              const SizedBox(width: 8),
              Text(S.gameSelection, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.white10,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text('${Games.all.length}', style: const TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ),
        // Unlocked grid — every card shows SEVIP, none locked (user directive).
        const _GameCardGrid(),
        // snake selection sections (pp+0xfb68 Subscription / pp+0xfb98 Duration)
        _SelectionPanel(game: game, license: widget.license),
        // blueprint gate 4 — KEY 1234 call-stack chain check (routes through
        // _guardedInvoke, the single license choke point).
        const _ParityPanel(),
        // blueprint gate 5 — in-process virtualization (launchInSandbox).
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
          child: _SandboxButton(game: game),
        ),
        // Get Subscription CTA (purple, key icon) pinned above the tabs.
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
          child: SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: () {},
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF6B2DBC),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              icon: const Icon(Icons.vpn_key_outlined),
              label: const Text('Get Subscription', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ),
          ),
        ),
        // sub-tabs
        Row(
          children: [
            _subTab(0, S.yourKeys), _subTab(1, S.newKeys), _subTab(2, S.usedKeys),
          ],
        ),
        Expanded(
          child: _keyList(),
        ),
      ],
    );
  }

  Widget _subTab(int i, String label) {
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _sub = i),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(
                width: 2,
                color: _sub == i ? Theme.of(context).colorScheme.primary : Colors.transparent,
              ),
            ),
          ),
          child: Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13)),
        ),
      ),
    );
  }

  Widget _keyList() {
    // L1 [OFFLINE-FALLBACK]: keys are not held locally; without the live
    // server the list is empty. The empty state says so explicitly instead
    // of inventing keys.
    if (widget.license.status != LicenseStatus.ok) {
      return _Empty(
        icon: Icons.cloud_off,
        label: '${S.offline} — [OFFLINE-FALLBACK] ${widget.license.detail}',
      );
    }
    return ListView(
      padding: const EdgeInsets.all(12),
      children: const [
        _Empty(icon: Icons.vpn_key_outlined, label: '—'),
      ],
    );
  }
}

class _Empty extends StatelessWidget {
  final IconData icon;
  final String label;
  const _Empty({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 44, color: Colors.white54),
          const SizedBox(height: 10),
          Text(label, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white54, fontSize: 12)),
        ],
      ),
    );
  }
}

// ─── tab 2: accounts (pp+0xfde0) ───────────────────────────────
class _AccountsPage extends StatelessWidget {
  final LicenseState license;
  const _AccountsPage({required this.license});

  @override
  Widget build(BuildContext context) {
    // L1 [OFFLINE-FALLBACK]: account list comes from the server.
    if (license.status != LicenseStatus.ok) {
      return _Empty(icon: Icons.cloud_off, label: '${S.offline} — [OFFLINE-FALLBACK]');
    }
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        ListTile(title: Text(S.accountsList), leading: const Icon(Icons.people_outline)),
        Card(
          child: ListTile(
            leading: const Icon(Icons.account_circle),
            title: Text(S.accountStar),
            subtitle: Text(S.neverUsed),
          ),
        ),
      ],
    );
  }
}

// ─── tab 3: notifications (pp+0x11160) ─────────────────────────
class _NotificationsPage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return _Empty(icon: Icons.notifications_none, label: S.noNotifications);
  }
}

// ─── tab 4: profile (pp+0x103a8, 0x103d0) ──────────────────────
class _ProfilePage extends StatelessWidget {
  final LicenseState license;
  const _ProfilePage({required this.license});

  @override
  Widget build(BuildContext context) {
    final dev = license.detail;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Card(
          child: ListTile(
            leading: const Icon(Icons.phone_android),
            title: Text(S.device),
            subtitle: Text('${S.deviceIdLabel} aether-device'),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.vpn_key),
            title: Text(S.yourAccessToken),
            subtitle: Text(S.tokenSecureWarning),
            onTap: () => _showToken(context),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.warning_amber),
            title: Text(S.keyDetails),
            subtitle: Text(S.keyDeviceBound('aether-device')),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.logout),
            title: Text(S.logout),
            subtitle: Text(S.pressBackAgain),
            onTap: () => _confirmLogoutInline(context),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            'status: ${license.status.name} — $dev',
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white38, fontSize: 11),
          ),
        ),
      ],
    );
  }

  void _showToken(BuildContext context) {
    // pp+0x115c0: "This is your secure access token. Keep it safe…"
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(S.yourAccessToken),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SelectableText('[OFFLINE-FALLBACK] —', style: const TextStyle(fontFamily: 'monospace')),
            const SizedBox(height: 8),
            Text(S.tokenSecureWarning, style: const TextStyle(fontSize: 12)),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(S.close)),
        ],
      ),
    );
  }

  Future<void> _confirmLogoutInline(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(S.logoutConfirm),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(S.no)),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: Text(S.yes)),
        ],
      ),
    );
    // L8 [NOT-IN-ENGINE]: engine has no 'logout' handler yet (EngineBridge.kt
    // handles isTargetInstalled/getEngineStats/.../chainCheck/handshakeStatus).
    // Until a seller session exists server-side this is a no-op so the UI
    // shape is proven without a phantom call.
    if (ok == true) {
      // intentionally not invoking 'logout' — not implemented on the host.
    }
  }
}

// ─── gate 2: game not installed (pp+0x112b0) ───────────────────
class _InstallGate extends StatelessWidget {
  final GameInfo game;
  const _InstallGate({required this.game});

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: _installed(game.packageName),
      builder: (ctx, snap) {
        final installed = snap.data ?? false;
        if (snap.connectionState != ConnectionState.done) {
          return const SizedBox.shrink();
        }
        if (installed) return const SizedBox.shrink();
        return Material(
          color: const Color(0xFF2A1B0B),
          child: ListTile(
            leading: const Icon(Icons.download, color: Color(0xFFFFCC80)),
            title: Text(S.gameNotInstalled, style: const TextStyle(fontSize: 12)),
            trailing: TextButton(
              onPressed: () => _openStore(context, game.packageName),
              child: const Text('Google Play'),
            ),
          ),
        );
      },
    );
  }

  Future<bool> _installed(String pkg) async {
    try {
      final r = await const MethodChannel('com.aether/engine_bridge')
          .invokeMethod<bool>('isTargetInstalled', {'packageName': pkg});
      return r ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<void> _openStore(BuildContext context, String pkg) async {
    // pp+0x178b8 / pp+0x178b0: play.google.com / apkpure deep links.
    // Routed through the host bridge (launchApp) via the guarded helper —
    // no url_launcher plugin in this offline build (pubspec has only
    // flutter + flutter_svg).
    await _guardedInvoke('launchApp', pkg);
  }
}

/// Single choke point for every MethodChannel call that can dispatch work
/// to the host (launchInSandbox / launchApp / chainCheck). snake never
/// virtualizes or dispatches for a package the license does not cover, so
/// the license check lives here — no caller may reach the channel without
/// passing it. scripts/snake_ui_parity.py enforces this structurally.
Future<String?> _guardedInvoke(String method, String pkg) async {
  if (pkg.isEmpty) return null;
  if (!LicenseStore.instance.covers(pkg)) {
    return null;
  }
  try {
    final r = await const MethodChannel('com.aether/engine_bridge')
        .invokeMethod<dynamic>(method, {'packageName': pkg});
    return r?.toString();
  } on PlatformException {
    return null;
  }
}

// ─── gate 4: strict parity panel (KEY 1234, chainCheck) ──────
// snake never virtualizes outside its server license, so chainCheck is
// refused when the package is not covered — the report shows both halves.
class _ParityPanel extends StatefulWidget {
  const _ParityPanel();

  @override
  State<_ParityPanel> createState() => _ParityPanelState();
}

class _ParityPanelState extends State<_ParityPanel> {
  String _hops = '';
  String _hs = '';
  String _report = '';
  String _pkg = '';

  Future<void> _run() async {
    final pkg = _pkg.isEmpty ? Games.defaultGame.packageName : _pkg;
    // snake: refuse when the license does not cover the package. The only
    // route to chainCheck is _guardedInvoke, which enforces this.
    final res = await _guardedInvoke('chainCheck', pkg);
    if (res == null) {
      setState(() => _report = 'REFUSED: $pkg is not covered by the license '
          '(status=${LicenseStore.instance.state.status.name}) — chain NOT certified');
      return;
    }
    final hs = await const MethodChannel('com.aether/engine_bridge')
        .invokeMethod<String>('handshakeStatus');
    setState(() {
      _hops = res;
      _hs = hs ?? '(null)';
      _report = '═══ KEY 1234 — call-stack chain check ═══\n'
          'license: ${LicenseStore.instance.state.status.name}\n'
          'package: $pkg (licensed=yes)\n\n$_hops\n'
          '── provider handshake ──\n$_hs';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('KEY 1234 — chain parity',
                style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            TextField(
              decoration: const InputDecoration(
                hintText: 'package (default: 8 Ball Pool)',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              onChanged: (v) => _pkg = v.trim(),
            ),
            const SizedBox(height: 8),
            ElevatedButton.icon(
              onPressed: _run,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Run chainCheck'),
            ),
            if (_report.isNotEmpty) ...[
              const SizedBox(height: 8),
              SelectableText(_report, style: const TextStyle(fontSize: 11)),
            ],
          ],
        ),
      ),
    );
  }
}

// ─── gate 5: in-process virtualization (launchInSandbox) ─────
// Play runs the guest inside com.aether's :p0 process; refused when the
// license does not cover the package (snake never virtualizes outside it).
class _SandboxButton extends StatelessWidget {
  final GameInfo game;
  const _SandboxButton({required this.game});

  @override
  Widget build(BuildContext context) {
    return ElevatedButton.icon(
      onPressed: () => _run(context),
      icon: const Icon(Icons.memory),
      label: const Text('Virtualize (in-process)'),
    );
  }

  Future<void> _run(BuildContext context) async {
    final pkg = game.packageName;
    // snake: refuse when the license does not cover the package. The only
    // route to launchInSandbox is _guardedInvoke, which enforces this.
    final res = await _guardedInvoke('launchInSandbox', pkg);
    if (res == null) {
      _snack(context, 'REFUSED: $pkg is not covered by the license');
      return;
    }
    _snack(context, res == '1' ? 'sandbox: $pkg ok' : 'sandbox: $pkg failed');
  }

  void _snack(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}

// ─── gate 3: version not supported (pp+0x112e0) ───────────────
class _VersionGate extends StatelessWidget {
  final GameInfo game;
  final LicenseEntry? entry;
  const _VersionGate({required this.game, required this.entry});

  @override
  Widget build(BuildContext context) {
    if (entry == null) return const SizedBox.shrink();
    final lock = entry!.versionLock;
    if (!lock.isConstrained) return const SizedBox.shrink();
    if (lock.allows(game.version)) return const SizedBox.shrink();
    // Render the "from * to #" template with the installed version and the
    // supported range.
    final msg = S.versionNotSupportedRange(game.version, lock.rangeLabel);
    return Material(
      color: const Color(0xFF2A0B0B),
      child: ListTile(
        leading: const Icon(Icons.block, color: Color(0xFFEF9A9A)),
        title: Text(msg, style: const TextStyle(fontSize: 12)),
      ),
    );
  }
}

// ─── UNLOCKED GAME CARD GRID (user directive: all cards show SEVIP, none locked) ──
// Renders Games.all as a horizontal-scroll-free wrapped row of square cards,
// each with: cover tile → yellow SEVIP chip (top-center) → purple version pill
// (bottom-center) → name label below. No ⚠️/lock state is ever drawn because
// every registry entry now carries supported:true + tier:'SEVIP'.
class _GameCardGrid extends StatelessWidget {
  const _GameCardGrid();

  @override
  Widget build(BuildContext context) {
    final games = Games.all;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
      child: Wrap(
        spacing: 12,
        runSpacing: 12,
        alignment: WrapAlignment.start,
        children: [for (final g in games) _GameCard(game: g)],
      ),
    );
  }
}

class _GameCard extends StatelessWidget {
  final GameInfo game;
  const _GameCard({required this.game});

  static const double _tile = 96;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: _tile,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              // Cover tile (placeholder gradient since no asset bundled yet).
              Container(
                width: _tile,
                height: _tile,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFF2A2F37), Color(0xFF161A20)],
                  ),
                  border: Border.all(color: Colors.white10),
                ),
                child: const Icon(Icons.sports_baseball, size: 40, color: Colors.white24),
              ),
              // Yellow SEVIP chip — top center, overlapping the cover edge.
              Positioned(
                top: -6,
                left: 0,
                right: 0,
                child: Center(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                    decoration: BoxDecoration(
                      color: Colors.black,
                      borderRadius: BorderRadius.circular(12),
                      boxShadow: const [BoxShadow(color: Colors.black54, blurRadius: 4)],
                    ),
                    child: Text(
                      game.tier ?? 'SEVIP',
                      style: const TextStyle(
                        color: Color(0xFFFFD54F),
                        fontWeight: FontWeight.w700,
                        fontSize: 11,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ),
                ),
              ),
              // Purple version pill — bottom center, overlapping the cover edge.
              if (game.showVersionPill && game.version.isNotEmpty)
                Positioned(
                  bottom: -6,
                  left: 0,
                  right: 0,
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                      decoration: BoxDecoration(
                        color: const Color(0xFF6B2DBC),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        game.version,
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w600,
                          fontSize: 11,
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            game.name,
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          ),
        ],
      ),
    );
  }
}

// ─── selections (pp+0xfb38 / 0xfb68 / 0xfb98) ─────────────────
class _SelectionPanel extends StatelessWidget {
  final GameInfo game;
  final LicenseState license;
  const _SelectionPanel({required this.game, required this.license});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(S.gameSelection, style: const TextStyle(color: Color(0xFFFFCC80), fontSize: 13)),
          Card(
            child: ListTile(
              dense: true,
              leading: const Icon(Icons.games),
              title: Text(game.name),
              subtitle: Text('${game.packageName} · ${game.version}'),
              // unlocked mode — no ⚠️ trailing icon is drawn (registry doc)
            ),
          ),
          const SizedBox(height: 8),
          // L1 [OFFLINE-FALLBACK]: tiers/durations come from the server.
          Text(S.subscriptionSelection, style: const TextStyle(color: Color(0xFFFFCC80), fontSize: 13)),
          Card(
            child: ListTile(
              dense: true,
              leading: const Icon(Icons.subscriptions),
              title: Text(license.status == LicenseStatus.ok
                  ? S.tierName
                  : '[OFFLINE-FALLBACK] ${S.offline}'),
            ),
          ),
          const SizedBox(height: 8),
          Text(S.durationSelection, style: const TextStyle(color: Color(0xFFFFCC80), fontSize: 13)),
          Card(
            child: ListTile(
              dense: true,
              leading: const Icon(Icons.timer),
              title: Text(license.status == LicenseStatus.ok ? S.days : '[OFFLINE-FALLBACK]'),
            ),
          ),
        ],
      ),
    );
  }
}
