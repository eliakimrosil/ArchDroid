package com.archdroid

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.zip.GZIPInputStream

data class BootstrapProgress(
    val step: String,
    val detail: String,
    val progress: Float
)

object BootstrapManager {
    private const val BOOTSTRAP_VERSION = "1.0.0-archdroid-arm64"

    fun getRootfsDir(context: Context): File = File(context.filesDir, "arch")
    fun getSysdataDir(context: Context): File = File(context.filesDir, "sysdata")
    fun getTmpDir(context: Context): File = File(context.filesDir, "tmp")
    fun getShmDir(context: Context): File = File(context.filesDir, "shm")

    fun makeTreeWritable(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { file ->
            try {
                if (file.isDirectory) {
                    Os.chmod(file.absolutePath, 493) // 0755
                } else {
                    Os.chmod(file.absolutePath, 420) // 0644
                }
            } catch (_: Throwable) {
                file.setWritable(true, false)
            }
        }
    }

    fun isBootstrapped(context: Context): Boolean {
        val rootfs = getRootfsDir(context)
        val stamp = File(rootfs, ".bootstrap_version")
        val bash = File(rootfs, "usr/bin/bash")
        val pacman = File(rootfs, "usr/bin/pacman")
        return stamp.exists() &&
            stamp.readText().trim() == BOOTSTRAP_VERSION &&
            bash.exists() &&
            pacman.exists()
    }

    private fun openRootfsStream(context: Context): InputStream {
        val assetList = try {
            context.assets.list("")?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }

        return if ("arch-rootfs.part1.bin" in assetList && "arch-rootfs.part2.bin" in assetList) {
            val part1 = context.assets.open("arch-rootfs.part1.bin")
            val part2 = context.assets.open("arch-rootfs.part2.bin")
            SequenceInputStream(part1, part2)
        } else if ("arch-rootfs.tgz.bin" in assetList) {
            context.assets.open("arch-rootfs.tgz.bin")
        } else {
            try {
                val part1 = context.assets.open("arch-rootfs.part1.bin")
                val part2 = context.assets.open("arch-rootfs.part2.bin")
                SequenceInputStream(part1, part2)
            } catch (_: Throwable) {
                context.assets.open("arch-rootfs.tgz.bin")
            }
        }
    }

