# Qingyu v3 offline input data

SCOWL English dictionary and affix expansion: upstream LibreOffice en_US,
49,568 base entries. Upstream SCOWL permission notices are retained in
SCOWL-README.txt and embedded in the APK. The generated word list is modified.

Chinese digit/readings and reverse English glosses: CC-CEDICT by MDBG and
community contributors, CC BY-SA 4.0; see ../cedict. These portions of
input-v3.db are modified CC-CEDICT derivatives and retain CC BY-SA 4.0.

Chinese frequency ranking and lexical part-of-speech tags: jieba dict.txt,
Copyright 2013 Sun Junyi, MIT; its complete permission notice is retained
and embedded in the APK. Tags are joined only for exact Chinese dictionary
words. Unknown words and English words remain neutral; tags describe the
dictionary reading, not contextual disambiguation.

English frequency and bigram counts: original English public-domain works
by Lewis Carroll (1865), Jane Austen (1813), Arthur Conan Doyle (1892),
and L. Frank Baum (1900). Downloaded ebook identifiers and exact hashes in
manifest.json. Headers, licenses and book prose are not shipped in the APK;
the builder extracts aggregate occurrence counts, then adds original modern
phrases and weights. Public-domain status concerns original texts, not logos.
https://www.gutenberg.org/policy/license.html

Rebuild: python tools/build_input_dictionary.py. Saved snapshots in project
.cache/input-v2 are reused. Output hashes, counts and all editorial changes
are documented in manifest.json. Runtime requires no network.
