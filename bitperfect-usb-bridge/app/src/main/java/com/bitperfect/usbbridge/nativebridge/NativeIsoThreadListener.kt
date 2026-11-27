package com.bitperfect.usbbridge.nativebridge

/**
 * Callback invoked from C++ UsbIsoStreamer::eventLoopThread() right after gettid().
 * Signature must stay exact: (I)V
 */
interface NativeIsoThreadListener {
    fun onIsoThreadStarted(tid: Int)
}
