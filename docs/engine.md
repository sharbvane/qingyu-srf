# Input engines and translation boundaries

Implementation scope: v0.2, 2026-10-06. This document describes current code and contracts; it does not certify a new APK, test run, or physical-device performance. Version-specific validation belongs in the corresponding validation record.

## The v0.1 foundation remains

The first Android MVP integrated the real AOSP PinyinIME C++ decoder and system dictionary rather than a handwritten pinyin table. `ChineseEngine` defines the platform-neutral contract, `PinyinEngine` implements the first adapter, and `NativeDecoder` is its small JNI boundary. The decoder supports continuous full pinyin, partial selection, a sentence candidate, incremental search, cached dictionary lookup, ranking, and a persistent user dictionary.

The small dependency set and Apache-2.0 upstream license made an installable offline Chinese MVP practical. It is still an older statistical decoder and lexicon. New vocabulary, fuzzy spelling, typo tolerance and context ranking need further work; it is not equivalent to a current commercial IME's language model. A future librime adapter can replace it behind the contract, with separate schema, data, deployment and license work. Fcitx5 Android and Trime are references, not copied application frontends.

v0.2 retains that Chinese core and adds independent nine-key and English input, lightweight continuation prediction, editing/navigation panels and local/model translation. The optional translation model never generates or ranks full-pinyin Chinese candidates.

## Thread ownership and installation

- The Android main thread owns touch, drawing, editor context reads and `InputConnection` calls. A single input worker serializes native search/selection/deletion, nine-key/English suggestions, prediction and candidate preview. Ordered input commits are posted back to the main thread.
- Only one `PinyinEngine` may be open in a process, on its owning worker. Native instance and thread checks reject another concurrent owner.
- AOSP dictionary initialization belongs to the input worker. The larger optional input database, English forms and bigrams are installed/loaded once by a separate `qingyu-data-install` thread, then handed to the input worker after initialization. Their installation holds neither the Chinese decoder queue nor the gloss queue.
- Gloss installation, SQLite lookup and model callbacks use the independent background gloss worker. Reverse English gloss lookup is synchronized; the SQLite database is read-only. UI updates pass session, revision and language checks.
- Learning files are saved at lifecycle boundaries. Translation clients, databases, callbacks and workers are released when the service closes. Separation avoids synchronous waits; device-level CPU, memory and storage contention still requires measurement.

The input worker is not merely a native `search` loop: it also preserves ordered selection, deletion, source preview and editor operations. The important isolation is that auxiliary asset installation and model work do not run ahead of Chinese input in this queue.

## Full-pinyin contract and bounds

- `open(dictPath, userPath)` precedes search. `search(pinyin)` publishes immutable `EngineSnapshot` data containing `composing`, active `rawPinyin` and up to 128 candidates; equal states can reuse a snapshot. IDs are meaningful only for that snapshot.
- `select(id)` can fix a Chinese prefix without committing, or return `committedText` when the composition is complete. Candidate zero includes a native fixed prefix; the adapter removes that prefix from the displayed remaining choice. Glosses never enter the native selection contract.
- AOSP bounds each native sentence to nine syllables and the native search workspace to 39 letters. The adapter retains requested input and segments the remaining suffix during selection. Earlier chosen segments remain in `composing`; `rawPinyin` identifies the active segment. Backspacing through a segment restores the preceding one for editing.
- Invalid/unparsed letters remain in the composing text. Explicit completion can preserve them as literal text when no candidate resolves them. It must check this case before and after the multi-segment completion loop; neither normal selection nor translation may silently discard an unresolved tail.
- An active adapter composition accepts at most 64 ASCII pinyin characters. Overlong direct API calls are rejected rather than truncated. The UI finishes the active composition and starts another segment when it reaches the bound, preserving continuous input.
- `backspace()` edits engine composition, not committed editor text. Empty composition delegates deletion to the platform adapter. `flush()` belongs at lifecycle boundaries; `close()` flushes and releases ownership.

The native code pre-parses the nine-syllable boundary before expanding its lattice and sizes candidate/deduplication scratch space using actual ABI struct sizes. These limits protect the old decoder workspace; they do not authorize dropping the original input.

## Whole-composition translation preview

