<p align="center">
  <img src="docs/images/qingyu-logo.svg" width="96" alt="Qingyu logo" />
</p>

<h1 align="center">Qingyu Input Method · 轻语</h1>
<p align="center">Type Chinese. Meet English along the way.</p>

<p align="center">
  <a href="README.md">中文</a> · English
</p>

<p align="center">
  <a href="https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk"><img alt="Published APK v0.7.1" src="https://img.shields.io/badge/Published_APK-v0.7.1-476B57"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

**Qingyu is first a Chinese keyboard.** Type pinyin and choose Chinese as usual. A quiet gloss in the selected language appears above a candidate, with English as the default and local dictionaries first. Tapping “开发” still enters only “开发”.

**Qingyu v0.7.1 editor and candidate stability update is now released:** [GitHub Release and APK](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.7.1), [SHA-256 file](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk.sha256), [installation notes](releases/README-v0.7.1.md), and [validation](docs/validation-v0.7.1.md). Nine-key preedit shows inferred pinyin. The left rail scrolls through all syllable alternatives and advances after each selection; backtracking reopens previous choices without committing text. Holding a letter group opens a slide-to-select uppercase/lowercase/number picker.

## Demo Video

<p align="center"><a href="https://raw.githubusercontent.com/sharbvane/qingyu-srf/main/docs/media/qingyu-demo.mp4"><img src="docs/images/qingyu-demo-preview.gif" width="300" alt="Animated preview of the Qingyu Input Method demo; click to download the full video" /></a></p>

<p align="center"><a href="https://raw.githubusercontent.com/sharbvane/qingyu-srf/main/docs/media/qingyu-demo.mp4">▶ Download the full demo (30 seconds · 1080 × 1920 · MP4 · 8.1 MB)</a></p>

## Preview

| Text editing | Dark nine-key |
| --- | --- |
| ![Qingyu text editing panel with backspace, undo and translation](docs/images/editor-v0.7.1-light.png) | ![Qingyu dark keyboard with candidates and attached pinyin](docs/images/nine-v0.7.1-dark.png) |

Screenshots show the actual v0.7.1 editing panel and dark nine-key keyboard.

## Features

- **Personalized input:** Actual nine-key choices now learn typed pinyin, initials and phrases durably. Chinese prediction commits count once; one accidental choice has little influence. Full-key initials and mixed sentence spellings reuse the modern syllable graph. Common pinyin and lowercase letters lead the rail; uppercase letters and digits remain available by scrolling.
- **Nine-key initials and mixed spelling:** One syllable graph covers the existing modern dictionary. Each syllable can use its full spelling or initial; `773` ranks “输入法” first. The scrolling rail offers every uppercase/lowercase letter, digit and valid syllable for the current key. Individual letters can join into full syllables; choices advance without committing and support backtracking. Segmentation shows a small 1: tap to split pinyin, hold and slide to enter 1. Ambiguous short codes can be narrowed by choosing letters or syllables.
- **Full pinyin and nine-key:** The AOSP decoder uses a pinned modern Rime-ice lexicon, with indexed alternate segmentations and bounded sentence composition. v0.6 adds context statistics from real Chinese text to improve sentence composition and prediction. Full pinyin has a manual segmentation key to insert a pinyin separator. Repeated word, initials and phrase choices gradually improve local ranking while preserving existing learning records. One-edit typo suggestions are optional candidates and preserve the original letters. Full-pinyin Enter commits raw letters; nine-key confirmation commits a Chinese candidate.
- **English candidates:** Over 121,000 word forms, completions, explicit spelling suggestions and contextual next-word prediction, with Chinese glosses.
- **Compact keyboard top:** Idle shows a short empty candidate strip and icon navigation. While composing, candidates replace the navigation area; navigation returns when composition ends. Fixed overall top space keeps the keys steady. Pending queries retain candidates and matching annotations; same-word refreshes preserve gestures and scrolling, and repeated annotation batches are deduplicated rather than cancelled and reloaded. The expanded list replaces the keys, scrolls continuously up and down, and wraps complete sentences and translations. A prediction chain stops after at most three selections; the rightmost X clears current predictions. Unreliable contexts can produce no prediction.
- **Quiet language learning:** Local definitions come first; installed on-device models asynchronously fill missing visible character, word, phrase and sentence translations. Chinese candidates do not wait for translation. Tap enters the original word, hold opens layered details, and swipe up directly enters a concise translation in the selected language. Full dictionary senses remain in details. Switching the language updates candidates, details and swipe translations together. The compact candidate row and expanded list show no Google brand strip or badge; held-candidate details retain model attribution. See the [SDK attribution notes](third_party/mlkit/SOURCE.md) for the applicable source requirements.
- **Phrases and sentences:** Local phrases first; downloaded on-device models translate other sentences asynchronously. Failure preserves normal input.
- **Text editing:** A circular direction pad supports cursor movement, selection and line boundaries, beside a two-column, four-row action grid with hold-to-repeat backspace, ordered session undo and translation that replaces the original range. Without a selection, only Chinese is translated. Chinese or mixed selections preserve foreign content; a confidently detected foreign selection uses its installed model to translate into Chinese. Original punctuation, numbers, emoji, repeated spaces, tabs and newlines are preserved; ordinary word spaces follow natural target-language spacing for complete foreign phrases. Failed or stale translations never replace the editor. External text changes invalidate undo, and password text is excluded. See [editing rules and limits](docs/text-editing.md). Selection, copy, cut, paste, 100 recent clips and the existing icon navigation remain.
- **Updates and project links:** More → Check for updates shows the official GitHub release version, notes and APK size inside the app. Downloads undergo package and signing checks before Android handles installation. Project homepage opens the official repository.
- **Segmentation and letter shortcuts:** Chinese full pinyin uses a manual segmentation key. English retains Shift for temporary uppercase and a quick double-tap to lock uppercase. Hold a letter and slide left for uppercase or right for lowercase; digit-bearing letters offer uppercase on the left, lowercase in the middle and the digit on the right. Release to enter the highlighted character.
- **Quiet part-of-speech colors:** Exact jieba Chinese dictionary tags select muted noun, verb, adjective, adverb and function-word tones. Unknown and English words remain neutral. These are lexical defaults, not contextual disambiguation.
- **Everyday typing:** Chinese punctuation, English, numbers and mixed input; repeat backspace, spacebar cursor gestures and candidate browsing.
- **Simple keyboard:** Sage light and dark themes, continuous 78%–124% height, two styles, haptics and previews.
- **Local input:** Input and learned frequencies stay on device. Password fields disable candidates, glosses, learning and clipboard history; sensitive system clips are excluded.

