package com.bitperfect.rootdac

import android.app.Application
import com.bitperfect.rootdac.usb.RootDacManager
import com.topjohnwu.superuser.Shell

class BitPerfectRootApp : Application() {

    /**
     * Application-scoped singleton so switching to Apple Music, pressing Back, rotating screen,
     * or HyperOS reclaiming the background Activity NEVER disconnects the USB DAC or interrupts music.
     */
    val rootDacManager: RootDacManager by lazy {
        RootDacManager(this).apply { start() }
    }

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }
}
