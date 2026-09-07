// widgets/game_card.dart — Single game card (8 Ball Pool only)
// Aether offline-only shows 1 hardcoded game
import 'package:flutter/material.dart';
import '../data/games.dart';

// GameInfo imported from data/games.dart (Phase 10 — single source of truth)
class GameCard extends StatelessWidget {
  const GameCard({
    super.key,
    required this.game,
    required this.onTap,
  });
  final GameInfo game;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: game.supported ? onTap : null,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFF1A1E26),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: const Color(0xFF2A2F3A),
            width: 1,
          ),
        ),
        child: Row(
          children: [
            // Cover placeholder (icon — no image asset available)
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(8),
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color(0xFFFFD700), Color(0xFF8B0000)],
                ),
              ),
              child: const Icon(
                Icons.sports_baseball,
                color: Colors.black,
                size: 32,
              ),
            ),
            const SizedBox(width: 14),
            // Name + version badge
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    game.name,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xFF6B2DBC),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      game.version,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            if (!game.supported)
              const Icon(Icons.warning_amber_rounded,
                  color: Colors.amber, size: 32),
          ],
        ),
      ),
    );
  }
}
