"""Build the small offline Chinese context model. Standard library only.

Only UD GSDSimp r2.18 TRAIN is used for sentence/part-of-speech evidence.
Dev/test are evaluation inputs, never model inputs. The already pinned v2
lexicon contributes word-internal character evidence, not invented sentences.
The binary is gzip-compressed for APK size and uses sorted numeric tables at
runtime. Rebuilding does not rewrite any personal dictionary or pinyin model.
"""
from collections import Counter, defaultdict
from pathlib import Path
import gzip
import hashlib
import json
import math
import re
import sqlite3
import struct
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/chinese-context-v1'
NOTICE = ROOT / 'third_party/input-data/chinese-context'
ASSET = ROOT / 'app/src/main/assets/input/chinese-context-v1.bin.gz'
PIN = '7b61ed473f963e911788efdf1f478154bc1053e4'
BASE = f'https://raw.githubusercontent.com/UniversalDependencies/UD_Chinese-GSDSimp/{PIN}/'
TAGS = ['X', 'NOUN', 'PROPN', 'VERB', 'AUX', 'ADJ', 'ADV', 'PRON', 'ADP', 'SCONJ', 'CCONJ', 'PART', 'NUM', 'DET', 'INTJ', 'PUNCT', 'SYM']
CHINESE = re.compile(r'[\u3400-\u9fff]+\Z')
sources = {}
EXPECTED_SHA256 = {
    'zh_gsdsimp-ud-train.conllu': '956636fe612a1166e8b19e7413fee2e73d68231aca2f0455be2c616b947d629d',
    'zh_gsdsimp-ud-dev.conllu': 'd03f1eeb93b16071bfbbe6c76b971554be87c9a2307b3f3a820dd7c07f73fb63',
    'zh_gsdsimp-ud-test.conllu': '3af8046a6f32477b4d5cf3dd06bbf38682a380fe77aade3f68de97e51ab94900',
    'LICENSE.txt': '899b1804a12ebc090b96339614eede1b64b686721b650a71430b55b5235f7f79',
    'README.md': '02287bdf80282151d8ca7ef3c3f7a3c3b98609f7266f145e3e3dd0a05693abd3',
}


def fetch(name):
    target = CACHE / name
    if not target.exists():
        request = urllib.request.Request(BASE + name, headers={'User-Agent': 'Qingyu-context-builder/0.6'})
        with urllib.request.urlopen(request, timeout=45) as response:
            target.write_bytes(response.read())
    content = target.read_bytes()
    checksum = hashlib.sha256(content).hexdigest()
    if checksum != EXPECTED_SHA256[name]:
        raise RuntimeError(f'Pinned UD source hash mismatch: {target}')
    sources[name] = {'url': BASE + name, 'sha256': checksum, 'bytes': len(content)}
    return content.decode('utf-8-sig')


def sentences(content):
    text, tokens = '', []
    for line in content.splitlines() + ['']:
        if line.startswith('# text = '):
            text = line[9:]
        elif not line:
            if text:
                yield text, tokens
            text, tokens = '', []
        elif not line.startswith('#'):
            fields = line.split('\t')
            if fields[0].isdigit():
                tokens.append((fields[1], fields[3]))


