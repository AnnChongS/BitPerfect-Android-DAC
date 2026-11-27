package com.bitperfect.usbbridge.shizuku;

interface IShizukuAudioService {
    void destroy();
    int getUid();
    String runShellCommand(String command);
    String dumpAudioPlayback(int targetUid);
    String dumpAudioPolicySubmix();
    String applyBackgroundKeepAlive(String packageName);
    String dumpDiagnosticsSnapshot(int targetUid, String packageName);
}
