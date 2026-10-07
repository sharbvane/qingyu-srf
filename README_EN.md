<p align="center">
  <img src="docs/images/qingyu-logo.svg" width="96" alt="Qingyu logo" />
</p>

<h1 align="center">Qingyu Input Method · 轻语</h1>
<p align="center">Type Chinese. Meet English along the way.</p>

<p align="center">
  <a href="README.md">中文</a> · English
</p>

<p align="center">
  <a href="https://github.com/sharbvane/qingyu-srf/releases/download/v0.5.0/Qingyu-0.5.0.apk"><img alt="Published APK v0.5.0" src="https://img.shields.io/badge/Published_APK-v0.5.0-476B57"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

**Qingyu is first a Chinese keyboard.** Type pinyin and choose Chinese as usual. A quiet English gloss appears above a candidate; tapping “开发” still enters only “开发”.

## Preview

| Light theme | Dark theme |
| --- | --- |
| ![Qingyu light keyboard with candidates and attached pinyin](docs/images/keyboard-v0.4.0-light.png) | ![Qingyu dark keyboard with candidates and attached pinyin](docs/images/keyboard-v0.4.0-dark.png) |

v0.5.0 is now available on [GitHub Releases](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.5.0). The [validation record](docs/validation-v0.5.0.md) covers common pinyin, sentence learning, signing and real IME checks; the UI previews below illustrate the keyboard themes.

## Features

- **Full pinyin and nine-key:** The AOSP decoder uses a pinned modern Rime-ice lexicon, with indexed alternate segmentations and bounded sentence composition. Repeated word, initials and phrase choices gradually improve local ranking. One-edit typo suggestions are optional candidates and preserve the original letters. Enter commits raw pinyin.
- **English candidates:** Over 121,000 word forms, completions, explicit spelling suggestions and contextual next-word prediction, with Chinese glosses.
- **Compact keyboard top:** Idle shows a short empty candidate strip and icon navigation. While composing, candidates replace the navigation area; navigation returns when composition ends. Fixed overall top space keeps the keys steady. The expanded grid replaces the keys and scrolls continuously up and down. A prediction chain stops after at most three selections; the rightmost X clears current predictions.
- **Quiet language learning:** The candidate strip shows local definitions only, with no Google model output or branding row. Tap enters the original word; hold opens details. Swipe up enters an available local translation. A model translation opens attributed details and requires an explicit confirmation to enter it. Optional language models remain downloadable for the details page.
- **Phrases and sentences:** Local phrases first; downloaded on-device models translate other sentences asynchronously. Failure preserves normal input.
- **Icon navigation:** More, text editing, Emoji, keyboard modes and hide. Tap the same icon again to close its panel. Selection, select all, copy, cut, paste and 100 recent clipboard items.
- **Updates and project links:** More → Check for updates shows the official GitHub release version, notes and APK size inside the app. Downloads undergo package and signing checks before Android handles installation. Project homepage opens the official repository.
- **Case and letter shortcuts:** Chinese full pinyin starts lowercase. Tap Shift once for one directly committed uppercase English letter; double-tap quickly to lock uppercase. These letters bypass pinyin. Hold a letter and slide left for uppercase or right for lowercase; digit-bearing letters offer uppercase on the left, lowercase in the middle and the digit on the right. Release to enter the highlighted character.
- **Quiet part-of-speech colors:** Exact jieba Chinese dictionary tags select muted noun, verb, adjective, adverb and function-word tones. Unknown and English words remain neutral. These are lexical defaults, not contextual disambiguation.
- **Everyday typing:** Chinese punctuation, English, numbers and mixed input; repeat backspace, spacebar cursor gestures and candidate browsing.
- **Simple keyboard:** Sage light and dark themes, continuous 78%–124% height, two styles, haptics and previews.
- **Local input:** Input and learned frequencies stay on device. Password fields disable candidates, glosses, learning and clipboard history; sensitive system clips are excluded.

Missing local glosses stay blank. Dictionary senses and model translations can be inaccurate. The bounded sentence beam uses source frequencies and local context; complex semantics, nine-key ambiguities and English prediction still need real-device feedback.

