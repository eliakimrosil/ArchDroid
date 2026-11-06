package com.archdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ArchDroidBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        Log.i("ArchDroidBootReceiver", "Received broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                ArchDroidScheduler.rescheduleAllPersisted(context.applicationContext)
            } catch (e: Throwable) {
                Log.e("ArchDroidBootReceiver", "Failed to reschedule persisted jobs: ${e.message}", e)
            }
        }
    }
}
