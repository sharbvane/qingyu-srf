// Copyright 2026 Qingyu contributors. Licensed under Apache-2.0.
#include <jni.h>
#include <cstring>
#include <vector>
#include "aosp/include/pinyinime.h"
#include "aosp/include/splparser.h"

using namespace ime_pinyin;

namespace {
// Keep AOSP's original native workspace boundary. The Java adapter retains a
// longer raw composition and continues its suffix after a segment is selected.
constexpr size_t kMaxInput = 39;
constexpr size_t kResultBuffer = 256;

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value),
        data_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) { }
    ~UtfChars() { if (data_) env_->ReleaseStringUTFChars(value_, data_); }
    const char* get() const { return data_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* data_;
};
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_qingyu_core_NativeDecoder_predict(JNIEnv* env, jclass, jstring context) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    auto empty = [&]() { return env->NewObjectArray(0, stringClass, nullptr); };
    if (!context) return empty();
    jsize length = env->GetStringLength(context);
    if (length <= 0) return empty();
    // Upstream im_get_predicts computes fixed_ptr but passes his_buf. Pass a
    // bounded, nul-terminated BMP Chinese suffix to avoid that upstream bug.
    jchar history[kMaxPredictSize + 1] = {};
    jsize take = length < static_cast<jsize>(kMaxPredictSize) ? length : static_cast<jsize>(kMaxPredictSize);
    env->GetStringRegion(context, length - take, take, history);
    if (env->ExceptionCheck()) return nullptr;
    jsize start = take;
    while (start > 0 && history[start - 1] >= 0x3400 && history[start - 1] <= 0x9fff) --start;
    if (start == take) return empty();
    jsize size = take - start;
    for (jsize i = 0; i < size; ++i) history[i] = history[start + i];
    history[size] = 0;
    char16 (*predictions)[kMaxPredictSize + 1] = nullptr;
    size_t count = im_get_predicts(reinterpret_cast<const char16*>(history), predictions);
    if (!predictions || count == 0) return empty();
    if (count > 64) count = 64;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
    if (!result) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        jsize actual = 0;
        while (actual < static_cast<jsize>(kMaxPredictSize) && predictions[i][actual] != 0) ++actual;
        jstring word = env->NewString(reinterpret_cast<const jchar*>(predictions[i]), actual);
        if (!word) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), word);
        env->DeleteLocalRef(word);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qingyu_core_NativeDecoder_open(JNIEnv* env, jclass, jstring system, jstring user) {
    UtfChars dict(env, system), personal(env, user);
    if (!dict.get() || !personal.get()) return JNI_FALSE;
    bool opened = im_open_decoder(dict.get(), personal.get());
    if (opened) im_set_max_lens(kMaxInput, kMaxInput);
    else im_close_decoder();
    return opened ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qingyu_core_NativeDecoder_close(JNIEnv*, jclass) { im_close_decoder(); }

extern "C" JNIEXPORT void JNICALL
Java_com_qingyu_core_NativeDecoder_reset(JNIEnv*, jclass) { im_reset_search(); }

extern "C" JNIEXPORT jint JNICALL
Java_com_qingyu_core_NativeDecoder_search(JNIEnv* env, jclass, jstring pinyin) {
    UtfChars input(env, pinyin);
    if (!input.get()) return 0;
    size_t length = std::strlen(input.get());
    if (length > kMaxInput) length = kMaxInput;
    // Bound syllables before lattice expansion, rather than after it. The
    // upstream parser is cheap and shares its already loaded spelling trie.
    // This avoids exhausting native dictionary milestones on long repeated
    // vowels while keeping the full requested text in the Java adapter.
    uint16 ids[9] = {};
    uint16 starts[10] = {};
    bool lastPrefix = false;
    SpellingParser parser;
    uint16 count = parser.splstr_to_idxs(input.get(), static_cast<uint16>(length),
                                       ids, starts, 9, lastPrefix);
    if (count == 9 && starts[9] > 0 && starts[9] < length) length = starts[9];
    return static_cast<jint>(im_search(input.get(), length));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qingyu_core_NativeDecoder_choose(JNIEnv*, jclass, jint id) {
    return id < 0 ? 0 : static_cast<jint>(im_choose(static_cast<size_t>(id)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qingyu_core_NativeDecoder_cancelLastChoice(JNIEnv*, jclass) {
    return static_cast<jint>(im_cancel_last_choice());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qingyu_core_NativeDecoder_delete(JNIEnv*, jclass, jint position,
                                        jboolean syllable, jboolean clearFixed) {
    if (position < 0) return 0;
    return static_cast<jint>(im_delsearch(static_cast<size_t>(position),
                                        syllable == JNI_TRUE, clearFixed == JNI_TRUE));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qingyu_core_NativeDecoder_pinyin(JNIEnv* env, jclass) {
    size_t decoded = 0;
    const char* result = im_get_sps_str(&decoded);
    return env->NewStringUTF(result ? result : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qingyu_core_NativeDecoder_choice(JNIEnv* env, jclass, jint id) {
    char16 buffer[kResultBuffer] = {};
    const char16* result = id < 0 ? nullptr :
        im_get_candidate(static_cast<size_t>(id), buffer, kResultBuffer);
    if (!result) return env->NewStringUTF("");
    size_t length = 0;
    while (length < kResultBuffer && result[length]) ++length;
    return env->NewString(reinterpret_cast<const jchar*>(result), static_cast<jsize>(length));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qingyu_core_NativeDecoder_fixedLength(JNIEnv*, jclass) {
    return static_cast<jint>(im_get_fixed_len());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_qingyu_core_NativeDecoder_syllableStarts(JNIEnv* env, jclass) {
    const uint16* positions = nullptr;
    size_t count = im_get_spl_start_pos(positions);
    if (!positions || count >= kMaxInput + 1) return env->NewIntArray(0);
    std::vector<jint> converted(count + 1);
    for (size_t i = 0; i <= count; ++i) converted[i] = positions[i];
    jintArray result = env->NewIntArray(static_cast<jsize>(converted.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(converted.size()), converted.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qingyu_core_NativeDecoder_flush(JNIEnv*, jclass) { im_flush_cache(); }

extern "C" JNIEXPORT void JNICALL
Java_com_qingyu_core_NativeDecoder_setLearningEnabled(JNIEnv*, jclass, jboolean enabled) {
    im_set_learning_enabled(enabled == JNI_TRUE);
}
