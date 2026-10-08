"""Deterministic held-out contexts and host indexed-edge fixtures, no training.

The first 48 usable multi-token Chinese runs from the pinned UD DEV split are
chosen in source order, without consulting decoder ranks. They remain a full
assessment: difficult encyclopedia readings are not hidden or deleted.
Original everyday phrases are separate strict regression cases. Neither list
is an input to build_chinese_context.py.
"""
from pathlib import Path
import hashlib
import json
import re
import sqlite3
from build_chinese_context import ROOT, CACHE, PIN, sentences, fetch

DAILY = '''woyaokanduanshipin 我要看短视频
qingsaomazhifu 请扫码支付
woxiangdawangyueche 我想打网约车
woxiangkandianying 我想看电影
jintianwoyaoqushangban 今天我要去上班
wanshangwomenyiqichifan 晚上我们一起吃饭
mingtianyiqiqugongyuan 明天一起去公园
nikeyibangwokankanma 你可以帮我看看吗
woxihuanxuexizhongwen 我喜欢学习中文
nixianzaiyoushijianma 你现在有时间吗
woxiangmaiyibeikafei 我想买一杯咖啡
kuaidiyijingdaole 快递已经到了
womenxuyaochongxinsheji 我们需要重新设计
qingbangwodakaishezhi 请帮我打开设置
mingtiankenenghuixiayu 明天可能会下雨
wozhengzaixuexibiancheng 我正在学习编程
qinggaosuwonidexiangfa 请告诉我你的想法
zhegewentiyijingjiejue 这个问题已经解决
woxiangzuogaotiehuijia 我想坐高铁回家
jintiandetianqihenhao 今天的天气很好
zhegebanbenkeyigengxin 这个版本可以更新
niyoumeiyoudaichongdianqi 你有没有带充电器
woxianzaizhunbeichumen 我现在准备出门
dengyixiawomashanghuilai 等一下我马上回来
qingbawenjianfageiwo 请把文件发给我
womenmingtianzaitaolun 我们明天再讨论
woxiangtingyishougequ 我想听一首歌曲
wojuedezheyangbijiaohao 我觉得这样比较好
jintiangongzuoyoudianmang 今天工作有点忙
woyijingshoudaoxiaoxi 我已经收到消息
qingwenfujinyoumeiyouditiezhan 请问附近有没有地铁站
zhegeerweimawufashibie 这个二维码无法识别
woxiangxianshiyixia 我想先试一下
xindeanzhuobanbenhenliuchang 新的安卓版本很流畅
woxuyaoyuyuemingtiandeshijian 我需要预约明天的时间
woxiangqubeijing 我想去北京
jintiantianqihenhao 今天天气很好'''


def main():
    db = sqlite3.connect(ROOT / 'app/src/main/assets/pinyin/lexicon-v2.db')
    cases = [line.split() + ['daily-sentence', '5'] for line in DAILY.splitlines()]
    dev_path = CACHE / 'zh_gsdsimp-ud-dev.conllu'
    dev_content = fetch('zh_gsdsimp-ud-dev.conllu')
    origins = []
    used = {row[1] for row in cases}
    reading_cache = {}
    def reading(word):
        if word not in reading_cache:
            rows = list(db.execute('SELECT pinyin FROM words WHERE text=? ORDER BY weight DESC LIMIT 1', (word,)))
            reading_cache[word] = rows[0][0] if rows else ''
        return reading_cache[word]
    count = 0
    for sentence_id, (text, tokens) in enumerate(sentences(dev_content), 1):
        runs, run = [], []
        for word, tag in tokens + [('', 'PUNCT')]:
            if re.fullmatch(r'[\u3400-\u9fff]+', word) and tag != 'PROPN':
                run.append(word)
            else:
                if run:
                    runs.append(run)
                run = []
        for words in runs:
            target = ''.join(words)
            if not 6 <= len(target) <= 12 or len(words) < 3 or target in used:
                continue
            readings = [reading(word) for word in words]
            if not all(readings):
                continue
            code = ''.join(readings).replace("'", '')
            if len(code) > 48:
                continue
            cases.append([code, target, 'ud-dev-heldout', '5', 'assessment'])
            origins.append({'code': code, 'text': target, 'sentence_id_1_based': sentence_id, 'full_sentence': text, 'tokens': words})
            used.add(target);count += 1
            if count == 48:
                break
        if count == 48:
            break
    corpus = ROOT / 'tools/chinese-context-heldout.tsv'
    corpus.write_text('# Original daily regressions plus deterministic UD DEV assessment; no rank-based admission.\n' + '\n'.join('\t'.join(row) for row in cases) + '\n', encoding='utf-8')
    notice = ROOT / 'third_party/input-data/chinese-context/heldout-manifest.json'
    notice.write_text(json.dumps({'source_commit': PIN, 'source_split': 'DEV (not trained)', 'source_sha256': hashlib.sha256(dev_path.read_bytes()).hexdigest(), 'selection': 'first48 source-order, complete known-token Chinese runs length6..12, >=3tokens, no PROPN, <=48pinyinletters, no rank filtering', 'cases': origins}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # Indexed edge export used ONLY by the platform-neutral host check; APK and
    # Android checks query the real SQLite asset, never this fixture.
    codes = set()
    for row in cases:
        code = row[0]
        for start in range(len(code)):
            for end in range(start+1, min(len(code), start+32)+1):
                codes.add(code[start:end])
        codes.add(code)
    fixture = ROOT / '.tools/chinese-context-edges.tsv'
    rows = 0
    with fixture.open('w', encoding='utf-8') as output:
        for code in sorted(codes):
            for text, pinyin, weight in db.execute('SELECT text,pinyin,weight FROM words WHERE raw=? ORDER BY weight DESC LIMIT 64', (code,)):
                output.write(f'{code}\t{text}\t{pinyin}\t{weight}\n');rows += 1
    print(f'CASES daily={len(DAILY.splitlines())} heldout={count} edges={rows}; {corpus}')


if __name__ == '__main__':
    main()
