#pragma once

#include <cstdarg>
#include <cstdio>
#include <functional>
#include <mutex>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace bitperfect {

enum class LogLevel : int {
    Debug = 0,
    Info  = 1,
    Warn  = 2,
    Error = 3
};

using LogCallback = std::function<void(LogLevel, const std::string&)>;

class NativeLogger {
public:
    static NativeLogger& instance() {
        static NativeLogger logger;
        return logger;
    }

    void setCallback(LogCallback cb) {
        std::lock_guard<std::mutex> lock(mutex_);
        callback_ = std::move(cb);
    }

    void log(LogLevel level, const char* tag, const char* fmt, ...) {
        char buf[1024];
        va_list args;
        va_start(args, fmt);
        vsnprintf(buf, sizeof(buf), fmt, args);
        va_end(args);

#ifdef __ANDROID__
        int prio = ANDROID_LOG_INFO;
        switch (level) {
            case LogLevel::Debug: prio = ANDROID_LOG_DEBUG; break;
            case LogLevel::Info:  prio = ANDROID_LOG_INFO;  break;
            case LogLevel::Warn:  prio = ANDROID_LOG_WARN;  break;
            case LogLevel::Error: prio = ANDROID_LOG_ERROR; break;
        }
        __android_log_print(prio, tag, "%s", buf);
#else
        const char* lvlStr = "I";
        switch (level) {
            case LogLevel::Debug: lvlStr = "D"; break;
            case LogLevel::Info:  lvlStr = "I"; break;
            case LogLevel::Warn:  lvlStr = "W"; break;
            case LogLevel::Error: lvlStr = "E"; break;
        }
        fprintf(level == LogLevel::Error ? stderr : stdout, "[%s/%s] %s\n", lvlStr, tag, buf);
#endif

        LogCallback cbCopy;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cbCopy = callback_;
        }
        if (cbCopy) {
            cbCopy(level, std::string(buf));
        }
    }

private:
    NativeLogger() = default;
    std::mutex mutex_;
    LogCallback callback_;
};

#define BP_LOG_TAG "BitPerfectUSB"
#define BP_LOGD(fmt, ...) ::bitperfect::NativeLogger::instance().log(::bitperfect::LogLevel::Debug, BP_LOG_TAG, fmt, ##__VA_ARGS__)
#define BP_LOGI(fmt, ...) ::bitperfect::NativeLogger::instance().log(::bitperfect::LogLevel::Info,  BP_LOG_TAG, fmt, ##__VA_ARGS__)
#define BP_LOGW(fmt, ...) ::bitperfect::NativeLogger::instance().log(::bitperfect::LogLevel::Warn,  BP_LOG_TAG, fmt, ##__VA_ARGS__)
#define BP_LOGE(fmt, ...) ::bitperfect::NativeLogger::instance().log(::bitperfect::LogLevel::Error, BP_LOG_TAG, fmt, ##__VA_ARGS__)

} // namespace bitperfect
