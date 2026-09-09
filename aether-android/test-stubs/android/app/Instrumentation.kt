package android.app

import android.content.Context
import android.os.IBinder
import android.content.Intent
import android.os.Bundle
import android.content.pm.ActivityInfo

open class Instrumentation {
    open fun newActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Activity = Activity()

    open fun callActivityOnCreate(
        activity: Activity,
        savedInstanceState: Bundle?
    ) { /* no-op */ }

    open fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Any?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityInfo? = null
}
