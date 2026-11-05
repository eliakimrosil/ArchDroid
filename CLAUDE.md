# ArchDroid — CLAUDE.md

Standalone Android Linux terminal app running **Arch Linux ARM (`aarch64`)** inside an embedded **PRoot** runtime with:
- **Native GNU libc (glibc)** — 100% native compatibility with standard Linux CLI tools, Python wheels, prebuilt NodeJS, and Rust binaries.
- **Pacman 7.1** — Curated minimal Arch rootfs with pre-populated `archlinuxarm` keyring and PRoot sandbox workarounds (`DisableSandboxFilesystem`, `DisableSandboxSyscalls`).
- **Antigravity CLI (`agy` v1.5.1)** — Pre-bundled offline in the APK (`assets/agy.gz.bin`) running natively on glibc.
- **Claude Code (`claude`)** — Installed on first boot via `@anthropic-ai/claude-code` with an Android PRoot Node.js `libuv` compatibility shim (`/usr/local/lib/node-android-fix.js`).
- **OpenAI Codex CLI (`codex`)** — Installed on first boot via `@openai/codex` (`aarch64` native binary).
- **Hermes Agent (`hermes`)** — Installed on first boot from `NousResearch/hermes-agent` using `uv` with `UV_LINK_MODE=copy` into `/root/.hermes/hermes-agent`.

---

## Build & Install

```bash
# Build debug APK
cd /home/eliakimrosil/Projects/AndroidApp/ArchDroid
/home/eliakimrosil/gradle/gradle-8.7/bin/gradle assembleDebug

# Output APK is automatically copied to /sdcard/Download/ArchDroid-debug.apk
```

---

## Architecture

| Component | Implementation |
|---|---|
| Terminal Engine & View | `com.github.termux.termux-app:terminal-view:v0.118.0` + `terminal-emulator:v0.118.0` |
| Linux Runtime | `libproot.so`, `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so` in `jniLibs/arm64-v8a/` (`targetSdk = 28`, `extractNativeLibs = true`) |
| Rootfs | Curated Arch Linux ARM `aarch64` rootfs with native glibc and pacman 7.1 in `assets/arch-rootfs.part{1,2}.bin` |
| Bundled Agent | `assets/agy.gz.bin` (`antigravity-cli` `aarch64` binary extracted to `/opt/agy/agy` on first launch) |
| First-Boot Provisioner | `/usr/local/bin/install-agents` (installs `nodejs`, `npm`, `python`, `uv`, `git`, `ripgrep`, `claude`, `codex`, and `hermes`) |
| Samsung DeX & Desktop | Freeform window resizeable (`1080x720` default), density keepalive, full hardware keyboard shortcuts, text mouse pointer, wheel zoom, right/middle-click copy & paste, auto-collapsing touch keys |
| Android PRoot Fixes | Spoofed `/proc/{stat,loadavg,uptime,version,vmstat}` + `/sys/fs/selinux` binds + `/usr/local/lib/node-android-fix.js` (`NODE_OPTIONS`) |

---

## Desktop & Keyboard/Mouse Controls

- **Clipboard:** `Ctrl+Shift+C` (copy selected text), `Ctrl+Shift+V` / `Shift+Insert` (paste).
- **Mouse Clicks:**
  - **Right-Click:** Copies selected text (if selection active) or pastes from clipboard / opens tab context menu.
  - **Middle-Click:** Pastes from clipboard / closes tab.
  - **Hover:** I-beam text cursor.
- **Mouse Wheel / Zoom:**
  - `Ctrl + Mouse Wheel Up/Down`: Zoom font size in/out.
  - `Ctrl + =` / `Ctrl + -` / `Ctrl + 0`: Zoom in / Zoom out / Reset font size.
- **Tabs:**
  - `Ctrl+T`: New tab.
  - `Ctrl+W`: Close active tab.
  - `Ctrl+Tab` / `Ctrl+Shift+Tab`: Next / Previous tab.
  - `Alt + 1` .. `Alt + 9`: Jump directly to `tty1` .. `tty9`.
  - Double-tap tab: Rename tab.
  - Long press / Right-click tab: Context menu (Rename, Close, Close Other Tabs).
- **Foreground Service & Persistence:**
  - Tabs and dev processes remain alive when closing or minimizing app.
  - `moveTaskToBack(true)` on back press.
  - Persistent real-time history sync via `PROMPT_COMMAND` in `~/.bash_history`.

---

## Source Files

All under `app/src/main/java/com/archdroid/`:

- `MainActivity.kt` — Jetpack Compose dark terminal UI, multi-session tabs, theme picker, cursor customization, keyboard shortcuts.
- `TerminalSessionHolder.kt` — Singleton managing the lifecycle of background PRoot sessions.
- `TerminalForegroundService.kt` — Persistent foreground service with CPU WakeLock and Wi-Fi lock.
- `BootstrapManager.kt` — Extracts `arch-rootfs.tgz.bin`, sets up permissions and real-time history config.
- `ProotSessionManager.kt` — Configures and spawns `libproot.so` sessions with Arch Linux environment.
- `TerminalThemeManager.kt` — Color schemes with signature Arch Linux Cyan (`#1793D1`) default theme and custom fonts.
- `TerminalSelectionController.kt` — High-performance desktop text selection controller.
