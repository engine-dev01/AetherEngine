// widgets/key_card.dart — key detail card
//
// Mirrors the Key Details fields found in the pool:
//   Tier Name / Locked For / Activated For / Unlock it / Lock it
// (pp+0xfe68..0x10118). When the server is unreachable the card carries an
// [OFFLINE-FALLBACK] tag instead of fabricating values (blueprint §C L1).

import 'package:flutter/material.dart';
import '../i18n/strings.dart';

class KeyCard extends StatelessWidget {
  final String tier;
  final String? lockedFor;
  final String? activatedFor;
  final String date;
  final bool locked;
  final VoidCallback? onToggleLock;
  final bool offline;

  const KeyCard({
    super.key,
    required this.tier,
    this.lockedFor,
    this.activatedFor,
    required this.date,
    required this.locked,
    this.onToggleLock,
    this.offline = false,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text('${S.tierName}: $tier',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
                Icon(locked ? Icons.lock : Icons.lock_open, size: 16),
              ],
            ),
            const Divider(height: 12),
            if (lockedFor != null) _row(S.lockedFor, lockedFor!),
            if (activatedFor != null) _row(S.activatedFor, activatedFor!),
            _row('Date', date),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: onToggleLock,
                  child: Text(locked ? S.unlockIt : S.lockIt),
                ),
              ],
            ),
            if (offline)
              const Text('[OFFLINE-FALLBACK]',
                  style: TextStyle(color: Colors.white38, fontSize: 10)),
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Text('$label: $value', style: const TextStyle(fontSize: 12)),
    );
  }
}