Missing definitions or unavailable translations stay blank. Dictionary senses and model translations can be inaccurate. The bounded sentence beam combines source frequencies, corpus context and local habits; complex semantics, nine-key ambiguities and English prediction still need real-device feedback. Detail examples are traceable excerpts from real source text, with no example shown when none is suitable.

## Install and try it

1. Download and install the [Qingyu v0.7.1 APK](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk), and get its SHA-256 file from the [GitHub Release](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.7.1). Earlier versions are listed under [all GitHub Releases](https://github.com/sharbvane/qingyu-srf/releases). Android 8.0 or newer is required.
2. Install and open Qingyu. Use **Enable Qingyu Input Method**, then **Switch to Qingyu**. Android requires these system settings steps.
3. In any text field, try `anzhuo`, `dangang` or `nohao`. Tap a Chinese candidate to enter Chinese only.

The current public release is **v0.7.1**. Download the APK and SHA-256 file from the [GitHub Release](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.7.1), and read the [validation record](docs/validation-v0.7.1.md). It installs over v0.7.0 and preserves learning data. See [signing and backup](docs/release-signing.md). Long-term physical-device use remains unverified.

Choose English, Japanese, French, German, Russian or Spanish under More → annotation language. Explicitly selecting a required non-English language for the first time starts its optional model download. Normal typing never downloads a model. Wi-Fi is the default; Translation model management can explicitly allow mobile data. The page shows real transferred bytes, a known total or an indeterminate indicator, waiting states, failure reasons and retry, plus installed size and deletion. During v0.6.6 validation, all five extra Japanese, French, German, Russian and Spanish models were freshly downloaded and tested for on-device translation on the Android 15 / API 35 emulator. This round's final v0.7.1 Release APK also passed deletion and redownload regressions for Japanese and German models. Downloads still require a network that can reach the official model service. Extra languages reserve no model storage before downloading. Chinese and English keyboards remain available.

Check for updates is also available in app settings. Android requires installation-source permission and system confirmation; installation is not silent. See [in-app update behavior](docs/in-app-updates.md) for download restoration, package validation and recovery.

## Android support

| Item | Current support |
| --- | --- |
| Android | 8.0+ (API 26+); target SDK 35 |
| ABIs | arm64-v8a, armeabi-v7a and x86_64 |
| IME | Enable and select it in Android system settings |
| Validation | Android 15 x86_64 emulator; physical devices and OEM app compatibility still need testing |

## Architecture

- Android `InputMethodService` and an original Canvas keyboard UI.
- AOSP PinyinIME through JNI, supplemented by indexed modern full-pinyin/initials candidates and bounded sentence composition. Character, part-of-speech and next-token statistics from a pinned TRAIN corpus provide context ranking. Chinese events run on one serial engine thread.
- Android-independent interfaces for candidate snapshots, input engines and translation providers live in `core/` to keep future platform work decoupled.
- A separate low-priority translation worker queries a local SQLite index with an in-memory cache. Lookup failures never block Chinese input.
- Compact candidate geometry depends on Chinese text; late foreign-language glosses do not change candidate width. Expanded entries wrap complete text.
- The overall keyboard top reserves 100 dp in portrait and 92 dp in landscape, shared between idle candidates/navigation and the composing candidate area. Raw pinyin remains attached to its upper edge. The expanded grid scrolls vertically within the existing keyboard body. Lexical tag lookup shares the auxiliary worker; missing tags do not affect input.

See the [upstream and architecture research](docs/research.md).

## Offline and privacy

Input, candidates, translation inference and clipboard processing run on device. No remote text translation endpoint is called. The APK includes Chinese and English dictionaries, local meanings, Chinese context statistics and a small shared offline language-identification resource; optional translation weights remain on demand. Broader sentence translation needs on-device models; Japanese, French, German, Russian and Spanish glosses additionally need their language models. First explicitly selecting a non-English annotation language starts an optional download, with Wi-Fi as the default and mobile data available through an explicit choice. Models can also be downloaded or retried from Translation model management. Ordinary typing never initiates a model download.

The app has network permission. Google ML Kit can contact Google for models, configuration, compatibility and diagnostics, sending device/installation identifiers, language configuration, input/output size and performance metadata; it does not send input or output text. See [SDK provenance and privacy](third_party/mlkit/SOURCE.md). Explicit update checks read GitHub Releases metadata; download requests include no input or clipboard text. Clipboard history can be cleared or disabled and is not uploaded. No advertising is integrated.

v0.6.6 replaces the blanket 16 KiB ARM64 model block with a background check of the actual packaged official native library. Safe layouts can download and translate; unknown or unsafe binaries retain local input and definitions. Official binaries are unchanged. Runtime checks cover a 16 KiB x86_64 emulator; ARM64 physical devices remain untested. See [SDK evidence and limits](third_party/mlkit/SOURCE.md).

## Build from source

Windows, JDK 17 and PowerShell 7 are required. The setup script downloads the pinned Android SDK, NDK, CMake and Gradle into the ignored local `.tools/` directory:

```powershell
pwsh -File scripts/setup-toolchain.ps1
pwsh -File scripts/build.ps1 -Variant Release -Test
```

Build output goes to `releases/`. Local tools and signing files are excluded by `.gitignore`. Restore the existing signing materials on a new machine; never generate a replacement key. See [signing](docs/release-signing.md), the [v0.7.1 validation record](docs/validation-v0.7.1.md) and [installation notes and limits](releases/README-v0.7.1.md).

## Roadmap

- [x] Installable Android full-pinyin MVP
- [x] Offline English candidate glosses and a display toggle
- [ ] Tune touch feel, app switching and power use on physical devices
- [x] Pinned modern Chinese lexicon, alternate segmentation, sentence composition and gradual learning
- [ ] Review gloss quality and expand local dictionary coverage
- [ ] Design a separate iOS Keyboard Extension
- [x] English, Japanese, French, German, Russian and Spanish annotations and on-device sentence translation
- [x] English candidates, nine-key, text editing and next-word prediction
- [x] In-app update checks, downloads and a system installation entry point
- [ ] Evaluate a Korean translation provider

## Contributing

Bug reports, dictionary-quality feedback and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md), include reproduction steps and the Android version, and report relevant build or test results. New Qingyu-authored code is offered under GPL-3.0-only. Third-party code, data and media remain under their respective licenses.

## License and attributions

**Qingyu-authored code is licensed under GNU GPL-3.0-only**; see [LICENSE](LICENSE). The repository also contains third-party material distributed with its source license and notices: AOSP PinyinIME under Apache-2.0, and an adapted CC-CEDICT dictionary under CC BY-SA 4.0. Preserve the upstream attribution and notices:

- [Third-party licenses and NOTICE](LICENSE-THIRD-PARTY.md)
- [AOSP PinyinIME provenance and changes](third_party/pinyinime/SOURCE.md)
- [CC-CEDICT data provenance and processing](third_party/cedict/README.md)
- [UD Chinese GSDSimp context statistics and real examples](third_party/input-data/chinese-context/SOURCE.md): CC BY-SA 4.0, pinned TRAIN input only; DEV/TEST never contribute statistics or examples, and the upstream caveat about rights in underlying text is retained.

Each license's terms and compatibility scope continue to apply. The root GPL notice does not erase upstream rights. Trademarks, product names and logos are not transferred by the code license.

<p align="center"><sub>轻一点输入，久一点相遇。 · Type lightly. Learn naturally.</sub></p>
