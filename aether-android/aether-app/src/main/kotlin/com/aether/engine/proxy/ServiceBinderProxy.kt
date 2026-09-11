package com.aether.engine.proxy

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ServiceBinderProxy — 8-Service Binder Proxy (8 system services)
 *
 * สร้าง fake binder objects สำหรับระบบ services 8 ตัว:
 * 1. IActivityManager     — activity lifecycle control
 * 2. IPackageManager      — package query/modify
 * 3. IJobScheduler        — background job control
 * 4. IStorageManager      — storage/volume info
 * 5. IUserManager         — user profile info
 * 6. IAccountManager      — account/auth info
 * 7. ILocationManager     — location data
 * 8. INotificationManager — notification control
 *
 * แต่ละ proxy:
 * - สร้าง via java.lang.reflect.Proxy (dynamic proxy)
 * - Intercepts method calls → delegate to real system service
 * - สามารถ override return values สำหรับ detection bypass
 * - Thread-safe (per-service singleton)
 *
 * ใช้คู่กับ VirtualFS.setupForApp() เพื่อ redirect package paths
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
        SERVICE_NETWORK_STATS, SERVICE_DISPLAY, SERVICE_CLIPBOARD_PRIMARY
    )

    // ─── Cache ───
    private val proxyCache = mutableMapOf<String, Any>()
    private val realServiceCache = mutableMapOf<String, Any>()

    // ─── Package Override ───
    private var overridePackage: String = ""
    private var originalPackage: String = ""

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

            // สร้าง proxy สำหรับแต่ละ service
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

            Log.i(TAG, "Initialized ${proxyCache.size} service proxies")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize service proxies: ${e.message}")
        }
    }

    /**
     * ดึง proxy object สำหรับ service ที่ต้องการ
     */
    fun getProxy(serviceName: String): Any? {
        return proxyCache[serviceName]
    }

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
     * ตรวจสอบว่า proxy พร้อมใช้งานหรือไม่
     */
    fun isInitialized(): Boolean = proxyCache.isNotEmpty()

    /**
     * Clear all cached proxies (เรียกตอน engine shutdown)
     */
    fun shutdown() {
        proxyCache.clear()
        realServiceCache.clear()
        Log.i(TAG, "Service proxies cleared")
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
            // ดึง real binder จาก ServiceManager
            val realBinder = getBinderFromServiceManager(svcManager, serviceNameKey)
            if (realBinder == null) {
                Log.w(TAG, "Cannot find binder for service: $serviceNameKey")
                return
            }

            // ดึง IInterface class สำหรับ service นี้
            val iInterfaceClass = getIInterfaceClass(serviceNameKey)
            if (iInterfaceClass == null) {
                Log.w(TAG, "Cannot find IInterface class for: $serviceNameKey")
                return
            }

            // สร้าง proxy object
            val proxy = Proxy.newProxyInstance(
                iInterfaceClass.classLoader,
                arrayOf(iInterfaceClass),
                ServiceInvocationHandler(realBinder, iInterfaceClass, serviceName)
            )

            proxyCache[serviceName] = proxy
            realServiceCache[serviceName] = realBinder

            Log.d(TAG, "Created proxy for $serviceName (${iInterfaceClass.simpleName})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create proxy for $serviceName: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    //  InvocationHandler
    // ══════════════════════════════════════════

    /**
     * InvocationHandler สำหรับ proxy method calls
     * Intercepts → delegate to real binder → optionally override results
     */
    private class ServiceInvocationHandler(
        private val realBinder: Any,
        private val iInterfaceClass: Class<*>,
        private val serviceName: String
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            //  intercept special methods
            val methodName = method.name

            // ── Package query interception ──
            if (serviceName == SERVICE_PACKAGE) {
                return handlePackageCall(method, args)
            }

            // ── Activity manager interception ──
            if (serviceName == SERVICE_ACTIVITY) {
                return handleActivityCall(method, args)
            }

            // ── Default: delegate to real binder ──
            return try {
                val asBinderMethod = realBinder.javaClass.getMethod("asBinder")
                val binder = asBinderMethod.invoke(realBinder)

                if (binder is IBinder) {
                    // ใช้ transact ผ่าน Binder
                    handleBinderTransact(binder, method, args)
                } else {
                    // Fallback: direct method invocation
                    method.invoke(realBinder, *(args ?: emptyArray()))
                }
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

                // Override package name ถ้ามี
                val modifiedArgs = args?.map { arg ->
                    if (arg is String && arg == originalPackage && overridePackage.isNotEmpty()) {
                        overridePackage
                    } else {
                        arg
                    }
                }?.toTypedArray()

                val asBinderMethod = realBinder.javaClass.getMethod("asBinder")
                val binder = asBinderMethod.invoke(realBinder) as? IBinder
                    ?: return method.invoke(realBinder, *(modifiedArgs ?: emptyArray()))

                handleBinderTransact(binder, method, modifiedArgs)
            } catch (e: Exception) {
                Log.w(TAG, "Package proxy call failed: ${e.message}")
                null
            }
        }

        private fun handleActivityCall(method: Method, args: Array<out Any>?): Any? {
            // Override package ใน ActivityManager calls
            return handlePackageCall(method, args)
        }

        private fun handleBinderTransact(binder: IBinder, method: Method, args: Array<out Any>?): Any? {
            // สำหรับ IInterface ที่มี asBinder() method
            // ใช้ transact() ผ่าน Binder interface
            try {
                // ลอง direct invocation ก่อน
                return method.invoke(realBinder, *(args ?: emptyArray()))
            } catch (e: Exception) {
                Log.w(TAG, "Binder transact failed: ${e.message}")
                return null
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
e(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "IInterface class not found for $serviceName")
            null
        }
    }
}
nager"
                else -> return null
            }
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "IInterface class not found for $serviceName")
            null
        }
    }
}
