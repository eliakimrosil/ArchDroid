package com.archdroid

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ArchDroidScheduler {

    private const val TAG = "ArchDroidScheduler"
    const val MIN_PERIOD_MS = 900_000L // 15 minutes minimum enforced by Android OS

    private fun getJobsFile(context: Context): File {
        return File(context.filesDir, "archdroid_jobs.json")
    }

    @Synchronized
    fun loadJobs(context: Context): JSONArray {
        val f = getJobsFile(context)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    @Synchronized
    fun saveJobs(context: Context, jobs: JSONArray) {
        try {
            getJobsFile(context).writeText(jobs.toString(2))
        } catch (_: Throwable) {}
    }

    fun scheduleJob(
        context: Context,
        jobId: Int,
        script: String,
        periodMs: Long = MIN_PERIOD_MS,
        network: String = "any",
        batteryNotLow: Boolean = false,
        charging: Boolean = false,
        persisted: Boolean = true
    ): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false

        val effectivePeriod = periodMs.coerceAtLeast(MIN_PERIOD_MS)
        val component = ComponentName(context, ArchDroidJobService::class.java)

        val extras = PersistableBundle().apply {
            putInt("job_id", jobId)
            putString("script", script)
        }

        val netType = when (network.lowercase()) {
            "unmetered" -> JobInfo.NETWORK_TYPE_UNMETERED
            "cellular", "not_roaming" -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            "none" -> JobInfo.NETWORK_TYPE_NONE
            else -> JobInfo.NETWORK_TYPE_ANY
        }

        val builder = JobInfo.Builder(jobId, component)
            .setPeriodic(effectivePeriod)
            .setRequiredNetworkType(netType)
            .setExtras(extras)

        if (batteryNotLow) builder.setRequiresBatteryNotLow(true)
        if (charging) builder.setRequiresCharging(true)

        if (persisted) {
            try {
                builder.setPersisted(true)
            } catch (e: Throwable) {
                Log.w(TAG, "Cannot persist job $jobId across boot: ${e.message}")
            }
        }

        val result = jobScheduler.schedule(builder.build())
        val scheduled = result == JobScheduler.RESULT_SUCCESS

        if (scheduled) {
            val jobs = loadJobs(context)
            val updated = JSONArray()
            for (i in 0 until jobs.length()) {
                val j = jobs.optJSONObject(i) ?: continue
                if (j.optInt("id") != jobId) {
                    updated.put(j)
                }
            }
            val jobObj = JSONObject().apply {
                put("id", jobId)
                put("script", script)
                put("period_ms", effectivePeriod)
                put("network", network)
                put("battery_not_low", batteryNotLow)
                put("charging", charging)
                put("persisted", persisted)
                put("updated_at", System.currentTimeMillis())
            }
            updated.put(jobObj)
            saveJobs(context, updated)
            Log.i(TAG, "Successfully scheduled Job $jobId (period=${effectivePeriod}ms)")
        } else {
            Log.e(TAG, "Failed to schedule Job $jobId via Android JobScheduler")
        }

        return scheduled
    }

    fun cancelJob(context: Context, jobId: Int): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        jobScheduler?.cancel(jobId)

        val jobs = loadJobs(context)
        val updated = JSONArray()
        var removed = false
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            if (j.optInt("id") == jobId) {
                removed = true
            } else {
                updated.put(j)
            }
        }
        saveJobs(context, updated)
        Log.i(TAG, "Cancelled Job $jobId (removed=$removed)")
        return removed
    }

    fun cancelAllJobs(context: Context): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        jobScheduler?.cancelAll()
        saveJobs(context, JSONArray())
        Log.i(TAG, "Cancelled all scheduled jobs")
        return true
    }

    fun rescheduleAllPersisted(context: Context) {
        val jobs = loadJobs(context)
        Log.i(TAG, "Rescheduling ${jobs.length()} persisted jobs...")
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            val id = j.optInt("id", -1)
            val script = j.optString("script", "")
            if (id > 0 && script.isNotEmpty()) {
                val period = j.optLong("period_ms", MIN_PERIOD_MS)
                val network = j.optString("network", "any")
                val batteryNotLow = j.optBoolean("battery_not_low", false)
                val charging = j.optBoolean("charging", false)
                val persisted = j.optBoolean("persisted", true)
                scheduleJob(context, id, script, period, network, batteryNotLow, charging, persisted)
            }
        }
    }

    fun executeJobWithWakeLock(context: Context, script: String, jobId: Int): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ArchDroid:JobExecution_$jobId"
        )?.apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L) // 15-minute safety timeout
        }

        return try {
            ProotSessionManager.executeHeadlessScript(context, script, jobId)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Throwable) {}
        }
    }
}