Normal candidate click selects original Chinese. Long press opens definitions; an upward candidate swipe explicitly requests translated input. For full pinyin, `previewCandidate(id)` resolves the entire composition represented by that selection: earlier completed segments, the chosen prefix and the native nine-syllable tail. It continues selecting the remaining segment's first choices within the adapter bound, preserves unparsed letters, and returns the complete source rather than just the label currently visible in the candidate cell.

Preview temporarily disables native learning. It saves the active input, selected choices, completed segments, immutable snapshot and candidate count, then restores native selection and verifies candidate ordering before returning ownership. It must not learn, accept an editor commit, lose tail characters or invalidate the caller's IDs. The service associates the asynchronous translation with the original session/revision/request and commits only while those still match. A failed, empty or stale translation leaves the original composition available.

Nine-key preview follows the chosen candidate's `consumedDigits`, then resolves the remaining digit sequence; unresolved digits are retained. English preview uses the selected English word, and continuation preview uses the selected next-word candidate. It does not translate an entire chat history.

## Nine-key input

`LocalInputDictionary` has an independent CC-CEDICT tone-free reading-to-digit index, with 121,070 reading entries. jieba frequency weights, original everyday continuations and bounded local counts rank collisions. Exact and incomplete reading matches coexist with prefix candidates. `NineKeyCandidate.consumedDigits` tells the service how much to fix, so remaining digits survive partial choice and deletion.

Longer digit sequences can use bounded word segmentation with frequency/context bonuses. This is a lightweight statistical T9 implementation, not a neural decoder or a guarantee of fluent sentence ranking. The native full-pinyin decoder is not fed digit strings. Failed optional input-data initialization leaves full pinyin available; nine-key suggestions may remain unavailable until that data is ready.

## English input and continuation

`EnglishEngine` contains **121,167 word forms**, expanded from SCOWL/Hunspell data. Prefix lookup, bounded edit-distance spelling suggestions, unigram frequency, **17,580 bigrams**, original everyday weights and bounded local learning produce suggestions. It preserves case conventions and always permits the user's intentional spelling or a new name. Space commits what the user typed; selecting a suggestion is the explicit way to change spelling. English candidate/prediction choice adds the normal word separator.

There are only **20,126 local English-to-Chinese gloss entries** in the reverse CC-CEDICT table. This is a separate coverage set, not a translation for every indexed word form. Common inflections attempt simple stem lookup. Missing entries can receive asynchronous on-device model translations when models are ready, otherwise they remain blank. Details and reproducible sources are in `third_party/input-data/manifest.json` and `assets_sources.md`.

After composition finishes, English next-word prediction uses the previous word, bigrams and local counters. Chinese continuation first combines AOSP `im_get_predicts` with CC-CEDICT suffix continuations, original everyday pairs and local counters. The native predictor accepts a bounded trailing BMP Chinese context and does not query while pinyin composition is active. The service validates context/session/revision before showing predictions. These are lightweight statistical mechanisms, not neural next-token models; they are independent of ML Kit translation inference.

## Definitions, languages and model translation

Chinese annotations select English, Japanese or French (`zh→en/ja/fr`); English annotations default to Chinese (`en→zh`), independently of the Chinese annotation setting. Display-off hides/cancels routine annotation requests; a deliberate long press or upward swipe remains a separate request.

`TranslationRepository` first checks authored local phrases and local dictionaries. Chinese-to-English has broad CC-CEDICT coverage; Japanese/French local coverage is a small phrase table. Reading-only full dictionary senses are separate from a single translation suitable for commit. If local translation misses and the required models are ready, ML Kit 17.0.3 runs translation on the device. Non-English pairs can route through English and lose nuance. Outputs are casual aids, not guaranteed accurate translations.

Routine annotations are coalesced for about 100 ms. Local lookup covers the bounded candidate snapshot; model supplementation covers only missing candidates on the visible page, serializing one inference at a time. Scrolling or changing a page starts a new request nonce and cancels the remaining old queue. The repository has a 256-entry translation cache and an eight-request inference limit; duplicate requests share results. Late results require request nonce, session, revision, source/target language and display-state checks. Detailed translation additionally checks its action request, so a result cannot overwrite later typing.

