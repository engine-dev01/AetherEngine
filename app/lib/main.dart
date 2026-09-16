// main.dart — AetherEngine Flutter shell.
//
// Entry point only. The app, theme and root widget live in
// screens/home_screen.dart (SnakeEngineApp) so that the whole UI surface is
// one tree, matching the snake panel (docs/SNAKE_UI_BLUEPRINT.md).
//
// This file deliberately defines NO widget of its own: a second MaterialApp
// here would shadow the one in home_screen.dart and drop the connectivity
// gate (snake gate 1, pp+0xf630).
import 'screens/home_screen.dart';

void main() => runApp(const SnakeEngineApp());
