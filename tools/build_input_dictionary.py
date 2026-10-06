"""Generate v2 input data from saved upstream snapshots; Python stdlib only.

SCOWL supplies spelling and inflected forms. Public-domain nineteenth-century
books supply frequency/bigrams; small original modern phrases improve chat.
CC-CEDICT supplies real pinyin coverage and reverse glosses; jieba ranks Chinese.
No network or corpus parsing occurs while typing.
"""
from collections import Counter, defaultdict
from pathlib import Path
import gzip
import hashlib
import json
import re
import sqlite3
import urllib.request
import time

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/input-v2'
ASSETS = ROOT / 'app/src/main/assets/input'
NOTICE = ROOT / 'third_party/input-data'
for directory in (CACHE, ASSETS, NOTICE):
    directory.mkdir(parents=True, exist_ok=True)
sources = {}

def fetch(name, url):
    target = CACHE / name
    if not target.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'Qingyu-dictionary-builder/0.2'})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=25) as response:
                    content = response.read()
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(1)
        target.write_bytes(content)
    sources[name] = {'url': url, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
    return target.read_text(encoding='utf-8-sig')

dictionary = fetch('en_US.dic', 'https://raw.githubusercontent.com/LibreOffice/dictionaries/master/en/en_US.dic')
affix = fetch('en_US.aff', 'https://raw.githubusercontent.com/LibreOffice/dictionaries/master/en/en_US.aff')
scowl_notice = fetch('SCOWL-README.txt', 'https://raw.githubusercontent.com/LibreOffice/dictionaries/master/en/README_en_US.txt')
jieba = fetch('jieba-dict.txt', 'https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt')
jieba_notice = fetch('jieba-LICENSE.txt', 'https://raw.githubusercontent.com/fxsjy/jieba/master/LICENSE')
(NOTICE / 'SCOWL-README.txt').write_text(scowl_notice, encoding='utf-8')
(NOTICE / 'jieba-LICENSE.txt').write_text(jieba_notice, encoding='utf-8')
licenses = ROOT / 'app/src/main/assets/licenses'
(licenses / 'SCOWL-README.txt').write_text(scowl_notice, encoding='utf-8')
(licenses / 'jieba-LICENSE.txt').write_text(jieba_notice, encoding='utf-8')

# Apply the upstream single-character Hunspell prefix/suffix flags, including
# cross products explicitly permitted by the file. No guessed inflections.
rules = defaultdict(list)
cross = {}
for line in affix.splitlines():
    parts = line.split()
    if len(parts) == 4 and parts[0] in ('PFX', 'SFX'):
        cross[(parts[0], parts[1])] = parts[2] == 'Y'
    elif len(parts) >= 5 and parts[0] in ('PFX', 'SFX'):
        kind, flag, strip, add, condition = parts[:5]
        rules[(kind, flag)].append(('' if strip == '0' else strip,
            '' if add == '0' else add.split('/')[0], re.compile(('^' if kind == 'PFX' else '') + condition + ('$' if kind == 'SFX' else ''))))

def apply(word, kind, flag):
    for strip, add, condition in rules.get((kind, flag), ()):
        if not condition.search(word):
            continue
        if kind == 'PFX' and (not strip or word.startswith(strip)):
            yield add + word[len(strip):]
        elif kind == 'SFX' and (not strip or word.endswith(strip)):
            yield (word[:-len(strip)] if strip else word) + add

words = set()
for line in dictionary.splitlines()[1:]:
    stem, _, flags = line.partition('/')
    # Proper names remain searchable but lowercase lexicon avoids case churn.
    if not re.fullmatch("[A-Za-z]+(?:'[A-Za-z]+)?", stem) or '!' in flags:
        continue
    forms = {stem}
    for flag in flags:
        forms.update(apply(stem, 'PFX', flag))
        forms.update(apply(stem, 'SFX', flag))
        if cross.get(('PFX', flag)):
            for prefixed in apply(stem, 'PFX', flag):
                for suffix in flags:
                    if cross.get(('SFX', suffix)):
                        forms.update(apply(prefixed, 'SFX', suffix))
    words.update(word.lower() for word in forms if 1 <= len(word) <= 32 and re.fullmatch("[A-Za-z]+(?:'[A-Za-z]+)?", word))

frequency = Counter()
pairs = Counter()
# All original authors died by 1930, the original English texts are nineteenth
# century. Keep source files out of APK; only occurrence counts are shipped.
books = {'alice.txt': (11, 'Lewis Carroll, 1865'), 'pride.txt': (1342, 'Jane Austen, 1813'),
    'sherlock.txt': (1661, 'Arthur Conan Doyle, 1892'), 'oz.txt': (55, 'L. Frank Baum, 1900')}
for name, (number, author) in books.items():
    raw = fetch(name, f'https://www.gutenberg.org/cache/epub/{number}/pg{number}.txt')
    match = re.search(r'\*\*\* START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK .*?\*\*\*(.*?)\*\*\* END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK', raw, re.S | re.I)
    if not match:
        raise ValueError(f'Cannot identify licensed book body: {name}')
    body = match.group(1).replace('\u2019', "'")
    sources[name]['author'] = author
    tokens = re.findall("[a-z]+(?:'[a-z]+)?", body.lower())
    # Reject OCR/non-dictionary artifacts instead of letting prose invent words.
    frequency.update(token for token in tokens if token in words)
    pairs.update((a, b) for a, b in zip(tokens, tokens[1:]) if a in words and b in words)

modern = '''hello how are you; hello world; thank you very much; thanks for your help;
good morning; good afternoon; good evening; good night; nice to meet you;
see you soon; see you tomorrow; see you later; have a nice day; have a good day;
how are you doing; how can i help you; i am fine; i am happy; i am sorry;
i am working on the project; i will send you the message; i would like to;
i want to learn english; i love you; i think it is; i know what you mean;
i need your help; i can help you; can you help me; could you please;
would you like to; let me know; let us go; please let me know;
please check the email; please send me the file; please try again;
do you have time; do you want to; what do you think; what is your name;
where are you; where is the meeting; when will you come; when can we meet;
we are going to; we need to discuss; we can do it; it is a good idea;
that sounds good; sounds great; no problem; of course; you are welcome;
happy birthday; happy new year; congratulations on your success;
the next version; the latest update; the new project; the software developer;
design the interface; develop the project; test the application;
android keyboard; english input; chinese input; typing experience;
copy and paste; text editing; dark mode; light mode; local dictionary;
artificial intelligence; machine learning; open source; source code;
turn on the translation; turn off the vibration; change the keyboard;
on my way; at home; at work; in the morning; in the evening;
this is important; it works well; i agree with you; i understand;
good luck; take care; sorry about that; talk to you later'''
for phrase in modern.replace('\n', ' ').split(';'):
    tokens = phrase.strip().split()
    words.update(tokens)
    frequency.update({word: 3500 for word in tokens})
    pairs.update({pair: 500 for pair in zip(tokens, tokens[1:])})
# Rank everyday tokens before corpus proper names and obsolete vocabulary.
common = 'the to and a i you it is in that of for we have this with on be are was my can do not your will please hello thank thanks good yes no help day today tomorrow work time project design develop development computer phone programming software message world love like want need know'.split()
for rank, word in enumerate(common):
    words.add(word)
    frequency[word] += 150000 // (rank + 1)
(ASSETS / 'english-words.tsv').write_text(''.join(f'{word}\t{max(1, frequency[word])}\n' for word in sorted(words)), encoding='utf-8')
grouped = defaultdict(list)
for (previous, word), count in pairs.items():
    if count >= 2:
        grouped[previous].append((word, count))
(ASSETS / 'english-bigrams.tsv').write_text(''.join(f'{previous}\t{word}\t{count}\n' for previous in sorted(grouped)
    for word, count in sorted(grouped[previous], key=lambda item: (-item[1], item[0]))[:24]), encoding='utf-8')

zh_frequency = {}
for line in jieba.splitlines():
    fields = line.split()
    if len(fields) >= 2:
        zh_frequency[fields[0]] = int(fields[1])
cedict = gzip.open(ROOT / '.cache/cedict.gz', 'rt', encoding='utf-8').read()
pattern = re.compile(r'^(\S+) (\S+) \[(.*?)\] /(.+)/$')
digits_map = str.maketrans({char: digit for digit, letters in zip('23456789', ['abc','def','ghi','jkl','mno','pqrs','tuv','wxyz']) for char in letters})
nine = {}
reverse = defaultdict(dict)
for line in cedict.splitlines():
    match = pattern.match(line)
    if not match:
        continue
    traditional, chinese, reading, meanings = match.groups()
    if not re.fullmatch(r'[\u3400-\u9fff]+', chinese):
        continue
    syllables = re.sub(r'[1-5]', '', reading.lower()).replace('u:', 'v').split()
    pinyin = "'".join(syllables)
    joined = ''.join(syllables)
    if not re.fullmatch(r'[a-z]+', joined) or len(joined) > 64:
        continue
    digits = joined.translate(digits_map)
    weight = max(1, zh_frequency.get(chinese, 1))
    # CEDICT capitals flag proper nouns. Reduce ambiguous surnames at one key.
    if reading[:1].isupper():
        weight = max(1, weight // 10)
    nine[(digits, chinese, pinyin)] = weight
    for gloss in meanings.split('/'):
        if re.match(r'(variant|old variant|see |CL:|surname |also written)', gloss, re.I):
            continue
        english = re.sub(r'\[[^]]*\]|\([^)]*\)', '', gloss).strip().lower()
        english = re.sub(r'^to ', '', english).strip()
        if re.fullmatch("[a-z]+(?:'[a-z]+)?", english) and english in words:
            reverse[english][chinese] = max(weight, reverse[english].get(chinese, 0))

overrides = {'hello':'你好', 'world':'世界', 'i':'我', 'you':'你', 'we':'我们', 'he':'他', 'she':'她',
 'it':'它', 'they':'他们', 'my':'我的', 'your':'你的', 'our':'我们的', 'the':'这/那', 'a':'一', 'an':'一',
 'is':'是', 'am':'是', 'are':'是', 'was':'曾是', 'were':'曾是', 'be':'是', 'been':'曾是', 'being':'存在',
 'have':'有', 'has':'有', 'had':'曾有', 'do':'做', 'does':'做', 'did':'做了', 'to':'到', 'of':'的',
 'in':'在里面', 'on':'在上面', 'at':'在', 'with':'和/用', 'for':'为了', 'from':'来自', 'and':'和',
 'but':'但是', 'not':'不', 'no':'不', 'yes':'是', 'can':'能', 'could':'可以', 'will':'将', 'would':'愿意',
 'should':'应该', 'must':'必须', 'may':'可能', 'might':'也许', 'please':'请', 'thanks':'谢谢', 'thank':'感谢',
 'good':'好', 'great':'很好', 'fine':'很好', 'nice':'不错', 'happy':'开心', 'sorry':'抱歉', 'help':'帮助',
 'helping':'帮助', 'helped':'帮助了', 'hello':'你好', 'how':'怎样', 'what':'什么', 'where':'哪里',
 'when':'何时', 'why':'为什么', 'who':'谁', 'which':'哪一个', 'much':'很多', 'very':'非常', 'more':'更多',
 'today':'今天', 'tomorrow':'明天', 'yesterday':'昨天', 'time':'时间', 'day':'天', 'morning':'早上',
 'afternoon':'下午', 'evening':'晚上', 'night':'夜晚', 'love':'爱', 'like':'喜欢', 'want':'想要', 'need':'需要',
 'know':'知道', 'think':'想/认为', 'understand':'理解', 'work':'工作', 'working':'工作中', 'home':'家',
 'project':'项目', 'design':'设计', 'develop':'开发', 'development':'开发', 'programming':'编程',
 'program':'程序', 'computer':'电脑', 'phone':'手机', 'software':'软件', 'message':'消息', 'email':'邮件',
 'file':'文件', 'send':'发送', 'meet':'见面', 'meeting':'会议', 'go':'去', 'come':'来', 'see':'看/见到',
 'learn':'学习', 'english':'英语', 'chinese':'中文', 'keyboard':'键盘', 'input':'输入', 'typing':'打字',
 'translation':'翻译', 'dictionary':'词典', 'local':'本地', 'copy':'复制', 'paste':'粘贴', 'text':'文字',
 'dark':'深色', 'light':'浅色/光', 'mode':'模式', 'change':'改变', 'open':'打开', 'source':'来源/源代码',
 'code':'代码', 'test':'测试', 'application':'应用', 'update':'更新', 'version':'版本', 'experience':'体验'}
database = ASSETS / 'input-v2.db'
if database.exists():
    database.unlink()
db = sqlite3.connect(database)
db.execute('PRAGMA journal_mode=OFF')
db.execute('CREATE TABLE nine (digits TEXT, text TEXT, pinyin TEXT, weight INTEGER, PRIMARY KEY(digits,text,pinyin)) WITHOUT ROWID')
db.executemany('INSERT INTO nine VALUES (?,?,?,?)', [(digits, text, pinyin, weight) for (digits,text,pinyin),weight in sorted(nine.items())])
db.execute('CREATE INDEX nine_rank ON nine(digits, weight DESC)')
db.execute('CREATE TABLE chinese (text TEXT PRIMARY KEY,weight INTEGER NOT NULL) WITHOUT ROWID')
db.executemany('INSERT INTO chinese VALUES (?,?)', sorted({text:weight for (_,text,_),weight in nine.items()}.items()))
db.execute('CREATE TABLE english_gloss (word TEXT PRIMARY KEY, zh TEXT NOT NULL) WITHOUT ROWID')
glosses = {word:'；'.join(zh for zh,_ in sorted(values.items(), key=lambda item:(-item[1], len(item[0]), item[0]))[:2]) for word,values in reverse.items()}
glosses.update(overrides)
db.executemany('INSERT INTO english_gloss VALUES (?,?)', sorted(glosses.items()))
db.commit()
db.execute('VACUUM')
db.close()
manifest = {'sources':sources, 'english_words':len(words), 'english_bigrams':sum(min(24,len(v)) for v in grouped.values()),
 'nine_key_readings':len(nine), 'english_chinese_glosses':len(glosses), 'editorial_modifications':
 'Hunspell flag expansion; lowercase forms; taboo suggestion flags omitted; ASCII English filtering; prose-body unigram/bigram counts only; original everyday phrase/count weighting; jieba frequency join; tone-free CEDICT T9 digit index; single-word English CEDICT reverse senses plus explicit editorial overrides.',
 'english_gloss_overrides':overrides, 'modern_phrases':modern,
 'outputs':{file.name:{'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()} for file in ASSETS.iterdir()}}
(NOTICE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
(NOTICE / 'README.md').write_text('''# Qingyu v2 offline input data

SCOWL English dictionary and affix expansion: upstream LibreOffice en_US,
49,568 base entries. Upstream SCOWL permission notices are retained in
SCOWL-README.txt and embedded in the APK. The generated word list is modified.

Chinese digit/readings and reverse English glosses: CC-CEDICT by MDBG and
community contributors, CC BY-SA 4.0; see ../cedict. These portions of
input-v2.db are modified CC-CEDICT derivatives and retain CC BY-SA 4.0.

Chinese frequency ranking: jieba dict.txt, Copyright 2013 Sun Junyi,
MIT; its complete permission notice is retained and embedded in the APK.

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
''', encoding='utf-8')
(licenses / 'input-v2-sources.txt').write_text((NOTICE/'README.md').read_text(encoding='utf-8'), encoding='utf-8')
print(json.dumps({key:manifest[key] for key in ('english_words','english_bigrams','nine_key_readings','english_chinese_glosses','outputs')}, ensure_ascii=True))
