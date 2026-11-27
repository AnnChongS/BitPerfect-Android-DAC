package com.bitperfect.usbbridge.shizuku;

import android.content.Context;
import android.os.Process;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService executing inside uid=2000 (com.android.shell, ADB authorization — ZERO Root).
 *
 * Capabilities under uid=2000:
 *  - dumpsys audio: auto-sniff Apple Music real sample rate (FormatInfo{..., sampleRate=...})
 *  - dumpsys media.audio_policy: probe r_submix HAL supported rates
 *  - deviceidle / appops / standby-bucket: keep both BitPerfect USB Bridge & Apple Music unthrottled
 *  - Zero-root PAL / AHAL_StreamOut_QTI / AudioFlinger diagnostic snapshot
 */
@Keep
public class ShizukuAudioUserService extends IShizukuAudioService.Stub {

    private static final String APPLE_MUSIC_PKG = "com.apple.android.music";

    @Keep
    public ShizukuAudioUserService() {
    }

    @Keep
    public ShizukuAudioUserService(Context context) {
        // Context constructor supported by Shizuku API v13
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }

    @Override
    public String runShellCommand(String command) {
        return execShell(command, 8000L, 262144);
    }

    @Override
    public String dumpAudioPlayback(int targetUid) {
        String uidNeedle = "u/pid:" + targetUid + "/";
        String raw = execShell("dumpsys audio", 5000L, 524288);
        if (raw.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(2048);
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.contains(uidNeedle) ||
                (line.contains("AudioPlaybackConfiguration") && line.contains("state:started"))) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public String dumpAudioPolicySubmix() {
        String raw = execShell("dumpsys media.audio_policy", 6000L, 524288);
        if (raw.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(4096);
        String[] lines = raw.split("\n");
        boolean capturing = false;
        int capturedLines = 0;
        for (String line : lines) {
            if (!capturing && line.contains("\"r_submix\"")) {
                capturing = true;
            }
            if (capturing) {
                sb.append(line).append('\n');
                capturedLines++;
                if (capturedLines > 120) break;
                if (capturedLines > 12 && line.trim().matches("^\\d+\\.\\s+Handle:.*") && !line.contains("r_submix")) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String applyBackgroundKeepAlive(String packageName) {
        if (packageName == null || !packageName.matches("^[a-zA-Z0-9._]+$")) {
            return "invalid packageName";
        }
        String selfRes = applyKeepAliveForPkg(packageName);
        String amRes = applyKeepAliveForPkg(APPLE_MUSIC_PKG);
        return "uid=" + Process.myUid() + " | bridge=[" + selfRes + "] | appleMusic=[" + amRes + "]";
    }

    private static String applyKeepAliveForPkg(String pkg) {
        String r1 = execShell("cmd deviceidle whitelist +" + pkg, 3500L, 2048).trim();
        String r2 = execShell("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow", 3500L, 2048).trim();
        String r3 = execShell("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", 3500L, 2048).trim();
        String r4 = execShell("am set-standby-bucket " + pkg + " active", 3500L, 2048).trim();
        return "idle=" + (r1.isEmpty() ? "OK" : r1) +
               ",bg=" + (r2.isEmpty() ? "OK" : r2) +
               ",anyBg=" + (r3.isEmpty() ? "OK" : r3) +
               ",bucket=" + (r4.isEmpty() ? "active" : r4);
    }

    @Override
    public String dumpDiagnosticsSnapshot(int targetUid, String packageName) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("========== Shizuku (uid=").append(Process.myUid()).append(") 音频路由与 PAL 诊断快照 ==========\n");

        String routingPref = execShell("settings get secure usb_audio_automatic_routing_disabled", 3000L, 1024).trim();
        sb.append("usb_audio_automatic_routing_disabled = ").append(routingPref).append('\n');

        sb.append("--- dumpsys audio (Apple Music UID=").append(targetUid).append(" & USB devices) ---\n");
        String audioDump = execShell("dumpsys audio", 5000L, 524288);
        String uidNeedle = "u/pid:" + targetUid + "/";
        int audioLines = 0;
        for (String line : audioDump.split("\n")) {
            if (line.contains(uidNeedle) ||
                line.contains("USB_HEADSET") ||
                line.contains("USB_DEVICE") ||
                line.contains("usb_headset") ||
                line.contains("usb_device") ||
                line.contains("card=") ||
                line.contains("mConnectedDevices") ||
                (line.contains("AudioPlaybackConfiguration") && line.contains("state:started"))) {
                sb.append("  ").append(line.trim()).append('\n');
                if (++audioLines >= 45) break;
            }
        }

        sb.append("--- dumpsys media.audio_flinger (threads / Duplicating / r_submix / underrun) ---\n");
        String flingerDump = execShell("dumpsys media.audio_flinger", 5000L, 524288);
        int flingerLines = 0;
        for (String line : flingerDump.split("\n")) {
            if (line.contains("Output thread") ||
                line.contains("Input thread") ||
                line.contains("Duplicating") ||
                line.contains("Standby:") ||
                line.contains("Sample rate:") ||
                line.contains("HAL frame count") ||
                line.contains("underrun") ||
                line.contains("overrun") ||
                line.contains("r_submix") ||
                line.contains("IoHandle") ||
                line.contains(String.valueOf(targetUid))) {
                sb.append("  ").append(line.trim()).append('\n');
                if (++flingerLines >= 60) break;
            }
        }

        sb.append("--- dumpsys media.audio_policy (r_submix) ---\n");
        String submix = dumpAudioPolicySubmix();
        int submixLines = 0;
        for (String line : submix.split("\n")) {
            if (line.trim().isEmpty()) continue;
            sb.append("  ").append(line).append('\n');
            if (++submixLines >= 35) break;
        }

        sb.append("--- recent logcat (PAL / AHAL_StreamOut_QTI / AudioFlinger / r_submix) ---\n");
        String logcat = execShell(
                "logcat -d -t 200 | grep -E 'PAL:|AHAL_StreamOut|pal_stream|r_submix|RemoteSubmix|Duplicating|AudioFlinger|underrun|overrun' | tail -n 45",
                5000L,
                131072
        );
        if (logcat.trim().isEmpty()) {
            sb.append("  (最近 200 行 logcat 无 PAL/AHAL/r_submix 报错)\n");
        } else {
            for (String line : logcat.split("\n")) {
                if (!line.trim().isEmpty()) {
                    sb.append("  ").append(line).append('\n');
                }
            }
        }
        sb.append("=================================================================");
        return sb.toString();
    }

    private static String execShell(String cmd, long timeoutMs, int maxChars) {
        java.lang.Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            StringBuilder sb = new StringBuilder(Math.min(maxChars, 8192));
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) {
                    int remaining = maxChars - sb.length();
                    if (remaining <= 0) break;
                    sb.append(buf, 0, Math.min(n, remaining));
                }
            }
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            return sb.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        } finally {
            if (proc != null) {
                try {
                    proc.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
