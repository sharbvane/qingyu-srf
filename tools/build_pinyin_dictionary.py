"""Prepare an updated AOSP lexicon from pinned, licensed source snapshots.

Native compilation is performed by build-pinyin-model.ps1. No packages beyond
Python's standard library, runtime queries, handwritten missing-word patches,
new runtime engine or network-on-keystroke path are needed.
"""
from collections import Counter
from pathlib import Path
import argparse
import hashlib
import json
import re
import gzip
import sqlite3
import struct
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/pinyin-v2'
OUTPUT = ROOT / '.tools/pinyin-model'
NOTICE = ROOT / 'third_party/pinyinime'
REV = 'da1fbe602e38f26db846fa10120ee64c2b0324c0'
URL = f'https://raw.githubusercontent.com/iDvel/rime-ice/{REV}/cn_dicts/base.dict.yaml'
BASE_HASH = '0418d5103d8e40c8fff4b686a873ad03e9836cd0419ecae0b7e1e2526312dba0'
OLD_REV = '05cc8f8f2465553bc6d19b2ede24073d079eeed7'
OLD_PATH = ROOT / f'.tools/engine-source/PinyinIME-{OLD_REV}/jni/data/rawdict_utf16_65105_freq.txt'
OLD_HASH = '408700f28a56091fa07f3b849a0f134fbfc71e6b2ae9b3f52973a5b076f599ff'
MAX_READINGS = 235000  # AOSP compiler allocates 240000 lemmas; leave headroom.


def checked(path, expected):
    content = path.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != expected:
        raise ValueError(f'{path.name}: expected {expected}, received {actual}')
    return content


