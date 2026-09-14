package com.aether.engine.proxy

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ServiceBinderProxy — binder proxy layer แบบ SNAKE (bt0/j8/ob parity)
 *
 * กลไก (สกัดจริงจาก jadx — transcript: reference/NATIVE_CALLSITE_MAP.md §5,
 *  T1 class/sig: reference/snake/F2_dex_natives.txt; ตัวเลขบรรทัด jadx = T2 — audit C16):
 *   1. realIface = IXxx$Stub.asInterface(ServiceManager.getService(key))
 *      (= SNAKE ob.h(): d30.java:6 / b40.java:28 — delegate ตัวจริง ไม่ใช่ raw binder)
 *   2. proxy = java.lang.reflect.Proxy(iInterfaceClass, handler)  (= ob.b():15)
 *   3. wrapper = BinderWrapper(real, proxy) ใส่ ServiceManager.sCache[key]
 *      (= j8.java:35-40 l(name) → bt0.b = sCache field, bt0.java:11)
 *      → AOSP getService() เช็ค sCache ก่อน → ทุกผู้ใช้ใน process ได้ wrapper;
 *        Stub.asInterface(wrapper) → queryLocalInterface → คืน proxy → เข้า handler
 *   4. "activity": proxy还被ใส่ IActivityManagerSingleton.mInstance (= tz.i() →
 *      uu0.m1.b/l1.b, tz.java:451) เพราะ ActivityManager ไม่อ่าน sCache
 *   5. handler delegate = method.invoke(realIface, args) (= ob.invoke fallback)
 *
 * Services ที่ init() ติดตั้งจริง 11 ตัว (constants มี 40 — SNAKE 48):
 * activity package jobscheduler mount user account location notification
 * shortcut usagestats — ตรง 11 ตัวที่ guest 8BP/GMS เรียกใน crash trace
 */
object ServiceBinderProxy {

    private const val TAG = "AetherServiceProxy"

    // ─── Service Names ───
    const val SERVICE_ACTIVITY     = "activity_manager"
    const val SERVICE_PACKAGE      = "package_manager"
    const val SERVICE_JOB          = "job_manager"
    const val SERVICE_STORAGE      = "storage_manager"
    const val SERVICE_USER          = "user_manager"
    const val SERVICE_ACCOUNT       = "account_manager"
    const val SERVICE_LOCATION      = "location_manager"
    const val SERVICE_NOTIFICATION  = "notification_manager"
    const val SERVICE_TELEPHONY     = "telephony_manager"
    const val SERVICE_WIFI          = "wifi_manager"
    const val SERVICE_NETWORK       = "connectivity_manager"
    const val SERVICE_POWER         = "power_manager"
    const val SERVICE_ALARM         = "alarm_manager"
    const val SERVICE_INPUT         = "input_manager"
    const val SERVICE_WINDOW        = "window_manager"
    const val SERVICE_LOCATION_GPS  = "gps_manager"
    const val SERVICE_CLIPBOARD     = "clipboard_manager"
    const val SERVICE_VIBRATE       = "vibrator_manager"
    const val SERVICE_AUDIO         = "audio_manager"
    const val SERVICE_CAMERA        = "camera_manager"
    const val SERVICE_SENSOR        = "sensor_manager"
    const val SERVICE_BLUETOOTH     = "bluetooth_manager"
    const val SERVICE_USB           = "usb_manager"
    const val SERVICE_THUMBNAILS    = "thumbnail_manager"
    const val SERVICE_DROPBOX       = "dropbox_manager"
    const val SERVICE_VOICE         = "voice_interaction_manager"
    const val SERVICE_INPUT_METHOD  = "input_method_manager"
    const val SERVICE_TEXT_SERVICES = "text_services_manager"
    const val SERVICE_PRINT         = "print_manager"
    const val SERVICE_SEARCH        = "search_manager"
    const val SERVICE_APPWIDGET     = "appwidget_manager"
    const val SERVICE_WALLPAPER     = "wallpaper_manager"
    const val SERVICE_ACCESSIBILITY = "accessibility_manager"
    const val SERVICE_RESTRICTIONS  = "restriction_manager"
    const val SERVICE_BATTERY       = "battery_manager"
    const val SERVICE_NETWORK_STATS = "network_stats_manager"
    const val SERVICE_DISPLAY       = "display_manager"
    const val SERVICE_CLIPBOARD_PRIMARY = "primary_clipboard_manager"
    const val SERVICE_SHORTCUT      = "shortcut_service"
    const val SERVICE_USAGE_STATS   = "usage_stats_manager"

