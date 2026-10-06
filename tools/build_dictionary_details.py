"""CC-CEDICT complete dictionary senses for the reading panel, not for committing text."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
source = root / ".cache/cedict.gz"
target = root / "app/src/main/assets/translation/zh-en-details.db"
entries = {}
pattern = re.compile(r"^(\S+) (\S+) \[(.*?)\] /(.+)/$")
for line in gzip.open(source, "rt", encoding="utf-8"):
    match = pattern.match(line)
    if not match:
        continue
    _, simplified, pinyin, meanings = match.groups()
    pinyins, senses = entries.setdefault(simplified, ([], []))
    if pinyin not in pinyins:
        pinyins.append(pinyin)
    for meaning in meanings.split("/"):
        if meaning and meaning not in senses:
            senses.append(meaning)

if target.exists():
    target.unlink()
database = sqlite3.connect(target)
database.execute("PRAGMA journal_mode=OFF")
database.execute("CREATE TABLE details (zh TEXT PRIMARY KEY, pinyin TEXT NOT NULL, meanings TEXT NOT NULL) WITHOUT ROWID")
database.executemany("INSERT INTO details VALUES (?,?,?)", (
    (zh, " / ".join(pinyins), "\n".join(senses)) for zh, (pinyins, senses) in sorted(entries.items())
))
database.commit()
database.execute("VACUUM")
pronunciation, full_senses = database.execute("SELECT pinyin,meanings FROM details WHERE zh='开发'").fetchone()
assert pronunciation == "kai1 fa1" and all(sense in full_senses for sense in ("to exploit", "to open up", "to develop"))
assert database.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
database.close()
manifest = {
    "source": "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz",
    "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "license": "CC-BY-SA-4.0",
    "entry_count": len(entries),
    "database_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
    "modifications": "Simplified headword index; merged alternate pronunciations and full, deduplicated original senses. Display only; never committed as translation.",
}
(root / "third_party/cedict/details-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"entries": len(entries), "bytes": target.stat().st_size, "sha256": manifest["database_sha256"]}))
