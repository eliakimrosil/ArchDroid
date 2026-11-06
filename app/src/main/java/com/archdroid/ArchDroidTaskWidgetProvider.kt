package com.archdroid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ArchDroidTaskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_EXECUTE_TASK) {
            val jobId = intent.getIntExtra(EXTRA_JOB_ID, 101)
            val items = ArchDroidAutomationManager.getAllAutomations(context)
            val target = items.find { it.id == jobId } ?: items.firstOrNull()
            if (target != null) {
                ArchDroidAutomationManager.executeAutomation(context, target, force = true)
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.archdroid.ACTION_WIDGET_EXECUTE_TASK"
        const val EXTRA_JOB_ID = "extra_job_id"
        private const val PREFS_NAME = "archdroid_widget_prefs"

        fun saveWidgetJobId(context: Context, appWidgetId: Int, jobId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt("widget_job_id_$appWidgetId", jobId).apply()
        }

        fun getWidgetJobId(context: Context, appWidgetId: Int): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt("widget_job_id_$appWidgetId", 101) // Default to Phoenix RPS
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val jobId = getWidgetJobId(context, appWidgetId)
            val items = ArchDroidAutomationManager.getAllAutomations(context)
            val target = items.find { it.id == jobId } ?: items.firstOrNull()

            val views = RemoteViews(context.packageName, R.layout.widget_task)

            val title = target?.name ?: "Job $jobId"
            val isRunning = ArchDroidAutomationManager.runningJobIds.contains(jobId)
            val statusText = when {
                isRunning -> "⚡ Running now..."
                target != null && target.lastRun != "Never" -> "Last: ${target.lastRun}"
                else -> "Tap to execute"
            }

            views.setTextViewText(R.id.widget_task_title, title)
            views.setTextViewText(R.id.widget_task_status, statusText)

            // Setup click intent on both the root layout and the play button
            val runIntent = Intent(context, ArchDroidTaskWidgetProvider::class.java).apply {
                action = ACTION_EXECUTE_TASK
                putExtra(EXTRA_JOB_ID, jobId)
            }
            val pendingRun = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                runIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_btn_run, pendingRun)
            views.setOnClickPendingIntent(R.id.widget_root, pendingRun)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
