/*
 * AOSP-accurate Intent stub (compile-time only, NOT runtime)
 *
 * Signature source: android.googlesource.com/platform/frameworks/base
 *   refs/heads/main/core/java/android/content/Intent.java
 *   (verified 2026-09-10 — only public surface AetherInstrumentation uses)
 *
 * Scope: 4 method + 1 constant needed by AetherInstrumentation.buildStubIntent
 *   - Intent() no-arg ctor
 *   - setClassName(pkg, cls): Intent
 *   - putExtra(name, value): Intent
 *   - addFlags(flags): Intent
 *   - getStringExtra(name): String?
 *   - FLAG_ACTIVITY_NEW_TASK = 0x10000000
 *
 * NOT a runtime replacement. The unit test never runs Intent methods; it only
 * calls AetherInstrumentation.buildStubIntent (which itself calls Intent()).
 * On plain JVM, Intent() is a no-arg ctor, so this stub is sufficient to make
 * `kotlinc` happy and to instantiate at test time.
 */
package android.content

class Intent {
    var component: ComponentName? = null
    var flags: Int = 0
    private val extras: MutableMap<String, String> = mutableMapOf()

    fun setClassName(pkg: String, cls: String): Intent {
        this.component = ComponentName(pkg, cls)
        return this
    }

    fun putExtra(name: String, value: String): Intent {
        extras[name] = value
        return this
    }

    fun addFlags(flags: Int): Intent {
        this.flags = this.flags or flags
        return this
    }

    fun getStringExtra(name: String): String? = extras[name]

    companion object {
        // Verified: frameworks/base/core/java/android/content/Intent.java
        //   public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
        const val FLAG_ACTIVITY_NEW_TASK: Int = 0x10000000
    }
}
