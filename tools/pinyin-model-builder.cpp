// Offline model compiler; uses the same vendored AOSP serialization as Android.
#include <cstdio>
#include "dicttrie.h"
using namespace ime_pinyin;
int main(int argc, char** argv) {
    if (argc != 4) { std::fprintf(stderr, "usage: pinyin-model-builder raw-utf16 valid-hanzi-utf16 output.dat\n"); return 2; }
    DictTrie dictionary;
    if (!dictionary.build_dict(argv[1], argv[2]) || !dictionary.save_dict(argv[3])) return 1;
    std::puts("PINYIN_MODEL_BUILT");
    return 0;
}
