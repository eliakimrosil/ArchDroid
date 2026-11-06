# ArchDroid

> A modern multi-tab Android Arch Linux terminal emulator optimized for developers and AI coding agents.

---

## Overview

**ArchDroid** is a standalone Android terminal application that runs an embedded **Arch Linux ARM (`aarch64`)** environment with native **GNU C Library (glibc)** inside a native **PRoot** sandbox without requiring root privileges.

### Core Features
- **Native glibc Runtime:** Complete binary compatibility with standard Linux packages, prebuilt Node.js, Python wheels, and Rust/Go binaries with zero translation shims.
- **Pacman 7.1:** Curated minimal Arch Linux ARM rootfs with pacman, bash, coreutils, curl, gnupg, and pre-populated keyrings.
- **Multi-Tab Terminal:** Smooth tab switching, persistent sessions, custom tab renaming, and safe tab close controls.
- **AI Coding Agents Ready:** Pre-bundled with Antigravity CLI (`agy` v1.5.1) and automated single-command provisioner for `claude`, `codex`, and `hermes`.
- **Background Persistence:** Powered by an Android Foreground Service with CPU `WakeLock` & `WifiLock` so dev servers, scripts, and sessions stay alive when switching apps or locking your device.
- **Real-Time Bash History:** Commands are instantly written to `~/.bash_history` via `PROMPT_COMMAND` synchronization across all open tabs.
- **Samsung DeX & Desktop Optimized:** Hardware keyboard shortcuts (`Ctrl+T`, `Ctrl+W`, `Ctrl+Tab`), mouse wheel zoom, right-click context menu, and automatic touch vs. hardware input detection.
- **Arch Aesthetics:** Curated Arch Linux Cyan theme (`#1793D1`) and dark palettes with cursor customizations and custom font support.

---

## Tech Stack & Architecture

| Layer | Component |
|---|---|
| **UI** | Jetpack Compose + Material3 (`Kotlin`) |
| **Terminal Core** | Termux Terminal View & Emulator (`v0.118.0`) |
| **Linux Runtime** | Embedded PRoot (`libproot.so`, `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so`) |
| **Rootfs** | Curated Arch Linux ARM base rootfs + native glibc + pacman 7.1 |
| **Foreground Service** | `TerminalForegroundService` with low-priority ongoing notification and exit action |

---

## Build Instructions

```bash
# Compile Debug APK
cd /home/eliakimrosil/Projects/AndroidApp/ArchDroid
/home/eliakimrosil/gradle/gradle-8.7/bin/gradle assembleDebug
```

Built APK is automatically placed in `/sdcard/Download/ArchDroid-debug.apk`.
