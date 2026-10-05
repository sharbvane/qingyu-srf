# Chinese input engine

The MVP uses the real AOSP PinyinIME C++ decoder and its bundled system dictionary rather than a handwritten small pinyin table. It runs fully offline. `ChineseEngine` defines the platform-neutral contract; `PinyinEngine` is this first adapter, and `NativeDecoder` is the small JNI boundary. The Android service keeps all decoder calls on one serial worker. An immutable `EngineSnapshot` is published to the UI. Gloss lookup uses a different worker and cannot block native candidate generation.

## Why this first engine

The AOSP decoder supports continuous full pinyin, partial word selection, a sentence candidate, candidate ranking, incremental search, cached dictionary lookup, and a persistent user dictionary. Its small dependency set and Apache-2.0 license make the first installable Android version practical. JNI uses public NDK APIs and is compiled for arm64-v8a, armeabi-v7a, and x86_64, with 16 KiB ELF page alignment.

This is an older statistical decoder and older system lexicon. It is a useful offline MVP base, but contemporary vocabulary, typo correction, fuzzy spelling, context prediction, and ranking quality still need device feedback and lexicon evolution. The first release must not be described as equivalent in language-model quality to current commercial IMEs. A future Rime/librime adapter can replace it behind `ChineseEngine`; Rime's schemas/dictionaries and deployment tooling would add integration and license obligations that deserve a separate milestone. Fcitx5 for Android and Trime remain architectural references rather than imported app UI/code.

## Contract

- Create one `PinyinEngine` per process. Call `open(dictPath, userPath)` on the decoder worker before searching. A second concurrently open instance and calls on a different thread are rejected.
- `search(pinyin)` returns `composing`, the active `rawPinyin`, and up to 128 candidates. Repeated searches for the same state reuse the immutable snapshot. Candidate IDs are valid for that state only.
- `select(id)` may fix a Chinese prefix without committing it, or return nonempty `committedText` when the entire composition is complete. English gloss text never enters this contract.
- Candidate zero in the native engine includes the selected prefix; the adapter removes it from the displayed candidate text so the UI shows only the remaining selection.
- AOSP bounds a sentence to nine syllables and a native search to 39 letters. The adapter retains every requested keystroke and segments longer input during selection rather than dropping the truncated suffix. Earlier selected segments stay in `composing`; `rawPinyin` describes the active segment. Backspacing through a suffix restores the preceding segment for editing. Keeping the original native workspace bound also prevents pathological repeated-vowel input from exhausting its small node pool.
- Invalid/unparsed tails also stay visible. If no candidate can resolve such a tail, the service may explicitly commit `composing` as text. It must check this fallback both before and after a multi-segment completion loop.
- `backspace()` updates native selection state; it does not delete from the editor. The service deletes already committed text when the engine composition is empty.
- Each active composition segment is bounded to 64 ASCII pinyin characters. Overlong direct API calls are rejected rather than silently truncated. When the UI reaches 64 active characters, it completes the current composition and starts the next segment, so continuous typing can continue.
- `flush()` is for lifecycle boundaries, not every key; `close()` flushes and releases the native decoder.

## Privacy and learning

`setLearningEnabled(false)` disables all paths that add user lemmas or update their frequencies in the decoder. Existing learned words can still appear in candidates. This matches the intent of Android `IME_FLAG_NO_PERSONALIZED_LEARNING`: stop new learning rather than erase existing ranking. Password fields are handled by the service without pinyin/translation lookup. User dictionaries are local app-private files. No network translation is involved.

Upstream maintains the user dictionary in memory and can automatically persist accumulated learning during normal operations; therefore disabling only `flush()` is insufficient. The setter guards the actual mutation paths. Normal learned data accumulated before disabling can still be persisted later; new private-field selections do not change it.

## Rebuilding and checking

The standard APK build compiles source through CMake/NDK. On this Windows machine, run from the project-local ASCII `subst` drive used by the project build workflow to avoid native tooling failures with the Chinese workspace path. `tools/build-native.ps1` is a direct NDK clang fallback and optionally builds an `adb shell` smoke executable; by default its output stays under `.tools/engine-build`. Use `-PackagePrebuilt` only when externalNativeBuild is disabled to avoid duplicate packaged libraries.

`tools/engine-smoke.cpp` tests the real native dictionary, full-pinyin selection, partial selection, deletion, learning privacy, reopen, and 1,000 type/delete cycles. `tools/EngineAdapterSmoke.java` is compiled to DEX and executed with `app_process` to test the actual Java/JNI contract, long composition segmentation, invalid-tail preservation, worker confinement, and process-global instance ownership. Results are recorded in `docs/engine-smoke-results.txt` and `docs/engine-adapter-results.txt`.

Run `powershell -File tools/test-engine.ps1 -Serial emulator-5554 -Abi x86_64` to rebuild both smoke programs and execute them on the Android emulator. A connected phone may use `-Serial <adb-device-id> -Abi arm64-v8a`. This test does not enable/select an IME or change editor content.

Boundary checks include 64 repeated vowel characters and 100 deterministic random 64-letter input/delete sequences. These checks led to two explicit native bounds: pre-parse nine syllables before expanding the lattice, and size the shared candidate/deduplication scratch workspace with the actual ABI struct sizes. Both guards preserve requested text and prevent the old engine's debug assertions from becoming input-process failures.

The latest scripted run on the API 35 x86_64 emulator measured 6,285 key searches in 1,000 complete type/delete cycles. Native search plus candidate-zero retrieval measured p50 0.029 ms, p95 0.219 ms, and max 4.218 ms; the Java/JNI adapter, including immutable snapshots with up to 128 candidate strings, measured p50 0.077 ms, p95 0.377 ms, and max 4.597 ms. Host/emulator scheduling makes these measurements vary across runs; they are emulator decoder timings, not phone end-to-end latency, animation FPS, or a long-term daily-use guarantee. Real phone performance and varied editor behavior remain part of acceptance testing.

Source, hash, license, and modification details: `third_party/pinyinime/SOURCE.md` and `third_party/pinyinime/NOTICE`.
