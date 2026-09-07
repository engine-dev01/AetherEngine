package com.aether.engine.proxy

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * ProxyJobService — JobScheduler-based proxy worker (JobScheduler proxy)
 *
 * แต่ละ instance รันใน process pool แยก เพื่อรับงาน background ผ่าน JobScheduler
 * ใช้แทน Service ปกติในกรณีที่ต้องการการันตี execution แม้ app อยู่ใน background
 */
open class ProxyJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // Work dispatched to process pool — jobFinished when complete
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true // Reschedule if stopped
    }

    class P0 : ProxyJobService()
    class P1 : ProxyJobService()
    class P2 : ProxyJobService()
    class P3 : ProxyJobService()
}
