#include "native_log.h"
#include "usb_dac_engine.h"

#include <jni.h>
#include <memory>
#include <mutex>
#include <string>

namespace {

JavaVM* gJavaVm = nullptr;
std::mutex gCallbackMutex;
jobject gLogListenerGlobalRef = nullptr;
jmethodID gOnNativeLogMethod = nullptr;
jobject gIsoThreadListenerGlobalRef = nullptr;
jmethodID gOnIsoThreadStartedMethod = nullptr;

std::mutex gEngineMutex;
std::string gLastReport;
std::string gLastProfileJson;

bitperfect::UsbDacEngine& globalEngine() {
    static bitperfect::UsbDacEngine instance;
    return instance;
}

std::string escapeJsonString(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else out += c;
    }
    return out;
}

void dispatchLogToKotlin(bitperfect::LogLevel level, const std::string& msg) {
    if (!gJavaVm) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnvRc = gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvRc == JNI_EDETACHED) {
        if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
            return;
        }
        attached = true;
    } else if (getEnvRc != JNI_OK || !env) {
        return;
    }

    jobject localListener = nullptr;
    jmethodID methodId = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCallbackMutex);
        if (gLogListenerGlobalRef && gOnNativeLogMethod) {
            localListener = env->NewLocalRef(gLogListenerGlobalRef);
            methodId = gOnNativeLogMethod;
        }
    }

    if (localListener && methodId) {
        jstring jMsg = env->NewStringUTF(msg.c_str());
        if (jMsg) {
            env->CallVoidMethod(localListener, methodId, static_cast<jint>(level), jMsg);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jMsg);
        }
        env->DeleteLocalRef(localListener);
    }

    if (attached) {
        gJavaVm->DetachCurrentThread();
    }
}

