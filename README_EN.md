<p align="center">
  <img src="docs/images/qingyu-logo.svg" width="96" alt="Qingyu logo" />
</p>

<h1 align="center">Qingyu Input Method · 轻语</h1>
<p align="center">Type Chinese. Meet English along the way.</p>

<p align="center">
  <a href="README.md">中文</a> · English
</p>

<p align="center">
  <a href="https://github.com/sharbvane/qingyu-srf/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/sharbvane/qingyu-srf?label=release"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

**Qingyu is first a Chinese keyboard.** Type pinyin and choose Chinese as usual. A quiet English gloss appears above a candidate; tapping “开发” still enters only “开发”.

## Preview

| Light theme | Dark theme |
| --- | --- |
| ![Qingyu light keyboard with kaifa candidates and English glosses](docs/images/keyboard-light.png) | ![Qingyu dark keyboard with sheji candidates and English glosses](docs/images/keyboard-dark.png) |

Captured from a running Android 15 emulator.

## Features

- **Full pinyin:** AOSP PinyinIME native decoder, sentence and segment candidates, candidate paging and expansion.
- **Quiet English glosses:** A bundled CC-CEDICT dictionary annotates candidates asynchronously. Candidate taps commit Chinese only; annotations can be turned off.
- **Everyday typing:** Chinese punctuation, English, numbers and mixed input; repeat backspace, long-press numbers, spacebar cursor gestures and candidate browsing.
- **Simple keyboard:** Light and dark themes, keyboard height, haptic feedback and key preview settings.
- **Offline by design:** No network permission or cloud translation. Input and optional user frequency data stay on device. Password fields use direct input and disable candidates and learning.

Missing dictionary entries stay blank. Glosses are common dictionary senses, not contextual translations. The bundled AOSP lexicon is old, so newer words and complex sentence ranking need improvement.

## Install and try it

1. Download `Qingyu-0.1.0.apk` from [GitHub Releases](https://github.com/sharbvane/qingyu-srf/releases/latest). Android 8.0 or newer is required.
2. Install and open Qingyu. Use **Enable Qingyu Input Method**, then **Switch to Qingyu**. Android requires these system settings steps.
3. In any text field, type `kaifa`, `xiangmu` or `sheji`. Tap a Chinese candidate to enter Chinese only.

Version **v0.1.0** passed 17 automated system-input checks on an Android 15 / x86_64 emulator. This release uses a development signing key for community testing; a dedicated release key will be established before a production-stable release.

## Android support

| Item | Current support |
| --- | --- |
| Android | 8.0+ (API 26+); target SDK 35 |
| ABIs | arm64-v8a, armeabi-v7a and x86_64 |
| IME | Enable and select it in Android system settings |
| Validation | Android 15 x86_64 emulator; physical devices and OEM app compatibility still need testing |

## Architecture

- Android `InputMethodService` and an original Canvas keyboard UI.
- AOSP PinyinIME C++ decoder connected through JNI; Chinese input events run on one serial engine thread.
- Android-independent interfaces for candidate snapshots, input engines and translation providers live in `core/` to keep future platform work decoupled.
- A separate low-priority translation worker queries a local SQLite index with an in-memory cache. Lookup failures never block Chinese input.
- Candidate geometry depends on Chinese text; late English glosses do not change candidate width.

See the [upstream and architecture research](docs/research.md).

## Offline and privacy

Qingyu requests no network permission and uses no ads, analytics, cloud translation or telemetry. Pinyin and candidates are processed on device. Optional user frequency data is stored locally. Password input is excluded from Chinese candidates, glosses and learning.

## Build from source

Windows, JDK 17 and PowerShell 7 are required. The setup script downloads the pinned Android SDK, NDK, CMake and Gradle into the ignored local `.tools/` directory:

```powershell
pwsh -File scripts/setup-toolchain.ps1
pwsh -File scripts/build.ps1 -Variant Release -Test
```

Build output goes to `releases/`. Local tools and signing files are excluded by `.gitignore`. See the [validation log](docs/validation.md) for test scope and results.

## Roadmap

- [x] Installable Android full-pinyin MVP
- [x] Offline English candidate glosses and a display toggle
- [ ] Tune touch feel, app switching and power use on physical devices
- [ ] Evaluate a more modern Simplified Chinese lexicon and ranking with clear source licenses
- [ ] Review gloss quality and expand local dictionary coverage
- [ ] Design a separate iOS Keyboard Extension
- [ ] Evaluate Japanese, Korean and Spanish translation providers

## Contributing

Bug reports, dictionary-quality feedback and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md), include reproduction steps and the Android version, and report relevant build or test results. New Qingyu-authored code is offered under GPL-3.0-only. Third-party code, data and media remain under their respective licenses.

## License and attributions

**Qingyu-authored code is licensed under GNU GPL-3.0-only**; see [LICENSE](LICENSE). The repository also contains third-party material distributed with its source license and notices: AOSP PinyinIME under Apache-2.0, and an adapted CC-CEDICT dictionary under CC BY-SA 4.0. Preserve the upstream attribution and notices:

- [Third-party licenses and NOTICE](LICENSE-THIRD-PARTY.md)
- [AOSP PinyinIME provenance and changes](third_party/pinyinime/SOURCE.md)
- [CC-CEDICT data provenance and processing](third_party/cedict/README.md)

Each license's terms and compatibility scope continue to apply. The root GPL notice does not erase upstream rights. Trademarks, product names and logos are not transferred by the code license.

<p align="center"><sub>轻一点输入，久一点相遇。 · Type lightly. Learn naturally.</sub></p>