    private val ALL_SERVICES = listOf(
        SERVICE_ACTIVITY, SERVICE_PACKAGE, SERVICE_JOB, SERVICE_STORAGE,
        SERVICE_USER, SERVICE_ACCOUNT, SERVICE_LOCATION, SERVICE_NOTIFICATION,
        SERVICE_TELEPHONY, SERVICE_WIFI, SERVICE_NETWORK, SERVICE_POWER,
        SERVICE_ALARM, SERVICE_INPUT, SERVICE_WINDOW, SERVICE_LOCATION_GPS,
        SERVICE_CLIPBOARD, SERVICE_VIBRATE, SERVICE_AUDIO, SERVICE_CAMERA,
        SERVICE_SENSOR, SERVICE_BLUETOOTH, SERVICE_USB, SERVICE_THUMBNAILS,
        SERVICE_DROPBOX, SERVICE_VOICE, SERVICE_INPUT_METHOD, SERVICE_TEXT_SERVICES,
        SERVICE_PRINT, SERVICE_SEARCH, SERVICE_APPWIDGET, SERVICE_WALLPAPER,
        SERVICE_ACCESSIBILITY, SERVICE_RESTRICTIONS, SERVICE_BATTERY,
        SERVICE_NETWORK_STATS, SERVICE_DISPLAY, SERVICE_CLIPBOARD_PRIMARY,
        SERVICE_SHORTCUT, SERVICE_USAGE_STATS
    )

    // ─── Cache ───
    private val proxyCache = mutableMapOf<String, Any>()
    private val realServiceCache = mutableMapOf<String, Any>()

    // ─── Package Override ───
    private var overridePackage: String = ""
    private var originalPackage: String = ""

    /**
     * methods ที่ String arg = CALLER IDENTITY slot (ระบบตรวจ uid↔pkg) →
     * guest→host ก่อน delegate; ทุก query ที่ pkg เป็น DATA key ส่ง guest ตรง.
     * พิสูจน์จาก device log 13:01:48: SE ×3 families ตรงกับ list นี้พอดี
     * (virtual PMS/AMS server-side P4/P5 จะค่อย ๆ โยกคำตอบกลับมาที่นี่)
     */
    private val CALLER_SPOOF_METHODS = setOf(
        "registerReceiver", "registerReceiverWithFeature", "unregisterReceiver",
        "getContentProvider", "getContentProviderExternal",
        "getIntentSender", "getIntentSenderWithFeature",
        "broadcastIntent", "broadcastIntentWithFeature",
        "bindServiceInstance", "bindService", "bindIsolatedService",
        "startService", "peekService",
        "serviceCanBind", "bindBackupAgent",
    )

    // ══════════════════════════════════════════
    //  Initialization
    // ══════════════════════════════════════════

    /**
     * Initialize all 8 service proxies
     * @param context — Application context
     * @param fakePkg — Package name ปลอม (ถ้าต้องการ override)
     */
    fun init(context: android.content.Context, fakePkg: String = "") {
        overridePackage = fakePkg
        originalPackage = context.packageName
        try {
            val svcManager = getServiceManager()

            // สร้าง proxy สำหรับแต่ละ service — KEY ที่ 3 = ชื่อจริงใน ServiceManager
            // (ServiceFetcher constants — A16 ServiceManager.java:47+ / d30.l("isub")
            //  pattern: sCache ถูก index ด้วยชื่อจริง ไม่ใช่ชื่อ "…_manager")
            createProxyForService(SERVICE_ACTIVITY, svcManager, "activity")
            createProxyForService(SERVICE_PACKAGE, svcManager, "package")
            createProxyForService(SERVICE_JOB, svcManager, "jobscheduler")
            createProxyForService(SERVICE_STORAGE, svcManager, "mount")
            createProxyForService(SERVICE_USER, svcManager, "user")
            createProxyForService(SERVICE_ACCOUNT, svcManager, "account")
            createProxyForService(SERVICE_LOCATION, svcManager, "location")
            createProxyForService(SERVICE_NOTIFICATION, svcManager, "notification")
            createProxyForService(SERVICE_SHORTCUT, svcManager, "shortcut")
            createProxyForService(SERVICE_USAGE_STATS, svcManager, "usagestats")
            createProxyForService(SERVICE_WINDOW, svcManager, "window")

            Log.i(TAG, "Initialized ${proxyCache.size} service proxies")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize service proxies: ${e.message}")
        }
    }

