// widgets/license_banner.dart — license status banner
//
// Shows the current LicenseStore state exactly as snake shows server states
// (Offline / unauthorized / failed / ok). Offline is labeled
// [OFFLINE-FALLBACK] so the deviation is visible and auditable
// (SNAKE_UI_BLUEPRINT.md §C L1).

import 'package:flutter/material.dart';
import '../data/license.dart';
import '../i18n/strings.dart';

class LicenseBanner extends StatelessWidget {
  final LicenseState state;
  const LicenseBanner({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final (color, icon, text) = _look();
    return Material(
      color: color,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            Icon(icon, size: 16),
            const SizedBox(width: 8),
            Expanded(
              child: Text(text, style: const TextStyle(fontSize: 11),
                  overflow: TextOverflow.ellipsis, maxLines: 2),
            ),
          ],
        ),
      ),
    );
  }

  (Color, IconData, String) _look() {
    switch (state.status) {
      case LicenseStatus.ok:
        return (const Color(0xFF1B3A24), Icons.check_circle, state.detail);
      case LicenseStatus.offline:
        return (const Color(0xFF3E2A10), Icons.cloud_off,
            '[OFFLINE-FALLBACK] ${S.offline} — ${state.detail}');
      case LicenseStatus.unauthorized:
        return (const Color(0xFF3E1010), Icons.lock, state.detail);
      case LicenseStatus.failed:
        return (const Color(0xFF3E1010), Icons.error, '${S.unknownError}: ${state.detail}');
    }
  }
}
