# 集成来源

| 内容 | 来源 | 许可 | 项目中修改 |
|---|---|---|---|
| AOSP PinyinIME 中文原生解码器和系统词库 | https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/ （网络传输使用注明 AOSP 来源的 oxangen/PinyinIME 镜像） | Apache-2.0，上游 NOTICE 保留 | 自有 JNI facade、现代 NDK 构建、禁用学习接口、安全边界；精确文件与 hash 见 third_party/pinyinime |
| CC-CEDICT 简体中英词典 | https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz | CC BY-SA 4.0，以下载数据头为准 | 按简体词目索引；简短首义；删除交叉引用/括号；常用词人工短释义；完整修改与 hash 在 third_party/cedict/manifest.json |
| CC-CEDICT 完整释义、英文反向释义与九键读音 | 同上 | CC BY-SA 4.0 | 完整义项/拼音 SQLite、反向英文词义、去声调九键索引；详见 third_party/cedict/details-manifest.json 与 third_party/input-data/manifest.json |
| SCOWL 美式英文 Hunspell 词典 | https://github.com/LibreOffice/dictionaries/tree/master/en | 上游多来源宽松许可，完整原声明保留在 SCOWL-README.txt | 按词缀展开、过滤不适合建议的标记、121167 词形索引 |
| jieba 词频数据 | https://github.com/fxsjy/jieba | MIT | 与九键读音 join 作为排序频率；原 LICENSE 随 APK 保留 |
| 英文公开文学词频/双词统计 | https://www.gutenberg.org/ebooks/11 、1342、1661、55 | 四本原作品属于公有领域，实际仅提取原文学正文统计 | 不收录 Gutenberg 页眉或文本；另加入项目原创日常短语权重；精确 URL/hash 见 input-data/manifest.json |
| UD Chinese GSDSimp r2.18 中文上下文统计与真实短例句 | https://github.com/UniversalDependencies/UD_Chinese-GSDSimp/tree/7b61ed473f963e911788efdf1f478154bc1053e4 | CC BY-SA 4.0；Peng Qi、Koichi Yasuoka 与 UD 社区；保留上游底层文本权利声明 | 仅 TRAIN 3,997 句 / 98,614 token 生成字符、词性、下一词统计与真实摘录；DEV/TEST 不参与；许可、变换和 SHA-256 见 third_party/input-data/chinese-context/ |
| Google ML Kit Translation 17.0.3 与端侧模型 | https://developers.google.com/ml-kit/language/translation/android | Google SDK / ML Kit 条款，模型独立下载；不属于轻语原创 GPL 内容 | 独立异步 provider，不传原文到云翻译；隐私及归属见 third_party/mlkit/SOURCE.md |
| powered by Google Translate 明暗 PNG | https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip | Google 品牌归属规范 | 使用原始 PNG，仅等比例显示，无重绘或着色；保留在长按详情与模型管理，键盘候选不显示；来源、SHA256 与适用要求见 third_party/mlkit/SOURCE.md |
| 图标、键盘视觉、品牌「轻语」 | 本项目原创 vector 与 Canvas | GPL-3.0-only（项目代码）；产品名与商标不随许可证转让 | 原创 |

Fcitx5 Android、Trime、librime、青简与 Z次方仅为研究参考，未集成其 UI、品牌或代码。
CC-CEDICT 数据的贡献者包括 MDBG 与社区；参考作品 CEDICT (c) 1997, 1998 Paul Andrew Denisowski。
