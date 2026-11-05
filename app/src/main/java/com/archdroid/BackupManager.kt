package com.archdroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater

data class BackupProgress(
    val stage: String,
    val detail: String,
    val progress: Float = 0f,
    val currentCount: Int = 0
)

object BackupManager {

    private const val PREFS_FILE_NAME = "archdroid_prefs"
    private const val META_PREFS_ENTRY = ".archdroid_meta/prefs.json"

    fun getBackupDir(): File {
        val sdcardDownload = File("/sdcard/Download")
        if (sdcardDownload.exists() && sdcardDownload.canWrite()) {
            return sdcardDownload
        }
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDownload != null && (publicDownload.exists() || publicDownload.mkdirs())) {
            return publicDownload
        }
        val fallback = File("/storage/emulated/0/Download")
        if (fallback.exists() && fallback.canWrite()) {
            return fallback
        }
        return sdcardDownload
    }

    fun listLocalBackups(): List<File> {
        val dir = getBackupDir()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("ArchDroid-") &&
                (file.name.endsWith(".tar.gz") || file.name.endsWith(".tgz"))
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    suspend fun createFullBackup(
        context: Context,
        targetFile: File? = null,
        onProgress: (BackupProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val rootfsDir = BootstrapManager.getRootfsDir(context)
            if (!rootfsDir.exists()) {
                return@withContext Result.failure(IllegalStateException("Rootfs directory does not exist"))
            }

            val backupDir = getBackupDir()
            backupDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = targetFile ?: File(backupDir, "ArchDroid-FullBackup-$timestamp.tar.gz")
            if (outFile.exists()) outFile.delete()

            onProgress(BackupProgress("Initializing Snapshot", "Preparing filesystem walker...", 0.05f))

            val countTracker = intArrayOf(0)
            val gzipParams = GzipParameters().apply {
                compressionLevel = Deflater.BEST_SPEED
            }

            FileOutputStream(outFile).use { fos ->
                BufferedOutputStream(fos, 128 * 1024).use { bos ->
                    GzipCompressorOutputStream(bos, gzipParams).use { gzos ->
                        TarArchiveOutputStream(gzos).use { tarOut ->
                            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                            addDirectoryToTar(
                                baseDir = rootfsDir,
                                currentDir = rootfsDir,
                                tarOut = tarOut,
                                onProgress = onProgress,
                                countTracker = countTracker
                            )

                            // Export SharedPreferences into metadata entry
                            try {
                                val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                                val allEntries = prefs.all
                                val jsonString = JSONObject(allEntries).toString(2)
                                val metaBytes = jsonString.toByteArray(Charsets.UTF_8)
                                val metaEntry = TarArchiveEntry(META_PREFS_ENTRY)
                                metaEntry.size = metaBytes.size.toLong()
                                metaEntry.mode = 420 // 0644
                                tarOut.putArchiveEntry(metaEntry)
                                tarOut.write(metaBytes)
                                tarOut.closeArchiveEntry()
                            } catch (_: Throwable) {}

                            tarOut.finish()
                        }
                    }
                }
            }

            onProgress(
                BackupProgress(
                    stage = "Backup Completed",
                    detail = "Snapshot saved: ${outFile.name} (${outFile.length() / (1024 * 1024)} MB)",
                    progress = 1.0f,
                    currentCount = countTracker[0]
                )
            )

            Result.success(outFile)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun addDirectoryToTar(
        baseDir: File,
        currentDir: File,
        tarOut: TarArchiveOutputStream,
        onProgress: (BackupProgress) -> Unit,
        countTracker: IntArray
    ) {
        val children = currentDir.listFiles() ?: return
        for (child in children) {
            val relPath = child.relativeTo(baseDir).path.replace('\\', '/')

            // Exclusions: virtual fs, mountpoints, volatile caches, sockets
            if (relPath == "dev" || relPath.startsWith("dev/") ||
                relPath == "proc" || relPath.startsWith("proc/") ||
                relPath == "sys" || relPath.startsWith("sys/") ||
                relPath == "tmp" || relPath.startsWith("tmp/") ||
                relPath == "sdcard" || relPath.startsWith("sdcard/") ||
                relPath == "storage" || relPath.startsWith("storage/") ||
                relPath == "run" || relPath.startsWith("run/") ||
                relPath == "var/run" || relPath.startsWith("var/run/") ||
                relPath == "var/lock" || relPath.startsWith("var/lock/") ||
                relPath == "var/cache/pacman/pkg" || relPath.startsWith("var/cache/pacman/pkg/") ||
                relPath.endsWith(".sock") || relPath.endsWith(".pid") || relPath == ".bootstrap_lock"
            ) {
                continue
            }

            val stat = try {
                Os.lstat(child.absolutePath)
            } catch (_: Throwable) {
                null
            } ?: continue

            val isSymlink = OsConstants.S_ISLNK(stat.st_mode)

            when {
                isSymlink -> {
                    val linkTarget = try {
                        Os.readlink(child.absolutePath)
                    } catch (_: Throwable) {
                        null
                    } ?: continue

                    val entry = TarArchiveEntry(relPath, TarArchiveEntry.LF_SYMLINK)
                    entry.linkName = linkTarget
                    entry.mode = stat.st_mode and 0xfff
                    tarOut.putArchiveEntry(entry)
                    tarOut.closeArchiveEntry()

                    countTracker[0]++
                    if (countTracker[0] % 150 == 0) {
                        onProgress(
                            BackupProgress(
                                stage = "Creating Full Snapshot",
                                detail = relPath,
                                progress = 0.5f,
                                currentCount = countTracker[0]
                            )
                        )
                    }
                }
                child.isDirectory -> {
                    val entryName = if (relPath.endsWith("/")) relPath else "$relPath/"
                    val entry = TarArchiveEntry(entryName)
                    entry.mode = stat.st_mode and 0xfff
                    tarOut.putArchiveEntry(entry)
                    tarOut.closeArchiveEntry()

                    countTracker[0]++
                    if (countTracker[0] % 150 == 0) {
                        onProgress(
                            BackupProgress(
                                stage = "Creating Full Snapshot",
                                detail = relPath,
                                progress = 0.5f,
                                currentCount = countTracker[0]
                            )
                        )
                    }
                    addDirectoryToTar(baseDir, child, tarOut, onProgress, countTracker)
                }
                child.isFile -> {
                    val entry = TarArchiveEntry(child, relPath)
                    entry.mode = stat.st_mode and 0xfff
                    entry.size = child.length()
                    tarOut.putArchiveEntry(entry)
                    try {
                        FileInputStream(child).use { fis ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = fis.read(buffer)
                                if (read <= 0) break
                                tarOut.write(buffer, 0, read)
                            }
                        }
                    } catch (_: Throwable) {}
                    tarOut.closeArchiveEntry()

                    countTracker[0]++
                    if (countTracker[0] % 150 == 0) {
                        onProgress(
                            BackupProgress(
                                stage = "Creating Full Snapshot",
                                detail = relPath,
                                progress = 0.5f,
                                currentCount = countTracker[0]
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun restoreFullBackup(
        context: Context,
        inputStream: InputStream,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val rootfsDir = BootstrapManager.getRootfsDir(context)
            if (rootfsDir.exists()) {
                BootstrapManager.makeTreeWritable(rootfsDir)
            }
            rootfsDir.mkdirs()

            onProgress(BackupProgress("Restoring Full Snapshot", "Reading snapshot archive...", 0.1f))

            val deferredDirModes = mutableListOf<Pair<File, Int>>()
            val deferredSymlinks = mutableListOf<Pair<String, File>>()
            var count = 0

            GzipCompressorInputStream(BufferedInputStream(inputStream, 64 * 1024)).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextEntry
                    val buffer = ByteArray(64 * 1024)

                    while (entry != null) {
                        val cleanName = entry.name.removePrefix("./").removePrefix("/")

                        if (cleanName == META_PREFS_ENTRY) {
                            // Restore SharedPreferences
                            try {
                                val baos = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val r = tar.read(buffer)
                                    if (r <= 0) break
                                    baos.write(buffer, 0, r)
                                }
                                val json = JSONObject(baos.toString(Charsets.UTF_8.name()))
                                val editor = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
                                for (key in json.keys()) {
                                    when (val v = json.get(key)) {
                                        is Boolean -> editor.putBoolean(key, v)
                                        is Int -> editor.putInt(key, v)
                                        is Long -> editor.putLong(key, v)
                                        is Double -> editor.putFloat(key, v.toFloat())
                                        is String -> editor.putString(key, v)
                                    }
                                }
                                editor.apply()
                            } catch (_: Throwable) {}
                            entry = tar.nextEntry
                            continue
                        }

                        if (cleanName.isNotEmpty()) {
                            count++
                            if (count % 150 == 0) {
                                onProgress(
                                    BackupProgress(
                                        stage = "Restoring Full Snapshot",
                                        detail = cleanName,
                                        progress = 0.5f,
                                        currentCount = count
                                    )
                                )
                            }

                            val outFile = File(rootfsDir, cleanName)

                            when {
                                entry.isDirectory -> {
                                    outFile.mkdirs()
                                    val mode = if (entry.mode == 0) 493 else (entry.mode or 448) and 0xfff
                                    chmodSafe(outFile, mode)
                                    deferredDirModes.add(outFile to (entry.mode and 0xfff))
                                }
                                entry.isSymbolicLink -> {
                                    val parent = outFile.parentFile
                                    if (parent != null && !parent.exists()) parent.mkdirs()
                                    if (parent != null && !parent.canWrite()) chmodSafe(parent, 493)
                                    if (outFile.exists()) outFile.delete()
                                    try {
                                        Os.symlink(entry.linkName, outFile.absolutePath)
                                    } catch (_: Throwable) {
                                        deferredSymlinks.add(entry.linkName to outFile)
                                    }
                                }
                                entry.isLink -> {
                                    val parent = outFile.parentFile
                                    if (parent != null && !parent.exists()) parent.mkdirs()
                                    if (parent != null && !parent.canWrite()) chmodSafe(parent, 493)
                                    if (outFile.exists()) outFile.delete()
                                    val linkTarget = "/" + entry.linkName.removePrefix("./").removePrefix("/")
                                    try {
                                        Os.symlink(linkTarget, outFile.absolutePath)
                                    } catch (_: Throwable) {
                                        deferredSymlinks.add(linkTarget to outFile)
                                    }
                                }
                                entry.isFile -> {
                                    val parent = outFile.parentFile
                                    if (parent != null && !parent.exists()) parent.mkdirs()
                                    if (parent != null && !parent.canWrite()) chmodSafe(parent, 493)
                                    if (outFile.exists()) {
                                        if (!outFile.canWrite()) chmodSafe(outFile, 420)
                                        outFile.delete()
                                    }
                                    FileOutputStream(outFile).use { fos ->
                                        while (true) {
                                            val read = tar.read(buffer)
                                            if (read <= 0) break
                                            fos.write(buffer, 0, read)
                                        }
                                    }
                                    val mode = if (entry.mode == 0) 420 else (entry.mode or 384) and 0xfff
                                    chmodSafe(outFile, mode)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }

            for ((target, linkFile) in deferredSymlinks) {
                try {
                    val parent = linkFile.parentFile
                    if (parent != null && !parent.canWrite()) chmodSafe(parent, 493)
                    if (linkFile.exists()) linkFile.delete()
                    Os.symlink(target, linkFile.absolutePath)
                } catch (_: Throwable) {}
            }

            for ((dirFile, mode) in deferredDirModes) {
                try {
                    chmodSafe(dirFile, (mode or 448) and 0xfff)
                } catch (_: Throwable) {}
            }

            // Ensure critical directories and permissions
            listOf("tmp", "proc", "sys", "dev", "shm", "sdcard", "storage").forEach {
                File(rootfsDir, it).mkdirs()
            }
            try {
                Os.chmod(File(rootfsDir, "tmp").absolutePath, 511) // 0777
            } catch (_: Throwable) {}

            File(rootfsDir, ".bootstrap_version").writeText("1.0.0-archdroid-arm64")

            onProgress(
                BackupProgress(
                    stage = "Restore Completed",
                    detail = "Restored $count files successfully.",
                    progress = 1.0f,
                    currentCount = count
                )
            )

            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun chmodSafe(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (_: Throwable) {
            file.setReadable((mode and 256) != 0, false)
            file.setWritable((mode and 128) != 0, false)
            file.setExecutable((mode and 64) != 0, false)
        }
    }

    fun createShareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/gzip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
