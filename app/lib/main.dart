// main.dart — AetherEngine Flutter shell (home screen — 8 Ball Pool only)
// UI: banner + game card + paywall + bottom nav
// Excluded: subscription/device API calls, social share links
import 'package:flutter/material.dart';
import 'screens/home_screen.dart';
import 'i18n/strings.dart';

void main() => runApp(const AetherApp());

class AetherApp extends StatelessWidget {
  const AetherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: S.appNameTitle,
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
      home: const HomeScreen(),
    );
  }
}
