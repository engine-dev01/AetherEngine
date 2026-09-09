// screens/detect_screen.dart — P4: pack-driven scanner UI (หน้าแรกของแอพ)
// สแกน sample บนเครื่องด้วย packs ที่ bundle ใน APK — offline ทั้งเส้น
// ผล = verdict + hits ทุก rule ที่ยิง (อ้าง note หลักฐานที่มาในแต่ละ rule)
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../i18n/strings.dart';

class DetectScreen extends StatefulWidget {
  const DetectScreen({super.key});
  @override
  State<DetectScreen> createState() => _DetectScreenState();
}

class _DetectScreenState extends State<DetectScreen> {
  static const _channel = MethodChannel('com.aether/engine_bridge');
  final _pathCtrl = TextEditingController();
  bool _busy = false;
  Map<String, dynamic>? _report;

  Future<void> _pick() async {
    // ใช้ path ที่พิมพ์เอง (รองรับ file picker ในอนาคต)
    if (_pathCtrl.text.isEmpty) return;
    await _scan(_pathCtrl.text);
  }

  Future<void> _scan(String path) async {
    setState(() { _busy = true; _report = null; });
    try {
      final r = await _channel.invokeMethod<Map<Object?, Object?>>('scanSample', path);
      setState(() => _report = r?.cast<String, dynamic>());
    } on PlatformException catch (e) {
      setState(() => _report = {'error': e.message});
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(strings.detectTitle, style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 8),
        Text(strings.detectSubtitle, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 16),
        TextField(
          controller: _pathCtrl,
          decoration: InputDecoration(
            labelText: strings.detectPathLabel,
            hintText: '/storage/emulated/0/Download/SNAKE.apk',
            border: const OutlineInputBorder(),
            suffixIcon: IconButton(
              icon: _busy ? const CircularProgressIndicator() : const Icon(Icons.search),
              onPressed: _busy ? null : _pick,
            ),
          ),
        ),
        if (_report != null) ...[
          const SizedBox(height: 16),
          _verdictCard(),
          const SizedBox(height: 12),
          _hitsList(),
        ],
      ]),
    );
  }

  Widget _verdictCard() {
    final err = _report?['error'] as String?;
    if (err != null) {
      return Card(color: Colors.red.shade900, child: ListTile(
        leading: const Icon(Icons.error_outline, color: Colors.white),
        title: Text(err, style: const TextStyle(color: Colors.white)),
      ));
    }
    final packs = (_report?['packs'] as Map?)?.cast<String, dynamic>() ?? {};
    return Column(children: packs.entries.map((e) {
      final verdict = e.value['verdict'] as String? ?? '—';
      final score = e.value['score'] ?? 0.0;
      final color = verdict == 'DETECTED' ? Colors.red
          : verdict == 'SUSPICIOUS' ? Colors.orange : Colors.green;
      return Card(child: ListTile(
        leading: Icon(_verdictIcon(verdict), color: color),
        title: Text('${e.key}  →  $verdict'),
        subtitle: Text('score $score'),
      ));
    }).toList());
  }

  IconData _verdictIcon(String v) =>
      v == 'DETECTED' ? Icons.warning : v == 'SUSPICIOUS' ? Icons.help_outline : Icons.check_circle;

  Widget _hitsList() {
    final hits = (_report?['hits'] as List?) ?? [];
    if (hits.isEmpty) return const SizedBox.shrink();
    return Card(child: Column(children: [
      ListTile(title: Text(strings.detectHitsTitle)),
      ...hits.map((h) {
        final m = (h as Map).cast<String, dynamic>();
        return ListTile(dense: true,
          leading: const Icon(Icons.fiber_manual_record, size: 10),
          title: Text(m['rule'] ?? '', style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
          subtitle: Text('${m['pack'] ?? ''} · ${m['entry'] ?? ''}', style: const TextStyle(fontSize: 11)),
        );
      }),
    ]));
  }
}
