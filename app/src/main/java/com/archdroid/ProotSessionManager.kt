package com.archdroid

import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object ProotSessionManager {

    fun createArchSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        initialCommand: String? = null
    ): TerminalSession {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath
        val prootLoader = File(nativeLibDir, "libproot-loader.so").absolutePath
        val prootLoader32 = File(nativeLibDir, "libproot-loader32.so").absolutePath

        val rootfsDir = BootstrapManager.getRootfsDir(context)
        val sysdataDir = BootstrapManager.getSysdataDir(context)
        val tmpDir = BootstrapManager.getTmpDir(context)
        val shmDir = BootstrapManager.getShmDir(context)

        // Clean stale temporary sockets & lock files from previous runs / reboots
        try {
            tmpDir.listFiles()?.forEach { if (it.name.startsWith("proot-") || it.name.endsWith(".sock") || it.name.endsWith(".lock")) it.deleteRecursively() }
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
        File(rootfsDir, "tmp").mkdirs()
        BootstrapManager.createSysdataFiles(sysdataDir)

        val shellInside = when {
            File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(rootfsDir, "bin/bash").exists() -> "/bin/bash"
            else -> "/bin/sh"
        }

        val argsList = mutableListOf(
            prootBin,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=\\Linux\\localhost\\6.17.0-ArchDroid-PRoot\\#1 SMP PREEMPT_DYNAMIC aarch64\\aarch64\\localdomain\\-1\\",
            "-L",
            "--change-id=0:0",
            "--rootfs=${rootfsDir.absolutePath}",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=${File(sysdataDir, "sys_empty").absolutePath}:/sys/fs/selinux",
            "--bind=${File(sysdataDir, "loadavg").absolutePath}:/proc/loadavg",
            "--bind=${File(sysdataDir, "stat").absolutePath}:/proc/stat",
            "--bind=${File(sysdataDir, "uptime").absolutePath}:/proc/uptime",
            "--bind=${File(sysdataDir, "version").absolutePath}:/proc/version",
            "--bind=${File(sysdataDir, "vmstat").absolutePath}:/proc/vmstat",
            "--bind=${File(sysdataDir, "sysctl_entry_cap_last_cap").absolutePath}:/proc/sys/kernel/cap_last_cap",
            "--bind=${File(sysdataDir, "sysctl_inotify_max_user_watches").absolutePath}:/proc/sys/fs/inotify/max_user_watches",
            "--bind=${File(sysdataDir, "sysctl_kernel_overflowuid").absolutePath}:/proc/sys/kernel/overflowuid",
            "--bind=${File(sysdataDir, "sysctl_kernel_overflowgid").absolutePath}:/proc/sys/kernel/overflowgid",
            "--bind=${shmDir.absolutePath}:/dev/shm"
        )

        val sdcard = File("/storage/self/primary")
        if (sdcard.exists()) {
            argsList.add("--bind=/storage/self/primary:/sdcard")
        }

        if (initialCommand.isNullOrBlank()) {
            argsList.add(shellInside)
            argsList.add("-l")
        } else {
            argsList.add(shellInside)
            argsList.add("-lc")
            argsList.add("$initialCommand; exec $shellInside -l")
        }

        val envList = arrayOf(
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
            "PROOT_LOADER=$prootLoader",
            "PROOT_LOADER_32=$prootLoader32",
            "LD_LIBRARY_PATH=$nativeLibDir",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin",
            "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
            "NODE_OPTIONS=--require /usr/local/lib/node-android-fix.js --dns-result-order=ipv4first",
            "UV_LINK_MODE=copy",
            "HISTFILE=/root/.bash_history",
            "HISTSIZE=10000",
            "HISTFILESIZE=20000",
            "HISTCONTROL=ignoreboth:erasedups"
        )

        return TerminalSession(
            prootBin,
            rootfsDir.absolutePath,
            argsList.toTypedArray(),
            envList,
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            sessionClient
        )
    }

    @Deprecated(
        "Use createArchSession instead",
        ReplaceWith("createArchSession(context, sessionClient, initialCommand)")
    )
    fun createAlpineSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        initialCommand: String? = null
    ): TerminalSession = createArchSession(context, sessionClient, initialCommand)

    fun executeHeadlessScript(
        context: Context,
        scriptPath: String,
        jobId: Int = -1
    ): Int {
        val rootfsDir = BootstrapManager.getRootfsDir(context)
        val sysdataDir = BootstrapManager.getSysdataDir(context)
        val tmpDir = BootstrapManager.getTmpDir(context)
        val shmDir = BootstrapManager.getShmDir(context)

        tmpDir.mkdirs()
        shmDir.mkdirs()
        File(rootfsDir, "tmp").mkdirs()
        BootstrapManager.createSysdataFiles(sysdataDir)

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath
        val prootLoader = File(nativeLibDir, "libproot-loader.so").absolutePath
        val prootLoader32 = File(nativeLibDir, "libproot-loader32.so").absolutePath

        val shellInside = when {
            File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(rootfsDir, "bin/bash").exists() -> "/bin/bash"
            else -> "/bin/sh"
        }

        val cleanScript = scriptPath.trim()
        val execCmd = if (cleanScript.contains(" ") && !cleanScript.startsWith("\"") && !cleanScript.contains("'")) {
            "\"$cleanScript\""
        } else {
            cleanScript
        }

        val argsList = mutableListOf(
            prootBin,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=\\Linux\\localhost\\6.17.0-ArchDroid-PRoot\\#1 SMP PREEMPT_DYNAMIC aarch64\\aarch64\\localdomain\\-1\\",
            "-L",
            "--change-id=0:0",
            "--rootfs=${rootfsDir.absolutePath}",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=${File(sysdataDir, "sys_empty").absolutePath}:/sys/fs/selinux",
            "--bind=${File(sysdataDir, "loadavg").absolutePath}:/proc/loadavg",
            "--bind=${File(sysdataDir, "stat").absolutePath}:/proc/stat",
            "--bind=${File(sysdataDir, "uptime").absolutePath}:/proc/uptime",
            "--bind=${File(sysdataDir, "version").absolutePath}:/proc/version",
            "--bind=${File(sysdataDir, "vmstat").absolutePath}:/proc/vmstat",
            "--bind=${File(sysdataDir, "sysctl_entry_cap_last_cap").absolutePath}:/proc/sys/kernel/cap_last_cap",
            "--bind=${File(sysdataDir, "sysctl_inotify_max_user_watches").absolutePath}:/proc/sys/fs/inotify/max_user_watches",
            "--bind=${File(sysdataDir, "sysctl_kernel_overflowuid").absolutePath}:/proc/sys/kernel/overflowuid",
            "--bind=${File(sysdataDir, "sysctl_kernel_overflowgid").absolutePath}:/proc/sys/kernel/overflowgid",
            "--bind=${shmDir.absolutePath}:/dev/shm"
        )

        val sdcard = File("/storage/self/primary")
        if (sdcard.exists()) {
            argsList.add("--bind=/storage/self/primary:/sdcard")
        }

        argsList.add(shellInside)
        argsList.add("-lc")
        argsList.add(execCmd)

        val envMap = mapOf(
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_LOADER" to prootLoader,
            "PROOT_LOADER_32" to prootLoader32,
            "LD_LIBRARY_PATH" to nativeLibDir,
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "NODE_OPTIONS" to "--require /usr/local/lib/node-android-fix.js --dns-result-order=ipv4first",
            "UV_LINK_MODE" to "copy",
            "ARCHDROID_JOB_ID" to jobId.toString(),
            "ARCHDROID_STANDALONE" to "1"
        )

        return try {
            val pb = ProcessBuilder(argsList)
            pb.environment().putAll(envMap)
            pb.redirectErrorStream(true)

            val logDir = File(context.filesDir, "job_logs")
            logDir.mkdirs()
            val logFile = File(logDir, "job_${if (jobId >= 0) jobId.toString() else "adhoc"}.log")
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

            val proc = pb.start()
            proc.waitFor()
        } catch (_: Throwable) {
            -1
        }
    }
}
