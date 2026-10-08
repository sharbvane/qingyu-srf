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

## v0.6.6: ARM64 / 16 KiB compatibility gate

Google Maven metadata, checked 2026-10-08, still lists 17.0.3 as latest;
the official August 7, 2024 release notes explicitly include translation
17.0.3 in the Android SDK update for 16 KiB page sizes:
https://dl.google.com/dl/android/maven2/com/google/mlkit/translate/maven-metadata.xml
https://developers.google.com/ml-kit/release-notes#august_7_2024

The Android guide warns that an unaligned RELRO end can protect writable
data and crash. Merely meeting LOAD and APK ZIP alignment does not rule
that out:
https://developer.android.com/guide/practices/page-sizes#relro

The former Qingyu guard rejected every 16 KiB ARM64 process solely because
the official library's RELRO end is `0xe75000`. Inspection of this exact
binary shows the RELRO covers an entire writable LOAD
`[0xdc8000, 0xe75000)`. The next writable LOAD starts at `0xe789f0`, after
the rounded protection end `0xe78000`. The rounded tail therefore contains
padding, not writable data. AOSP's `phdr_table_protect_gnu_relro` rounds the
range to pages; `_extend_gnu_relro_prot_end` distinguishes a whole LOAD
from a partial writable LOAD. This explains why the generic end-modulo
check was too broad for this layout. It is a structural inference from the
official loader and inspected library, not an ARM64 runtime measurement:
https://android.googlesource.com/platform/bionic/+/361ba86734fb2821a6adcfdf775db8abd04e0de0/linker/linker_phdr.cpp

Inspected official AAR SHA-256:
`b6194f7b42034309cf8299784b2d5d70a82a2e9287fbe1650dea4d1f3ad1fe55`.
Its untouched ARM64 library SHA-256:
`35b3d0366291347e3f25e80e809ee398d716aea49cd03278007fedd526a1496f`.

Qingyu now checks the packaged library's bounded ELF program headers on
the translation worker before any model operation on non-4 KiB devices.
It accepts only 4 / 16 KiB page sizes, validates LOAD alignment and
file/address congruence, and rejects any rounded RELRO range that covers
writable bytes outside that RELRO or executable content. Unknown,
truncated or incompatible binaries keep local input/glosses usable without
loading model JNI. The check reads at most 16 KiB from the app's own
base/split APK; it does not change ELF bytes, reduce RELRO protection,
request page-compat mode, or send typed text to a server. APK auditing
independently checks the same segment safety and records exact library
hashes. Fixtures cover safe padding, unsafe writable overlap, partial
RELRO, 4 KiB LOAD alignment, executable overlap and malformed headers.

Downloads, model hash validation, progress, retry, optional language storage
and asynchronous inference keep their existing SDK paths. Real translation
checks and environment details belong in this version's validation report;
an ARM64 physical device has not been tested here.
