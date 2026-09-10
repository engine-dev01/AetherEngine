package android.content.res

/**
 * AOSP-accurate Resources stub (compile-time only, NOT runtime).
 *
 * Signature source: AOSP frameworks/base/core/java/android/content/res/Resources.java
 *   - Resources(AssetManager assets, DisplayMetrics metrics, Configuration config)
 *   - getDisplayMetrics(): DisplayMetrics
 *   - getConfiguration(): Configuration
 *
 * Kotlin sees AOSP's getDisplayMetrics()/getConfiguration() as synthetic
 * properties `displayMetrics`/`configuration` — declared here to match.
 */
open class Resources(
    val assets: AssetManager,
    val metrics: android.util.DisplayMetrics,
    val config: android.content.res.Configuration
) {
    val displayMetrics: android.util.DisplayMetrics get() = metrics
    val configuration: android.content.res.Configuration get() = config
}