def model_bounds(content):
    size, count = struct.unpack_from('<II', content)
    assert size == 8 and count < 65535
    spellings = [content[13 + i * size:13 + (i + 1) * size - 1].split(b'\0')[0] for i in range(count)]
    offset = 13 + size * count
    character_readings = struct.unpack_from('<I', content, offset)[0]
    positions = struct.unpack_from('<9I', content, offset + 4)
    lemma_ids = struct.unpack_from('<9I', content, offset + 40)
    assert character_readings <= 65535 and lemma_ids[-1] < 240000
    assert all(a <= b for a, b in zip(positions, positions[1:]))
    stored_readings = sum((positions[i + 1] - positions[i]) // (i + 1) for i in range(8))
    return spellings, {'full_spelling_count': count, 'character_reading_index_items': character_readings,
                       'lemma_readings': stored_readings, 'system_end_id_exclusive': lemma_ids[-1], 'max_word_characters': 8}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--record-output', action='store_true')
    args = parser.parse_args()
    for directory in (CACHE, OUTPUT, NOTICE):
        directory.mkdir(parents=True, exist_ok=True)
    base = CACHE / 'rime-ice-base.dict.yaml'
    if not base.exists():
        with urllib.request.urlopen(urllib.request.Request(URL, headers={'User-Agent': 'Qingyu-lexicon-builder/0.5'}), timeout=60) as response:
            base.write_bytes(response.read())
    text = checked(base, BASE_HASH).decode('utf-8-sig')
    old = checked(OLD_PATH, OLD_HASH).decode('utf-16').splitlines()
    legacy = {}
    for line in old:
        fields = line.split()
        legacy[(fields[0], tuple(fields[3:]))] = float(fields[1])
    old_spellings = {syllable for _, reading in legacy for syllable in reading}
    entries = dict(legacy)
    modern = {}
    source_rows = 0
    for line in text.splitlines():
        fields = line.split('\t')
        if len(fields) != 3 or not fields[2].isdigit():
            continue
        source_rows += 1
        word, spelling, frequency = fields
        # Keep the complete legacy spelling alphabet and therefore its sorted
        # numeric IDs; existing user-pinyin.dat remains readable after upgrade.
        reading = tuple({'nve': 'nue', 'lve': 'lue'}.get(s, s) for s in spelling.split())
        if not re.fullmatch(r'[\u3400-\u9fff]{2,8}', word) or len(word) != len(reading) or not all(s in old_spellings for s in reading):
            continue
        key = word, reading
        modern[key] = max(modern.get(key, 0), int(frequency))
    # Prioritize coverage of everyday two-syllable words, then upstream word
    # frequency. This is a whole-corpus rule, independent of regression cases.
    ranked = sorted(modern, key=lambda key: (-modern[key] * (3 if len(key[0]) == 2 else 1), key))
    for key in ranked:
        if key not in entries and len(entries) >= MAX_READINGS:
            continue
        # Modern upstream weights replace obsolete corpus counts; the legacy
        # fallback retains a smaller prior for names/rare words not in base.
        entries[key] = max(1, modern[key])
    for key in legacy:
        if key not in modern:
            entries[key] = max(1, legacy[key] * .15)
    # Every reading of one written word shares its lexical prior. AOSP's
    # unigram model stores one frequency per word, not one per pronunciation.
    word_weights = {}
    for (word, _), frequency in entries.items():
        word_weights[word] = max(word_weights.get(word, 0), frequency)
    entries = {key: word_weights[key[0]] for key in entries}
    assert len(entries) <= MAX_READINGS < 240000
    assert {s for _, reading in entries for s in reading} == old_spellings
    hanzi_readings = {(char, reading[i]) for (word, reading) in entries for i, char in enumerate(word)}
    assert len(hanzi_readings) + 1 <= 65535
    # Validate the actual 8-bit per-prefix node counts and 24-bit offsets before
    # the native builder casts them. No silent truncation with a larger corpus.
    children = {}
    homophones = Counter()
    prefixes = set()
    for word, reading in entries:
        homophones[reading] += 1
        for size, syllable in enumerate(reading):
            prefix = reading[:size]
            children.setdefault(prefix, set()).add(syllable)
            prefixes.add(reading[:size + 1])
    assert all(len(values) <= (65535 if len(prefix) < 2 else 255) for prefix, values in children.items())
    assert all(count <= (65535 if len(reading) == 1 else 255) for reading, count in homophones.items())
    assert len(prefixes) < 0x1000000 and len(entries) < 0x1000000
    raw = '\n'.join(f'{word} {frequency:.8f} 0 {" ".join(reading)}' for (word, reading), frequency in sorted(entries.items())) + '\n'
    (OUTPUT / 'raw-utf16.txt').write_text(raw, encoding='utf-16')
    (OUTPUT / 'valid-hanzi-utf16.txt').write_text(''.join(sorted({char for word, _ in entries for char in word})), encoding='utf-16')
    # The indexed companion retains explicit word readings. Unlike AOSP's
    # candidate collector it can union multiple legal syllable segmentations.
    lexical=dict(entries)
    ext_url=f'https://raw.githubusercontent.com/iDvel/rime-ice/{REV}/cn_dicts/ext.dict.yaml'
    ext_hash='8e49a0294c0139e63c815cc33a2d438cc144eb7889297d30cec899a368697c06'
    ext=CACHE/'rime-ice-ext.dict.yaml'
    if not ext.exists():
        with urllib.request.urlopen(ext_url,timeout=60) as response:ext.write_bytes(response.read())
    ext_text=checked(ext,ext_hash).decode('utf-8-sig')
    for line in ext_text.splitlines():
        fields=line.split('\t')
        if len(fields)!=3 or not fields[2].isdigit():continue
        word, spelling, frequency=fields
        reading=tuple({'nve':'nue','lve':'lue'}.get(s,s) for s in spelling.split())
        if re.fullmatch(r'[\u3400-\u9fff]{2,8}',word) and len(word)==len(reading) and all(s in old_spellings for s in reading):
            key=word,reading;lexical[key]=max(lexical.get(key,0),int(frequency),1)
    cedict_hash=hashlib.sha256((ROOT/'.cache/cedict.gz').read_bytes()).hexdigest()
    with gzip.open(ROOT/'.cache/cedict.gz','rt',encoding='utf-8') as cedict:
        for line in cedict:
            match=re.match(r'^(\S+) (\S+) \[(.*?)\]',line)
            if not match:continue
            word=match[2]
            reading=tuple({'nve':'nue','lve':'lue'}.get(s,s) for s in re.sub('[1-5]','',match[3].lower()).replace('u:','v').split())
            if re.fullmatch(r'[\u3400-\u9fff]{2,8}',word) and len(word)==len(reading) and all(s in old_spellings for s in reading):
                lexical.setdefault((word,reading),1)
    asset=ROOT/'app/src/main/assets/pinyin/lexicon-v2.db'
    asset.unlink(missing_ok=True)
    with sqlite3.connect(asset) as database:
        database.execute('PRAGMA journal_mode=OFF')
        database.execute('PRAGMA user_version=2')
        # A compact rowid table keeps each secondary index's locator numeric;
        # WITHOUT ROWID would repeat raw/text/pinyin in all three indexes.
        database.execute('CREATE TABLE words(raw TEXT,initials TEXT,text TEXT,pinyin TEXT,weight INTEGER)')
        database.executemany('INSERT INTO words VALUES(?,?,?,?,?)',((''.join(reading),''.join(s[0] for s in reading),word,"'".join(reading),max(1,int(weight))) for (word,reading),weight in sorted(lexical.items())))
        database.execute('CREATE INDEX raw_rank ON words(raw,weight DESC)')
        database.execute('CREATE INDEX initials_rank ON words(initials,weight DESC)')
        database.execute('CREATE INDEX text_rank ON words(text,weight DESC)')
        database.commit();database.execute('VACUUM')
    (NOTICE/'RIME-ICE-EXT-SOURCE-HEADER.txt').write_text('\n'.join(ext_text.splitlines()[:ext_text.splitlines().index('---')])+'\n',encoding='utf-8')
    license_file=CACHE/'Rime-ice-LICENSE'
    if not license_file.exists():
        with urllib.request.urlopen(f'https://raw.githubusercontent.com/iDvel/rime-ice/{REV}/LICENSE',timeout=60) as response:license_file.write_bytes(response.read())
    license_bytes=checked(license_file,'3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986')
    (NOTICE/'Rime-ice-LICENSE').write_bytes(license_bytes)
    (ROOT/'app/src/main/assets/licenses/Rime-ice-LICENSE').write_bytes(license_bytes)
    header = '\n'.join(text.splitlines()[:text.splitlines().index('---')]) + '\n'
    (NOTICE / 'RIME-ICE-SOURCE-HEADER.txt').write_text(header, encoding='utf-8')
    manifest = {
        'format': 'AOSP PinyinIME binary model v2',
        'sources': {
            'rime_ice_base': {'url': URL, 'revision': REV, 'sha256': BASE_HASH, 'license': 'GPL-3.0-only', 'readings': source_rows},
            'aosp_raw': {'revision': OLD_REV, 'sha256': OLD_HASH, 'license': 'Apache-2.0', 'readings': len(legacy)},
            'rime_ice_ext': {'url': ext_url, 'revision': REV, 'sha256': ext_hash, 'license': 'GPL-3.0-only'},
            'cedict_companion': {'sha256_gzip':cedict_hash,'license':'CC BY-SA 4.0','provenance':'third_party/cedict/manifest.json'}},
        'readings': len(entries), 'unique_words': len(word_weights), 'modern_readings_added': len(set(entries) - set(legacy)),
        'lengths': dict(sorted(Counter(len(word) for word, _ in entries).items())),
        'legacy_spelling_count': len(old_spellings), 'legacy_spelling_ids_retained': True,
        'hanzi_reading_index_items': len(hanzi_readings) + 1, 'trie_prefix_nodes': len(prefixes),
        'max_deep_children': max(len(v) for p, v in children.items() if len(p) >= 2),
        'max_multi_syllable_homophones': max(n for p, n in homophones.items() if len(p) >= 2),
        'transformations': 'Retain all old legal readings; select up to 235000 total readings by upstream frequency with 3x admission priority for two-character words; modern weights replace old counts, 0.15 legacy-only fallback prior; shared maximum word weight across polyphonic readings; lve/nve map to legacy lue/nue; reject non-BMP/non-Chinese, length >8, unsupported syllables and mismatched character/syllable counts; no benchmark-specific weights or words; builder removes obsolete >4-character and <60-frequency filters.',
        'raw_source': {'bytes': (OUTPUT / 'raw-utf16.txt').stat().st_size, 'sha256': hashlib.sha256((OUTPUT / 'raw-utf16.txt').read_bytes()).hexdigest()}}
    manifest['lexicon']={'path':'app/src/main/assets/pinyin/lexicon-v2.db','readings':len(lexical),'bytes':asset.stat().st_size,'sha256':hashlib.sha256(asset.read_bytes()).hexdigest(),'license':'GPL-3.0-only; CEDICT-derived companion entries additionally retain CC BY-SA 4.0 attribution','indexes':'raw_rank, initials_rank, text_rank'}
    if args.record_output:
        asset = ROOT / 'app/src/main/assets/pinyin/dict_pinyin.dat'
        model = asset.read_bytes()
        old_model = checked(OLD_PATH.parents[2] / 'res/raw/dict_pinyin.dat', '6bf0bbde4e3134cce38d08524a9f4dc1af40435243c8e36b38eb68d1e14462b2')
        spellings, bounds = model_bounds(model)
        assert spellings == model_bounds(old_model)[0], 'Serialized full-spelling IDs changed; existing user dictionary cannot be reused'
        manifest['output'] = {'path': 'app/src/main/assets/pinyin/dict_pinyin.dat', 'bytes': len(model), 'sha256': hashlib.sha256(model).hexdigest(),
                              'serialized_bounds': bounds, 'actual_spelling_id_order_matches_v0_4': True}
    (NOTICE / 'model-v2-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=True))


if __name__ == '__main__':
    main()
