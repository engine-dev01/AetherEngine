package android.app

import android.content.Context

open class Application {
    open val packageName: String? = null
    open val packageManager: android.content.pm.PackageManager? = null
    open val resources: android.content.res.Resources? = null
}
