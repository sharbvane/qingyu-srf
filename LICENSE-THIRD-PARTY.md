# Third-party licenses and attributions

Qingyu-authored source code is licensed under GNU GPL-3.0-only. The APK also bundles the third-party material below. Its original notices and license terms are retained; the root license does not replace them.

| Component | Where used | Source license | Required notices |
| --- | --- | --- | --- |
| Android Open Source Project PinyinIME decoder and pinyin dictionary | `app/src/main/cpp/aosp/`, `app/src/main/assets/pinyin/` | Apache License 2.0 | Upstream source headers and `third_party/pinyinime/NOTICE` |
| CC-CEDICT English dictionary, adapted for this application | `app/src/main/assets/translation/zh-en.db` | CC BY-SA 4.0; the GPLv3 compatibility path applies to adaptations incorporated into GPLv3 software | Attribution, source snapshot header, modifications and provenance in `third_party/cedict/` |
| CC-CEDICT complete definitions, reversed English glosses and nine-key readings | `zh-en-details.db`, `input-v2.db` | CC BY-SA 4.0 | Source, adaptation manifests and original header retained |
| SCOWL / Hunspell American English words | `assets/input/english-words.tsv` | Original permissive source notices | `assets/licenses/SCOWL-README.txt`, `third_party/input-data/` |
| jieba frequency data | Nine-key ranking | MIT | `assets/licenses/jieba-LICENSE.txt` |
| Four original public-domain literary works | Aggregated English frequency and bigram counts | Public-domain original prose | URLs, authors, years and snapshot hashes in input-data manifest; no Gutenberg headers bundled |
| Google ML Kit Translation SDK and downloaded models | Optional on-device translation | Google ML Kit / SDK terms | `third_party/mlkit/SOURCE.md`; these components are not Qingyu-authored GPL content |
| Official Google Translate attribution badges | Next to model output | Google brand attribution guidelines | Unmodified official images, source and hashes in ML Kit provenance |

The Apache License 2.0 is compatible with GPLv3, while Apache copyright and NOTICE requirements still apply. CC BY-SA 4.0 has a one-way compatibility path for adaptations incorporated into GPLv3 software; attribution and other applicable BY-SA conditions remain relevant. See the license stewards' guidance:

- [Apache License 2.0 and GPL compatibility](https://www.apache.org/licenses/GPL-compatibility.html)
- [Creative Commons BY-SA compatibility with GPLv3](https://creativecommons.org/compatible-licenses/)
- [GNU General Public License version 3](https://www.gnu.org/licenses/gpl-3.0.html)

No third-party source is represented as Qingyu-authored. Additional provenance, pinned source revisions, checksums, data changes and bundled license copies are recorded beside the corresponding components and in [`assets_sources.md`](assets_sources.md).
