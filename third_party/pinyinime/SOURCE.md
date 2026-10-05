# AOSP PinyinIME decoder provenance

Upstream: Android Open Source Project, `platform/packages/inputmethods/PinyinIME`.

- Official repository: https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/
- Mirror used for retrieval: https://github.com/oxangen/PinyinIME
- Pinned mirror revision: `05cc8f8f2465553bc6d19b2ede24073d079eeed7`
- Mirror identifies itself as a copy of the official AOSP project. Its final commit only adds its README. Source copyright headers identify the Android Open Source Project.
- Retrieved on 2026-10-04. Official Gitiles was used to verify project/API/license; direct official-host cloning and archive downloads timed out in the current network. GitHub CLI also failed with a TLS handshake timeout. GitHub codeload succeeded.
- Imported components: `jni/include/*.h`, `jni/share/*.cpp`, `res/raw/dict_pinyin.dat`, and project `NOTICE`.
- License: Apache License 2.0, retained in [NOTICE](NOTICE) and upstream file headers. The official project includes `MODULE_LICENSE_APACHE2`.
- Dictionary: `app/src/main/assets/pinyin/dict_pinyin.dat`, 1,068,442 bytes.
- Dictionary SHA-256: `6bf0bbde4e3134cce38d08524a9f4dc1af40435243c8e36b38eb68d1e14462b2`.
- Original downloaded master ZIP SHA-256: `ffa3289caf1559ed831a5a7a087ea17c5a317142134d6fdc9c46eae1413820b2`.
- Verified pinned-revision ZIP SHA-256: `6cfb6b3cf7add8a35a30baab18010b1c2ebe801186150d7a72e6c6f6c6f401e1`. Every imported header/source matched the pinned revision before the documented patches.

Qingyu modifications to imported sources:

1. `dictlist.h` / `dictlist.cpp`: dictionary count and serialized sizes explicitly use 32-bit fields, including the previously 64-bit-dependent save path.
2. `matrixsearch.h` / `matrixsearch.cpp`: learning-enabled flag prevents new user lemmas and frequency updates while disabled. Skipping a lifecycle flush alone would not suffice because upstream can flush automatically while learning.
3. `pinyinime.h` / `pinyinime.cpp`: public native setter for that flag.
4. `compat/cutils/log.h` is a separate compatibility shim using the public NDK log API. The original Android system-private JNI service and libcutils are not linked.
5. `matrixsearch.cpp::get_lpis`: shared candidate/deduplication scratch space is bounded using actual struct sizes on each ABI. Dense abbreviated input previously triggered a debug assertion or discarded candidates in release mode; the adapter now keeps the best-scored candidates that fit.

The native workspace retains the original 40-step bound. JNI supplies at most 39 pinyin characters and nine syllables per native search, using AOSP's own spelling parser before lattice expansion, while the Java adapter preserves up to 64 requested characters and continues remaining segments. Expanding the workspace alone was rejected after a pathological repeated-vowel boundary test reached native pool limits. Pre-parsing the syllable bound avoids filling those pools before the engine's normal post-search truncation runs.

Original Chinese decoding, system lexicon, incremental lattice search, candidate order, lemma selection, and user dictionary implementation remain the AOSP engine. English gloss data are a separate module and do not affect its ranking or selection.