    /**
     * อัปเดต identity หลัง guest bind (VirtualAppContainer upgrade path) —
     * proxies ถูก install แล้ว; handler อ่าน 2 ตัวแปรนี้ทุก call (visibility ตามปกติของ object singleton)
     */
    fun setIdentity(realPkg: String, guestPkg: String) {
        originalPackage = realPkg
        overridePackage = guestPkg
        Log.i(TAG, "identity: real=$realPkg override=$guestPkg")
    }

    // ══════════════════════════════════════════
    //  Proxy accessors
    // ══════════════════════════════════════════

    /**
     * ดึง proxy object สำหรับ service ที่ต้องการ
     */
    fun getProxy(serviceName: String): Any? = proxyCache[serviceName]

    /**
     * ดึง real service object (ก่อน proxy)
     */
    fun getRealService(serviceName: String): Any? {
        return realServiceCache[serviceName]
    }

    /**
     * คืน list ของ service names ที่มี proxy แล้ว
     */
    fun listProxiedServices(): List<String> {
        return proxyCache.keys.toList()
    }

    /**
     * chainCheck ใช้พิสูจน์ "callต่อไป": sCache ของ process นี้มี wrapper ของเรา
     * จริงกี่ key จาก 11 — OK=wrapper / REAL=ของจริงครอง / EMPTY=ไม่มี
     */
    fun sCacheVerify(): String {
        val cache = serviceManagerCache() ?: return "sCache UNAVAILABLE (hidden-API?)"
        val keys = proxyCache.keys.mapNotNull { sCacheKeyOf(it) }.sorted()
        val sb = StringBuilder()
        var ok = 0
        for (k in keys) {
            val v = cache[k]
            val st = when {
                v == null -> "EMPTY"
                v.javaClass.name.contains("BinderWrapper") -> { ok++; "OK" }
                else -> "REAL"
            }
            sb.append("$k=$st ")
        }
        return "sCache $ok/${keys.size}: $sb"
    }

    /**
     * ตรวจสอบว่า proxy พร้อมใช้งานหรือไม่
     */
    fun isInitialized(): Boolean = proxyCache.isNotEmpty()

    /**
     * Clear all cached proxies (เรียกตอน engine shutdown)
     */
    fun shutdown() {
        // SNAKE ไม่ uninstall (process ตายพร้อม session) — แต่กัน :pN ที่ reuse
        // container แล้ว shutdown: ถอด wrapper ของเราออกจาก sCache ก่อน
        val cache = serviceManagerCache()
        for ((name, _) in proxyCache.toList()) {
            val key = sCacheKeyOf(name) ?: continue
            if (cache != null && cache[key] is BinderWrapper) cache.remove(key)
            proxyCache.remove(name)
            realServiceCache.remove(name)
        }
        Log.i(TAG, "Service proxies cleared (sCache restored to real binders)")
    }

    /** name → ServiceManager key ที่ init() ใช้ install (ตรวจได้, 2 ทางเลือก) */
    private fun sCacheKeyOf(name: String): String? = when (name) {
        SERVICE_ACTIVITY -> "activity"
        SERVICE_PACKAGE -> "package"
        SERVICE_JOB -> "jobscheduler"
        SERVICE_STORAGE -> "mount"
        SERVICE_USER -> "user"
        SERVICE_ACCOUNT -> "account"
        SERVICE_LOCATION -> "location"
        SERVICE_NOTIFICATION -> "notification"
        SERVICE_SHORTCUT -> "shortcut"
        SERVICE_USAGE_STATS -> "usagestats"
        SERVICE_WINDOW -> "window"
        else -> null
    }

    // ══════════════════════════════════════════
    //  Core Proxy Factory
    // ══════════════════════════════════════════