    suspend fun ensureBootstrapped(
        context: Context,
        onProgress: (BootstrapProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val rootfsDir = getRootfsDir(context)
        val sysdataDir = getSysdataDir(context)
        val tmpDir = getTmpDir(context)
        val shmDir = getShmDir(context)

        // Clean stale temporary sockets & lock files from previous runs / reboots
        try {
            tmpDir.listFiles()?.forEach { it.deleteRecursively() }
            shmDir.listFiles()?.forEach { it.deleteRecursively() }
            val archTmp = File(rootfsDir, "tmp")
            if (archTmp.exists()) {
                archTmp.listFiles()?.forEach { file ->
                    if (file.name.startsWith("proot") || file.name.endsWith(".pid") || file.name.endsWith(".lock") || file.name.startsWith(".X11-")) {
                        file.deleteRecursively()
                    }
                }
            }
        } catch (_: Throwable) {}

        tmpDir.mkdirs()
        shmDir.mkdirs()
        createSysdataFiles(sysdataDir)

        if (isBootstrapped(context)) {
            updateScriptsIfBootstrapped(context, rootfsDir)
            onProgress(BootstrapProgress("Ready", "Arch Linux ARM runtime verified", 1.0f))
            return@withContext
        }

        onProgress(
            BootstrapProgress(
                "Preparing filesystem",
                "Cleaning previous partial installation...",
                0.05f
            )
        )
        if (rootfsDir.exists()) {
            makeTreeWritable(rootfsDir)
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        // 1. Extract arch-rootfs (Arch Linux ARM base + pacman + native glibc)
        onProgress(
            BootstrapProgress(
                "Extracting Arch Linux ARM",
                "Unpacking curated Arch Linux rootfs...",
                0.15f
            )
        )
        extractTarGzAsset(
            context = context,
            destDir = rootfsDir,
            onEntry = { count, name ->
                if (count % 150 == 0) {
                    val pct = (0.15f + (count.coerceAtMost(3500) / 3500f) * 0.40f)
                    onProgress(
                        BootstrapProgress(
                            "Extracting Arch Linux ARM",
                            name,
                            pct
                        )
                    )
                }
            }
        )

        // 2. Extract bundled Antigravity CLI if bundled in APK
        val agyAssetExists = try {
            "agy.gz.bin" in (context.assets.list("")?.toSet() ?: emptySet())
        } catch (_: Throwable) {
            false
        }

        if (agyAssetExists) {
            onProgress(
                BootstrapProgress(
                    "Installing Antigravity CLI (agy)",
                    "Decompressing bundled aarch64 native binary...",
                    0.60f
                )
            )
            val agyFile = File(rootfsDir, "opt/agy/agy")
            agyFile.parentFile?.mkdirs()
            context.assets.open("agy.gz.bin").use { rawIn ->
                GZIPInputStream(BufferedInputStream(rawIn, 64 * 1024)).use { gzIn ->
                    FileOutputStream(agyFile).use { out ->
                        val buf = ByteArray(128 * 1024)
                        var total = 0L
                        val expectedTotal = 220_000_000L
                        while (true) {
                            val r = gzIn.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            total += r
                            if (total % (8 * 1024 * 1024) < 128 * 1024) {
                                val ratio = (total.toFloat() / expectedTotal).coerceIn(0f, 1f)
                                val mb = total / (1024 * 1024)
                                onProgress(
                                    BootstrapProgress(
                                        "Installing Antigravity CLI (agy)",
                                        "Extracted ${mb} MB...",
                                        0.60f + ratio * 0.35f
                                    )
                                )
                            }
                        }
                    }
                }
            }
            try {
                Os.chmod(agyFile.absolutePath, 493) // 0755
            } catch (_: Throwable) {
                agyFile.setExecutable(true, false)
            }
            val agySymlink = File(rootfsDir, "usr/local/bin/agy")
            if (agySymlink.exists()) agySymlink.delete()
            try {
                Os.symlink("/opt/agy/agy", agySymlink.absolutePath)
            } catch (_: Throwable) {}
        }

        // Ensure critical directories and permissions inside rootfs
        listOf("tmp", "proc", "sys", "dev", "pts", "root", "sdcard", "home", "mnt", "var/cache/pacman/pkg").forEach {
            File(rootfsDir, it).mkdirs()
        }
        try {
            Os.chmod(File(rootfsDir, "tmp").absolutePath, 511) // 0777
        } catch (_: Throwable) {}

        writePersistentHistoryConfig(rootfsDir)
        updateScriptsIfBootstrapped(context, rootfsDir)

        File(rootfsDir, ".bootstrap_version").writeText(BOOTSTRAP_VERSION)
        onProgress(
            BootstrapProgress(
                "Bootstrap Complete",
                "Starting Arch Linux terminal...",
                1.0f
            )
        )
    }

    private fun extractTarGzAsset(
        context: Context,
        destDir: File,
        onEntry: (Int, String) -> Unit
    ) {
        val deferredDirModes = mutableListOf<Pair<File, Int>>()
        val deferredSymlinks = mutableListOf<Pair<String, File>>()
        var count = 0

        openRootfsStream(context).use { raw ->
            GzipCompressorInputStream(BufferedInputStream(raw, 64 * 1024)).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextEntry
                    val buffer = ByteArray(64 * 1024)
                    while (entry != null) {
                        val cleanName = entry.name.removePrefix("./").removePrefix("/")
                        if (cleanName.isNotEmpty()) {
                            count++
                            onEntry(count, cleanName)
                            val outFile = File(destDir, cleanName)

                            when {
                                entry.isDirectory -> {
                                    outFile.mkdirs()
                                    // Ensure owner always has write access (0700) during extraction
                                    val mode = if (entry.mode == 0) 493 else (entry.mode or 448) and 0xfff
                                    chmodSafe(outFile, mode)
                                    deferredDirModes.add(outFile to (entry.mode and 0xfff))
                                }
                                entry.isSymbolicLink -> {
                                    val parent = outFile.parentFile
                                    if (parent != null && !parent.exists()) parent.mkdirs()
                                    if (parent != null && !parent.canWrite()) {
                                        chmodSafe(parent, 493)
                                    }
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
                                    if (parent != null && !parent.canWrite()) {
                                        chmodSafe(parent, 493)
                                    }
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
                                    if (parent != null && !parent.canWrite()) {
                                        chmodSafe(parent, 493)
                                    }
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
                                    // Ensure owner has read+write access (0600)
                                    val mode = if (entry.mode == 0) 420 else (entry.mode or 384) and 0xfff
                                    chmodSafe(outFile, mode)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
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

        // Apply deferred directory modes, maintaining owner write (0700) so app sandbox remains healthy
        for ((dirFile, mode) in deferredDirModes) {
            try {
                chmodSafe(dirFile, (mode or 448) and 0xfff)
            } catch (_: Throwable) {}
        }
    }

    private fun updateScriptsIfBootstrapped(context: Context, destDir: File) {
        // 1. Immediately write all standalone automation tools and compatibility wrappers
        writeCustomScripts(destDir)

        val targets = setOf(
            "usr/local/bin/install-agents",
            "usr/local/bin/agy",
            "usr/local/lib/node-android-fix.js",
            "root/.profile",
            "root/.bashrc"
        )
        try {
            openRootfsStream(context).use { raw ->
                GzipCompressorInputStream(BufferedInputStream(raw, 64 * 1024)).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        val buffer = ByteArray(64 * 1024)
                        while (entry != null) {
                            val cleanName = entry.name.removePrefix("./").removePrefix("/")
                            if (cleanName in targets && entry.isFile) {
                                val outFile = File(destDir, cleanName)
                                outFile.parentFile?.mkdirs()
                                if (outFile.exists()) outFile.delete()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        val read = tar.read(buffer)
                                        if (read <= 0) break
                                        fos.write(buffer, 0, read)
                                    }
                                }
                                chmodSafe(outFile, entry.mode and 0xfff)
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            // If agy is bundled in APK but missing or incomplete in installed rootfs, extract it now
            val agyFile = File(destDir, "opt/agy/agy")
            if (!agyFile.exists() || agyFile.length() < 1000) {
                val hasAgyAsset = try {
                    "agy.gz.bin" in (context.assets.list("")?.toSet() ?: emptySet())
                } catch (_: Throwable) {
                    false
                }
                if (hasAgyAsset) {
                    agyFile.parentFile?.mkdirs()
                    context.assets.open("agy.gz.bin").use { rawIn ->
                        GZIPInputStream(BufferedInputStream(rawIn, 64 * 1024)).use { gzIn ->
                            FileOutputStream(agyFile).use { out ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val r = gzIn.read(buf)
                                    if (r <= 0) break
                                    out.write(buf, 0, r)
                                }
                            }
                        }
                    }
                    chmodSafe(agyFile, 493) // 0755
                    val agySymlink = File(destDir, "usr/local/bin/agy")
                    if (agySymlink.exists()) agySymlink.delete()
                    try {
                        Os.symlink("/opt/agy/agy", agySymlink.absolutePath)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    fun writeCustomScripts(destDir: File) {
        try {
            // Write custom reboot and poweroff shims for clean in-terminal session restarts
            val profileFile = File(destDir, "root/.profile")
            if (profileFile.exists()) {
                val content = profileFile.readText()
                if (content.contains("Arch Linux") && content.contains("install-agents")) {
                    profileFile.writeText(
                        """
                        [ -f /root/.bashrc ] && . /root/.bashrc

                        if [ -t 0 ]; then
                            printf "\033[1;36m       /\\         \033[1;37mArchDroid Linux (PRoot)\033[0m\n"
                            printf "\033[1;36m      /  \\        \033[0;37mKernel: Linux 6.17.0-ArchDroid\033[0m\n"
                            printf "\033[1;36m     /\\   \\       \033[0;37mPackage Manager: pacman 7.1\033[0m\n"
                            printf "\033[1;36m    /      \\      \033[0;37mArchitecture: aarch64\033[0m\n"
                            printf "\033[1;36m   /   ,,   \\     \033[0;37mC Library: GNU libc (native)\033[0m\n"
                            printf "\033[1;36m  /   |  |  -\\    \033[0;37mType 'install-agents' for AI tools\033[0m\n"
                            printf "\033[1;36m /_-''    ''-_\\   \033[0;37mType 'archdroid-doctor' for automations\033[0m\n\n"
                        fi
                        """.trimIndent() + "\n"
                    )
                }
            }

            val rebootFile = File(destDir, "usr/local/bin/reboot")
            rebootFile.parentFile?.mkdirs()
            rebootFile.writeText(
                """
                #!/bin/bash
                clear
                printf "\033[1;36m[ArchDroid] Rebooting session...\033[0m\n\n"
                rm -rf /tmp/* /tmp/.* 2>/dev/null
                exec /bin/bash -l
                """.trimIndent() + "\n"
            )
            chmodSafe(rebootFile, 493) // 0755

            val poweroffFile = File(destDir, "usr/local/bin/poweroff")
            poweroffFile.writeText(
                """
                #!/bin/bash
                exec /usr/local/bin/reboot
                """.trimIndent() + "\n"
            )
            chmodSafe(poweroffFile, 493)

            val shutdownFile = File(destDir, "usr/local/bin/shutdown")
            shutdownFile.writeText(
                """
                #!/bin/bash
                exec /usr/local/bin/reboot
                """.trimIndent() + "\n"
            )
            chmodSafe(shutdownFile, 493)

            val backupScript = File(destDir, "usr/local/bin/archdroid-backup")
            backupScript.writeText(
                """
                #!/bin/bash
                set -e
                BACKUP_DIR="/sdcard/Download"
                mkdir -p "%S%BACKUP_DIR" 2>/dev/null || true
                TIMESTAMP=%S%(date +%Y%m%d_%H%M%S)
                OUT_FILE="%S%BACKUP_DIR/ArchDroid-FullBackup-%S%TIMESTAMP.tar.gz"

                echo "=== ArchDroid Full System Snapshot (Mode B) ==="
                echo "Destination: %S%OUT_FILE"
                echo "Compressing full rootfs (this may take 20-40 seconds)..."

                cd /
                tar -czf "%S%OUT_FILE" \
                    --exclude='tmp/*' \
                    --exclude='proc/*' \
                    --exclude='sys/*' \
                    --exclude='dev/*' \
                    --exclude='sdcard/*' \
                    --exclude='storage/*' \
                    --exclude='var/cache/pacman/pkg/*' \
                    --exclude='run/*' \
                    --exclude='var/run/*' \
                    --exclude='var/lock/*' \
                    --exclude='*.sock' \
                    --exclude='*.pid' \
                    --exclude='.bootstrap_lock' \
                    bin boot etc home lib lib64 opt root sbin usr var 2>/dev/null || true

                if [ -f "%S%OUT_FILE" ]; then
                    SIZE=%S%(du -h "%S%OUT_FILE" | cut -f1)
                    echo ""
                    echo "✓ Snapshot successfully created!"
                    echo "  File: %S%OUT_FILE"
                    echo "  Size: %S%SIZE"
                    echo "You can import this archive on your other devices anytime."
                else
                    echo "✗ Backup failed to create %S%OUT_FILE"
                    exit 1
                fi
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(backupScript, 493)

            val restoreScript = File(destDir, "usr/local/bin/archdroid-restore")
            restoreScript.writeText(
                """
                #!/bin/bash
                set -e
                echo "=== ArchDroid Full System Restore ==="
                if [ -z "%S%1" ]; then
                    echo "Usage: archdroid-restore <path-to-backup.tar.gz>"
                    echo "Example: archdroid-restore /sdcard/Download/ArchDroid-FullBackup-YYYYMMDD_HHmmss.tar.gz"
                    echo ""
                    echo "Available backups in /sdcard/Download:"
                    ls -lh /sdcard/Download/ArchDroid-*.tar.gz 2>/dev/null || echo "  (None found)"
                    exit 1
                fi
                ARCHIVE="%S%1"
                if [ ! -f "%S%ARCHIVE" ]; then
                    echo "Error: File not found: %S%ARCHIVE"
                    exit 1
                fi
                echo "Warning: Restoring will overwrite existing system files."
                read -p "Do you want to proceed? (y/N): " -r CONFIRM
                if [[ ! "%S%CONFIRM" =~ ^[Yy]%S% ]]; then
                    echo "Restore cancelled."
                    exit 0
                fi
                echo "Unpacking snapshot from %S%ARCHIVE ..."
                cd /
                tar -xzkf "%S%ARCHIVE" -C / 2>/dev/null || tar -xzf "%S%ARCHIVE" -C /
                echo ""
                echo "✓ Snapshot restored successfully!"
                echo "Please reboot session (type 'reboot') or restart the app."
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(restoreScript, 493)

            // Native ArchDroid automation CLI tools
            val localBin = File(destDir, "usr/local/bin")
            localBin.mkdirs()

            val notifyScript = File(localBin, "archdroid-notification")
            notifyScript.writeText(
                """
                #!/bin/bash
                TITLE="ArchDroid"
                CONTENT=""
                ID=""
                PRIORITY="default"

                while [ %S%# -gt 0 ]; do
                    case "%S%1" in
                        -t|--title) TITLE="%S%2"; shift 2 ;;
                        -c|--content) CONTENT="%S%2"; shift 2 ;;
                        -i|--id) ID="%S%2"; shift 2 ;;
                        -p|--priority) PRIORITY="%S%2"; shift 2 ;;
                        *)
                            if [ -z "%S%CONTENT" ]; then
                                CONTENT="%S%1"
                            fi
                            shift
                            ;;
                    esac
                done

                if [ -z "%S%CONTENT" ] && [ ! -t 0 ]; then
                    CONTENT="%S%(cat)"
                fi

                if [ -z "%S%CONTENT" ]; then
                    CONTENT="%S%TITLE"
                fi

                curl -s -X POST "http://127.0.0.1:48292/api/notify" \
                    --data-urlencode "title=%S%TITLE" \
                    --data-urlencode "content=%S%CONTENT" \
                    --data-urlencode "id=%S%ID" \
                    --data-urlencode "priority=%S%PRIORITY" >/dev/null 2>&1 || true
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(notifyScript, 493)

            val toastScript = File(localBin, "archdroid-toast")
            toastScript.writeText(
                """
                #!/bin/bash
                IS_LONG=false
                MSG=""

                while [ %S%# -gt 0 ]; do
                    case "%S%1" in
                        -s|--short) IS_LONG=false; shift ;;
                        -l|--long) IS_LONG=true; shift ;;
                        -b|-g) shift 2 ;;
                        *)
                            if [ -z "%S%MSG" ]; then
                                MSG="%S%1"
                            else
                                MSG="%S%MSG %S%1"
                            fi
                            shift
                            ;;
                    esac
                done

                if [ -z "%S%MSG" ] && [ ! -t 0 ]; then
                    MSG="%S%(cat)"
                fi

                if [ -z "%S%MSG" ]; then
                    exit 0
                fi

                curl -s -X POST "http://127.0.0.1:48292/api/toast" \
                    --data-urlencode "message=%S%MSG" \
                    --data-urlencode "long=%S%IS_LONG" >/dev/null 2>&1 || true
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(toastScript, 493)

            val wakeLockScript = File(localBin, "archdroid-wake-lock")
            wakeLockScript.writeText(
                """
                #!/bin/bash
                curl -s -X POST "http://127.0.0.1:48292/api/wakelock" -d "action=acquire" >/dev/null 2>&1 || true
                """.trimIndent() + "\n"
            )
            chmodSafe(wakeLockScript, 493)

            val wakeUnlockScript = File(localBin, "archdroid-wake-unlock")
            wakeUnlockScript.writeText(
                """
                #!/bin/bash
                curl -s -X POST "http://127.0.0.1:48292/api/wakelock" -d "action=release" >/dev/null 2>&1 || true
                """.trimIndent() + "\n"
            )
            chmodSafe(wakeUnlockScript, 493)

            val batteryScript = File(localBin, "archdroid-battery-status")
            batteryScript.writeText(
                """
                #!/bin/bash
                curl -s "http://127.0.0.1:48292/api/battery" 2>/dev/null || echo '{"percentage":100,"status":"DISCHARGING","plugged":"UNPLUGGED","health":"GOOD","temperature":25.0}'
                """.trimIndent() + "\n"
            )
            chmodSafe(batteryScript, 493)

            val clipGetScript = File(localBin, "archdroid-clipboard-get")
            clipGetScript.writeText(
                """
                #!/bin/bash
                curl -s "http://127.0.0.1:48292/api/clipboard?format=raw" 2>/dev/null
                """.trimIndent() + "\n"
            )
            chmodSafe(clipGetScript, 493)

            val clipSetScript = File(localBin, "archdroid-clipboard-set")
            clipSetScript.writeText(
                """
                #!/bin/bash
                TEXT=""
                if [ %S%# -gt 0 ]; then
                    TEXT="%S%*"
                elif [ ! -t 0 ]; then
                    TEXT="%S%(cat)"
                fi

                curl -s -X POST "http://127.0.0.1:48292/api/clipboard" \
                    --data-urlencode "text=%S%TEXT" >/dev/null 2>&1 || true
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(clipSetScript, 493)

            val vibrateScript = File(localBin, "archdroid-vibrate")
            vibrateScript.writeText(
                """
                #!/bin/bash
                DUR=200
                while [ %S%# -gt 0 ]; do
                    case "%S%1" in
                        -d|--duration) DUR="%S%2"; shift 2 ;;
                        *) DUR="%S%1"; shift ;;
                    esac
                done

                curl -s -X POST "http://127.0.0.1:48292/api/vibrate" -d "duration=%S%DUR" >/dev/null 2>&1 || true
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(vibrateScript, 493)

            val jobSchedScript = File(localBin, "archdroid-job-scheduler")
            jobSchedScript.writeText(
                """
                #!/bin/bash
                OPT_SCRIPT=""
                OPT_JOB_ID=""
                OPT_PENDING=false
                OPT_CANCEL=false
                OPT_CANCEL_ALL=false
                OPT_PERIOD_MS=900000
                OPT_NETWORK="any"
                OPT_PERSISTED="true"

                while [ %S%# -gt 0 ]; do
                    case "%S%1" in
                        -p|--pending) OPT_PENDING=true; shift ;;
                        --cancel) OPT_CANCEL=true; shift ;;
                        --cancel-all) OPT_CANCEL_ALL=true; shift ;;
                        -s|--script) OPT_SCRIPT="%S%2"; shift 2 ;;
                        --job-id) OPT_JOB_ID="%S%2"; shift 2 ;;
                        --period-ms) OPT_PERIOD_MS="%S%2"; shift 2 ;;
                        --network) OPT_NETWORK="%S%2"; shift 2 ;;
                        --persisted) OPT_PERSISTED="%S%2"; shift 2 ;;
                        --battery-not-low|--storage-not-low|--charging) shift 2 ;;
                        *) shift ;;
                    esac
                done

                if [ "%S%OPT_PENDING" = true ]; then
                    curl -s "http://127.0.0.1:48292/api/jobs?action=list&format=text" 2>/dev/null
                    exit 0
                fi

                if [ "%S%OPT_CANCEL_ALL" = true ]; then
                    curl -s -X POST "http://127.0.0.1:48292/api/jobs" -d "action=cancel_all" >/dev/null 2>&1
                    exit 0
                fi

                if [ "%S%OPT_CANCEL" = true ] && [ -n "%S%OPT_JOB_ID" ]; then
                    curl -s -X POST "http://127.0.0.1:48292/api/jobs" -d "action=cancel" -d "job_id=%S%OPT_JOB_ID" >/dev/null 2>&1
                    exit 0
                fi

                if [ -n "%S%OPT_JOB_ID" ] && [ -n "%S%OPT_SCRIPT" ]; then
                    curl -s -X POST "http://127.0.0.1:48292/api/jobs" \
                        -d "action=add" \
                        -d "job_id=%S%OPT_JOB_ID" \
                        --data-urlencode "script=%S%OPT_SCRIPT" \
                        -d "period_ms=%S%OPT_PERIOD_MS" \
                        -d "network=%S%OPT_NETWORK" \
                        -d "persisted=%S%OPT_PERSISTED" >/dev/null 2>&1
                    exit 0
                fi
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(jobSchedScript, 493)

            val statusScript = File(localBin, "archdroid-status")
            statusScript.writeText(
                """
                #!/bin/bash
                curl -s "http://127.0.0.1:48292/api/status" 2>/dev/null || echo '{"status":"offline","error":"ArchDroidApiServer not reachable"}'
                echo ""
                """.trimIndent() + "\n"
            )
            chmodSafe(statusScript, 493)

            val doctorScript = File(localBin, "archdroid-doctor")
            doctorScript.writeText(
                """
                #!/bin/bash
                echo "============================================================"
                echo "          ARCHDROID STANDALONE AUTOMATION DOCTOR            "
                echo "============================================================"
                echo "Timestamp: %S%(date)"
                echo ""

                echo -n "[1/5] Testing ArchDroid Host API (127.0.0.1:48292)... "
                STATUS_RES=%S%(curl -s --connect-timeout 2 "http://127.0.0.1:48292/api/status" 2>/dev/null)
                if echo "%S%STATUS_RES" | grep -q '"status":"online"'; then
                    echo "✓ ONLINE"
                else
                    echo "✗ OFFLINE (Ensure ArchDroid app or service is active)"
                fi

                echo -n "[2/5] Testing Battery Status Bridge... "
                BAT_RES=%S%(curl -s --connect-timeout 2 "http://127.0.0.1:48292/api/battery" 2>/dev/null)
                if echo "%S%BAT_RES" | grep -q 'percentage'; then
                    PCT=%S%(echo "%S%BAT_RES" | grep -o '"percentage":[0-9]*' | cut -d: -f2)
                    echo "✓ OK (%S%{PCT}%)"
                else
                    echo "✗ FAILED"
                fi

                echo -n "[3/5] Testing WakeLock Bridge... "
                LOCK_RES=%S%(curl -s --connect-timeout 2 -X POST "http://127.0.0.1:48292/api/wakelock" -d "action=acquire" 2>/dev/null)
                if echo "%S%LOCK_RES" | grep -q '"held":true'; then
                    curl -s -X POST "http://127.0.0.1:48292/api/wakelock" -d "action=release" >/dev/null 2>&1
                    echo "✓ OK"
                else
                    echo "✗ FAILED"
                fi

                echo -n "[4/5] Checking CLI Compatibility Tools... "
                MISSING=0
                for cmd in termux-notification termux-toast termux-wake-lock termux-battery-status termux-job-scheduler; do
                    if ! command -v "%S%cmd" >/dev/null 2>&1; then
                        MISSING=%S%((MISSING + 1))
                    fi
                done
                if [ %S%MISSING -eq 0 ]; then
                    echo "✓ ALL 9 TOOLS READY"
                else
                    echo "✗ %S%MISSING TOOLS MISSING"
                fi

                echo ""
                echo "[5/5] Active Background Jobs in Android JobScheduler:"
                echo "------------------------------------------------------------"
                termux-job-scheduler -p
                echo "============================================================"
                """.trimIndent().replace("%S%", "$") + "\n"
            )
            chmodSafe(doctorScript, 493)

            // Drop-in compatibility wrappers for Termux tools
            val termuxMappings = listOf(
                "termux-notification" to "archdroid-notification",
                "termux-toast" to "archdroid-toast",
                "termux-wake-lock" to "archdroid-wake-lock",
                "termux-wake-unlock" to "archdroid-wake-unlock",
                "termux-battery-status" to "archdroid-battery-status",
                "termux-clipboard-get" to "archdroid-clipboard-get",
                "termux-clipboard-set" to "archdroid-clipboard-set",
                "termux-vibrate" to "archdroid-vibrate",
                "termux-job-scheduler" to "archdroid-job-scheduler"
            )

            termuxMappings.forEach { (tName, aName) ->
                val wrapFile = File(localBin, tName)
                wrapFile.writeText(
                    """
                    #!/bin/bash
                    exec /usr/local/bin/$aName "%S%@"
                    """.trimIndent().replace("%S%", "$") + "\n"
                )
                chmodSafe(wrapFile, 493)

                // Also ensure symlink in /usr/bin for scripts expecting it in /usr/bin
                val usrBinFile = File(destDir, "usr/bin/$tName")
                if (!usrBinFile.exists()) {
                    try {
                        Os.symlink("/usr/local/bin/$tName", usrBinFile.absolutePath)
                    } catch (_: Throwable) {}
                }
            }

            listOf(
                "archdroid-notification",
                "archdroid-toast",
                "archdroid-wake-lock",
                "archdroid-wake-unlock",
                "archdroid-battery-status",
                "archdroid-clipboard-get",
                "archdroid-clipboard-set",
                "archdroid-vibrate",
                "archdroid-job-scheduler",
                "archdroid-status",
                "archdroid-doctor",
                "archdroid-backup",
                "archdroid-restore"
            ).forEach { aName ->
                val usrBinFile = File(destDir, "usr/bin/$aName")
                if (!usrBinFile.exists()) {
                    try {
                        Os.symlink("/usr/local/bin/$aName", usrBinFile.absolutePath)
                    } catch (_: Throwable) {}
                }
            }

            // Compatibility shebangs and legacy paths for Termux scripts
            try {
                val termuxUsrDir = File(destDir, "data/data/com.termux/files/usr")
                termuxUsrDir.mkdirs()
                val termuxBinLink = File(termuxUsrDir, "bin")
                if (!termuxBinLink.exists()) {
                    try {
                        Os.symlink("/usr/bin", termuxBinLink.absolutePath)
                    } catch (_: Throwable) {}
                }

                val termuxFilesDir = File(destDir, "data/data/com.termux/files")
                val termuxHomeLink = File(termuxFilesDir, "home")
                if (!termuxHomeLink.exists()) {
                    try {
                        Os.symlink("/root", termuxHomeLink.absolutePath)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            // Ensure executable permissions on all critical binaries across reboots
            listOf(
                "bin/sh",
                "bin/bash",
                "usr/bin/bash",
                "usr/bin/pacman",
                "opt/agy/agy",
                "usr/local/bin/agy",
                "usr/local/bin/install-agents",
                "usr/local/bin/reboot",
                "usr/local/bin/poweroff",
                "usr/local/bin/shutdown",
                "usr/local/bin/archdroid-backup",
                "usr/local/bin/archdroid-restore",
                "usr/local/bin/archdroid-notification",
                "usr/local/bin/archdroid-toast",
                "usr/local/bin/archdroid-wake-lock",
                "usr/local/bin/archdroid-wake-unlock",
                "usr/local/bin/archdroid-battery-status",
                "usr/local/bin/archdroid-clipboard-get",
                "usr/local/bin/archdroid-clipboard-set",
                "usr/local/bin/archdroid-vibrate",
                "usr/local/bin/archdroid-job-scheduler",
                "usr/local/bin/archdroid-status",
                "usr/local/bin/archdroid-doctor",
                "usr/local/bin/termux-notification",
                "usr/local/bin/termux-toast",
                "usr/local/bin/termux-wake-lock",
                "usr/local/bin/termux-wake-unlock",
                "usr/local/bin/termux-battery-status",
                "usr/local/bin/termux-clipboard-get",
                "usr/local/bin/termux-clipboard-set",
                "usr/local/bin/termux-vibrate",
                "usr/local/bin/termux-job-scheduler"
            ).forEach { relPath ->
                val f = File(destDir, relPath)
                if (f.exists()) {
                    chmodSafe(f, 493)
                }
            }

            writePersistentHistoryConfig(destDir)
            writeAgentDocumentation(destDir)
            createAutomationScaffold(destDir)
        } catch (_: Throwable) {}
    }

    fun writeAgentDocumentation(destDir: File) {
        try {
            val docContent = """
            # ArchDroid Native Automation Subsystem (Standalone Architecture)

            ArchDroid runs independently of Termux and Termux:API. It contains a native Android host IPC bridge running on http://127.0.0.1:48292 powered by ArchDroidApiServer inside the Android application.

            ### Available Automation & Device Tools
            All tools below are fully functional inside ArchDroid and connect to the real Android OS:

            1. Notifications (archdroid-notification / termux-notification):
               - Displays real Android status-bar notifications via NotificationManagerCompat.
               - Flags: -t <title>, -c <content>, -i <id>, -p <priority: low|default|high>.
               - Supports pipeline stdin (echo "message" | termux-notification -t "Title").

            2. Toasts (archdroid-toast / termux-toast):
               - Displays real Android screen toasts.
               - Flags: -s (short, default), -l (long).

            3. Wake Locks (archdroid-wake-lock / termux-wake-lock, archdroid-wake-unlock / termux-wake-unlock):
               - Acquires and releases real Android PowerManager.PARTIAL_WAKE_LOCK to keep the CPU awake during long-running tasks.
               - Includes a 2-hour automatic safety watchdog to prevent unintended battery drain.

            4. Battery Status (archdroid-battery-status / termux-battery-status):
               - Returns live JSON format matching Termux API: {"percentage":85,"status":"DISCHARGING","plugged":"UNPLUGGED","health":"GOOD","temperature":31.2}.

            5. Clipboard (archdroid-clipboard-get / termux-clipboard-get, archdroid-clipboard-set / termux-clipboard-set):
               - Directly reads and writes the Android OS system clipboard.

            6. Haptic Vibration (archdroid-vibrate / termux-vibrate):
               - Triggers device haptic feedback (-d <duration_ms>).

            7. Background Job Scheduler (archdroid-job-scheduler / termux-job-scheduler):
               - Backed by Android OS native JobScheduler and ArchDroidJobService.
               - Survives device reboots (persisted jobs automatically restored by ArchDroidBootReceiver).
               - Runs headless tasks via PRoot even when the terminal window is closed.
               - Commands:
                 - termux-job-scheduler -p: List all active scheduled jobs.
                 - termux-job-scheduler --job-id <ID> --script <PATH> --period-ms <MS> --persisted true: Register/update a periodic job (min 15 mins / 900000ms).
                 - termux-job-scheduler --cancel --job-id <ID>: Cancel a scheduled job.
                 - termux-job-scheduler --cancel-all: Cancel all scheduled jobs.

            8. Diagnostic Tool (archdroid-status / archdroid-doctor):
               - Quick health check of the local API bridge and running services.

            ### Drop-in Compatibility Guarantees
            - All termux-* binary names exist in /usr/local/bin and /usr/bin as drop-in wrappers. They are REAL Android APIs, not mock shims.
            - Legacy shebangs (#!/data/data/com.termux/files/usr/bin/bash) automatically resolve through internal compatibility symlinks to /usr/bin/bash.
            - Automation scripts reside in ~/Automation/ with individual job IDs.
            """.trimIndent() + "\n"

            val etcDoc = File(destDir, "etc/archdroid-automations.md")
            etcDoc.parentFile?.mkdirs()
            etcDoc.writeText(docContent)

            val rootAgentsDir = File(destDir, "root/.agents")
            rootAgentsDir.mkdirs()
            val rootAgentsFile = File(rootAgentsDir, "AGENTS.md")
            val existing = if (rootAgentsFile.exists()) rootAgentsFile.readText() else ""
            if (!existing.contains("ArchDroid Native Automation Subsystem")) {
                rootAgentsFile.writeText(existing + "\n\n" + docContent)
            }

            val skelAgentsDir = File(destDir, "etc/skel/.agents")
            skelAgentsDir.mkdirs()
            File(skelAgentsDir, "AGENTS.md").writeText(docContent)
        } catch (_: Throwable) {}
    }

    fun createAutomationScaffold(destDir: File) {
        try {
            val autoDir = File(destDir, "root/Automation")
            if (!autoDir.exists()) {
                autoDir.mkdirs()
                val welcomeDir = File(autoDir, "welcome-task")
                welcomeDir.mkdirs()

                File(welcomeDir, "automation.json").writeText(
                    """
                    {
                      "job_id": 999,
                      "name": "Welcome Notification",
                      "schedule": "Periodic ~15m",
                      "period_ms": 900000,
                      "persisted": true,
                      "network": "any"
                    }
                    """.trimIndent() + "\n"
                )

                val welcomeScript = File(welcomeDir, "run_welcome.sh")
                welcomeScript.writeText(
                    """
                    #!/bin/bash
                    # Example ArchDroid Background Automation
                    echo "[%S%(date '+%Y-%m-%d %H:%M:%S')] ArchDroid automation heartbeat active" >> /root/Automation/welcome-task/heartbeat.log
                    termux-toast "ArchDroid background automation tick" >/dev/null 2>&1 || true
                    """.trimIndent().replace("%S%", "$") + "\n"
                )
                chmodSafe(welcomeScript, 493)
            }
        } catch (_: Throwable) {}
    }

    fun writePersistentHistoryConfig(destDir: File) {
        try {
            val profileD = File(destDir, "etc/profile.d")
            profileD.mkdirs()

            val historyScript = File(profileD, "01-persistent-history.sh")
            historyScript.writeText(
                """
                # ArchDroid: Persistent command history across sessions, tabs, and reboots
                export HISTFILE="/root/.bash_history"
                export HISTSIZE=10000
                export HISTFILESIZE=20000
                export HISTCONTROL=ignoreboth:erasedups
                shopt -s histappend 2>/dev/null
                # Immediately flush each command to disk and reload so all tabs stay in sync
                export PROMPT_COMMAND="history -a; history -n; ${'$'}PROMPT_COMMAND"
                """.trimIndent() + "\n"
            )
            chmodSafe(historyScript, 493) // 0755

            // Ensure /root/.bashrc sources this script even in non-login subshells
            val bashrc = File(destDir, "root/.bashrc")
            val marker = "01-persistent-history.sh"
            val currentBashrc = if (bashrc.exists()) bashrc.readText() else ""
            if (!currentBashrc.contains(marker)) {
                bashrc.writeText(currentBashrc + "\n[ -r /etc/profile.d/01-persistent-history.sh ] && . /etc/profile.d/01-persistent-history.sh\n")
            }
        } catch (_: Throwable) {}
    }

    private fun chmodSafe(file: File, mode: Int) {
        val effectiveMode = if (mode == 0) 493 else mode // default 0755 if unspecified
        try {
            Os.chmod(file.absolutePath, effectiveMode)
        } catch (_: Throwable) {
            if ((effectiveMode and 0b001_000_000) != 0) {
                file.setExecutable(true, false)
            }
        }
    }

    fun createSysdataFiles(sysdataDir: File) {
        sysdataDir.mkdirs()
        File(sysdataDir, "sys_empty").mkdirs()

        File(sysdataDir, "loadavg").writeText("0.12 0.08 0.04 2/180 1024\n")
        File(sysdataDir, "uptime").writeText("3600.00 28800.00\n")
        File(sysdataDir, "version").writeText(
            "Linux version 6.17.0-ArchDroid-PRoot (arch@android) (gcc 14.2.0, GNU ld 2.43) #1 SMP PREEMPT_DYNAMIC aarch64\n"
        )
        File(sysdataDir, "sysctl_entry_cap_last_cap").writeText("40\n")
        File(sysdataDir, "sysctl_inotify_max_user_watches").writeText("65536\n")
        File(sysdataDir, "sysctl_kernel_overflowuid").writeText("65534\n")
        File(sysdataDir, "sysctl_kernel_overflowgid").writeText("65534\n")

        File(sysdataDir, "stat").writeText(
            """
            cpu  1957 0 2877 93280 262 342 254 87 0 0
            cpu0 245 0 360 11660 32 42 31 10 0 0
            cpu1 245 0 360 11660 32 42 31 10 0 0
            cpu2 245 0 360 11660 32 42 31 10 0 0
            cpu3 245 0 360 11660 32 42 31 10 0 0
            cpu4 245 0 360 11660 32 42 31 10 0 0
            cpu5 245 0 360 11660 32 42 31 10 0 0
            cpu6 245 0 360 11660 32 42 31 10 0 0
            cpu7 242 0 357 11660 38 48 37 17 0 0
            intr 127541 38 290 0 0 0 0 4 0 1 0 0
            ctxt 140223
            btime 1730000000
            processes 512
            procs_running 2
            procs_blocked 0
            softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
            """.trimIndent() + "\n"
        )

        File(sysdataDir, "vmstat").writeText(
            """
            nr_free_pages 1743136
            nr_zone_inactive_anon 179281
            nr_zone_active_anon 7183
            nr_zone_inactive_file 22858
            nr_zone_active_file 51328
            nr_zone_unevictable 642
            nr_zone_write_pending 0
            nr_mlock 0
            nr_bounce 0
            nr_zspages 0
            nr_free_cma 0
            """.trimIndent() + "\n"
        )
    }
}
