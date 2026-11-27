# BitPerfect Android DAC Suite

> Audiophile USB Audio Class 1.0 / 2.0 (UAC1/UAC2) Exclusive DAC Suite for **Android 14 – 17** (`arm64-v8a`, **16 KB page-size aligned**), featuring a custom C++17 `libusb` isochronous engine and dual **Root + LSPosed (True 24-bit/192 kHz)** & **Zero-Root / Shizuku (Integer Zero-SRC 44.1 / 48 kHz)** editions.

[English](#english) | [中文说明](#中文说明)

---

## English

### 1. Choose Your Edition

This repository contains **two independent Android apps** sharing the same flagship C++17 `libusb` UAC1/UAC2 hardware engine and unified Obsidian & Glacier Cyan UI:

| Feature | **BitPerfect Root DAC** (`bitperfect-root-dac/`) | **BitPerfect USB Bridge** (`bitperfect-usb-bridge/`) |
| :--- | :--- | :--- |
| **Package ID** | `com.bitperfect.rootdac` | `com.bitperfect.usbbridge` |
| **Privileges Needed** | **Root + LSPosed** (fallback Root-only 48 kHz mode included) | **Zero Root** (Manual mode) or **Shizuku ADB (`uid=2000`)** |
| **Audio Capture Path** | In-process `AudioTrack` hook inside Apple Music (**pre-`AudioFlinger`**) | `MediaProjection` (`AudioPlaybackCapture`, **post-`AudioFlinger`**) |
| **Native Sample Rates** | **True 1:1 Bit-Perfect `44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz`** | **`44.1 kHz`** (`44.1/88.2/176.4k`) & **`48.0 kHz`** (`48/96/192k`) integer divisor |
| **Bit Depth** | Auto-Match (`16 / 24 / 32-bit`), `24-bit (S24_3LE)`, `32-bit (S32_LE)` | `24-bit (S24_3LE)` or `32-bit (S32_LE)` |
| **System Volume Impact** | **None** (100% immune to phone volume buttons) | **Must keep phone media volume at 100% (`0 dB`)** |
| **Kernel RT Scheduling** | Linux `SCHED_FIFO` real-time USB ISO thread | High-priority non-root audio thread + `4 MB` SPSC ring buffer |
| **Best For** | Rooted audiophiles demanding true un-downsampled `24-bit / 192 kHz` | Unrooted phones wanting clean zero-SRC USB DAC bypass without UAPP |

> **Pre-built APKs**: Download the latest signed release APKs (`~3.6 MB` each, R8 + `-O3`) from the [**GitHub Releases**](../../releases) page.

---

### 2. Requirements & Compatibility (`v1.0.1`)

- **Android Version**: Android 14 (API 34) – Android 17 (API 36+), `arm64-v8a` only.
- **16 KB Page Size Ready**: Both `libbitperfect_usb_audio.so` and `libc++_shared.so` are compiled with `-Wl,-z,max-page-size=16384` (`PT_LOAD` alignment `0x4000`) and stored uncompressed for Android 15–17 devices.
- **Hardware**: External USB Type-C UAC 1.0 / UAC 2.0 Audio DAC dongle (tested extensively on **MOONDROP Muse Dash & Quark2**, CS43131 / ESS / AKM USB dongles).
- **Current Focus (`v1.0.1`)**: Tuned primarily for **Apple Music** (`com.apple.android.music`) on **Qualcomm Snapdragon (PAL)** devices. Universal multi-player & multi-SoC (MediaTek Dimensity / Google Tensor / Samsung Exynos) support is planned for **`v1.1.0`** on branch `feat/universal-soc-and-players`.

---

### 3. How to Use

#### Edition A: BitPerfect Root DAC (Root + LSPosed · True 24-bit/192 kHz)
1. Install `BitPerfect-Root-DAC-v1.0.1-arm64-v8a-release.apk` and grant **Root** permission in Magisk / KernelSU / APatch.
2. Open **LSPosed Manager** -> enable **BitPerfect Root DAC** -> check **Apple Music** (`com.apple.android.music`) in the module scope.
3. **Force-stop Apple Music once** so LSPosed injects the hook into a fresh process.
4. Tap **Allow** on the in-app **Background Battery Exemption** banner (strongly recommended on Xiaomi HyperOS / MIUI / ColorOS).
5. Plug in your USB DAC dongle, open **BitPerfect Root DAC**, and tap **Start Bridge** (automatically claims the USB DAC and opens the localhost direct bridge).
6. Tap **Apple Music** and press play — the DAC hardware crystal locks 1:1 to `44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz`. Use the app's **DAC Hardware Volume (`0.5 dB` precision)** slider to control loudness.

#### Edition B: BitPerfect USB Bridge (Zero-Root / Shizuku)
- **Pure Non-Root Manual Mode (No ADB / No Shizuku required)**:
  1. Plug in your USB DAC and grant Android USB permission.
  2. Select **`44.1 kHz Base`** (for `44.1 / 88.2 / 176.4 kHz` music) or **`48.0 kHz Base`** (for `48 / 96 / 192 kHz` music).
  3. Tap **Start Bridge** and confirm the system screen/audio capture dialog (`MediaProjection`).
  4. **Ensure Phone System Media Volume is 100% (`0 dB`)** — if it is below 100%, tap the **Set 100%** button on the in-app alert banner. Adjust actual listening loudness via **DAC Hardware Volume** inside the app.
- **Shizuku Auto-Rate Mode (`uid=2000`, Still Zero Root)**:
  1. Start **Shizuku** via Android Wireless Debugging and tap **Grant** inside **BitPerfect USB Bridge**.
  2. Switch to **Shizuku Auto** — the app automatically detects Apple Music's live track sample rate via `dumpsys audio`, seamlessly switches DAC clocks between `44.1 kHz` and `48.0 kHz` families, and whitelists both apps against background Doze/freeze.

---

### 4. FAQ & Essential Tips

#### Why can't Zero-Root / Shizuku output native `192 kHz`? Can I use `LSPatch` (Non-Root Xposed)?
- **Stock Android `r_submix` 48 kHz ceiling**: Android's `MediaProjection` (`AudioPlaybackCapture`) routes through the system's virtual `r_submix` HAL, which is capped at `48 kHz` on stock ROMs. To prevent Android's RecordThread from performing low-quality non-integer upsampling ("muffled 192 kHz"), `BitPerfect USB Bridge` locks clean integer divisors (`88.2/176.4 -> 44.1 kHz`, `96/192 -> 48.0 kHz`).
- **Why `LSPatch` does NOT work with Apple Music**: Unlike Root LSPosed (which hooks Zygote cleanly in memory without touching APKs), Non-Root **`LSPatch`** must unpack Apple Music's APK, inject DEX/native hooks, and **re-sign it with a third-party certificate**. Apple Music enforces official APK signature verification alongside Apple Account / FairPlay / Widevine DRM for Lossless/Hi-Res ALAC streaming — re-signing breaks sign-in or DRM playback. Furthermore, `LSPatch` was archived in 2024 and does not support Android 16/17.
- **Conclusion**: Use **BitPerfect Root DAC** (Root + LSPosed) for true `24-bit/192 kHz`, or **BitPerfect USB Bridge** for clean zero-repackaging unrooted playback.

#### Why is there no sound in Non-Root USB Bridge?
Check these two common causes:
1. **Phone System Media Volume is `0`**: `MediaProjection` captures audio *after* `AudioFlinger` volume attenuation. If system volume drops to `0/150`, the captured stream is multiplied by `0.0` (pure silence). Always keep system volume at **100%** (tap **Set 100%** in the app) and control volume with the **DAC Hardware Volume** slider.
2. **Both apps active on the same phone**: If you also installed **BitPerfect Root DAC** and left its LSPosed module enabled for Apple Music, its hook hijacks Apple Music's `AudioTrack` and sends `-84.3 dBFS` ultrasonic anchor silence into `AudioFlinger`, leaving `BitPerfect USB Bridge` capturing silence! Disable the LSPosed toggle (and restart Apple Music) before testing `BitPerfect USB Bridge`.

#### Recommended Apple Music Audio Settings
- **Audio Quality -> Lossless Audio**: ON (`Lossless` / `Hi-Res Lossless ALAC up to 24-bit/192 kHz`).
- **Dolby Atmos**: **OFF** (Atmos outputs multichannel spatial audio capped at 48 kHz; turning it OFF unlocks pure stereo 24-bit/192 kHz ALAC).
- **Sound Check (Volume Normalization)**: **OFF** for true bit-exact output.

#### Language & Hardware Self-Test
- **Bilingual UI (EN / 中文)**: Automatically follows your phone's system language (Chinese on `zh*` locales; English on English and all other locales). Tap **`EN` / `中文`** in the top-right bar anytime to toggle live, or choose `System / 中文 / English` in the **Device & Lang** tab.
- **440 Hz Crystal Self-Test**: Use the **440Hz Test** tab (`-24 dBFS` ear-safe double-precision C++ sine oscillator) to verify direct USB DAC PLL lock at `44.1 / 48 / 96 / 192 kHz` completely independent of any player.

---

### 5. Technical Architecture
- **Two-Stage Pure USB Takeover (`C++17 + libusb 1.0.27`)**: Detaches & claims `IF#2` (Playback AudioStreaming) first -> runs a 4 ms zero-frame ISO handshake -> detaches & claims `IF#0` (AudioControl), while **skipping `IF#1` (Microphone)** to avoid disturbing ADC clocks.
- **Zero-Lock `4 MB` SPSC Ring Buffer & 12-URB Async/Adaptive ISO OUT**: Single-pass `Float32 -> S24_3LE / S32_LE` conversion with `-O3` vectorization and zero audio-thread mutexes.
- **OEM Freezer & 60 s Silence-Kill Immunity (Root DAC)**: Pins Apple Music's timing `AudioTrack` to `TYPE_BUILTIN_SPEAKER` with an inaudible `-84.3 dBFS` Nyquist (`fs/2 >= 22.05 kHz`) anchor and `<95 ms` Binder self-wake, preventing HyperOS background freezes and 60-second `AT::remove` silence suspension.
- **Qualcomm PAL Pre-Warm & `zeroDrops` Auto-Heal (USB Bridge)**: Pre-warms `PAL_STREAM_DEEP_BUFFER` before detaching `snd-usb-audio` and monitors `r_submix` dropout bursts for automatic glitch-free resync.

---

### 6. Build from Source
1. **Prerequisites**: JDK 17+, Android SDK Platform 36, Android NDK `27.0.12077973`, CMake `3.22.1`. Official `libusb v1.0.27` is fetched automatically by CMake `FetchContent`.
2. **Build either app**:
   ```bash
   cd bitperfect-root-dac   # or: cd bitperfect-usb-bridge
   ./gradlew assembleRelease
   ```
   *(Optional)* Create a git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project directory for custom release signing; without it, Gradle automatically falls back to debug signing.
   *(ARM64 Linux build hosts only)*: Pass `-Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2`.

---

### 7. Roadmap (`v1.1.0` — Branch `feat/universal-soc-and-players`)
- **Universal Multi-Player Support**: Global `USAGE_MEDIA` auto-tracking (excluding self UID) + per-app picker for Apple Music, Spotify, Tidal, Qobuz, NetEase Cloud Music, QQ Music, Foobar2000, HibyMusic, etc., with live active-app display.
- **Multi-SoC Hardware Compatibility**: Automatic SoC detection (`Qualcomm Snapdragon`, `MediaTek Dimensity`, `Google Tensor`, `Samsung Exynos`, `Generic ARM`) with platform-tailored USB interface handoff, zero `libusb_reset_device()` teardown, and dynamic `r_submix` ceiling probing.

---

## 中文说明

### 1. 双版本选型对照

本仓库包含两款独立 Android 14 – 17（`arm64-v8a`，**16 KB 页对齐**）发烧级 USB DAC 独占直通应用，共享自研 C++17 `libusb` 异步等时引擎与深海曜石黑 + 冰川青双语 UI：

| 特性对比 | **BitPerfect Root DAC** (`bitperfect-root-dac/`) | **BitPerfect USB Bridge** (`bitperfect-usb-bridge/`) |
| :--- | :--- | :--- |
| **应用包名** | `com.bitperfect.rootdac` | `com.bitperfect.usbbridge` |
| **权限要求** | **Root + LSPosed**（内置免 LSPosed 的 Root 48k 备选引擎） | **完全免 Root**（纯手动）或 **Shizuku 无线调试 (`uid=2000`)** |
| **音频截取层级** | Apple Music 进程内 `AudioTrack` 源码直截（**绕过 `AudioFlinger`**） | `MediaProjection` 内部录屏捕获（位于 `AudioFlinger` 之后） |
| **支持采样率** | **真 1:1 源码直通 `44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz`** | **`44.1 kHz`**（`44.1/88.2/176.4k`）与 **`48.0 kHz`**（`48/96/192k`）整数分频 |
| **USB 传输位深** | 自动匹配原生（`16 / 24 / 32-bit`）、`24-bit`、`32-bit` | `24-bit (S24_3LE)` 或 `32-bit (S32_LE)` |
| **受手机音量影响** | **不受影响**（彻底免疫系统音量缩放） | **必须保持手机媒体音量满格 (`100% / 0 dB`)** |
| **内核实时调度** | Root `SCHED_FIFO` 实时内核线程驱动 USB 等时传输 | 高优先级用户态音频线程 + `4 MB` 零锁 SPSC 环形缓冲 |
| **适用人群** | 已 Root + LSPosed、追求 **真 `24-bit/192 kHz`** 无损直通的玩家 | 未 Root 手机、希望绕过系统 48k 强制重采样的用户 |

> **直接下载正式安装包**：前往 [**GitHub Releases**](../../releases) 下载已编译签名的正式精简包（开启 R8 与 C++ `-O3` 优化，单包仅约 `3.6 MB`）。

---

### 2. 环境与硬件要求（`v1.0.1`）

- **系统要求**：Android 14 – Android 17（`arm64-v8a`），原生通过 Android 15–17 **16 KB 内存页对齐**（`0x4000`）。
- **外接硬件**：支持标准 USB Audio Class 1.0 / 2.0（UAC1/UAC2）的 Type-C 解码耳放小尾巴（已在水月雨 `MOONDROP Muse Dash & Quark2` 等 UAC2 小尾巴上深度实测验证）。
- **当前定位（`v1.0.1`）**：核心针对 **Apple Music** 与 **高通 Snapdragon（PAL 音频框架）** 深度调优；**全手机芯片平台（天玑 / Tensor / Exynos）与全音乐软件通用适配**将在 `v1.1.0`（`feat/universal-soc-and-players` 分支）推出。

---

### 3. 快速使用指南

#### 方案 A：BitPerfect Root DAC（Root + LSPosed · 真 24-bit / 192 kHz 源码直通）
1. 安装 `BitPerfect-Root-DAC-v1.0.1-arm64-v8a-release.apk`，并在 Magisk / KernelSU / APatch 中授予 **Root** 权限。
2. 打开 **LSPosed 管理器** -> 启用 **BitPerfect Root DAC** 模块 -> 作用域勾选 **Apple Music**（`com.apple.android.music`）。
3. **强行停止一次 Apple Music**，确保 LSPosed 钩子干净注入新进程。
4. 点击本应用顶部的 **后台无限制省电豁免** 按钮（小米 HyperOS / ColorOS 等系统必开，防止切后台或锁屏被杀/冻结）。
5. 插入 USB 小尾巴 -> 打开 **BitPerfect Root DAC** -> 直接点击 **「启动源码直通」**（自动完成 USB 独占接管 + 启动本地直截端口）。
6. 打开 **Apple Music** 播放音乐，界面三格矩阵即可显示 `44.1 ~ 192.0 kHz` 真 1:1 硬件锁频！使用应用内的 **DAC 硬件音量滑块（`0.5 dB` 步进）** 调节响度。

#### 方案 B：BitPerfect USB Bridge（免 Root / Shizuku 双模式）
- **纯无 Root 手动模式（零门槛，无需电脑/Shizuku）**：
  1. 插入 USB 小尾巴并允许系统的 USB 访问权限弹窗。
  2. 根据所听曲目选择 **`44.1 kHz 基准`**（`44.1/88.2/176.4k`）或 **`48.0 kHz 基准`**（`48/96/192k`）。
  3. 点击 **「启动直通」** 并允许系统的屏幕/音频捕获授权（`MediaProjection`）。
  4. **务必保持手机系统媒体音量为满格（`100% / 0 dB`）**（若未满格，主界面会弹出红色/琥珀色提示条并支持「一键满格」），实际听歌音量统一使用下方的 **DAC 硬件音量** 调节。
- **Shizuku 自动切频模式（免 Root · 推荐）**：
  1. 通过手机无线调试启动 **Shizuku**，在本应用中点击 **「授权」** 并切换到 **「Shizuku 自动」**。
  2. 启动直通后，应用将自动通过 `dumpsys audio` 嗅探 Apple Music 当前播放曲目的真实采样率，在 `44.1 kHz` 与 `48.0 kHz` 晶振家族间无感切换，并自动为本应用与 Apple Music 下发后台防休眠白名单。

---

### 4. 常见问题与避坑说明（必读）

#### 1. 为什么无 Root / Shizuku 版不能直通 `192 kHz`？能用免 Root 的 `LSPatch` 吗？
- **Android 原生 `r_submix` 48 kHz 上限**：无 Root 的 `MediaProjection` 内录必须经过系统虚拟声卡 `r_submix`，原厂 Android 14–17 的 `r_submix` 硬件抽象层上限锁死在 `48 kHz`。强行在 `AudioRecord` 申请 `192 kHz` 会触发系统低质 4 倍插值导致声音发闷，因此 `BitPerfect USB Bridge` 采用干净的整数分频策略（`88.2/176.4k -> 44.1 kHz`，`96/192k -> 48.0 kHz`）。
- **为什么不能用 `LSPatch`（Shizuku 免 Root Xposed）来破解 192 kHz**：
  - Root 版 LSPosed 是在系统内存（Zygote）动态挂载，**完全不修改 Apple Music 安装包本身**；
  - 而免 Root 的 **`LSPatch`** 必须把 Apple Music 的 APK **解包、植入 Hook 代码，并用第三方密钥重新签名安装**。Apple Music 的无损 / 192 kHz ALAC 流媒体带有严格的 **官方 APK 签名校验与 FairPlay / Widevine DRM 鉴权**，重签名后会直接导致账号无法登录或无损曲目无法解码播放；并且 `LSPatch` 已于 2024 年归档停更，不兼容 Android 16/17。
  - 因此：**追求真 192 kHz 源码直通请使用 Root + LSPosed 版的 `BitPerfect Root DAC`，未 Root 手机请使用零侵入的 `BitPerfect USB Bridge`**。

#### 2. 为什么无 Root 版偶尔听着听着突然彻底没声？
通常由以下两个原因之一引起（应用内已内置实时检测提醒）：
1. **手机系统媒体音量变成了 `0`**：无 Root 内录受 `AudioFlinger` 音量缩放控制，系统音量为 `0/150` 时所有音频乘以 `0.0`，切歌和拉进度条都会是静音。请直接点击界面上的 **「一键满格」** 恢复 100% 满幅位深，只用下方 DAC 硬件音量调声音。
2. **同一台手机同时开启了 Root 版的 LSPosed 模块**：若你在同机测试无 Root 版，但 LSPosed 里仍勾选着 Root 版对 Apple Music 的作用域，Root 版的钩子会截走 Apple Music 的音频并往系统混音器写入 `-84.3 dBFS` 静音特征流，导致无 Root 版只抓到静音（界面会自动识别 `-84.3 dBFS` 并弹出红色冲突提示）。测试无 Root 版前请先在 LSPosed 中取消勾选并重启一次 Apple Music。

#### 3. Apple Music 推荐设置
- **音频质量 -> 无损音频**：开启（高解析度无损 `ALAC 最高 24-bit/192 kHz`）。
- **杜比全景声（Dolby Atmos）**：**建议关闭**（全景声会输出多声道空间音频并强制进入 48 kHz 处理管线，关闭后才能解锁纯正双声道 24-bit/192 kHz 源码）。
- **音量平衡（Sound Check）**：**建议关闭**，避免播放前数字增益改动原始采样位深。

#### 4. 双语切换、版本升级与晶振自检
- **中英双语**：默认跟随手机系统语言（中文系统自动显示中文，英文及其他语言系统一律显示英文）；点击右上角 **`EN` / `中文`** 胶囊可实时秒切，底部「设备与设置」标签页也支持 `跟随系统 / 中文 / English` 三档调整。
- **签名与覆盖升级**：GitHub Releases 发布的正式包使用统一正式签名密钥；若手机此前安装过测试版（Debug 签名），首次换装正式版需先卸载旧测试版一次（Root 版卸载重装后记得在 LSPosed 重新勾选一次作用域）。
- **440 Hz 硬件晶振自检**：底部「晶振自检」标签页内置 C++ 双精度 `-24 dBFS` 护耳正弦波，可完全脱离播放器独立验证 `44.1 / 48 / 96 / 192 kHz` 硬件锁频与 USB 链路稳定性。

---

### 5. 开源协议
本项目基于 [MIT License](LICENSE) 开源；底层 USB 通信库在编译时由 CMake 自动拉取官方 [`libusb v1.0.27`](https://github.com/libusb/libusb)（LGPL-2.1）。
