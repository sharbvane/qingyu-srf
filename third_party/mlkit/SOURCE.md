# ML Kit on-device translation

Dependency: `com.google.mlkit:translate:17.0.3`, verified against the official
Android guide on 2026-10-08; attribution rechecked on 2026-10-07:
https://developers.google.com/ml-kit/language/translation/android

The model translates complete input on-device. Qingyu never calls a remote
text translation endpoint. An explicit model-download action or the user's
first explicit selection of a required non-English annotation language calls
`downloadModelIfNeeded`. Wi-Fi is the default; the model manager offers an
explicit choice to allow mobile data. Missing connectivity, server failures,
storage failures and model validation failures show a readable reason and a
retry action. A non-Wi-Fi request without permission fails promptly instead
of remaining in an ambiguous waiting state. Model existence checks do not
download files. Candidate generation and editor operations do not wait for
dictionary lookup, model checks, downloads or inference.

Supported input sources are Chinese and English. Chinese annotations select
English, Japanese, French, German, Russian or Spanish; English annotations
select Chinese. The APK bundles only Chinese/English dictionaries and local
phrase meanings, not these optional model weights. Extra languages occupy no
model storage before downloading. The model-management page reports download
state, actual transfer bytes when available, installed file size and deletion. English is the
built-in pivot. Non-English translation additionally requires that language's
model and may lose nuance because it routes through English.
Model output is intended for casual translation, not guaranteed accuracy.
https://developers.google.com/ml-kit/language/translation

## Download observation and lifecycle

ML Kit's public `Task` does not expose download byte progress. The pinned
17.0.3 translation downloader calls Android `DownloadManager` and persists
its request ID via common 18.11.0's `SharedPrefManager`. Qingyu reads only
`com.google.mlkit.internal` / `downloading_model_id_` followed by
`TranslateRemoteModel.getUniqueModelNameForPersist()`. A public
`DownloadManager.Query` restricted to those app-owned IDs supplies real
`COLUMN_BYTES_DOWNLOADED_SO_FAR`, `COLUMN_TOTAL_SIZE_BYTES`, state and pause
or failure reason. This does not change SDK requests, model contents, hashes
or install behavior. No arbitrary model URL, mirror or text translation
endpoint is used. Unknown IDs or totals use an indeterminate indicator;
there is no synthetic progress. The byte total describes requests currently
known to the SDK, rather than guessing the total for an entire language pair.
The task's successful completion alone marks the pair ready after validation.

Verified against the cached 17.0.3 `internal.zzh` downloader and 18.11.0
`SharedPrefManager` bytecode. This is a version-specific, read-only observer;
if a future SDK changes the preference format, it falls back to an
indeterminate status without disabling downloads. Model checks and queries
run on the translation worker. Visible model management polls download
status every 800 ms and checks idle state every four seconds, so activity
recreation can reconnect to SDK-owned downloads. Polling stops when the
page is left, paused or destroyed.

https://developer.android.com/reference/android/app/DownloadManager

Model hosting still requires a network able to reach Google's official
endpoints. Qingyu cannot make a blocked endpoint reachable through retries;
it reports the failure rather than claiming that a model was installed.

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
In v0.5 the ordinary candidate row and expanded grid display only local
lexicon/phrase meanings. They do not invoke the model, show its results,
render a brand strip or reserve a 16 dp attribution slot. This preserves the
user's requested clear input surface without concealing the source of model
output. Missing local meanings remain blank. A long press opens full details;
an upward gesture commits a local translation directly, but a model result
opens the attributed details instead and requires the existing commit action.
Google model translations, badges and attribution remain in that details view;
model management and application help retain the source/privacy information.
The existing keyboard's overall fixed geometry is preserved.

The v0.6 request changes that earlier local-only policy: installed models may
fill missing visible annotations asynchronously, and upward gestures may
commit the selected language's model translation. Local lexicon/phrase
results remain first. The user's subsequent v0.6 instruction removes all
Google branding from the compact candidate row and expanded candidate list,
while retaining the real model annotations and direct upward commits. Model
source notes, badges and attributed actions remain in long-press details and
model management. This is the current UI scope, not a claim that attribution
only in details satisfies the official adjacent-output requirement above;
distribution must account for that difference. English dictionary descriptions
are offered only for Chinese-to-English details; other selected languages
use their own model instead of falling back to an English explanation.
Compact dictionary translations commit one sense without part-of-speech
prefixes; full dictionary definitions remain in the detail view. Inference
is bounded at eight active requests; saturated callers retry at most three
times, 350 ms apart, with model-generation and closed-instance guards.

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
