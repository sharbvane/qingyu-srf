# ML Kit on-device translation

Dependency: `com.google.mlkit:translate:17.0.3`, verified against the official
Android guide on 2026-10-05:
https://developers.google.com/ml-kit/language/translation/android

The model translates complete input on-device. Qingyu never calls a remote
text translation endpoint. An explicit model-download action or the user's
first explicit selection of a required non-English annotation language calls
`downloadModelIfNeeded`, with Wi-Fi required. Model existence checks do not
download files. Candidate generation and editor operations do not wait for
dictionary lookup, model checks, downloads or inference.

Supported input sources are Chinese and English. Chinese annotations select
English, Japanese, French, German, Russian or Spanish; English annotations
select Chinese. The APK bundles only Chinese/English dictionaries and local
phrase meanings, not these optional model weights. Extra languages occupy no
model storage before downloading. The model-management page reports download
state and actual installed file size and supports deletion. English is the
built-in pivot. Non-English translation additionally requires that language's
model and may lose nuance because it routes through English.
Model output is intended for casual translation, not guaranteed accuracy.
https://developers.google.com/ml-kit/language/translation

## Privacy

ML Kit processes input and output locally and does not send those texts to
Google. The SDK can contact Google for models, updates, accelerator
compatibility, remote configuration and diagnostics. It sends metadata such
as device/app information, installation identifiers, language configuration,
input/output size, latency and events/error codes. These SDK requests are
independent of Qingyu's explicit model-download or language-selection action.
Qingyu does not log
typed text or attach input/clipboard text to download requests.

https://developers.google.com/ml-kit/terms
https://developers.google.com/ml-kit/android-data-disclosure

## Attribution

ML Kit's translation guidelines require the applicable Cloud Translation
attribution, including the official badge beside model translation results,
Google attribution for actions, application/help links and a translation
accuracy disclaimer. Local dictionary and authored phrase translations are
labelled separately and are not attributed to Google.

https://developers.google.com/ml-kit/language/translation/translation-terms
https://docs.cloud.google.com/translate/attribution

Official badge source (no redraw or brand changes):
https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip

Retrieved successfully with Java 17, HTTP/1.1 and TLS 1.2 on 2026-10-05.
ZIP SHA-256:
`1cf5975466881127a227d4c0510518a83542eacff57d0ac5240430263035ee1b`.
Two unmodified original source PNGs are retained in `res/drawable-nodpi`:

- `google_translate_badge.png`: `png/color-regular@3x.png`, 528×48,
  SHA-256 `44aab849963bc32493c1b2ff4ed9d47aea8d16cdfa12d3aaebef22feb2ba2ca9`.
- `google_translate_badge_dark.png`: `png/white-regular@3x.png`, 528×48,
  SHA-256 `f5a888a335c107b6cc106e59804d08b5feb35b3755188b17ffc5e577298e6f1e`.

Display at the official regular badge ratio, typically 176×16 dp, without
tint, cropping, brand redraw or distortion. These are Google brand assets
used for required attribution, not Qingyu original or GPL licensed imagery.
The v0.4 keyboard top reserves 100 dp in portrait and 92 dp in landscape,
shared between idle candidates/navigation and composing candidates, including
fixed model-attribution space. Raw pinyin stays attached to its upper edge;
the expanded candidate grid scrolls vertically within the keyboard body and
retains its attribution.

The source PNG files retain the exact official bytes and SHA-256 values
above. Release packaging applies AAPT lossless PNG encoding optimization,
so the packaged file names and encoded-byte hashes can differ. The final
package audit compares decoded RGBA pixels and the original 528×48
dimensions; both remain identical to the official source graphics. It
records source and packaged hashes separately in the version's
`releases/qa/<version>/release-package.json`.

## 16 KiB ARM64 device limitation

The official 17.0.3 AAR's ARM64 `libtranslate_jni.so` has a GNU_RELRO end
at `0xe75000`, which is not aligned to 16 KiB. Google Maven metadata still
lists 17.0.3 as the newest release on 2026-10-05. Although older SDK release
notes claim 16 KiB support, the current Android compatibility guide also
requires a 16 KiB-aligned RELRO end:
https://developer.android.com/guide/practices/page-sizes#relro

Qingyu does not modify the proprietary binary. On a 64-bit ARM process
using pages larger than 4 KiB, it reports the model as unsupported and
does not instantiate a Translator or download its models. Chinese/English
input, local Chinese/English dictionary glosses, Chinese/English phrases and
complete dictionary definitions remain available. Revisit this guard when
an official fixed SDK is available; no affected physical device was tested.
