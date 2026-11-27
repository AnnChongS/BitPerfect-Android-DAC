# BitPerfect USB Bridge

[English](#english) | [中文说明](#中文说明)

---

## English

**BitPerfect USB Bridge** is an audiophile-grade **Zero-Root / Shizuku** exclusive USB Audio Class 1.0/2.0 (UAC1/UAC2) DAC bridge for **Android 14 – 17** (`arm64-v8a`, 16 KB page-size aligned). It routes internal audio from **Apple Music** (and other media apps supporting `AudioPlaybackCapture`) directly to external USB Type-C DAC dongles via custom C++ `libusb` isochronous transfers, bypassing Android's mandatory 48 kHz system USB mixer resampling.

### Key Features
- **Dual Operating Modes (Zero Root Required)**:
  - **Manual Mode (Pure Non-Root)**: Select `44.1 kHz` (`44.1 / 88.2 / 176.4 kHz` integer family) or `48.0 kHz` (`48 / 96 / 192 kHz` integer family) with zero ADB or Root setup.
  - **Shizuku Auto Mode (`uid=2000`)**: Automatically sniffs Apple Music's active track sample rate via `dumpsys audio` and switches DAC clocks between `44.1 kHz` and `48.0 kHz` families seamlessly.
- **Custom C++17 `libusb` UAC1/UAC2 Engine**:
  - Two-stage glitch-free kernel driver handoff (`IF#2` Playback -> `IF#0` AudioControl while leaving `IF#1` Microphone untouched).
  - Full UAC2 `ClockSource` / `ClockSelector` verification + asynchronous/adaptive 12-URB Isochronous OUT engine + `4 MB` lock-free SPSC ring buffer.
  - True hardware UAC FeatureUnit volume control (`0.5 dB` precision) + `24-bit (S24_3LE)` / `32-bit (S32_LE)` wire format selection.
- **Qualcomm PAL Anti-Stutter & Auto-Heal**:
  - Pre-warms `PAL_STREAM_DEEP_BUFFER` prior to `snd-usb-audio` detachment and includes an automatic `zeroDrops` self-healing watchdog across Hi-Res track transitions.
- **System Volume Guard**:
  - Live `STREAM_MUSIC` volume monitor with 1-tap `Set 100% (0 dB)` restoration to prevent muted or bit-reduced `AudioPlaybackCapture`.
- **Bilingual UI (English & 中文)**:
  - Automatically follows system language (`zh*` -> Chinese, all other locales -> English) with instant 1-tap in-app switching.

> **Note on >48 kHz Hi-Res**: Stock Android's `r_submix` virtual capture HAL is capped at `48 kHz`. To avoid blurry non-integer resampling, `88.2/176.4 kHz` tracks map cleanly by integer divisor to `44.1 kHz` and `96/192 kHz` tracks map to `48.0 kHz`. For true `24-bit / 192 kHz` un-downsampled output on rooted devices, see sister project **BitPerfect Root DAC**.

### Building from Source
1. Requirements: JDK 17+, Android SDK Platform 36, Android NDK `27.0.12077973`, CMake `3.22.1`.
2. *(On Windows / macOS / x86_64 Linux)*: Comment out `android.aapt2FromMavenOverride` in `gradle.properties`.
3. Build APK:
   ```bash
   ./gradlew assembleRelease
   ```
   *(Optional)* Create `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in project root for custom release signing; otherwise Gradle automatically signs with the debug key for local testing.

---

## 中文说明

**BitPerfect USB Bridge** 是一款专为 **Android 14 – 17**（`arm64-v8a`，原生支持 `16 KB` 内存页对齐）打造的 **免 Root / Shizuku 双模** USB UAC1/UAC2 解码耳放（小尾巴）独占直通工具。它通过底层 C++ `libusb` 异步等时传输直接驱动外接 USB DAC，绕过 Android 系统默认对 USB 声卡的 48 kHz 强制重采样。

### 核心特性
- **双模式架构（完全免 Root）**：
  - **无 Root 手动模式**：零门槛即插即用，提供 `44.1 kHz 基准`（适配 `44.1/88.2/176.4k`）与 `48.0 kHz 基准`（适配 `48/96/192k`）整数分频锁定。
  - **Shizuku 自动模式 (`uid=2000`)**：通过无线调试授权 Shizuku 后，自动嗅探 Apple Music 当前曲目采样率并在 `44.1 / 48 kHz` 时钟族之间无感切换。
- **自研 C++17 `libusb` UAC1/UAC2 独占引擎**：
  - 两阶段纯净接管（先接管 `IF#2` 播放流并完成静音握手，再接管 `IF#0` 控制接口，跳过 `IF#1` 麦克风接口避免干扰 ADC 时钟）。
  - 支持 UAC2 硬件音量寄存器（`0.5 dB` 步进）、`24-bit (S24_3LE)` / `32-bit (S32_LE)` 传输位深切换、`4 MB` 零锁 SPSC 环形缓冲与内置 `440 Hz` 双精度硬件晶振自检。
- **高通 PAL 防卡顿与自动自愈**：
  - 内置 `PAL_STREAM_DEEP_BUFFER` 预热与 Hi-Res 切歌主动重同步 + `zeroDrops` 静音洞自愈看门狗，彻底解决切歌卡顿与二次连接杂音。
- **系统音量满幅守护 + 中英双语自适应**：
  - 实时检测手机媒体音量，未满格或静音时提供「一键满格（0 dB 无损位深）」按钮；界面默认跟随系统语言（中文系统显示中文，英文及其他语言系统显示英文），并支持右上角一键切换。

### 源码编译
1. 环境要求：JDK 17+、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1`。
2. 在 Windows / macOS / x86_64 Linux 上编译前，请注释掉 `gradle.properties` 最后一行的 `android.aapt2FromMavenOverride`。
3. 执行构建：
   ```bash
   ./gradlew assembleRelease
   ```

## License
Released under the [MIT License](LICENSE).
