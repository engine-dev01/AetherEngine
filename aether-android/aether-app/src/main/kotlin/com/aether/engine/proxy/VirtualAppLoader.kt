package com.aether.engine.proxy

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * VirtualAppLoader — port of prototype `jv0.O2()` (REPORT.md §7.2).
 *
 * Loads the *target* application's `Application` object INTO the host process
 * (com.aether) so the guest runs virtualized, instead of the host merely
 * dispatching an external Intent to the installed app.
 *
 * Prototype chain (jv0.O2):
 *   createPackageContext(target, CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY)
 *     → getPackageInfoNoCheck / makeApplication (via ActivityThread + LoadedApk)
 *     → newApplication(classLoader, appClass, context)
 *     → attachBaseContext
 *     → callApplicationOnCreate(app)
 *
 * All reflection is best-effort and fully guarded: a failure returns a
 * LoadResult with success=false and a reason, never throws into the caller.
 * The target APK must be present on device (queryable) for this to succeed —
 * on a sandbox/CI host it will report a clean "package not found" instead.
 */
object VirtualAppLoader {

    private const val TAG = "AetherVAppLoader"

    // Android context flags (avoid importing hidden constants)
    private const val CONTEXT_INCLUDE_CODE = 0x00000001
    private const val CONTEXT_IGNORE_SECURITY = 0x00000002

    data class LoadResult(
        val success: Boolean,
        val reason: String,
        val targetPackage: String,
        val applicationClass: String? = null,
        val loadedApplication: Application? = null,
        val providersInstalled: Int = 0,
        val guestClassLoader: ClassLoader? = null,
    )

    @Volatile private var loaded: LoadResult? = null

    fun lastResult(): LoadResult? = loaded

    /**
     * Load [targetPkg]'s Application into this process.
     *
     * @param host       host context (com.aether Application context)
     * @param targetPkg  target package name (e.g. com.miniclip.eightballpool)
     * @param appClassHint  optional Application class from package.conf; when
     *                      null, resolved from the target's ApplicationInfo.
     * @param callOnCreate  whether to invoke the guest Application.onCreate().
     *   DEFAULT false: onCreate triggers the guest's SDK initializers
     *   (Facebook/Firebase/Unity/AppLovin) which spawn background threads that
     *   require a fully-virtualized context (currentApplication redirect +
     *   ContentProvider install + system-service hooks). Until that scaffold
     *   exists, calling onCreate crashes an uncatchable guest thread. We do the
     *   safe part (context + class + attachBaseContext) and DEFER onCreate.
     */
    fun load(
        host: Context,
        targetPkg: String,
        appClassHint: String? = null,
        callOnCreate: Boolean = false,
        providers: List<String> = emptyList(),
    ): LoadResult {
        val r = try {
            doLoad(host, targetPkg, appClassHint, callOnCreate, providers)
        } catch (t: Throwable) {
            LoadResult(false, "exception: ${t.message}", targetPkg)
        }
        loaded = r
        if (r.success) Log.i(TAG, "loaded $targetPkg (${r.applicationClass})")
        else Log.w(TAG, "load $targetPkg failed: ${r.reason}")
        return r
    }