void dispatchIsoThreadStartedToKotlin(int tid) {
    if (!gJavaVm || tid <= 0) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnvRc = gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvRc == JNI_EDETACHED) {
        if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
            return;
        }
        attached = true;
    } else if (getEnvRc != JNI_OK || !env) {
        return;
    }

    jobject localListener = nullptr;
    jmethodID methodId = nullptr;
    {
        std::lock_guard<std::mutex> lock(gCallbackMutex);
        if (gIsoThreadListenerGlobalRef && gOnIsoThreadStartedMethod) {
            localListener = env->NewLocalRef(gIsoThreadListenerGlobalRef);
            methodId = gOnIsoThreadStartedMethod;
        }
    }

    if (localListener && methodId) {
        env->CallVoidMethod(localListener, methodId, static_cast<jint>(tid));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(localListener);
    }

    if (attached) {
        gJavaVm->DetachCurrentThread();
    }
}

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJavaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeSetLogListener(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject listener
) {
    std::lock_guard<std::mutex> lock(gCallbackMutex);
    if (gLogListenerGlobalRef) {
        env->DeleteGlobalRef(gLogListenerGlobalRef);
        gLogListenerGlobalRef = nullptr;
        gOnNativeLogMethod = nullptr;
    }

    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        jmethodID mid = env->GetMethodID(cls, "onNativeLog", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (mid) {
            gLogListenerGlobalRef = env->NewGlobalRef(listener);
            gOnNativeLogMethod = mid;
            bitperfect::NativeLogger::instance().setCallback(dispatchLogToKotlin);
        }
    } else {
        bitperfect::NativeLogger::instance().setCallback(nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeSetIsoThreadListener(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject listener
) {
    std::lock_guard<std::mutex> lock(gCallbackMutex);
    if (gIsoThreadListenerGlobalRef) {
        env->DeleteGlobalRef(gIsoThreadListenerGlobalRef);
        gIsoThreadListenerGlobalRef = nullptr;
        gOnIsoThreadStartedMethod = nullptr;
    }

    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        jmethodID mid = env->GetMethodID(cls, "onIsoThreadStarted", "(I)V");
        env->DeleteLocalRef(cls);
        if (mid) {
            gIsoThreadListenerGlobalRef = env->NewGlobalRef(listener);
            gOnIsoThreadStartedMethod = mid;
            globalEngine().setIsoThreadStartedCallback(dispatchIsoThreadStartedToKotlin);
        }
    } else {
        globalEngine().setIsoThreadStartedCallback(nullptr);
    }
}

JNIEXPORT jint JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeGetIsoThreadTid(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return static_cast<jint>(globalEngine().getIsoThreadTid());
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeOpenAndInspect(
    JNIEnv* env,
    jobject /*thiz*/,
    jint fd,
    jint vendorId,
    jint productId,
    jstring fallbackName,
    jdouble initialVolumeDb
) {
    std::string nameStr;
    if (fallbackName) {
        const char* chars = env->GetStringUTFChars(fallbackName, nullptr);
        if (chars) {
            nameStr = chars;
            env->ReleaseStringUTFChars(fallbackName, chars);
        }
    }

    std::lock_guard<std::mutex> lock(gEngineMutex);
    auto& engine = globalEngine();

    std::string report;
    std::string profileJson;
    std::string error;

    bool ok = engine.openAndInspect(
        static_cast<int>(fd),
        static_cast<uint16_t>(vendorId),
        static_cast<uint16_t>(productId),
        nameStr,
        static_cast<double>(initialVolumeDb),
        report,
        profileJson,
        error
    );

    if (!ok) {
        gLastReport.clear();
        gLastProfileJson.clear();
        std::string errJson = "{\"ok\":false,\"error\":\"" + escapeJsonString(error) + "\"}";
        return env->NewStringUTF(errJson.c_str());
    }

    gLastReport = report;
    gLastProfileJson = profileJson;

    std::string okJson = "{\"ok\":true,\"profile\":" + profileJson + "}";
    return env->NewStringUTF(okJson.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeGetLastReport(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return env->NewStringUTF(gLastReport.c_str());
}

JNIEXPORT void JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeClose(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean restoreUnityVolume,
    jboolean resetUsbPort
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    globalEngine().close(restoreUnityVolume == JNI_TRUE, resetUsbPort == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeStartSineTest(
    JNIEnv* env,
    jobject /*thiz*/,
    jint sampleRate,
    jint preferredBitDepth
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    auto& engine = globalEngine();
    std::string error;
    bool ok = engine.startSineTest(
        static_cast<uint32_t>(sampleRate),
        static_cast<int>(preferredBitDepth),
        error
    );
    if (!ok) {
        std::string errJson = "{\"ok\":false,\"error\":\"" + escapeJsonString(error) + "\"}";
        return env->NewStringUTF(errJson.c_str());
    }
    return env->NewStringUTF("{\"ok\":true}");
}

JNIEXPORT void JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeStopStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    globalEngine().stopStream();
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeSetHardwareVolumeDb(
    JNIEnv* env,
    jobject /*thiz*/,
    jdouble db
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    auto& engine = globalEngine();
    std::string error;
    bool ok = engine.setHardwareVolumeDb(static_cast<double>(db), error);
    if (!ok) {
        std::string errJson = "{\"ok\":false,\"error\":\"" + escapeJsonString(error) + "\"}";
        return env->NewStringUTF(errJson.c_str());
    }
    return env->NewStringUTF("{\"ok\":true}");
}

JNIEXPORT void JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeSetBufferDurationMs(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint bufferMs
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    auto& engine = globalEngine();
    engine.setBufferDurationMs(static_cast<int>(bufferMs));
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativePrepareCaptureStream(
    JNIEnv* env,
    jobject /*thiz*/,
    jint sampleRate,
    jint preferredBitDepth
) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    bitperfect::CaptureHardwareConfig cfg{};
    std::string error;
    bool ok = globalEngine().prepareCaptureStream(
        static_cast<uint32_t>(sampleRate),
        static_cast<int>(preferredBitDepth),
        cfg,
        error
    );
    if (!ok) {
        std::string errJson = "{\"ok\":false,\"error\":\"" + escapeJsonString(error) + "\"}";
        return env->NewStringUTF(errJson.c_str());
    }
    std::string okJson =
        std::string("{\"ok\":true,\"dacSampleRate\":") + std::to_string(cfg.sampleRate) +
        ",\"dacBitResolution\":" + std::to_string(cfg.bitResolution) +
        ",\"altSetting\":" + std::to_string(cfg.altSetting) +
        ",\"channels\":" + std::to_string(cfg.channels) +
        ",\"dacFormat\":\"" + bitperfect::uac::pcmFormatToString(cfg.pcmFormat) + "\"}";
    return env->NewStringUTF(okJson.c_str());
}

JNIEXPORT jint JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativePushCapturedPcmFrames(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject directBuffer,
    jint frameCount,
    jint captureFormatCode
) {
    if (!directBuffer || frameCount <= 0) return 0;
    auto* ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong cap = env->GetDirectBufferCapacity(directBuffer);
    const auto fmt = static_cast<bitperfect::CapturePcmFormat>(captureFormatCode);
    const size_t srcFrameBytes = bitperfect::captureBytesPerSample(fmt) * 2u; // Stereo (2ch)
    if (!ptr || srcFrameBytes == 0) return 0;

    const size_t requiredBytes = static_cast<size_t>(frameCount) * srcFrameBytes;
    if (cap < static_cast<jlong>(requiredBytes)) {
        BP_LOGE("nativePushCapturedPcmFrames: DirectBuffer too small (cap=%lld < need=%zu for %d frames)",
                static_cast<long long>(cap), requiredBytes, static_cast<int>(frameCount));
        return 0;
    }

    size_t written = globalEngine().pushCapturedPcmFrames(
        ptr,
        static_cast<size_t>(frameCount),
        fmt
    );
    return static_cast<jint>(written);
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_usbbridge_nativebridge_NativeUsbAudioBridge_nativeGetTelemetryJson(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string json = globalEngine().getTelemetryJson();
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
