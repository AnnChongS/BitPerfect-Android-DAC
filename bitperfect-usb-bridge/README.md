# BitPerfect USB Bridge (`v1.1.0`)

[English](#english) | [中文说明](#中文说明)

---

## English

**BitPerfect USB Bridge** is a universal **Zero-Root / Shizuku** exclusive USB Audio Class 1.0/2.0 (UAC1/UAC2) DAC bridge for **Android 14 – 17** (`arm64-v8a`, **16 KB page-size aligned**). It captures internal audio from **any music player, audio app, or rhythm game** (`AudioPlaybackCapture`, `USAGE_MEDIA + USAGE_GAME + USAGE_UNKNOWN`) and streams directly to external USB Type-C DAC dongles via a custom C++17 `libusb` isochronous engine — bypassing Android's mandatory 48 kHz USB mixer resampling.

### What's New in `v1.1.0` (Universal Multi-SoC & Multi-Player)
- **Universal Multi-SoC Compatibility**:
  - Automatic runtime SoC profiling for **Qualcomm Snapdragon**, **Samsung Exynos**, **MediaTek Dimensity**, **Google Tensor**, **Xiaomi XRING**, and generic ARM devices.
  - Isolates Qualcomm PAL DeepBuffer pre-warm strictly to Snapdragon chips; non-Qualcomm SoCs use progressive offload-safe USB detach, zero illegal ISO `libusb_clear_halt()`, zero `libusb_reset_device()`, and a single-segment xHCI ISO ring (`< 255 TRBs`) for rock-solid zero-root playback on Samsung Exynos & other non-Qualcomm devices.
- **Universal Multi-Player + Rhythm-Game Low Latency**:
  - **Global Auto-Track** (captures all music / audio / rhythm-game apps, automatically excluding self) or **Single-App Lock** via a searchable full-device App Picker (Apple Music, Spotify, Tidal, NetEase Cloud, QQ Music, DLsite Sound, Phigros, Arcaea, Cytus, etc.).
  - **60 ms Rhythm-Game Low-Latency Mode** (`10 ms` AudioRecord chunks + `12 ms` / `24 ms` DMA ring) alongside `150 / 250 / 400 ms` Hi-Fi jitter-free presets.
- **Anti-Speaker-Blast & Anti-Ear-Blast Shields**:
  - In-place `Global <-> Single App` capture swap without closing the USB DAC, plus automatic `STREAM_MUSIC` mute + `KEYCODE_MEDIA_PAUSE` shield during scope switches, rebinds, stops, and unplugs so audio never blasts out of phone speakers at 100% volume.
  - Hardware volume retry (up to 3 attempts per channel) + automatic post-`SET_INTERFACE` hardware gain re-enforcement so switching sample rates (`44.1 <-> 48 kHz`) or bit depth (`24 <-> 32-bit`) never resets DAC hardware gain to `0 dB`.
- **Xiaomi XRING Protection & Hub Disclaimer**:
  - On Xiaomi XRING devices where kernel `xring_usb_adsp` hijacks direct-plugged USB DACs (known on XRING O1 Tablet HyperOS OS4.0.6.0), direct USB is locked by default with a high-friction red risk-confirmation override (for testing XRING O1 phones / future XRING O3 / newer ROMs) + runtime ADSP-reset circuit breaker. External USB Hub connections are allowed with an explicit stability & audio-quality disclaimer.
- **Dynamic `r_submix` Probing & Shizuku Auto-Rate (`uid=2000`)**:
  - Automatically probes the device's `r_submix` ceiling via Shizuku for native Hi-Res pass-through when supported, or clean integer-divisor locking (`44.1 / 48 kHz` families) on stock 48 kHz HALs.

### Building from Source
1. Requirements: JDK 17+, Android SDK Platform 36, Android NDK `27.0.12077973`, CMake `3.22.1`.
2. *(On Windows / macOS / x86_64 Linux)*: Comment out `android.aapt2FromMavenOverride` in `gradle.properties`.
3. Build APK:
   ```bash
   ./gradlew assembleRelease
   ```
   *(Optional)* Create `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in project root for release signing; otherwise Gradle falls back to debug signing.

---

## 中文说明

**BitPerfect USB Bridge** 是一款专为 **Android 14 – 17**（`arm64-v8a`，原生支持 `16 KB` 内存页对齐）打造的全平台 **免 Root / Shizuku 双模** USB UAC1/UAC2 解码耳放（小尾巴）独占直通工具。它通过底层 C++17 `libusb` 等时传输直接驱动外接 USB DAC，支持捕获**任意音乐软件、音声应用与音游**内部音频，绕过 Android 系统默认对 USB 声卡的 48 kHz 强制重采样。

### `v1.1.0` 核心升级（全芯片平台 · 全音乐/音游软件 · 双重防爆音）
- **全芯片平台通用适配**：
  - 自动识别 **高通骁龙（Snapdragon）**、**三星 Exynos**、**联发科天玑（Dimensity）**、**Google Tensor**、**小米玄戒（XRING）** 及通用 ARM 平台。
  - 高通 PAL DeepBuffer 预热严格隔离至高通平台；非高通平台采用通用 Offload 安全接管顺序、彻底移除非法 ISO `clear_halt` 与 `libusb_reset_device`，并启用单段 xHCI 安全环（`< 255 TRBs`），**已在三星纯无 Root 环境实测稳定直出**。
- **全应用捕获 + 60 ms 音游低延迟**：
  - 支持 **全局自动追踪**（自动捕获所有第三方音乐/音声/音游应用，排除本应用）与 **指定单个 App 锁定**（内置可搜索全机应用选择器，支持 Apple Music、网易云、QQ 音乐、DLsite Sound、Phigros、Arcaea 等）。
  - 新增 **60 ms 音游低延迟档位**，并保留 `150 / 250 / 400 ms` Hi-Fi 抗抖动档位。
- **防外放社死盾 + 防切频 0 dB 炸耳保护**：
  - **防外放爆音盾**：切换“全局 ↔ 单应用”原地热切换不断开 USB；切频重绑定、停止直通或拔出耳机时自动静音系统媒体通道并发送暂停指令，防止公共场合 100% 音量外放。
  - **防硬件音量回弹 0 dB**：底层 C++ 新增单声道失败自动重试（最多 3 次）以及 `SET_INTERFACE` 切频/切位深后自动二次强锁目标 dB，彻底根治切换 `44.1/48 kHz` 或 `24/32-bit` 时硬件音量偶发回弹 `0 dB` 炸耳。
- **小米玄戒（XRING）直插防死机护盾 + Hub 免责模式**：
  - 自动识别直插与外接 USB Hub 拓扑；针对已知玄戒 O1 平板（OS4.0.6.0）内核 `xring_usb_adsp` 锁死直插声卡的问题，默认锁定直插并内置 ADSP 复位防死机熔断，同时保留醒目的**红色高风险免责解锁入口**（含勾选确认 + 3 秒阅读倒计时）供玄戒 O1 手机 / 未来玄戒 O3 / 新版系统测试；外接 USB Hub 则在提示不稳定与音质不保证免责声明后开放。
- **中英双语自适应 + 动态 `r_submix` 探测**：
  - 界面随系统语言自动切换中文/英文并支持右上角一键切换；授权 Shizuku 后自动嗅探全机任意活跃播放器采样率与系统 `r_submix` 能力上限。

### 源码编译
1. 环境要求：JDK 17+、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1`。
2. 在 Windows / macOS / x86_64 Linux 上编译前，请注释掉 `gradle.properties` 中的 `android.aapt2FromMavenOverride`。
3. 执行构建：
   ```bash
   ./gradlew assembleRelease
   ```

## License
Released under the [MIT License](LICENSE).