    /**
     * สร้าง dynamic proxy สำหรับ system service
     * ใช้ java.lang.reflect.Proxy สำหรับ interface-based proxy
     */
    private fun createProxyForService(serviceName: String, svcManager: Any, serviceNameKey: String) {
        try {
            if (proxyCache.containsKey(serviceName)) {
                Log.d(TAG, "Skip re-install: $serviceNameKey (already active)")
                return
            }
            // ── ดึง real binder จาก ServiceManager ──
            val realBinder = getBinderFromServiceManager(svcManager, serviceNameKey)
            if (realBinder == null) {
                Log.w(TAG, "Cannot find binder for service: $serviceNameKey")
                return
            }
            // ── ดึง IInterface class สำหรับ service นี้ ──
            val iInterfaceClass = getIInterfaceClass(serviceNameKey)
            if (iInterfaceClass == null) {
                Log.w(TAG, "Cannot find IInterface class for: $serviceNameKey")
                return
            }
            // ── realIface ผ่าน IXxx$Stub.asInterface(realBinder) ──
            // SNAKE ob.b()/d30.java:6: h() = Stub.asInterface(bt0.c.b(name)) —
            // delegate ต้องเป็น IInterface ตัวจริง (ไม่ใช่ raw IBinder ที่
            // method.invoke จะพังเงียบแบบโค้ดเดิม)
            val realIface = asInterfaceOf(iInterfaceClass, realBinder)
            if (realIface == null) {
                Log.w(TAG, "asInterface failed for: $serviceNameKey")
                return
            }

            // ── dynamic proxy (เฉพาะ interface — แบบ ob.java:15) ──
            val handler = ServiceInvocationHandler(realIface, iInterfaceClass, serviceName)
            val proxy = Proxy.newProxyInstance(
                iInterfaceClass.classLoader,
                arrayOf(iInterfaceClass),
                handler,
            )
            val wrapper = BinderWrapper(realBinder, null, serviceNameKey)
            wrapper.local = proxy            // j8.n ↔ handler (circular — set 2 จังหวะ)
            handler.boundWrapper = wrapper   // j8: this-as-IBinder

            proxyCache[serviceName] = proxy
            realServiceCache[serviceName] = realIface

            // ★ INSTALL — SNAKE j8.l(name) → bt0.b(sCache).put(name, this):
            // AOSP ServiceManager.getService() เช็ค sCache ก่อนเสมอ → guest/SDK
            // ทุกตัวใน process ได้ wrapper ตัวนี้แทน real binder;
            // Stub.asInterface(wrapper) → wrapper.queryLocalInterface(DESCRIPTOR)
            // คืน proxy เรา → ทุก call วิ่งเข้า handler (ไม่มีขั้นนี้ = ของตกแต่ง
            // — บทเรียนจาก aether-live1/2 SecurityException ทั้งวง)
            installIntoServiceManager(serviceNameKey, wrapper)

            // AMS ไม่ได้อ่านผ่าน sCache บน Android 现代: ActivityManager.getService()
            // = IActivityManagerSingleton.get() (cache mInstance) — SNAKE tz.i()
            // แทนที่ singleton ผ่าน m1.b="IActivityManagerSingleton" (tz.java:451)
            // → ทำเหมือนกัน: ยัด proxy ลง mInstance
            // 2) static caches ที่ข้าม sCache โดยตรง (AOSP16 ตรวจแล้ว):
            //    activity → ActivityManager.IActivityManagerSingleton.mInstance (tz.i)
            //    package  → ActivityThread.sPackageManager (A16 AT:2954 — เช็ค static
            //               ก่อน getService → ต้องทับ; = c20.i() t1.c.d(obj2))
            //    window   → WindowManagerGlobal.sWindowManagerService (cache ครั้งเดียว)
            when (serviceNameKey) {
                "activity" -> replaceActivityManagerSingleton(proxy)
                "package" -> patchPackageManagerCaches(proxy)
                "window" -> clearWindowManagerGlobalCache()
            }

            Log.i(TAG, "Installed $serviceNameKey: sCache + handler (SNAKE j8/ob parity)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create proxy for $serviceName: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    //  SNAKE bt0/j8/ob equivalents — the install machinery
    // ══════════════════════════════════════════

    /** IXxx${'$'}Stub.asInterface(binder) — ทางเดียวกับ d30.java:6/ob.h() ใช้ */
    private fun asInterfaceOf(iInterfaceClass: Class<*>, binder: IBinder): Any? {
        return try {
            val stub = Class.forName(iInterfaceClass.name + "${'$'}Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "asInterfaceOf ${iInterfaceClass.simpleName}: ${e.message}")
            null
        }
    }

    /** bt0.java:11 — handle ของ ServiceManager.sCache (ArrayMap<String,IBinder>) */
    private fun serviceManagerCache(): MutableMap<String, IBinder>? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val f = smClass.getDeclaredField("sCache")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            f.get(null) as? MutableMap<String, IBinder>
        } catch (e: Exception) {
            Log.w(TAG, "sCache handle failed: ${e.message}")
            null
        }
    }

    /**
     * BinderWrapper — เทียบ SNAKE j8 (j8.java:6 `extends ob implements IBinder`):
     * วัตถุจริงที่ถูกใส่เข้า ServiceManager.sCache ภายใต้ key ของ service.
     * AOSP ServiceManager.getService() เช็ค sCache ก่อน ping to servicemanager →
     * ทุกผู้ใช้ใน process ได้ wrapper นี้; Stub.asInterface() เรียก
     * queryLocalInterface(DESCRIPTOR) → คืน dynamic proxy ของเรา (j8.java:52
     * ทำแบบเดียวกัน: return g()) → method calls วิ่งเข้า handler.
     * transact() ยังคง forward ไป real binder (j8.java:61) เผื่อผู้ใช้ที่ถือ
     * binder ตรง ๆ ไม่ผ่าน asInterface.
     */
    private class BinderWrapper(
        private val real: IBinder,
        var local: Any?,
        private val key: String,
    ) : IBinder {
        override fun queryLocalInterface(descriptor: String): IInterface? = local as? IInterface
        override fun transact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean =
            real.transact(code, data, reply, flags)
        override fun getInterfaceDescriptor(): String? = real.interfaceDescriptor
        override fun pingBinder(): Boolean = real.pingBinder()
        override fun isBinderAlive(): Boolean = real.isBinderAlive()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = real.linkToDeath(recipient, flags)
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = real.unlinkToDeath(recipient, flags)
        override fun dump(fd: java.io.FileDescriptor, prefix: Array<out String>?) = real.dump(fd, prefix)
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) = real.dumpAsync(fd, args)
    }

    /**
     * j8.l(name) → sCache.put(name, wrapper) — จุด install จริง (ตัวเดียวกับ
     * ที่ Aether HEAD ขาด — สกัดจาก jadx: bt0.java:11 + j8.java:35-40)
     */
    private fun installIntoServiceManager(key: String, wrapper: BinderWrapper) {
        val cache = serviceManagerCache()
        if (cache == null) {
            Log.w(TAG, "sCache unavailable — $key stays REAL binder (hidden-API?)")
            return
        }
        try {
            cache[key] = wrapper
            Log.d(TAG, "sCache[$key] ← BinderWrapper")
        } catch (e: Exception) {
            Log.w(TAG, "sCache put $key failed: ${e.message}")
        }
    }

    /**
     * c20.i() parity (c20.java:344-346): `t1.c.d(obj2)` = เขียนทับ
     * ActivityThread.sPackageManager ด้วย proxy — A16 GetIPackageManager()
     * เช็ค static sPackageManager ก่อน (ActivityThread16.java:2954) ถ้าไม่ทับ
     * "package" ใน sCache ก็ไร้ผล
     */
    private fun patchPackageManagerCaches(proxy: Any) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val f = atClass.getDeclaredField("sPackageManager")
            f.set(null, proxy)
            Log.i(TAG, "ActivityThread.sPackageManager ← proxy (c20.t1.c parity)")
        } catch (e: Exception) {
            Log.w(TAG, "sPackageManager patch failed: ${e.message}")
        }
    }

    /**
     * ล้าง WindowManagerGlobal.sWindowManagerService ที่ cache real binder ไว้
     * ครั้งเดียว — ให้มัน re-get ผ่าน sCache ของเรา (b40 "window")
     */
    private fun clearWindowManagerGlobalCache() {
        try {
            val clz = Class.forName("android.view.WindowManagerGlobal")
            val f = clz.getDeclaredField("sWindowManagerService")
            f.set(null, null)
            Log.i(TAG, "WindowManagerGlobal.sWindowManagerService cleared → re-get via sCache")
        } catch (e: Exception) {
            Log.w(TAG, "WindowManagerGlobal cache clear failed: ${e.message}")
        }
    }

    /**
     * tz.i() equivalent — ActivityManager.IActivityManagerSingleton.mInstance ← proxy
     * (android.util.Singleton.mInstance บน superclass ของ anonymous singleton;
     * minSdk 28 → ใช้เส้นทาง API26+ นี้ทางเดียว ไม่แตะ gDefault เก่า)
     */
    private fun replaceActivityManagerSingleton(proxy: Any) {
        try {
            val am = Class.forName("android.app.ActivityManager")
            val singletonField = am.getDeclaredField("IActivityManagerSingleton")
            singletonField.isAccessible = true
            val singleton = singletonField.get(null) ?: return
            var c: Class<*>? = singleton.javaClass
            while (c != null && c != Any::class.java) {
                try {
                    val mi = c.getDeclaredField("mInstance")
                    mi.isAccessible = true
                    mi.set(singleton, proxy)
                    Log.i(TAG, "AMS singleton replaced (mInstance ← proxy @ ${c.simpleName})")
                    return
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            Log.w(TAG, "mInstance field not found on singleton chain")
        } catch (e: Exception) {
            Log.w(TAG, "AMS singleton replace failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    //  InvocationHandler
    // ══════════════════════════════════════════

    /**
     * InvocationHandler สำหรับ proxy method calls
     *
     * SNAKE ob.java parity: delegate ทุก call ที่ไม่มี handler =
     * `method.invoke(this.m, args)` เมื่อ m = realIface จาก
     * IXxx$Stub.asInterface(realBinder) — ไม่ใช่ raw IBinder (โค้ดเดิมพยายาม
     * invoke method ของ IInterface บน BinderProxy = พังเงียบ → SecurityException
     * ไม่เคยถูก intercept จริง)
     */
    private class ServiceInvocationHandler(
        private val realIface: Any,
        private val iInterfaceClass: Class<*>,
        private val serviceName: String,
    ) : InvocationHandler {

        /** j8.n ↔ BinderWrapper ใน sCache — ให้ asBinder() คืน object เดียวกับที่ system จะให้ */
        @Volatile var boundWrapper: BinderWrapper? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            //  intercept special methods
            val methodName = method.name

            // ── plumbing (Object/IInterface) — j8.java กัน method เดิมไม่ถูก invoke ──
            when {
                method.declaringClass == Any::class.java -> when (methodName) {
                    "hashCode" -> return System.identityHashCode(proxy)
                    "equals" -> return proxy === args?.getOrNull(0)
                    else -> return proxy.toString()
                }
                methodName == "asBinder" -> {
                    // IInterface.asBinder() → BinderWrapper ของเรา (object เดียวกับ sCache)
                    // ผู้ใช้ที่ถือ binder ต่อ จะเจอ queryLocalInterface → คืน proxy → กลับเข้า handler
                    return boundWrapper ?: try {
                        realIface.javaClass.getMethod("asBinder").invoke(realIface)
                    } catch (_: Exception) { null }
                }
            }

            // ── Package query interception ──
            if (serviceName == SERVICE_PACKAGE) {
                return handlePackageCall(method, args)
            }

            // ── Activity manager interception ──
            if (serviceName == SERVICE_ACTIVITY) {
                return handleActivityCall(method, args)
            }

            // ── Shortcut service interception (8BP: Play Games shortcuts) ──
            if (serviceName == SERVICE_SHORTCUT) {
                return handleShortcutCall(method, args)
            }

            // ── Usage stats (contains measurement dynamite) ──
            if (serviceName == SERVICE_USAGE_STATS) {
                return handleUsageStatsCall(method, args)
            }

            // ── Default: delegate to real IInterface (ob.invoke fallback) ──
            return try {
                method.invoke(realIface, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e   // cause เดิม → SDK เห็น exception ตรง ๆ เหมือนไม่มี proxy
            } catch (e: Exception) {
                Log.w(TAG, "Proxy call failed for $serviceName.$methodName: ${e.message}")
                null
            }
        }

        private fun handlePackageCall(method: Method, args: Array<out Any>?): Any? {
            return try {
                // Phase 3.5.B: getApplicationInfo override.
                // When the app asks for its OWN package info, return the
                // fake ApplicationInfo so any code that reads sourceDir
                // or nativeLibraryDir sees the virtual app's path, not
                // the real engine's path.
                if (method.name == "getApplicationInfo" && args != null && args.isNotEmpty()) {
                    val pkg = args[0] as? String
                    if (pkg == overridePackage) {
                        val flags = if (args.size > 1 && args[1] is Int) args[1] as Int else 0
                        return VirtualAppContainer.overrideApplicationInfo(pkg)
                    }
                }

                // ★ SNAKE caller-contract (device 13:01:48.530): ระบบจริงตรวจ
                // 'caller package ∈ process ของ uid' — process เราคือ com.aether
                // (u0a756); op-package ที่เรา spoof เป็น guest ใน framework ทำให้
                // caller-slot กลายเป็น guest = SE เสมอ (registerReceiverWithFeature
                // ฆ่า EightBallPoolActivity.onCreate, getContentProvider ×10,
                // getIntentSenderWithFeature) → caller-slot ต้องกลับเป็น HOST
                // เฉพาะ whitelist — DATA query (getPackageInfo(guest) ฯลฯ) ส่งตรง
                // ให้ real PMS คืนข้อมูลเกมจริง (version 4013 = ที่ guest SDK ต้องการ)
                val effectiveArgs = if (method.name in CALLER_SPOOF_METHODS &&
                    overridePackage.isNotEmpty()) {
                    args?.map { arg ->
                        if (arg is String && arg == overridePackage) originalPackage else arg
                    }?.toTypedArray()
                } else args
                method.invoke(realIface, *(effectiveArgs ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                // ★ logcat 00:31:20.797 (com.aether_1.zip): เดิม catch(e:Exception)
                // กลืน InvocationTargetException (message=null) → คืน null →
                // ActivityThread "Failed to find provider info for
                // com.aether.proxy.content.1" → provider-spawn slot 1-3 พังทั้งแถบ
                // = symptom-level ซ่อนต้นเหตุจริง; ทำตาม default branch ด้านบน:
                // rethrow cause เพื่อให้ AOSP เห็น NameNotFound จริง (semantics
                // เดิมของ IPackageManager — caller มี try/catch อยู่แล้ว)
                Log.w(TAG, "Package proxy rethrow ${method.name}: " +
                    "${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                throw (e.cause as? Exception) ?: Exception(e)
            } catch (e: Exception) {
                Log.w(TAG, "Package proxy call failed ${method.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        private fun handleActivityCall(method: Method, args: Array<out Any>?): Any? {
            // Override package ใน ActivityManager calls
            return handlePackageCall(method, args)
        }

        private fun handleShortcutCall(method: Method, args: Array<out Any>?): Any? {
            return try {
                val methodName = method.name
                // ป้องกัน "Calling package name mismatch" จาก Play Games shortcuts
                // (case-insensitive — จริง ๆ เกมเรียก getDynamicShortcuts ด้วย S ใหญ่)
                if (methodName.equals("getShortcuts", ignoreCase = true) ||
                    methodName.contains("shortcut", ignoreCase = true)) {
                    Log.d(TAG, "Blocked shortcut call: $methodName for ${overridePackage ?: originalPackage}")
                    // ★ device evidence (com.aether_1.zip 00:28:30 PID15854):
                    //   IShortcutService.getShortcuts ประกาศ return = ParceledListSlice —
                    //   คืน emptyList → CCE "EmptyList to ParceledListSlice" ที่
                    //   $Proxy12.getShortcuts → FATAL บน initialize-shortcuts
                    return emptyParceledListSlice()
                }
                
                // ★ SNAKE caller-contract (device 13:01:48.530): ระบบจริงตรวจ
                // 'caller package ∈ process ของ uid' — process เราคือ com.aether
                // (u0a756); op-package ที่เรา spoof เป็น guest ใน framework ทำให้
                // caller-slot กลายเป็น guest = SE เสมอ (registerReceiverWithFeature
                // ฆ่า EightBallPoolActivity.onCreate, getContentProvider ×10,
                // getIntentSenderWithFeature) → caller-slot ต้องกลับเป็น HOST
                // เฉพาะ whitelist — DATA query (getPackageInfo(guest) ฯลฯ) ส่งตรง
                // ให้ real PMS คืนข้อมูลเกมจริง (version 4013 = ที่ guest SDK ต้องการ)
                val effectiveArgs = if (method.name in CALLER_SPOOF_METHODS &&
                    overridePackage.isNotEmpty()) {
                    args?.map { arg ->
                        if (arg is String && arg == overridePackage) originalPackage else arg
                    }?.toTypedArray()
                } else args
                method.invoke(realIface, *(effectiveArgs ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                // แบบเดียวกับ handlePackageCall: ให้ caller เห็น cause จริง
                Log.w(TAG, "Shortcut proxy rethrow ${method.name}: " +
                    "${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                throw (e.cause as? Exception) ?: Exception(e)
            } catch (e: Exception) {
                Log.w(TAG, "Shortcut proxy call failed ${method.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}")
                emptyParceledListSlice() // ปลอดภัย: คืน empty ผลลัพธ์ที่ถูกประเภทเสมอ
            }
        }

        /** ผลว่างที่ type ตรงกับ AIDL return (ParceledListSlice) — fallback = emptyList
         *  เฉพาะกรณี reflection fail (device เก่าที่ class ย้าย package) */
        private fun emptyParceledListSlice(): Any = runCatching {
            Class.forName("android.content.pm.ParceledListSlice")
                .getConstructor(List::class.java)
                .newInstance(emptyList<Any>())
        }.getOrElse { emptyList<Any>() }

        private fun handleUsageStatsCall(method: Method, args: Array<out Any>?): Any? {
            return try {
                val methodName = method.name
                // ป้องกัน measurement dynamite crashes
                if (methodName.contains("usage") || methodName.contains("stats") || 
                    methodName.contains("configurationInfo") || methodName.contains("appPredictor")) {
                    Log.d(TAG, "Blocked usage/stats call: $methodName for ${overridePackage ?: originalPackage}")
                    return null // Return null แทนที่จะ throw
                }
                
                // ★ SNAKE caller-contract (device 13:01:48.530): ระบบจริงตรวจ
                // 'caller package ∈ process ของ uid' — process เราคือ com.aether
                // (u0a756); op-package ที่เรา spoof เป็น guest ใน framework ทำให้
                // caller-slot กลายเป็น guest = SE เสมอ (registerReceiverWithFeature
                // ฆ่า EightBallPoolActivity.onCreate, getContentProvider ×10,
                // getIntentSenderWithFeature) → caller-slot ต้องกลับเป็น HOST
                // เฉพาะ whitelist — DATA query (getPackageInfo(guest) ฯลฯ) ส่งตรง
                // ให้ real PMS คืนข้อมูลเกมจริง (version 4013 = ที่ guest SDK ต้องการ)
                val effectiveArgs = if (method.name in CALLER_SPOOF_METHODS &&
                    overridePackage.isNotEmpty()) {
                    args?.map { arg ->
                        if (arg is String && arg == overridePackage) originalPackage else arg
                    }?.toTypedArray()
                } else args
                method.invoke(realIface, *(effectiveArgs ?: emptyArray()))
            } catch (e: Exception) {
                Log.w(TAG, "Usage stats proxy call failed: ${e.message}")
                null // ปลอดภัย: คืนค่า null เสมอ
            }
        }

    }

    // ══════════════════════════════════════════
    //  System Service Lookup
    // ══════════════════════════════════════════

    /**
     * ดึง ServiceManager object (Android internal)
     */
    private fun getServiceManager(): Any {
        val smClass = Class.forName("android.os.ServiceManager")
        return smClass
    }

    /**
     * ดึง binder จาก ServiceManager
     */
    private fun getBinderFromServiceManager(svcManager: Any, serviceName: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder
            binder
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get service binder for $serviceName: ${e.message}")
            null
        }
    }

    /**
     * ดึง IInterface class สำหรับ service
     * (เช่น IActivityManager, IPackageManager)
     */
    private fun getIInterfaceClass(serviceName: String): Class<*>? {
        return try {
            val className = when (serviceName) {
                "activity"    -> "android.app.IActivityManager"
                "package"     -> "android.content.pm.IPackageManager"
                "jobscheduler" -> "android.app.job.IJobScheduler"
                "mount"       -> "android.os.storage.IStorageManager"
                "user"        -> "android.os.IUserManager"
                "account"     -> "android.accounts.IAccountManager"
                "location"    -> "android.location.ILocationManager"
                "notification"-> "android.app.INotificationManager"
                "telephony"   -> "android.telephony.ITelephony"
                "wifi"        -> "android.net.wifi.IWifiManager"
                "network"     -> "android.net.ConnectivityManager"
                "power"       -> "android.os.IPowerManager"
                "alarm"       -> "android.app.IAlarmManager"
                "input"       -> "android.view.IInputManager"
                "window"      -> "android.view.IWindowManager"
                "gps"         -> "android.location.IGpsLocationProvider"
                "clipboard"   -> "android.content.IClipboard"
                "vibrator"    -> "android.os.IVibrator"
                "audio"       -> "android.media.IAudioService"
                "camera"      -> "android.hardware.ICameraService"
                "sensor"      -> "android.hardware.ISensorManager"
                "bluetooth"   -> "android.bluetooth.IBluetooth"
                "usb"         -> "android.hardware.usb.IUsbManager"
                "thumbnails"  -> "android.provider.IThumbnails"
                "dropbox"     -> "android.os.IDropBoxManager"
                "voice"       -> "android.speech.IVoiceInteractionManager"
                "inputmethod" -> "android.view.inputmethod.IInputMethodManager"
                "textservices"-> "android.text.ITextServicesManager"
                "print"       -> "android.print.IPrintManager"
                "search"      -> "android.app.ISearchManager"
                "appwidget"   -> "android.app.IAppWidgetService"
                "wallpaper"   -> "android.service.wallpaper.IWallPaperService"
                "accessibility"-> "android.view.IAccessibilityManager"
                "restrictions"-> "android.content.IRestrictionsManager"
                "battery"     -> "android.os.IBatteryManager"
                "netstats"    -> "android.net.INetworkStatsService"
                "display"     -> "android.view.IDisplayManager"
                "primary_clip"-> "android.content.IPrimaryClip"
                "shortcut"    -> "android.content.pm.IShortcutService"
                "usagestats"  -> "android.app.usage.IUsageStatsManager"
                "package"     -> "android.content.pm.IPackageManager"
                "jobscheduler" -> "android.app.job.IJobScheduler"
                "mount"       -> "android.os.storage.IStorageManager"
                "user"        -> "android.os.IUserManager"
                "account"     -> "android.accounts.IAccountManager"
                "location"    -> "android.location.ILocationManager"
                "notification" -> "android.app.INotificationManager"
                else -> return null
            }
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "IInterface class not found for $serviceName")
            null
        }
    }
}
