package com.bitperfect.usbbridge

import android.app.Application
import com.bitperfect.usbbridge.usb.UsbDacManager

class BitPerfectApp : Application() {

    /**
     * Application-scoped singleton so switching to Apple Music, pressing Back, rotating screen,
     * or HyperOS reclaiming the background Activity NEVER disconnects the USB DAC or kills playback!
     * Pure Zero-Root architecture (supports Pure Non-Root Manual + Shizuku ADB Auto modes).
     */
    val usbDacManager: UsbDacManager by lazy {
        UsbDacManager(this).apply { start() }
    }
}
