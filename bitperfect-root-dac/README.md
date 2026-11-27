# BitPerfect Root DAC

[English](#english) | [中文说明](#中文说明)

---

## English

**BitPerfect Root DAC** is an audiophile **Root + LSPosed** native `16/24/32-bit`, `44.1 kHz – 192 kHz` true **1:1 Bit-Perfect** USB Audio Class 1.0/2.0 (UAC1/UAC2) DAC driver for **Android 14 – 17** (`arm64-v8a`, 16 KB page-size aligned), purpose-built for **Apple Music** (`com.apple.android.music`).

### How It Achieves True 24-bit / 192 kHz Bit-Perfect on Android
Stock Android's `MediaProjection` and `r_submix` HAL cap internal audio capture at `48 kHz`. **BitPerfect Root DAC** completely bypasses `AudioFlinger`, `r_submix`, and Android's volume/mixer pipeline:
1. **In-Process LSPosed `AudioTrack` Direct Interceptor**: Captures Apple Music's decoded PCM (`16-bit`, `24-bit`, `32-bit` / `Float32`, `44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz`) directly inside Apple Music's process before it touches Android's mixer, streaming it over a zero-allocation slab-pooled localhost socket (`127.0.0.1:39528`).
2. **Built-in Speaker Clock Anchor + OEM Freezer Immunity**: Pins Apple Music's timing `AudioTrack` to `TYPE_BUILTIN_SPEAKER` using an inaudible `-84.3 dBFS` Nyquist (`fs/2`) ultrasonic anchor, preventing zombie `USB_HEADSET` HAL stalls as well as HyperOS / OEM background freeze & 60-second zero-sample track suspension.
3. **Custom C++17 `libusb` UAC2 Engine + `SCHED_FIFO`**: Locks your external USB DAC's hardware crystal (`ClockSource` / `ClockSelector`) to the exact track sample rate (`44.1k .. 192kHz`), elevates the USB Isochronous thread to Linux real-time `SCHED_FIFO`, and drives hardware UAC FeatureUnit gain at `0.5 dB` precision.
4. **Root AudioPolicy Loopback Fallback**: Includes an optional no-LSPosed Root `ROUTE_FLAG_LOOP_BACK` engine (capped at 48 kHz by stock `r_submix`).
5. **Bilingual UI (English & 中文)**: Auto-detects system language (`zh*` -> Chinese, all other locales -> English) with instant 1-tap in-app switching.

### Quick Setup
1. Install the APK and grant **Root** permission in Magisk / KernelSU / APatch.
2. Open **LSPosed Manager**, enable **BitPerfect Root DAC**, and check **Apple Music** (`com.apple.android.music`) in the module scope.
3. Force-stop Apple Music once, plug in your USB Type-C DAC, open **BitPerfect Root DAC**, and tap **Start Bridge**.

### Building from Source
1. Requirements: JDK 17+, Android SDK Platform 36, Android NDK `27.0.12077973`, CMake `3.22.1`.
2. *(On Windows / macOS / x86_64 Linux)*: Comment out `android.aapt2FromMavenOverride` in `gradle.properties`.
3. Build APK:
   ```bash
   ./gradlew assembleRelease
   ```

---

## 中文说明

**BitPerfect Root DAC** 是一款面向 **Android 14 – 17**（`arm64-v8a`，原生支持 `16 KB` 页对齐）的 **Root + LSPosed 真 `24-bit / 192 kHz` 源码级 Bit-Perfect** USB UAC1/UAC2 解码耳放独占直通应用，专为 **Apple Music** 高解析度无损流媒体打造。

### 核心技术亮点
- **LSPosed 进程内 `AudioTrack` 源码直截**：直接在 Apple Music 进程内拦截解码后的原始 PCM 数据（支持 `44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz` 全采样率与 `16/24/32-bit`），**100% 绕过 `AudioFlinger` 混音器与 Android `r_submix` 的 48 kHz 硬上限**。
- **扬声器时钟锚定 + HyperOS 后台防杀/防休眠**：自动将原音轨时钟锚定至手机内置扬声器并注入不可闻奈奎斯特极微防休眠信号，彻底根治高通 PAL 僵尸 USB 路由导致的 192kHz 卡顿、切后台 2 秒冻结及 60 秒静音强停问题。
- **C++17 `libusb` 硬件锁频 + `SCHED_FIFO` 实时内核线程**：两阶段纯净接管 USB DAC，精确切换 UAC2 硬件晶振频率与 `0.5 dB` 步进硬件音量，配备 `4 MB` 零锁 SPSC 抗抖动环形缓冲与 `440 Hz` 双精度正弦自检。
- **双引擎可选 + 中英双语自适应**：内置「LSPosed 源码直截（192kHz）」与「Root 环回备选（免 LSPosed，限 48kHz）」双模式；界面默认跟随系统语言（中文系统显示中文，英文及其他语言系统显示英文），支持右上角一键切换。

### 使用步骤
1. 安装 APK 并在 Magisk / KernelSU / APatch 中授予 **Root** 权限。
2. 在 **LSPosed 管理器** 中启用本模块，作用域勾选 **Apple Music**（`com.apple.android.music`），强行停止一次 Apple Music。
3. 插入 USB 小尾巴，打开本应用点击 **「启动源码直通」**，再打开 Apple Music 播放即可享受 192kHz 原生直通。

### 源码编译
1. 环境要求：JDK 17+、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1`。
2. 在 Windows / macOS / x86_64 Linux 上编译前，请注释掉 `gradle.properties` 最后一行的 `android.aapt2FromMavenOverride`。
3. 执行构建：
   ```bash
   ./gradlew assembleRelease
   ```

## License
Released under the [MIT License](LICENSE).