`refreshModels` checks locally installed models without downloading. Only the explicit download action invokes `downloadModelIfNeeded`, with `DownloadConditions.requireWifi()`. Typing, candidate choice and inference do not initiate downloads. Missing/downloading/failed states return a readable status and keep original input usable. The SDK guide describes this download and inference separation: [ML Kit Android translation](https://developers.google.com/ml-kit/language/translation/android).

Model results use Google's required original attribution badge; local dictionary/phrase results are labelled separately. SDK, model terms and badge provenance are recorded in `third_party/mlkit/SOURCE.md`.

The official translation SDK's ARM64 binary has a 16 KiB-incompatible GNU_RELRO end. On ARM64 processes using pages larger than 4 KiB, the repository disables SDK translator creation and model downloads instead of attempting to load that binary. Basic input, local glosses and dictionary definitions remain available; see `third_party/mlkit/SOURCE.md` for the exact upstream limitation.

## Learning, clipboard and network privacy

`setLearningEnabled(false)` guards actual native user-lemma mutations and frequency updates, not just `flush()`. Existing ranking is retained; prior normal learning may still be saved later. The same preference/privacy flags disable new English and Chinese continuation counters. They store bounded word/pair counts rather than full editor histories. Android `IME_FLAG_NO_PERSONALIZED_LEARNING` stops new learning while preserving normal input and optional annotations.

Password fields bypass candidate/translation lookup, lock direct input, suppress enlarged key previews and clipboard recording, and block password copy/cut. Clipboard history holds at most 100 unique text items in app-private storage; marked-sensitive clipboard text and overlarge items are skipped. Recording can be disabled, entries can be removed, and the panel can clear history. Disabling recording alone does not erase already saved entries. Clipboard and editor data are not attached to model downloads, and sensitive keystrokes must not enter product diagnostic logs.

Temporary per-key diagnostic logging has been removed from the product service; typed text is not written to product logs.

Unlike v0.1, v0.2 declares network permissions. Qingyu does not call a cloud text-translation endpoint. Input/output translation text stays on-device, but ML Kit may contact Google independently for model/configuration/compatibility/diagnostic activity and send device/app data, installation identifiers, configured languages, input/output sizes, latency, event types and error codes. Explicit Wi-Fi download constraints do not suppress all SDK metadata traffic. [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure), [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms).

## Building, validation and extension

The standard APK build compiles the native source through CMake/NDK for arm64-v8a, armeabi-v7a and x86_64 with 16 KiB native alignment. On this Windows workspace, the build scripts use a temporary ASCII `subst` drive to avoid NDK tooling failures on Chinese paths. `tools/build-native.ps1` is a direct clang fallback; prebuilt packaging is only appropriate when externalNativeBuild is disabled.

`tools/engine-smoke.cpp` exercises the real native dictionary and learning boundaries. `tools/EngineAdapterSmoke.java` runs through DEX/`app_process` to exercise the Java/JNI selection, segmentation, invalid-tail, preview restoration and ownership contracts. `core/` checks cover the platform-neutral algorithms, while Android integration tests cover actual editor and UI behavior. These are different evidence layers; source/build success or decoder microbenchmarks do not establish touch latency, FPS, power use, OEM compatibility, model quality or long-term daily usability.

Run `pwsh -File tools/test-engine.ps1 -Serial emulator-5554 -Abi x86_64` for the engine workflow, or use a connected device's actual serial and ABI. This workflow does not enable/select the IME. Consult version-specific validation records for actual executions; no new test-pass count, APK hash or release result is asserted here.

Current UI adds five navigation actions, editing/select/copy/cut/paste, bounded clipboard history, Emoji, full-pinyin/nine-key/English mode choice, 78%–124% height, two key styles, themes and feedback. Those Android surfaces are separate from the core. iOS will need its own Keyboard Extension, editor adapter, translation SDK/data adapters and safety/next-keyboard handling; existing Java/Android packages are not directly loadable there. Korean/Spanish annotations remain future providers.

Qingyu's own code currently uses GPL-3.0-only; AOSP source/data retains Apache-2.0, CC-CEDICT derivatives CC BY-SA 4.0, SCOWL its original notices, jieba MIT and Google SDK/model/brand assets their separate terms. Preserve original notices and data-processing provenance. See `LICENSE-THIRD-PARTY.md`, `third_party/pinyinime/SOURCE.md`, `third_party/pinyinime/NOTICE`, and the input/translation data manifests.
