# 集成来源

| 内容 | 来源 | 许可 | 项目中修改 |
|---|---|---|---|
| AOSP PinyinIME 中文原生解码器和系统词库 | https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/ （网络传输使用注明 AOSP 来源的 oxangen/PinyinIME 镜像） | Apache-2.0，上游 NOTICE 保留 | 自有 JNI facade、现代 NDK 构建、禁用学习接口、安全边界；精确文件与 hash 见 third_party/pinyinime |
| CC-CEDICT 简体中英词典 | https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz | CC BY-SA 4.0，以下载数据头为准 | 按简体词目索引；简短首义；删除交叉引用/括号；常用词人工短释义；完整修改与 hash 在 third_party/cedict/manifest.json |
| 图标、键盘视觉、品牌「轻语」 | 本项目原创 vector 与 Canvas | GPL-3.0-only（项目代码）；产品名与商标不随许可证转让 | 原创 |

Fcitx5 Android、Trime、librime、青简与 Z次方仅为研究参考，未集成其 UI、品牌或代码。
CC-CEDICT 数据的贡献者包括 MDBG 与社区；参考作品 CEDICT (c) 1997, 1998 Paul Andrew Denisowski。
