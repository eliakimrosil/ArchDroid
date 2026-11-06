package com.archdroid

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AutomationItem(
    val id: Int,
    val name: String,
    val schedule: String,
    val script: String,
    val periodMs: Long = 900_000L,
    val network: String = "any",
    val persisted: Boolean = true,
    val lastRun: String = "Never",
    val isRunning: Boolean = false,
    val logPath: String = ""
)

object ArchDroidAutomationManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    val runningJobIds = ConcurrentHashMap.newKeySet<Int>()

    // Known baseline registry
    private val knownJobs = mapOf(
        100 to Triple("Automation Guardian", "6:45 PM PST Daily", "run_automation_guardian.sh"),
        101 to Triple("Phoenix RPS", "7:30 AM & 7:30 PM PST", "run_rps.sh"),
        102 to Triple("Phoenix WSM", "7:35 AM & 7:35 PM PST", "run_wsm.sh"),
        103 to Triple("Van's Daily Audit", "7:00 PM PST", "run_scheduled_audit.sh"),
        104 to Triple("Fuel Price Forecast", "7:40 PM PHT", "run_forecast.sh"),
        105 to Triple("RMF Northlane Audit", "7:50 PM PST", "run_rmf_northlane_audit.sh"),
        106 to Triple("vans-deposit-tracker", "7:20 PM PST", "run_vansdeposittracker.sh"),
        107 to Triple("vans-truck-dir", "7:45 PM PST", "run_vans_truck_dir.sh")
    )

    fun getAllAutomations(context: Context): List<AutomationItem> {
        val registeredJobs = ArchDroidScheduler.loadJobs(context)
        val items = mutableListOf<AutomationItem>()
        val foundIds = mutableSetOf<Int>()

        // 1. Parse jobs registered in Android JobScheduler
        for (i in 0 until registeredJobs.length()) {
            val j = registeredJobs.optJSONObject(i) ?: continue
            val id = j.optInt("id", -1)
            val script = j.optString("script", "")
            if (id <= 0) continue

            foundIds.add(id)
            val (name, schedule) = resolveJobMeta(context, id, script)
            val lastRun = resolveLastRun(context, id, script)
            val logFile = File(context.filesDir, "job_logs/job_${id}.log")

            items.add(
                AutomationItem(
                    id = id,
                    name = name,
                    schedule = schedule,
                    script = script,
                    periodMs = j.optLong("period_ms", 900_000L),
                    network = j.optString("network", "any"),
                    persisted = j.optBoolean("persisted", true),
                    lastRun = lastRun,
                    isRunning = runningJobIds.contains(id),
                    logPath = logFile.absolutePath
                )
            )
        }

        // 2. Discover any known baseline jobs not yet registered
        for ((id, info) in knownJobs) {
            if (!foundIds.contains(id)) {
                val scriptPath = "/root/Automation/${info.first}/${info.third}"
                val lastRun = resolveLastRun(context, id, scriptPath)
                val logFile = File(context.filesDir, "job_logs/job_${id}.log")

                items.add(
                    AutomationItem(
                        id = id,
                        name = info.first,
                        schedule = info.second,
                        script = scriptPath,
                        periodMs = 900_000L,
                        network = "any",
                        persisted = true,
                        lastRun = lastRun,
                        isRunning = runningJobIds.contains(id),
                        logPath = logFile.absolutePath
                    )
                )
            }
        }

        return items.sortedBy { it.id }
    }

    private fun resolveJobMeta(context: Context, id: Int, script: String): Pair<String, String> {
        if (knownJobs.containsKey(id)) {
            val k = knownJobs[id]!!
            return Pair(k.first, k.second)
        }

        // Try reading automation.json in folder
        try {
            val scriptFile = File(script)
            val folder = scriptFile.parentFile
            val metaFile = if (folder != null) File(folder, "automation.json") else null
            if (metaFile != null && metaFile.exists()) {
                val json = JSONObject(metaFile.readText())
                val name = json.optString("name", folder?.name ?: "Job $id")
                val schedule = json.optString("schedule", "Periodic (~15m)")
                return Pair(name, schedule)
            }
        } catch (_: Throwable) {}

        val fallbackName = File(script).parentFile?.name ?: "Job $id"
        return Pair(fallbackName, "Periodic (~15m)")
    }

    private fun resolveLastRun(context: Context, id: Int, script: String): String {
        try {
            val rootfsDir = BootstrapManager.getRootfsDir(context)
            val cleanScript = script.removePrefix("/root/").removePrefix("/home/eliakimrosil/")
            val scriptFileInRootfs = File(rootfsDir, cleanScript)
            val folder = scriptFileInRootfs.parentFile

            if (folder != null && folder.exists()) {
                for (f in folder.listFiles().orEmpty()) {
                    if (f.name.startsWith(".last_") && f.name.endsWith("_date")) {
                        val text = f.readText().trim()
                        val lines = text.lines()
                        if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                            return lines.last().trim()
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback: check job log modification time
        val logFile = File(context.filesDir, "job_logs/job_${id}.log")
        if (logFile.exists() && logFile.length() > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(logFile.lastModified()))
        }

        return "Never"
    }

    fun executeAutomation(
        context: Context,
        item: AutomationItem,
        force: Boolean = true,
        onFinished: ((Int) -> Unit)? = null
    ) {
        if (runningJobIds.contains(item.id)) {
            mainHandler.post {
                Toast.makeText(context, "Job ${item.name} is already executing", Toast.LENGTH_SHORT).show()
            }
            return
        }

        runningJobIds.add(item.id)
        updateAllWidgets(context)
        TerminalForegroundService.instance?.updateNotification()

        mainHandler.post {
            Toast.makeText(context, "🚀 Running ${item.name}...", Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            val cleanScript = item.script.trim()
            val execCmd = if (force && !cleanScript.contains("--force")) {
                "$cleanScript --force"
            } else {
                cleanScript
            }

            val exitCode = try {
                ArchDroidScheduler.executeJobWithWakeLock(context, execCmd, item.id)
            } catch (e: Throwable) {
                -1
            } finally {
                runningJobIds.remove(item.id)
                updateAllWidgets(context)
                TerminalForegroundService.instance?.updateNotification()
            }

            mainHandler.post {
                if (exitCode == 0) {
                    Toast.makeText(context, "✅ ${item.name} completed successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ ${item.name} failed (exit code $exitCode)", Toast.LENGTH_LONG).show()
                }
                onFinished?.invoke(exitCode)
            }
        }
    }

    fun readRecentLogs(context: Context, jobId: Int, maxLines: Int = 120): String {
        val logFile = File(context.filesDir, "job_logs/job_${jobId}.log")
        if (!logFile.exists() || logFile.length() == 0L) {
            return "No output logged yet for Job $jobId.\nRun the job to see live output."
        }

        return try {
            val lines = logFile.readLines()
            val start = (lines.size - maxLines).coerceAtLeast(0)
            lines.subList(start, lines.size).joinToString("\n")
        } catch (e: Throwable) {
            "Error reading log: ${e.message}"
        }
    }

    fun updateAllWidgets(context: Context) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            
            // 1. Update single-task widgets
            val taskWidgetComponent = ComponentName(context, ArchDroidTaskWidgetProvider::class.java)
            val taskWidgetIds = appWidgetManager.getAppWidgetIds(taskWidgetComponent)
            if (taskWidgetIds != null && taskWidgetIds.isNotEmpty()) {
                val intent = Intent(context, ArchDroidTaskWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, taskWidgetIds)
                }
                context.sendBroadcast(intent)
            }

            // 2. Update dashboard overview widgets
            val dashWidgetComponent = ComponentName(context, ArchDroidDashboardWidgetProvider::class.java)
            val dashWidgetIds = appWidgetManager.getAppWidgetIds(dashWidgetComponent)
            if (dashWidgetIds != null && dashWidgetIds.isNotEmpty()) {
                val intent = Intent(context, ArchDroidDashboardWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, dashWidgetIds)
                }
                context.sendBroadcast(intent)
            }
        } catch (_: Throwable) {}
    }
}
