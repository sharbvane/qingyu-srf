#pragma once
// Compatibility boundary: AOSP's private libcutils is replaced by the public NDK log API.
// User input is never logged. These macros exist only for upstream's disabled perf tracing.
#ifdef __ANDROID__
#include <android/log.h>
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "QingyuDecoder", __VA_ARGS__)
#else
#define ALOGD(...) ((void)0)
#endif
