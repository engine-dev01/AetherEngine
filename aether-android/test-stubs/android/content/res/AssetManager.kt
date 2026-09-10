package android.content.res

/**
 * AOSP-accurate AssetManager stub (compile-time only, NOT runtime).
 *
 * Signature source: AOSP frameworks/base/core/java/android/content/res/AssetManager.java
 *   - addAssetPath(String path): Int   (public, returns cookie)
 *   - newInstance(): AssetManager     (reflection ctor used by SNAKE a5.java)
 *
 * Scope: 2 members needed by AetherInstrumentation round-8 fix
 */
open class AssetManager {
    /** AOSP: public native int addAssetPath(String path) — returns cookie */
    open fun addAssetPath(path: String): Int = 0
}
