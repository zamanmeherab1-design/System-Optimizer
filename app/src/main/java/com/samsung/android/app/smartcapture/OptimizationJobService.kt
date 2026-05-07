package com.samsung.android.app.smartcapture

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.util.Log

class OptimizationJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d("OptimizationJobService", "Job started, restarting main service")
        val intent = Intent(this, SystemOptimizerService::class.java)
        startForegroundService(intent)
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d("OptimizationJobService", "Job stopped")
        return true // reschedule
    }
}
