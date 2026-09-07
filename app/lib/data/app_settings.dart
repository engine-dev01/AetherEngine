// data/app_settings.dart — App-level settings registry (Phase 10: extensible)
//
// AetherEngine in-game menu settings (mirrors Snake Engine 2.2.6):
//   - Show lines: draw trajectory lines on table
//   - Keep lines: keep lines visible after shot
//   - Power lock: lock power at fixed value
//   - Default power: default cue power (0-100%)
//   - Auto play: auto-play game (bot mode)
//
// These are PLACEHOLDER fields for Phase 10 (UI not yet wired).
// Future phases (12-13) will read these values via the EngineBridge
// MethodChannel to control the game in real-time.
class AppSettings {
  // Basic settings (Snake menu §1)
  final bool showLines;
  final bool keepLinesAfterShot;
  final bool powerLock;
  final double defaultPower;  // 0.0-1.0 (0% to 100%)

  // Other settings (Snake menu §2)
  final bool showPockets;
  final double defaultPowerSlider;  // raw slider value (0-1)

  // Auto play settings (Snake menu §3)
  final bool enableAutoPlay;
  final String autoPlayMode;  // 'pro_fast' | 'pro_player' | 'fast_mode'
  final bool failedBreak;
  final bool illegalBreak;
  final bool prioritize9thBall;

  const AppSettings({
    this.showLines = true,
    this.keepLinesAfterShot = false,
    this.powerLock = false,
    this.defaultPower = 1.0,
    this.showPockets = true,
    this.defaultPowerSlider = 1.0,
    this.enableAutoPlay = false,
    this.autoPlayMode = 'pro_player',
    this.failedBreak = false,
    this.illegalBreak = false,
    this.prioritize9thBall = false,
  });

  /// Default settings (used by home screen "Basic settings" panel)
  static const AppSettings defaults = AppSettings();

  /// Copy with overrides — for settings panel UI
  AppSettings copyWith({
    bool? showLines,
    bool? keepLinesAfterShot,
    bool? powerLock,
    double? defaultPower,
    bool? showPockets,
    double? defaultPowerSlider,
    bool? enableAutoPlay,
    String? autoPlayMode,
    bool? failedBreak,
    bool? illegalBreak,
    bool? prioritize9thBall,
  }) {
    return AppSettings(
      showLines: showLines ?? this.showLines,
      keepLinesAfterShot: keepLinesAfterShot ?? this.keepLinesAfterShot,
      powerLock: powerLock ?? this.powerLock,
      defaultPower: defaultPower ?? this.defaultPower,
      showPockets: showPockets ?? this.showPockets,
      defaultPowerSlider: defaultPowerSlider ?? this.defaultPowerSlider,
      enableAutoPlay: enableAutoPlay ?? this.enableAutoPlay,
      autoPlayMode: autoPlayMode ?? this.autoPlayMode,
      failedBreak: failedBreak ?? this.failedBreak,
      illegalBreak: illegalBreak ?? this.illegalBreak,
      prioritize9thBall: prioritize9thBall ?? this.prioritize9thBall,
    );
  }
}
