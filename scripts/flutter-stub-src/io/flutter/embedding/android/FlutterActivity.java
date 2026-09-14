package io.flutter.embedding.android;

import android.app.Activity;
import android.os.Bundle;
import io.flutter.embedding.engine.FlutterEngine;

/**
 * shape-only stub — full_compile gate (flutter 3.22 embedding v2 surface).
 * onCreate เปิดเป็น protected (≡ Activity) เพื่อให้ override ได้;
 * configureFlutterEngine คือจุด init ที่ EngineBridge ต่อ messenger.
 */
public class FlutterActivity extends Activity {
  @Override protected void onCreate(Bundle savedInstanceState) {}
  public void configureFlutterEngine(FlutterEngine flutterEngine) {}
}
