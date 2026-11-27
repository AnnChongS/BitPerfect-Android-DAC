package com.bitperfect.rootdac.nativebridge

/**
 * Callback invoked from C++ JNI layer for real-time native logging.
 * Signature must stay exact: (ILjava/lang/String;)V
 */
interface NativeLogListener {
    fun onNativeLog(level: Int, message: String)
}