    private fun doLoad(
        host: Context,
        targetPkg: String,
        appClassHint: String?,
        callOnCreate: Boolean,
        providers: List<String>,
    ): LoadResult {
        // 1. createPackageContext(target) — includes target's code + resources
        val targetCtx: Context = try {
            host.createPackageContext(
                targetPkg,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
        } catch (e: Throwable) {
            return LoadResult(false, "createPackageContext: ${e.message}", targetPkg)
        }

        // 2. resolve target ApplicationInfo → Application class name
        val appInfo: ApplicationInfo = try {
            host.packageManager.getApplicationInfo(targetPkg, 0)
        } catch (e: Throwable) {
            return LoadResult(false, "getApplicationInfo: ${e.message}", targetPkg)
        }
        val appClassName = appClassHint
            ?: appInfo.className
            ?: "android.app.Application"

        // 3. load the Application class via the target's classloader
        val loader = targetCtx.classLoader
        val appClass: Class<*> = try {
            loader.loadClass(appClassName)
        } catch (e: Throwable) {
            return LoadResult(false, "loadClass($appClassName): ${e.message}", targetPkg, appClassName)
        }

        // 4. instantiate via Instrumentation.newApplication (prototype makeApplication path)
        val app: Application = try {
            newApplication(appClass, targetCtx)
        } catch (e: Throwable) {
            return LoadResult(false, "newApplication: ${e.message}", targetPkg, appClassName)
        }

        // 4.5 Scaffold-1: redirect ActivityThread.currentApplication → guest.
        //     Guest SDKs read currentApplication/getInitialApplication; without
        //     this they see the host (com.aether) or null → NPE. Guarded +
        //     fallback so a version mismatch never aborts the load.
        val bound = bindCurrentApplication(app)
        Log.i(TAG, "bindCurrentApplication($targetPkg) → $bound")

        // 4.6 Scaffold-2: install the guest's ContentProviders (jv0.R2 port).
        //     Guest SDKs (Firebase/FB/AppLovin/androidx-startup...) initialize
        //     via ContentProvider.attachInfo→onCreate. Without this, their
        //     getInstance() returns null → NPE inside Application.onCreate.
        //     Must run AFTER currentApplication redirect (providers read it).
        val installed = installProviders(app, targetCtx, providers)
        Log.i(TAG, "installProviders($targetPkg) → $installed/${providers.size}")

        // 5. callApplicationOnCreate — start the guest Application lifecycle.
        //    Deferred by default: full scaffold (service hooks + launcher) not
        //    wired yet. We report the instance as loaded (attached) w/o onCreate.
        if (!callOnCreate) {
            return LoadResult(true, "attached (onCreate deferred)", targetPkg, appClassName, app, installed, loader)
        }
        try {
            callApplicationOnCreate(app)
        } catch (e: Throwable) {
            // instance exists but onCreate failed — report but keep the app ref
            return LoadResult(false, "onCreate: ${e.message}", targetPkg, appClassName, app, installed)
        }

        return LoadResult(true, "ok", targetPkg, appClassName, app, installed, loader)
    }

    /**
     * newApplication via ActivityThread's Instrumentation, mirroring the
     * framework's LoadedApk.makeApplication path (prototype t1/tg reflection).
     */
    private fun newApplication(appClass: Class<*>, context: Context): Application {
        val at = currentActivityThread()
        if (at != null) {
            val instr = fieldValue(at, "mInstrumentation")
            if (instr != null) {
                val m = instr.javaClass.getMethod(
                    "newApplication",
                    ClassLoader::class.java, String::class.java, Context::class.java
                )
                val app = m.invoke(instr, appClass.classLoader, appClass.name, context) as Application
                // newApplication attaches internally — verify, re-attach if needed.
                // A baseless app must NEVER be returned (ConfigurationController NPE).
                if (attachBaseContext(app, context)) return app
            }
        }
        // fallback: direct instantiation + attach
        val app = appClass.getDeclaredConstructor().newInstance() as Application
        if (!attachBaseContext(app, context)) {
            throw IllegalStateException("attachBaseContext failed — guest app has no base context")
        }
        return app
    }

    /**
     * Scaffold-1: point ActivityThread's "current/initial application" at the
     * guest so guest SDKs that call ActivityThread.currentApplication() or
     * AppGlobals.getInitialApplication() see the guest, not the host.
     *
     * Sets (best-effort, each guarded):
     *   ActivityThread.mInitialApplication → guest
     *   ActivityThread.mAllApplications   += guest
     * Returns true if at least mInitialApplication was set.
     */
    private fun bindCurrentApplication(app: Application): Boolean {
        val at = currentActivityThread() ?: return false
        // Guard: never bind a baseless app into mInitialApplication —
        // ConfigurationController NPEs on the next activity launch.
        if (appMBaseOrNull(app) == null) {
            Log.e(TAG, "bindCurrentApplication REFUSED: guest app has no base context")
            return false
        }
        var okInitial = false
        try {
            val f = findField(at.javaClass, "mInitialApplication")
            if (f != null) { f.isAccessible = true; f.set(at, app); okInitial = true }
        } catch (e: Throwable) {
            Log.w(TAG, "bind mInitialApplication: ${e.message}")
        }
        try {
            val f = findField(at.javaClass, "mAllApplications")
            if (f != null) {
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(at) as? ArrayList<Application>
                if (list != null && !list.contains(app)) list.add(app)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "bind mAllApplications: ${e.message}")
        }
        return okInitial
    }

    /**
     * Attach [base] to [app] via DIRECT mBase field assignment (deterministic +
     * idempotent). Returns true if the app has a valid base context after.
     */
    private fun attachBaseContext(app: Application, base: Context): Boolean {
        return try {
            var c: Class<*>? = app.javaClass
            var field: java.lang.reflect.Field? = null
            while (c != null) {
                try { field = c.getDeclaredField("mBase"); break }
                catch (e: NoSuchFieldException) { c = c.superclass }
            }
            if (field == null) {
                Log.w(TAG, "attachBaseContext: mBase field NOT FOUND in ${app.javaClass.name} hierarchy")
                return false
            }
            field.isAccessible = true
            if (field.get(app) == null) {
                field.set(app, base)
            }
            val ok = field.get(app) != null
            Log.i(TAG, "attachBaseContext: mBase=${field.get(app)?.javaClass?.name} (ok=$ok)")
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "attachBaseContext: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Base context (ContextWrapper.mBase) of [app], or null if not attached. */
    private fun appMBaseOrNull(app: Application): Any? {
        var c: Class<*>? = app.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("mBase")
                f.isAccessible = true
                return f.get(app)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            } catch (e: Throwable) {
                return null
            }
        }
        return null
    }

    /**
     * Scaffold-2: instantiate + attach the guest's ContentProviders (jv0.R2
     * port). Each provider is loaded via the guest classloader and wired with
     * ContentProvider.attachInfo(context, providerInfo), which triggers its
     * onCreate — the entry point many SDKs use to initialize (androidx-startup
     * InitializationProvider, FirebaseInitProvider, FacebookInitProvider, ...).
     *
     * Each provider is guarded independently: one failing SDK never aborts the
     * rest. Returns the count successfully attached.
     *
     * @param providers fully-qualified provider class names from package.conf.
     */
    private fun installProviders(
        app: Application,
        guestCtx: Context,
        providers: List<String>,
    ): Int {
        if (providers.isEmpty()) return 0
        val loader = guestCtx.classLoader
        var ok = 0
        for (providerClass in providers) {
            try {
                val cls = loader.loadClass(providerClass)
                val provider = cls.getDeclaredConstructor().newInstance()
                    as android.content.ContentProvider
                // Build a minimal ProviderInfo; authority uses the provider FQCN
                // (guest SDK providers resolve their own instance, not by URI).
                val info = android.content.pm.ProviderInfo().apply {
                    authority = providerClass
                    packageName = app.packageName
                    name = cls.name
                    applicationInfo = app.applicationInfo
                    exported = false
                    enabled = true
                }
                provider.attachInfo(guestCtx, info)
                ok++
                Log.d(TAG, "provider attached: $providerClass")
            } catch (e: Throwable) {
                // ClassNotFound / abstract / onCreate failure — skip, keep going
                Log.w(TAG, "provider skip $providerClass: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return ok
    }

    private fun callApplicationOnCreate(app: Application) {
        val at = currentActivityThread()
        val instr = at?.let { fieldValue(it, "mInstrumentation") }
        if (instr != null) {
            val m = instr.javaClass.getMethod("callApplicationOnCreate", Application::class.java)
            m.invoke(instr, app)
        } else {
            app.onCreate()
        }
    }

    // ─── ActivityThread reflection helpers (prototype t1 toolkit) ───

    private fun currentActivityThread(): Any? = try {
        val cls = Class.forName("android.app.ActivityThread")
        cls.getMethod("currentActivityThread").invoke(null)
    } catch (e: Throwable) {
        Log.w(TAG, "currentActivityThread: ${e.message}"); null
    }

    private fun fieldValue(obj: Any, name: String): Any? = try {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(obj)
    } catch (e: Throwable) {
        try {
            val f = obj.javaClass.superclass?.getDeclaredField(name)
            f?.isAccessible = true
            f?.get(obj)
        } catch (e2: Throwable) { null }
    }

    /** Find a declared field walking up the superclass chain (null if absent). */
    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}