## Install and try it

1. Download and install the [Qingyu v0.5.0 APK](https://github.com/sharbvane/qingyu-srf/releases/download/v0.5.0/Qingyu-0.5.0.apk), or get the checksum from its [GitHub Release](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.5.0). Earlier versions are listed under [all GitHub Releases](https://github.com/sharbvane/qingyu-srf/releases). Android 8.0 or newer is required.
2. Install and open Qingyu. Use **Enable Qingyu Input Method**, then **Switch to Qingyu**. Android requires these system settings steps.
3. In any text field, try `anzhuo`, `dangang` or `nohao`. Tap a Chinese candidate to enter Chinese only.

The current public release is **v0.5.0**. Download the APK, checksum and [validation record](docs/validation-v0.5.0.md) from this repository release. It uses an independent Release key with authenticated rotation. For the first v0.4 upgrade, install the supplied local APK over the existing app without uninstalling. See [signing and backup](docs/release-signing.md). Long-term physical-device use remains unverified.

Choose English, Japanese, French, German, Russian or Spanish under More → annotation language. Explicitly selecting a required non-English language for the first time starts its optional model download, with Wi-Fi required. Normal typing never downloads a model. Translation model management, also available in app settings, shows status and actual installed size and supports deletion. Extra languages reserve no model storage before downloading. Chinese and English keyboards remain available.

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
- AOSP PinyinIME through JNI, supplemented by indexed modern full-pinyin/initials candidates and bounded sentence composition; Chinese events run on one serial engine thread.
- Android-independent interfaces for candidate snapshots, input engines and translation providers live in `core/` to keep future platform work decoupled.
- A separate low-priority translation worker queries a local SQLite index with an in-memory cache. Lookup failures never block Chinese input.
- Candidate geometry depends on Chinese text; late English glosses do not change candidate width.
- The overall keyboard top reserves 100 dp in portrait and 92 dp in landscape, shared between idle candidates/navigation and the composing candidate area. Raw pinyin remains attached to its upper edge. The expanded grid scrolls vertically within the existing keyboard body. Lexical tag lookup shares the auxiliary worker; missing tags do not affect input.

See the [upstream and architecture research](docs/research.md).

## Offline and privacy

Input, candidates, translation inference and clipboard processing run on device. No remote text translation endpoint is called. The APK includes only Chinese and English dictionaries and local meanings. Broader sentence translation needs on-device models; Japanese, French, German, Russian and Spanish glosses additionally need their language models. First explicitly selecting a non-English annotation language starts a Wi-Fi model download; models can also be downloaded from Translation model management. Ordinary typing never initiates a model download.

The app has network permission. Google ML Kit can contact Google for models, configuration, compatibility and diagnostics, sending device/installation identifiers, language configuration, input/output size and performance metadata; it does not send input or output text. See [SDK provenance and privacy](third_party/mlkit/SOURCE.md). Explicit update checks read GitHub Releases metadata; download requests include no input or clipboard text. Clipboard history can be cleared or disabled and is not uploaded. No advertising is integrated.

On ARM64 devices using 16 KiB memory pages, model translation is currently disabled because of the official SDK binary's RELRO alignment. Normal input, local Chinese/English dictionary definitions and Chinese/English phrases remain available; settings show the limitation.

## Build from source

Windows, JDK 17 and PowerShell 7 are required. The setup script downloads the pinned Android SDK, NDK, CMake and Gradle into the ignored local `.tools/` directory:

```powershell
pwsh -File scripts/setup-toolchain.ps1
pwsh -File scripts/build.ps1 -Variant Release -Test
```

Build output goes to `releases/`. Local tools and signing files are excluded by `.gitignore`. Restore the existing signing materials on a new machine; never generate a replacement key. See [signing](docs/release-signing.md) and the [validation log](docs/validation-v0.5.0.md).

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

Each license's terms and compatibility scope continue to apply. The root GPL notice does not erase upstream rights. Trademarks, product names and logos are not transferred by the code license.

<p align="center"><sub>轻一点输入，久一点相遇。 · Type lightly. Learn naturally.</sub></p>
