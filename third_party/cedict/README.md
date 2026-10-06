# CC-CEDICT attribution

CC-CEDICT is maintained by MDBG and community contributors.
Referenced work: CEDICT, Copyright 1997, 1998 Paul Andrew Denisowski.

Source: https://www.mdbg.net/chinese/dictionary?page=cc-cedict
License: Creative Commons Attribution-ShareAlike 4.0 International
https://creativecommons.org/licenses/by-sa/4.0/

This project's `app/src/main/assets/translation/zh-en.db` is a modified
CC-CEDICT derivative under the same CC BY-SA 4.0 license. The conversion
script and complete modifications (editorial overrides included) are in
`tools/build_dictionary.py` and `manifest.json`. Original downloaded
header retained in SOURCE_HEADER.txt. License is taken from this snapshot's
own header; some older web pages still describe earlier 3.0 snapshots.

Glosses describe dictionary senses, not contextual sentence translations.
Missing words deliberately have no annotation.

Version 0.2 also ships `translation/zh-en-details.db` under CC BY-SA 4.0.
It retains complete original English senses and pronunciations, indexed by
simplified headword, merging duplicate senses and alternate pronunciations.
`tools/build_dictionary_details.py` reproduces it; `details-manifest.json`
records its source and hash. Detailed senses are displayed for reading and
are never committed as a translated candidate.

`translation/phrase-gloss.tsv` is separately authored Qingyu data. Its
Japanese and French phrases are not derived from CC-CEDICT.
