package com.archdroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object ArchDroidApiServer {

    const val PORT = 48292
    private const val NOTIFICATION_CHANNEL_ID = "archdroid_automations"
    private const val NOTIFICATION_CHANNEL_NAME = "ArchDroid Automations"

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var automationWakeLock: PowerManager.WakeLock? = null

    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext
        isRunning = true

        ensureNotificationChannel(context)

        serverThread = thread(name = "ArchDroidApiServer", isDaemon = true) {
            try {
                val loopback = InetAddress.getByName("127.0.0.1")
                serverSocket = ServerSocket(PORT, 50, loopback)

                while (isRunning) {
                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (_: Throwable) {
                        null
                    } ?: break

                    thread(isDaemon = true) {
                        handleClient(clientSocket)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        serverThread = null

        releaseWakeLock()
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications from ArchDroid automations and background scripts"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val query = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            var contentLength = 0
            var contentType = ""
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (lower.startsWith("content-type:")) {
                    contentType = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buffer, read, contentLength - read)
                    if (r <= 0) break
                    read += r
                }
                String(buffer, 0, read)
            } else ""

            val params = parseParams(query, body, contentType)

            val (statusCode, responseBody) = processRequest(path, method, params, body)
            sendResponse(output, statusCode, responseBody)
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {}
        }
    }

    private fun parseParams(query: String, body: String, contentType: String): Map<String, String> {
        val map = mutableMapOf<String, String>()

        fun parseUrlEncoded(str: String) {
            str.split("&").forEach { pair ->
                val eq = pair.indexOf("=")
                if (eq > 0) {
                    val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    map[key] = value
                } else if (pair.isNotEmpty()) {
                    map[URLDecoder.decode(pair, "UTF-8")] = ""
                }
            }
        }

        if (query.isNotEmpty()) parseUrlEncoded(query)
        if (contentType.contains("application/x-www-form-urlencoded") && body.isNotEmpty()) {
            parseUrlEncoded(body)
        } else if (contentType.contains("application/json") && body.isNotEmpty()) {
            try {
                val json = JSONObject(body)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = json.optString(k, "")
                }
            } catch (_: Throwable) {}
        }

        return map
    }

    private fun processRequest(
        path: String,
        method: String,
        params: Map<String, String>,
        rawBody: String
    ): Pair<Int, String> {
        val ctx = appContext ?: return 500 to jsonError("Context not available")

        return when (path) {
            "/api/notify" -> {
                val title = params["title"] ?: "ArchDroid"
                val content = params["content"] ?: params["text"] ?: rawBody.trim()
                val id = params["id"]?.toIntOrNull() ?: (System.currentTimeMillis() % 100000).toInt()
                val priorityStr = params["priority"]?.lowercase() ?: "default"

                val priority = when (priorityStr) {
                    "high", "max" -> NotificationCompat.PRIORITY_HIGH
                    "low", "min" -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }

                showNotification(ctx, id, title, content, priority)
                200 to JSONObject().put("status", "ok").put("id", id).toString()
            }

            "/api/toast" -> {
                val msg = params["message"] ?: params["text"] ?: rawBody.trim()
                if (msg.isNotEmpty()) {
                    val isLong = params["long"]?.toBoolean() ?: false
                    mainHandler.post {
                        Toast.makeText(
                            ctx,
                            msg,
                            if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        ).show()
                    }
                    200 to JSONObject().put("status", "ok").toString()
                } else {
                    400 to jsonError("Missing toast message")
                }
            }

            "/api/wakelock" -> {
                val action = params["action"]?.lowercase() ?: "acquire"
                val held = if (action == "acquire" || action == "lock") {
                    acquireWakeLock(ctx)
                } else {
                    releaseWakeLock()
                    false
                }
                200 to JSONObject().put("status", "ok").put("held", held).toString()
            }

            "/api/battery" -> {
                val batteryJson = getBatteryStatus(ctx)
                200 to batteryJson.toString()
            }

            "/api/clipboard" -> {
                if (method == "GET") {
                    val clipText = getClipboardText(ctx)
                    val format = params["format"]?.lowercase() ?: "json"
                    if (format == "raw" || format == "text") {
                        200 to clipText
                    } else {
                        200 to JSONObject().put("text", clipText).toString()
                    }
                } else {
                    val text = params["text"] ?: rawBody
                    setClipboardText(ctx, text)
                    200 to JSONObject().put("status", "ok").toString()
                }
            }

            "/api/jobs" -> {
                handleJobsRequest(ctx, method, params, rawBody)
            }

            "/api/vibrate" -> {
                val duration = params["duration"]?.toLongOrNull() ?: 200L
                triggerVibration(ctx, duration)
                200 to JSONObject().put("status", "ok").put("duration_ms", duration).toString()
            }

            "/api/status" -> {
                200 to JSONObject().apply {
                    put("status", "online")
                    put("version", "1.0.0")
                    put("wakelock", automationWakeLock?.isHeld == true)
                    put("archdroid_api", true)
                    put("jobs_count", ArchDroidScheduler.loadJobs(ctx).length())
                }.toString()
            }

            else -> 404 to jsonError("Endpoint not found: $path")
        }
    }

    private fun showNotification(
        context: Context,
        id: Int,
        title: String,
        content: String,
        priority: Int
    ) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_archdroid)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(id, builder.build())
        } catch (_: Throwable) {}
    }

    fun isWakeLockHeld(): Boolean {
        synchronized(this) {
            return automationWakeLock?.isHeld == true
        }
    }

    private fun acquireWakeLock(context: Context): Boolean {
        synchronized(this) {
            try {
                if (automationWakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    automationWakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ArchDroid::AutomationScriptWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                    }
                }
                // Acquire with a 2-hour safety timeout to prevent permanent battery drain
                automationWakeLock?.acquire(2 * 60 * 60 * 1000L)
                val held = automationWakeLock?.isHeld == true
                TerminalForegroundService.instance?.updateNotification()
                return held
            } catch (_: Throwable) {
                return false
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(this) {
            try {
                if (automationWakeLock?.isHeld == true) {
                    automationWakeLock?.release()
                }
                TerminalForegroundService.instance?.updateNotification()
            } catch (_: Throwable) {}
        }
    }

    private fun getBatteryStatus(context: Context): JSONObject {
        val result = JSONObject()
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else 0

                val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                    BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                    else -> "UNKNOWN"
                }

                val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
                    else -> "UNPLUGGED"
                }

                val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                    else -> "UNKNOWN"
                }

                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0

                result.put("percentage", pct)
                result.put("status", status)
                result.put("plugged", plugged)
                result.put("health", health)
                result.put("temperature", temp)
            }
        } catch (_: Throwable) {}
        return result
    }

    private fun getClipboardText(context: Context): String {
        var text = ""
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).coerceToText(context).toString()
                }
            } catch (_: Throwable) {}
            latch.countDown()
        }
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {}
        return text
    }

    private fun setClipboardText(context: Context, text: String) {
        mainHandler.post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("ArchDroid", text))
            } catch (_: Throwable) {}
        }
    }

    private fun triggerVibration(context: Context, durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val dur = durationMs.coerceIn(10L, 5000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(dur)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun handleJobsRequest(
        context: Context,
        method: String,
        params: Map<String, String>,
        rawBody: String
    ): Pair<Int, String> {
        val mergedParams = if (rawBody.trimStart().startsWith("{")) {
            try {
                val map = params.toMutableMap()
                val json = JSONObject(rawBody)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = json.optString(k, "")
                }
                map
            } catch (_: Throwable) {
                params
            }
        } else params

        val action = mergedParams["action"]?.lowercase() ?: if (method == "GET") "list" else "status"

        return when (action) {
            "list", "get", "pending" -> {
                val jobs = ArchDroidScheduler.loadJobs(context)
                val format = mergedParams["format"]?.lowercase() ?: "json"
                if (format == "text") {
                    val sb = StringBuilder()
                    for (i in 0 until jobs.length()) {
                        val job = jobs.optJSONObject(i) ?: continue
                        val id = job.optInt("id", -1)
                        if (id < 0) continue
                        val script = job.optString("script", "")
                        val period = job.optLong("period_ms", ArchDroidScheduler.MIN_PERIOD_MS)
                        sb.append("Pending Job $id:\n")
                        sb.append("  period: ${period}ms\n")
                        if (script.isNotEmpty()) sb.append("  script: $script\n")
                    }
                    if (sb.isEmpty()) {
                        sb.append("No pending jobs found.\n")
                    }
                    200 to sb.toString()
                } else {
                    200 to jobs.toString()
                }
            }

            "add", "schedule" -> {
                val jobId = mergedParams["job_id"]?.toIntOrNull() ?: mergedParams["id"]?.toIntOrNull()
                val script = mergedParams["script"] ?: ""
                val periodMs = mergedParams["period_ms"]?.toLongOrNull() ?: ArchDroidScheduler.MIN_PERIOD_MS
                val network = mergedParams["network"] ?: "any"
                val batteryNotLow = mergedParams["battery_not_low"]?.toBoolean() ?: false
                val charging = mergedParams["charging"]?.toBoolean() ?: false
                val persisted = mergedParams["persisted"]?.toBoolean() ?: true

                if (jobId == null) {
                    return 400 to jsonError("Missing job_id")
                }

                val scheduled = ArchDroidScheduler.scheduleJob(
                    context,
                    jobId,
                    script,
                    periodMs,
                    network,
                    batteryNotLow,
                    charging,
                    persisted
                )
                200 to JSONObject().put("status", "ok").put("id", jobId).put("scheduled", scheduled).toString()
            }

            "cancel", "delete" -> {
                val jobId = mergedParams["job_id"]?.toIntOrNull() ?: mergedParams["id"]?.toIntOrNull()
                if (jobId == null) {
                    return 400 to jsonError("Missing job_id to cancel")
                }
                val removed = ArchDroidScheduler.cancelJob(context, jobId)
                200 to JSONObject().put("status", "ok").put("cancelled", removed).toString()
            }

            "cancel_all", "clear" -> {
                ArchDroidScheduler.cancelAllJobs(context)
                200 to JSONObject().put("status", "ok").put("cleared", true).toString()
            }

            "run", "trigger" -> {
                val jobId = mergedParams["job_id"]?.toIntOrNull() ?: mergedParams["id"]?.toIntOrNull()
                val script = mergedParams["script"] ?: ""
                if (script.isNotEmpty()) {
                    thread(isDaemon = true) {
                        ArchDroidScheduler.executeJobWithWakeLock(context, script, jobId ?: -1)
                    }
                    200 to JSONObject().put("status", "ok").put("message", "Triggered job execution").toString()
                } else {
                    400 to jsonError("Missing script to run")
                }
            }

            else -> {
                400 to jsonError("Unknown jobs action: $action")
            }
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = if (statusCode == 200) "OK" else if (statusCode == 404) "Not Found" else "Error"
        val trimmed = body.trimStart()
        val contentType = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            "application/json; charset=utf-8"
        } else {
            "text/plain; charset=utf-8"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun jsonError(msg: String): String {
        return JSONObject().put("status", "error").put("message", msg).toString()
    }
}
