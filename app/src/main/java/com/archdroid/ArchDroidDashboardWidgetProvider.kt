package com.archdroid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ArchDroidDashboardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateDashboardWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_EXECUTE_DASH_TASK -> {
                val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
                if (jobId > 0) {
                    val items = ArchDroidAutomationManager.getAllAutomations(context)
                    val target = items.find { it.id == jobId }
                    if (target != null) {
                        ArchDroidAutomationManager.executeAutomation(context, target, force = true)
                    }
                }
            }
            ACTION_REFRESH_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (appWidgetIds != null) {
                    for (id in appWidgetIds) {
                        updateDashboardWidget(context, appWidgetManager, id)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE_DASH_TASK = "com.archdroid.ACTION_DASH_EXECUTE_TASK"
        const val ACTION_REFRESH_WIDGET = "com.archdroid.ACTION_DASH_REFRESH"
        const val EXTRA_JOB_ID = "extra_job_id"

        fun updateDashboardWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_dashboard)
            val items = ArchDroidAutomationManager.getAllAutomations(context)

            // Active count badge
            val activeCount = items.count { it.persisted }
            views.setTextViewText(R.id.widget_dash_active_badge, "$activeCount Active")

            // Slot mappings:
            // 1: Phoenix RPS (101)
            // 2: Phoenix WSM (102)
            // 3: Van's Daily Audit (103)
            // 4: Automation Guardian (100)
            bindSlot(context, views, appWidgetId, R.id.widget_slot_1, R.id.widget_slot_1_title, R.id.widget_slot_1_sub, items.find { it.id == 101 }, 101, "Phoenix RPS", "7:30 AM/PM")
            bindSlot(context, views, appWidgetId, R.id.widget_slot_2, R.id.widget_slot_2_title, R.id.widget_slot_2_sub, items.find { it.id == 102 }, 102, "Phoenix WSM", "7:35 AM/PM")
            bindSlot(context, views, appWidgetId, R.id.widget_slot_3, R.id.widget_slot_3_title, R.id.widget_slot_3_sub, items.find { it.id == 103 }, 103, "Van's Audit", "7:00 PM PST")
            bindSlot(context, views, appWidgetId, R.id.widget_slot_4, R.id.widget_slot_4_title, R.id.widget_slot_4_sub, items.find { it.id == 100 }, 100, "Guardian", "6:45 PM Daily")

            // Refresh button PendingIntent
            val refreshIntent = Intent(context, ArchDroidDashboardWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val pendingRefresh = PendingIntent.getBroadcast(
                context,
                appWidgetId + 99000,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_dash_btn_refresh, pendingRefresh)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun bindSlot(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            slotLayoutId: Int,
            titleId: Int,
            subId: Int,
            item: AutomationItem?,
            defaultJobId: Int,
            defaultTitle: String,
            defaultSchedule: String
        ) {
            val title = item?.name ?: defaultTitle
            val isRunning = ArchDroidAutomationManager.runningJobIds.contains(item?.id ?: defaultJobId)
            val sub = if (isRunning) "⚡ Running..." else (item?.schedule ?: defaultSchedule)

            views.setTextViewText(titleId, title)
            views.setTextViewText(subId, sub)

            val clickIntent = Intent(context, ArchDroidDashboardWidgetProvider::class.java).apply {
                action = ACTION_EXECUTE_DASH_TASK
                putExtra(EXTRA_JOB_ID, item?.id ?: defaultJobId)
            }
            val pendingClick = PendingIntent.getBroadcast(
                context,
                appWidgetId * 100 + defaultJobId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(slotLayoutId, pendingClick)
        }
    }
}
