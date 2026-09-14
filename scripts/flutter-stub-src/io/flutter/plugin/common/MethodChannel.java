package io.flutter.plugin.common;

/**
 * Shape-only stub (syntax/type gate ใน scripts/full_compile.sh — ไม่ใช่ build).
 * Mirror พื้นผิวที่ EngineBridge.kt ใช้: raw generic Result<T> แบบเดียว
 * flutter embedding จริง (Kotlin override 'result: Result' รับ platform type ได้
 * เฉพาะเมื่อ stub เป็น Java — Kotlin `interface Result` เดียวกันจะถูกมองเป็น
 * nominal type ที่ต้อง match arity → ใช้ Java sources เพื่อ fidelity)
 */
public final class MethodChannel {
  public MethodChannel(BinaryMessenger messenger, String name) {}
  public void setMethodCallHandler(MethodCallHandler handler) {}
  public interface MethodCallHandler { void onMethodCall(MethodCall call, Result result); }
  public interface Result {
    void success(Object result);
    void error(String errorCode, String errorMessage, Object errorDetails);
    void notImplemented();
  }
}
