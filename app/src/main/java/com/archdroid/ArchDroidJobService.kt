package com.archdroid

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ArchDroidJobService : JobService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false

        val jobId = params.jobId
        val script = params.extras.getString("script") ?: getScriptForJob(applicationContext, jobId)

        if (script.isNullOrBlank()) {
            Log.w("ArchDroidJobService", "Job $jobId triggered but no script path found.")
            return false
        }

        serviceScope.launch {
            try {
                activeJobsCount++
                TerminalForegroundService.instance?.updateNotification()
                Log.i("ArchDroidJobService", "Executing scheduled Job $jobId: $script")
                val exitCode = ArchDroidScheduler.executeJobWithWakeLock(
                    applicationContext,
                    script,
                    jobId
                )
                Log.i("ArchDroidJobService", "Job $jobId execution finished with exit code $exitCode")
            } catch (e: Throwable) {
                Log.e("ArchDroidJobService", "Job $jobId failed with error: ${e.message}", e)
            } finally {
                activeJobsCount = (activeJobsCount - 1).coerceAtLeast(0)
                TerminalForegroundService.instance?.updateNotification()
                jobFinished(params, false)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Return true to request rescheduling if stopped early by system
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeJobsCount = 0
            private set
    }

    private fun getScriptForJob(context: Context, jobId: Int): String? {
        val jobs = ArchDroidScheduler.loadJobs(context)
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            if (j.optInt("id") == jobId) {
                return j.optString("script", "")
            }
        }
        return null
    }
}
