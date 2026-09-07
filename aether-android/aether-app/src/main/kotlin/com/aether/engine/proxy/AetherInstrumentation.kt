package com.aether.engine.proxy

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * AetherInstrumentation — Activity virtualization via Instrumentation hook
 * (Scaffold-4). Standard VirtualApp/DroidPlugin pattern; NO native ART hook
 * (the prototype's Native.update is RWX runtime-generated and cannot be ported
 * byte-exact — see docs/SCAFFOLD4_ACTIVITY_VIRTUALIZATION.md).
 *
 * Two intercepts on the process's ActivityThread.mInstrumentation:
 *
 *   Hook A — execStartActivity (hidden, via reflection guard):
 *     rewrite an Intent targeting a GUEST activity (not in our manifest) into
 *     the registered STUB (ProxyActivity$P0), stashing the real guest intent.
 *     AMS then sees a component IT KNOWS → launches in :p0 instead of routing
 *     out to the real installed app.
 *
 *   Hook B — newActivity (public API override):
 *     when ActivityThread builds the stub, detect the stashed guest intent and
 *     instantiate the REAL guest Activity class (guest classloader) instead.
 *
 * All reflection guarded: if a signature differs on this Android version the
 * hook degrades to pass-through (no crash), logged for device diagnosis.
 */
class AetherInstrumentation(
    private val base: Instrumentation,
    private val stubComponent: String,   // e.g. com.aether.engine.proxy.ProxyActivity$P0
    private val guestClassLoader: ClassLoader,
    private val guestApp: Application?,
) : Instrumentation() {

    companion object {
        private const val TAG = "AetherInstr"
        // Public so ProxyActivity can stash the guest target on the stub intent
        // (buildStubIntent) and newActivity (hook B) can read it back to swap.
        const val EXTRA_GUEST_INTENT = "aether.guest_intent"
        const val EXTRA_GUEST_CLASS = "aether.guest_class"

        @Volatile private var installed = false

        /**
         * Replace ActivityThread.mInstrumentation with an AetherInstrumentation
         * wrapping the current one. Idempotent + fully guarded.
         * Returns true on success.
         */
        fun install(
            stubComponent: String,
            guestClassLoader: ClassLoader,
            guestApp: Application?,
        ): Boolean {
            if (installed) return true
            return try {
                val atCls = Class.forName("android.app.ActivityThread")
                val at = atCls.getMethod("currentActivityThread").invoke(null) ?: return false
                val f = atCls.getDeclaredField("mInstrumentation")
                f.isAccessible = true
                val current = f.get(at) as? Instrumentation ?: return false
                if (current is AetherInstrumentation) { installed = true; return true }
                val wrapper = AetherInstrumentation(current, stubComponent, guestClassLoader, guestApp)
                f.set(at, wrapper)
                installed = true
                DiagLog.d(TAG, "installed (wrapping ${current.javaClass.name})")
                true
            } catch (e: Throwable) {
                DiagLog.err(TAG, "install failed", e)
                false
            }
        }

        /**
         * Build a STUB intent that AMS knows (targets [stubComponent], which is
         * registered in OUR manifest under :p0) while stashing the real guest
         * activity class + guest package in extras.
         *
         * Why not startActivity(guestIntent) directly: the guest activity is NOT
         * in our manifest, so AMS routes the intent OUT to the real installed app
         * (the 8BP process). The stub IS in our manifest → AMS launches it in :p0;
         * newActivity (hook B, a PUBLIC override) then swaps stub → real guest
         * activity using the guest classloader.
         *
         * This deliberately avoids execStartActivity (a hidden API that cannot be
         * reliably overridden on modern Android — the subclass method is never
         * invoked by ActivityThread). Rewriting the intent BEFORE startActivity is
         * the stable VirtualApp/DroidPlugin equivalent.
         */
        fun buildStubIntent(
            hostPkg: String,
            stubComponent: String,
            guestPkg: String,
            guestClass: String,
        ): Intent = Intent().apply {
            setClassName(hostPkg, stubComponent)
            putExtra(EXTRA_GUEST_CLASS, guestClass)
            putExtra(EXTRA_GUEST_INTENT, guestPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ─── Hook B: swap stub → guest activity at instantiation ───

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        val guestClass = intent?.getStringExtra(EXTRA_GUEST_CLASS)
        if (guestClass != null && className == stubComponent) {
            try {
                val act = base.newActivity(guestClassLoader, guestClass, intent)
                DiagLog.d(TAG, "newActivity swapped stub → $guestClass")
                return act
            } catch (e: Throwable) {
                DiagLog.err(TAG, "newActivity swap $guestClass failed", e)
                // fall through to stub
            }
        }
        DiagLog.d(TAG, "newActivity passthrough className=$className guestClass=$guestClass")
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        base.callActivityOnCreate(activity, icicle)
    }

    // ─── Hook A: rewrite guest-targeted startActivity → stub, stash real ───
    // execStartActivity is hidden API; we override via reflection-compatible
    // signature. Android has kept this signature stable since API 16; if it
    // differs, this override simply won't be invoked and we pass through.

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ): Any? {
        rewriteToStub(intent)
        return try {
            val m = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java, IBinder::class.java, IBinder::class.java,
                Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType,
                Bundle::class.java
            )
            m.isAccessible = true
            m.invoke(base, who, contextThread, token, target, intent, requestCode, options)
        } catch (e: Throwable) {
            DiagLog.err(TAG, "execStartActivity delegate failed", e)
            null
        }
    }

    /**
     * If [intent] targets a guest activity (a class NOT in our manifest under
     * the guest package), stash the guest class and retarget the intent at the
     * registered stub so AMS launches it in :p0 instead of routing out.
     */
    private fun rewriteToStub(intent: Intent) {
        try {
            val comp = intent.component ?: return
            val cls = comp.className
            // Guest activities live under the guest package; our stub is com.aether.*
            if (cls.startsWith("com.aether.")) return          // already ours — skip
            if (intent.getStringExtra(EXTRA_GUEST_CLASS) != null) return
            intent.putExtra(EXTRA_GUEST_CLASS, cls)
            intent.putExtra(EXTRA_GUEST_INTENT, comp.packageName)
            intent.setClassName(
                // stub lives in the host package (com.aether)
                guestApp?.let { "com.aether" } ?: "com.aether",
                stubComponent,
            )
            DiagLog.d(TAG, "rewriteToStub: $cls → $stubComponent")
        } catch (e: Throwable) {
            DiagLog.err(TAG, "rewriteToStub", e)
        }
    }
}
