"""Build a compact indexed Chinese -> English CC-CEDICT derivative.

Input must be a saved official CC-CEDICT gzip. No runtime network dependency.
The derivative data remains CC BY-SA 4.0; see third_party/cedict.
"""
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import sys

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]) if len(sys.argv) > 1 else root / '.cache/cedict.gz'
target = root / 'app/src/main/assets/translation/zh-en.db'
target.parent.mkdir(parents=True, exist_ok=True)
notice_dir = root / 'third_party/cedict'
notice_dir.mkdir(parents=True, exist_ok=True)
text = gzip.open(source, 'rt', encoding='utf-8').read()
headers = '\n'.join(line for line in text.splitlines() if line.startswith('#'))
(notice_dir / 'SOURCE_HEADER.txt').write_text(headers + '\n', encoding='utf-8')
entries = {}
pattern = re.compile(r'^(\S+) (\S+) \[(.*?)\] /(.+)/$')
for line in text.splitlines():
    match = pattern.match(line)
    if not match:
        continue
    traditional, simplified, pinyin, meanings = match.groups()
    glosses = meanings.split('/')
    for gloss in glosses:
        # Cross references and surname-only glosses are unhelpful in the tiny strip.
        if re.match(r'(variant|old variant|see |CL:|surname |also written)', gloss, re.I):
            continue
        gloss = re.sub(r'\[[^]]*\]', '', gloss)
        gloss = re.sub(r'\([^)]*\)', '', gloss).strip()
        gloss = re.sub(r'^to ', '', gloss)
        gloss = re.sub(r'\s+', ' ', gloss).strip(' ,;')
        if not gloss or len(gloss) > 70:
            continue
        if simplified not in entries:
            entries[simplified] = gloss
        break

# Editorial glosses: concise everyday usage, not contextual machine translations.
overrides = {
    '开发':'develop', '项目':'project', '编程':'programming', '部署':'deploy', '设计':'design',
    '你好':'hello', '谢谢':'thank you', '再见':'goodbye', '今天':'today', '明天':'tomorrow',
    '昨天':'yesterday', '现在':'now', '可以':'can', '知道':'know', '工作':'work',
    '学习':'learn', '输入法':'input method', '中文':'Chinese', '英文':'English', '英语':'English',
    '苹果':'apple', '朋友':'friend', '时间':'time', '消息':'message', '问题':'question',
    '喜欢':'like', '我们':'we', '你们':'you', '他们':'they', '手机':'phone',
    '电脑':'computer', '软件':'software', '代码':'code', '测试':'test', '版本':'version',
    '自然':'natural', '流畅':'smooth', '稳定':'stable', '键盘':'keyboard', '候选':'candidate',
    '翻译':'translate', '聊天':'chat', '搜索':'search', '文字':'text', '设置':'settings',
    '早上':'morning', '晚上':'evening', '吃饭':'eat', '回家':'go home', '需要':'need',
    '很':'very', '好':'good', '我':'I', '你':'you', '他':'he', '她':'she', '是':'be',
    '不':'not', '有':'have', '做':'do', '想':'want', '看':'look', '去':'go', '来':'come',
    '说':'say', '这':'this', '那':'that', '也':'also', '和':'and', '在':'at',
}
entries.update(overrides)
if target.exists():
    target.unlink()
db = sqlite3.connect(target)
db.execute('PRAGMA journal_mode=OFF')
db.execute('CREATE TABLE gloss (zh TEXT PRIMARY KEY, en TEXT NOT NULL) WITHOUT ROWID')
db.executemany('INSERT INTO gloss VALUES (?,?)', sorted(entries.items()))
db.commit()
db.execute('VACUUM')
db.close()
metadata = {
    'source':'https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz',
    'license':'CC-BY-SA-4.0', 'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
    'database_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'entry_count':len(entries), 'overrides':overrides,
    'modifications':'Simplified headword index; first usable gloss; references/parentheticals removed; concise editorial overrides.',
}
(notice_dir / 'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
(notice_dir / 'README.md').write_text('''# CC-CEDICT attribution

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
''', encoding='utf-8')
print(json.dumps({'entries':len(entries),'bytes':target.stat().st_size,'target':str(target)}, ensure_ascii=True))

