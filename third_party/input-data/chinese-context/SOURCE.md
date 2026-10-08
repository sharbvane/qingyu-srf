# Chinese context statistics and real examples (v0.6)

The optional offline asset is `app/src/main/assets/input/chinese-context-v1.bin.gz`.
Android AAPT expands this source to `assets/input/chinese-context-v1.bin` in
the APK. The runtime uses the uncompressed parser; the package audit compares
every byte with the source gzip's 3,494,939-byte expanded data. Loader checks
cover equivalent gzip/uncompressed reads and malformed-input rejection.
`tools/build_chinese_context.py` creates it deterministically with Python's
standard library. This does not change the native decoder, modern lexical
SQLite database, or any user's v0.5 learning file.

## Sources and licenses

- [UD Chinese GSDSimp, r2.18](https://github.com/UniversalDependencies/UD_Chinese-GSDSimp/tree/7b61ed473f963e911788efdf1f478154bc1053e4),
  fixed commit `7b61ed473f963e911788efdf1f478154bc1053e4`,
  **CC BY-SA 4.0**, contributors Peng Qi and Koichi Yasuoka. This simplified
  treebank was converted from Chinese GSD using OpenCC and manual corrections.
  Its upstream metadata describes the genre as wiki. Only TRAIN's 3,997
  sentences / 98,614 tokens contribute sentence counts, UPOS transitions,
  predictions, and examples. DEV and TEST never contribute model statistics.
  Exact source SHA-256 values and transformations are in `manifest.json`.
  The upstream LICENSE and complete README are preserved beside this file;
  the APK includes the upstream license statement and link to its full text.
  The upstream README explicitly limits Google's removal of the former NC
  restriction to annotations; Google claims no ownership of underlying text.
  We preserve that caveat and the corpus's attribution/share-alike notice.
- The already pinned [Rime Ice / AOSP / CC-CEDICT lexical inputs](../../pinyinime/SOURCE.md)
  supply **word-internal** character evidence. This is not a new sentence
  corpus and does not fabricate usage examples. The v2 index's SHA-256 is
  recorded in `manifest.json`; its existing licenses remain in the APK.
- The already pinned MIT jieba input snapshot used by input-v3 supplies POS
  tags for corpus words and single-character heads/tails. Its exact SHA-256
  is in `manifest.json`; the existing jieba license is preserved in the APK.

## Transformations and runtime bounds

Counts use a uniform 10x weight for real training text, plus one logarithmic
contribution per unique lexical word. These apply to all words; no benchmark
phrase, target spelling, or manually selected modern term has a special weight.
Character history resets at punctuation/Latin boundaries. Bigrams with fewer
than two occurrences and trigrams with fewer than three are omitted globally.
Numeric sorted tables use unsigned delta-varint keys and gzip. The asset is
2,736,395 bytes; loaded primitive ngram arrays occupy about 12.3 MB. Loading
happens on the existing auxiliary worker and failure leaves the v0.5 input path
available. Typing runs binary-search lookups, bounded 2,048-word internal-score
and 4,096-entry conditional-score memoization;
it never parses corpus data, trains a model, or contacts a server.

Sentence edges retain the source lexical frequencies and durable personal
counts. Each path's actual prefix receives its own character/class likelihood.
Only legal complete phonetic prefixes are queried, with at most 160 indexed
queries, 24 word options per query, twelve paths per boundary, 8,192 path
expansions, and 24 composed Chinese characters. The native
decoder remains the fallback and still owns partial choices and user learning.
An already supported native sentence keeps priority when the corpus does not
provide a clear two-point improvement. Raw letters, correction suggestions,
selected prefixes, virtual selection IDs, and preview privacy stay separate.

Next-token tables contain complete observed tokens with at least three training
occurrences and conditional support of at least 0.08. The existing reviewed
everyday phrase pairs supplement sparse wiki coverage; arbitrary dictionary
prefix tails were removed. Personal next-word preferences require at least
three choices; one/two accidental choices do not add a prediction. Unknown
contexts and sentence-ending punctuation return no prediction.

## Real example boundary

Only exact complete TRAIN `# text` sentences of 10–72 characters are stored.
They are selected deterministically by length/source order, capped at 384,
and screened for identifiers, Latin text, digits, and broad sensitive domains.
The word index includes only noun/verb/adjective/adverb tokens of 2–6 Chinese
characters actually contained in that sentence. This produces 1,207 word
entries. There is no template-generated or model-generated Chinese example.
A word without a suitable source sentence returns an empty string. These are
corpus excerpts, not personalized statements; the UI labels their source as
UD Chinese GSDSimp, and translations are handled by the existing separate
translation provider. They carry the corpus's CC BY-SA attribution notice.

## Independent checks

`tools/build_chinese_context_checks.py` creates 37 explicit daily sentence
regressions (the three known v0.5 gaps plus 34 additional phrases) and the first
48 eligible multi-token DEV fragments in source order. Selection uses token
availability/length boundaries, never candidate rank. `heldout-manifest.json`
preserves each DEV sentence number, full text, and token list. Difficult heldout
cases remain in the assessment report even when they fail. Neither the daily
corpus nor DEV is a training input. Host measurements use the actual core beam
with exported indexed edges and exclude JNI/SQLite timings; final Android
quality checks query the real assets and validate preview, selection and raw
keystrokes. See [v0.6 validation](../../../docs/validation-v0.6.0.md) for measured
results and limits.