def main():
    for directory in [CACHE, NOTICE, ASSET.parent]:
        directory.mkdir(parents=True, exist_ok=True)
    train = list(sentences(fetch('zh_gsdsimp-ud-train.conllu')))
    license_text, readme = fetch('LICENSE.txt'), fetch('README.md')
    (NOTICE / 'UD-GSDSimp-LICENSE.txt').write_text(license_text, encoding='utf-8')
    (NOTICE / 'UD-GSDSimp-README.md').write_text(readme, encoding='utf-8')
    (ROOT / 'app/src/main/assets/licenses/UD-GSDSimp-LICENSE.txt').write_text(license_text, encoding='utf-8')
    uni, bi, tri = Counter(), Counter(), Counter()
    pos = defaultdict(Counter)
    transitions = Counter()
    following = defaultdict(Counter)
    continuation = defaultdict(Counter)

    def characters(text, weight):
        # A punctuation/Latin boundary resets the character history.
        for run in re.findall(r'[\u3400-\u9fff]+', text):
            for i, char in enumerate(run):
                uni[char] += weight
                if i:
                    bi[run[i-1:i+1]] += weight
                if i > 1:
                    tri[run[i-2:i+1]] += weight

    for text, tokens in train:
        characters(text, 10)
        for word, tag in tokens:
            if CHINESE.fullmatch(word) and len(word) <= 12:
                pos[word][tag] += 1
        for (word, tag), (next_word, next_tag) in zip(tokens, tokens[1:]):
            transitions[tag, next_tag] += 1
            if CHINESE.fullmatch(word):
                following[word][next_tag] += 1
        for index, (word, tag) in enumerate(tokens):
            if index == 0 or not CHINESE.fullmatch(word) or len(word) > 6:
                continue
            # Only complete observed tokens; never arbitrary dictionary tails.
            history = ''
            for previous, _ in reversed(tokens[max(0, index-2):index]):
                if not CHINESE.fullmatch(previous):
                    break
                history = previous + history
                if len(history) > 6:
                    break
                continuation[history][word] += 1

    lexicon = ROOT / 'app/src/main/assets/pinyin/lexicon-v2.db'
    with sqlite3.connect(lexicon) as db:
        for text, weight in db.execute('SELECT text,MAX(weight) FROM words GROUP BY text'):
            # Each spelling is counted once per word. Log weights prevent the
            # large external source scale from swallowing the real corpus.
            characters(text, 1 + int(min(8, math.log1p(max(1, weight)) / 2)))
    jieba = ROOT / '.cache/input-v2/jieba-dict.txt'
    if not jieba.is_file():
        raise RuntimeError('Pinned input-v3 jieba snapshot is required; run its existing builder first')
    jieba_tags = {'n': 'NOUN', 'v': 'VERB', 'a': 'ADJ', 'b': 'ADJ', 'd': 'ADV', 'r': 'PRON',
                  'p': 'ADP', 'u': 'PART', 'y': 'PART', 'm': 'NUM', 'q': 'NUM', 'c': 'SCONJ', 'e': 'INTJ'}
    # Preserve the existing MIT jieba lexical tags, but keep the map small:
    # corpus words plus single-character heads/tails for unseen compounds.
    for line in jieba.read_text(encoding='utf-8').splitlines():
        word, frequency, tag = line.split()
        mapped = jieba_tags.get(tag[0])
        if mapped and (len(word) == 1 or word in pos):
            pos[word] = Counter({mapped: int(frequency)})
    pos = {word: TAGS.index(counts.most_common(1)[0][0]) for word, counts in pos.items() if counts.most_common(1)[0][0] in TAGS}

    # Broad support cutoffs, identical for every word; no test-case exceptions.
    bi = {word: count for word, count in bi.items() if count >= 2}
    tri = {word: count for word, count in tri.items() if count >= 3}
    # Corpus next-token evidence remains sparse. A high support/probability gate
    # is applied at runtime, with no backfill for uncertain contexts.
    predictions = {}
    for history, counts in continuation.items():
        total = sum(counts.values())
        candidates = [(word, count) for word, count in counts.items() if count >= 3 and count / total >= .08]
        if candidates:
            predictions[history] = (total, sorted(candidates, key=lambda row: (-row[1], row[0]))[:8])

    # Real, complete short training sentences only. No generated/rewritten text.
    # Reject sensitive encyclopedia domains and identifiers in this UI aid.
    unsuitable = re.compile(r'战争|军队|武器|枪|炮|炸弹|杀|死亡|自杀|性行为|性交|色情|疾病|癌|毒品|犯罪|宗教|总统|政党|政府|身份证|电话|邮箱|https?://|\d')
    eligible = [(index, text, tokens) for index, (text, tokens) in enumerate(train)
                if 10 <= len(text) <= 72 and not unsuitable.search(text) and not re.search(r'[A-Za-z]', text)]
    eligible.sort(key=lambda row: (len(row[1]), row[0]))
    example_sentences, examples = [], {}
    for _, text, tokens in eligible[:384]:
        sentence_id = len(example_sentences)
        example_sentences.append(text)
        for word, tag in tokens:
            if 2 <= len(word) <= 6 and CHINESE.fullmatch(word) and tag in {'NOUN', 'VERB', 'ADJ', 'ADV'}:
                examples.setdefault(word, sentence_id)

    output = bytearray(b'QYCM\x00\x00\x00\x02')
    def integer(value):
        output.extend(struct.pack('>i', value))
    def string(value):
        encoded = value.encode('utf-8')
        integer(len(encoded)); output.extend(encoded)
    def grams(table, size):
        integer(len(table))
        previous = 0
        def varint(value):
            while value >= 128:
                output.append((value & 127) | 128); value >>= 7
            output.append(value)
        for word, count in sorted(table.items(), key=lambda row: sum(ord(c) << (16 * (size-1-i)) for i, c in enumerate(row[0]))):
            key = sum(ord(c) << (16 * (size-1-i)) for i, c in enumerate(word))
            varint(key - previous);varint(count);previous = key
    string('UD Chinese GSDSimp r2.18 · Wikipedia (CC BY-SA 4.0); Rime Ice; jieba')
    grams(uni, 1);grams(bi, 2);grams(tri, 3)
    integer(len(pos))
    for word, tag in sorted(pos.items()):
        string(word);integer(tag)
    for a in TAGS:
        for b in TAGS:
            integer(transitions[a, b])
    integer(len(predictions))
    for history, (total, rows) in sorted(predictions.items()):
        string(history);integer(total);integer(len(rows))
        for word, count in rows:
            string(word);integer(count)
    integer(len(example_sentences))
    for text in example_sentences:
        string(text)
    integer(len(examples))
    for word, sentence_id in sorted(examples.items()):
        string(word);integer(sentence_id)
    ASSET.write_bytes(gzip.compress(output, compresslevel=9, mtime=0))
    manifest = {'format': 'QYCM 2 (sorted delta keys/counts as unsigned varints; other fields big endian; gzip)', 'training_split': 'train only',
                'source_commit': PIN, 'source_license': 'CC BY-SA 4.0', 'sources': sources,
                'training_sentences': len(train), 'training_tokens': sum(len(tokens) for _, tokens in train),
                'unigrams': len(uni), 'bigrams': len(bi), 'trigrams': len(tri), 'pos_words': len(pos),
                'prediction_contexts': len(predictions), 'example_sentences': len(example_sentences), 'example_words': len(examples),
                'lexicon_v2_sha256': hashlib.sha256(lexicon.read_bytes()).hexdigest(),
                'jieba_sha256': hashlib.sha256(jieba.read_bytes()).hexdigest(),
                'model_bytes': ASSET.stat().st_size, 'decoded_bytes': len(output),
                'model_sha256': hashlib.sha256(ASSET.read_bytes()).hexdigest(),
                'transformations': 'Character counts: TRAIN 10x; one contribution per unique modern word weighted 1+floor(min(8,log1p(weight)/2)); Chinese runs reset at punctuation; corpus UPOS transitions; lexical jieba POS on corpus words/single characters. Count cutoff bigrams>=2/trigrams>=3. Next-word tables are complete observed tokens with count>=3 and conditional support>=0.08. Example sentences are exact short TRAIN texts, deterministic length/order cap384 and broad sensitive-domain filter; dev/test excluded. No phrase/benchmark-specific counts or boosts.'}
    (NOTICE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    source_notice = f'''Chinese context statistics and exact source examples\n\nUD Chinese GSDSimp r2.18; contributors Peng Qi and Koichi Yasuoka.\nhttps://github.com/UniversalDependencies/UD_Chinese-GSDSimp/tree/{PIN}\nUpstream license: Creative Commons Attribution-ShareAlike 4.0 International\nhttps://creativecommons.org/licenses/by-sa/4.0/\n\nThe simplified treebank was converted from Chinese GSD with OpenCC and manual corrections. Genre in upstream metadata: wiki. Google's removal of the old NC condition applies to annotations; Google claims no ownership or copyright over underlying content. This caveat and the upstream attribution/share-alike terms remain applicable.\n\nChanges by Qingyu: deterministic TRAIN-only character/UPOS/next-token statistics; log-weighted word-internal evidence from the already licensed modern v2 lexicon; lexical tags from MIT jieba; numeric/gzip encoding; bounded short exact TRAIN example excerpts with broad sensitive-domain filtering. No DEV/TEST sentence is used for model statistics or examples. No generated/rephrased Chinese examples.\n\nTRAIN sentences {len(train)}; tokens {manifest['training_tokens']}; source examples {len(example_sentences)}; indexed example words {len(examples)}. Source and model SHA-256 records and reproducible builder are in third_party/input-data/chinese-context/manifest.json and tools/build_chinese_context.py in the Qingyu project.\n\nRime Ice GPL-3.0-only, AOSP Apache-2.0, CC-CEDICT CC BY-SA 4.0 and jieba MIT notices are included separately. The model is distributed with the project's GPL-3.0 terms and retains attribution to all source data; exact corpus excerpts retain their CC BY-SA 4.0 source attribution.\n'''
    (ROOT / 'app/src/main/assets/licenses/chinese-context-sources.txt').write_text(source_notice, encoding='utf-8')
    print(json.dumps({key: manifest[key] for key in ['training_sentences', 'training_tokens', 'unigrams', 'bigrams', 'trigrams', 'model_bytes', 'decoded_bytes', 'prediction_contexts', 'example_words', 'model_sha256']}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
