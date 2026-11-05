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
                            printf "\033[1;36m /_-''    ''-_\\   \033[0m\n\n"
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
                "usr/local/bin/shutdown"
            ).forEach { relPath ->
                val f = File(destDir, relPath)
                if (f.exists()) {
                    chmodSafe(f, 493)
                }
            }

            writePersistentHistoryConfig(destDir)
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
