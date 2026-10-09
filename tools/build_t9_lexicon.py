"""Derive the T9 syllable graph from every existing modern dictionary reading."""
from pathlib import Path
import gzip
import hashlib
import json
import sqlite3
import struct

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/assets/pinyin/lexicon-v2.db'
OUTPUT = ROOT / 'app/src/main/assets/pinyin/t9-v1.bin.gz'


def main():
    with sqlite3.connect(SOURCE) as database:
        rows = database.execute('SELECT rowid,pinyin,weight FROM words ORDER BY rowid').fetchall()
    syllables = sorted({part for _, reading, _ in rows for part in reading.split("'")})
    labels = {reading: index for index, reading in enumerate(syllables)}
    # Breadth-first IDs make the children of each node one contiguous range.
    children, terminals, weights, node_labels = [{}], [[]], [0], [0]
    for rowid, reading, weight in rows:
        node = 0
        weights[node] = max(weights[node], weight)
        for part in reading.split("'"):
            label = labels[part]
            if label not in children[node]:
                children[node][label] = len(children)
                children.append({}); terminals.append([]); weights.append(0); node_labels.append(label)
            node = children[node][label]
            weights[node] = max(weights[node], weight)
        terminals[node].append((rowid, weight))
    order = [0]
    starts = []
    for old_node in order:
        starts.append(len(order))
        order.extend(children[old_node][label] for label in sorted(children[old_node]))
    starts.append(len(order))
    terminal_starts, rowids = [], []
    for old_node in order:
        terminal_starts.append(len(rowids))
        rowids.extend(rowid for rowid, _ in sorted(terminals[old_node], key=lambda item: (-item[1], item[0])))
    terminal_starts.append(len(rowids))
    assert len(order) == len(children) and len(rowids) == len(rows)
    with OUTPUT.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as out:
        out.write(struct.pack('>5i', 0x51595439, 1, len(order), len(rowids), len(syllables)))
        for spelling in syllables:
            encoded = spelling.encode('ascii')
            out.write(struct.pack('>H', len(encoded)) + encoded)
        for values, format_ in ((starts, 'i'), (terminal_starts, 'i'),
                                ([weights[node] for node in order], 'i'),
                                ([node_labels[node] for node in order], 'H'), (rowids, 'i'),
                                ([weight for _, _, weight in rows], 'i')):
            out.write(struct.pack('>' + format_ * len(values), *values))
    metadata = {
        'format': 'Qingyu T9 syllable graph v1; gzip; big endian primitive arrays',
        'source': 'app/src/main/assets/pinyin/lexicon-v2.db',
        'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'path': str(OUTPUT.relative_to(ROOT)).replace('\\', '/'),
        'sha256': hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
        'bytes': OUTPUT.stat().st_size, 'nodes': len(order), 'readings': len(rowids),
        'syllables': len(syllables), 'primitive_array_bytes': 14 * len(order) + 8 + 8 * len(rowids),
        'transformations': 'Every source word reading retained; each syllable matches its full T9 code or initial digit; terminal rowids reference the unchanged source database; no example-specific aliases or weights.',
        'license': 'Same GPL-3.0-only and CC BY-SA 4.0 attribution as model-v2-manifest.json',
    }
    (ROOT / 'third_party/pinyinime/t9-v1-manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
