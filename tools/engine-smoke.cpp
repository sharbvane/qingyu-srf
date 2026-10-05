// Real native decoder checks. Runs on an Android device/emulator through adb shell.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <sys/stat.h>
#include "pinyinime.h"
using namespace ime_pinyin;

static void check(bool condition, const char* label) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", label); std::exit(1); }
}
static std::u16string choice(int id) {
    char16 text[256] = {};
    if (!im_get_candidate(id, text, 256)) return u"";
    return reinterpret_cast<const char16_t*>(text);
}
static int findCandidate(const std::u16string& expected, size_t count) {
    for (size_t i = 0; i < count; ++i) if (choice(i) == expected) return static_cast<int>(i);
    return -1;
}
static unsigned long long hashFile(const char* path) {
    FILE* file = std::fopen(path, "rb");
    check(file != nullptr, "user dictionary exists");
    unsigned long long hash = 1469598103934665603ULL;
    int ch;
    while ((ch = std::fgetc(file)) != EOF) { hash ^= static_cast<unsigned char>(ch); hash *= 1099511628211ULL; }
    std::fclose(file);
    return hash;
}

int main(int argc, char** argv) {
    check(argc == 3, "usage: engine-smoke dict_pinyin.dat user.dat");
    check(im_open_decoder(argv[1], argv[2]), "open AOSP system dictionary");
    im_set_max_lens(39, 39);
    const char* words[] = {"nihao", "kaifa", "xiangmu", "sheji", "zhongwen", "shurufa", "zaijian"};
    const std::u16string expected[] = {u"你好", u"开发", u"项目", u"设计", u"中文", u"输入法", u"再见"};
    for (size_t i = 0; i < 7; ++i) {
        im_reset_search();
        size_t count = im_search(words[i], std::strlen(words[i]));
        check(count > 0, "full pinyin has candidates");
        check(findCandidate(expected[i], count) >= 0, words[i]);
        int selected = findCandidate(expected[i], count);
        check(im_choose(selected) == 1, "word selection completes");
        check(choice(0) == expected[i], "selected word is unchanged");
    }
    std::puts("PASS full-pinyin dictionary and selection");

    im_reset_search();
    size_t count = im_search("zhongguorenmin", std::strlen("zhongguorenmin"));
    int prefix = findCandidate(u"中国", count);
    check(prefix > 0, "partial sentence candidate exists");
    count = im_choose(prefix);
    check(im_get_fixed_len() == 2 && count > 1, "partial selection retains remaining candidates");
    check(choice(0).substr(0, 2) == u"中国", "selected prefix preserved in sentence");
    size_t decoded = 0;
    check(std::strcmp(im_get_sps_str(&decoded), "zhongguorenmin") == 0, "partial selection preserves pinyin");
    count = im_delsearch(13, false, false);
    check(im_get_fixed_len() == 2 && count > 0, "backspace retains selected prefix");
    im_search("zhongguorenmin", std::strlen("zhongguorenmin"));
    check(im_choose(0) == 1 && choice(0) == u"中国人民", "full sentence selection after partial delete/retype");
    std::puts("PASS partial selection, backspace and candidate zero");

    im_flush_cache();
    unsigned long long beforePrivate = hashFile(argv[2]);
    im_set_learning_enabled(false);
    im_reset_search();
    count = im_search("qingyushurufa", std::strlen("qingyushurufa"));
    check(count > 0, "private field search works");
    im_choose(0);
    im_flush_cache();
    check(hashFile(argv[2]) == beforePrivate, "disabled learning does not change dictionary");
    std::puts("PASS field-level learning privacy");

    std::vector<double> durations;
    auto stressStart = std::chrono::steady_clock::now();
    for (int iteration = 0; iteration < 1000; ++iteration) {
        const char* input = words[iteration % 7];
        size_t length = std::strlen(input);
        im_reset_search();
        for (size_t step = 1; step <= length; ++step) {
            auto start = std::chrono::steady_clock::now();
            size_t candidates = im_search(input, step);
            if (candidates > 0) check(!choice(0).empty(), "rapid typing candidate is readable");
            auto end = std::chrono::steady_clock::now();
            durations.push_back(std::chrono::duration<double, std::milli>(end - start).count());
        }
        while (length > 0) {
            im_delsearch(--length, false, false);
        }
        size_t decodedLength = 1;
        const char* remaining = im_get_sps_str(&decodedLength);
        check(remaining && remaining[0] == '\0', "continuous deletion empties composition");
    }
    std::sort(durations.begin(), durations.end());
    double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - stressStart).count();
    std::printf("PASS 1000 rapid type/delete cycles; %zu key searches; total %.2f ms; p50 %.3f ms; p95 %.3f ms; max %.3f ms\n",
        durations.size(), elapsed, durations[durations.size() / 2], durations[durations.size() * 95 / 100], durations.back());
    im_close_decoder();
    check(im_open_decoder(argv[1], argv[2]), "user dictionary reopen works");
    check(im_search("shurufa", 7) > 0 && choice(0) == u"输入法", "reopen candidate search");
    im_close_decoder();
    std::puts("ALL_ENGINE_CHECKS_PASS");
    return 0;
}
