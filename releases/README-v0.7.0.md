# 轻语输入法 v0.7.0

保持轻语现有简洁墨绿色界面，本轮完善文本编辑、实际选词学习和九键体验。

- 文本编辑新增退格、撤回与翻译。按住退格连续删除；撤回按操作顺序恢复当前输入框的输入、删除、剪切、粘贴及替换，外部修改后停止旧撤回，密码内容不记录。
- 未选择文字只翻译输入框中的中文；中文或混合选区只翻译其中中文。纯外语选区由端侧识别语言，对应模型已安装时译为中文。成功直接替换原范围，并可撤回；失败、无可用本地译文且所需模型缺失、内容或选区变化时保留原文。
- 原标点、数字、Emoji、空白与换行保留，链接、邮箱和可识别代码保留。翻译异步处理，不影响中文候选。严格保持分隔符会限制外语整句的自然度，含中日共用汉字的极短文本仍可能难以识别。
- 实际九键选词触发长期拼音、简拼和短语学习，保留旧学习记录；修复中文预测一次选择重复学习两次的问题。
- 全键简拼和混拼复用现代词库音节图补充完整句候选；修复合法全拼长句被无关简拼路径挤占搜索预算的问题，例如「我们明天去北京」可直接整句选入。缓存九键匹配元数据，侧栏常用拼音与小写字母优先，大写与数字继续可滚动查看。
- 保留中英文、六种释义语言、候选长按及上滑、九键连续选择与回退、应用内更新；普通键盘候选和展开区继续不显示 Google 品牌栏。

[下载正式 APK](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.0/Qingyu-0.7.0.apk) · [SHA-256 校验文件](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.0/Qingyu-0.7.0.apk.sha256) · [验证记录](../docs/validation-v0.7.0.md) · [编辑规则与边界](../docs/text-editing.md)

Android 8.0+，版本代码 10，使用原独立 Release 签名。覆盖安装 v0.6.7，无需先卸载；已有设置、用户词典和学习文件保留。基础包新增小型共享端侧语言识别资源，额外翻译模型仍按需下载，不预置语言翻译权重。

未验证 ARM64 真机、厂商聊天应用及长期耗电/手感；复杂简拼重码与整句排序仍需真机反馈。测试中有 16KB 模拟器引用队列退出和一次候选状态断言失败；重跑通过不表示根因已排除，具体记录见验证说明。

## English

v0.7.0 adds repeating backspace, safe session undo and protected editor translation. A successful translation replaces the original range and can be undone; failure, unavailable required translation resources or changed editor content leaves the original intact. Local Chinese-to-English entries can work without a downloaded model; foreign-to-Chinese selections require installed models. Offline language identification handles supported foreign selections. Existing punctuation, numbers, emoji, whitespace, newlines and protected technical content are retained.

Actual nine-key choices now learn pinyin, initials and phrases durably. Chinese predictions learn once per commit, and full-key mixed/initial sentence input reuses the modern syllable graph. Valid full-pinyin sentences keep their search budget for complete pinyin words, avoiding unrelated initials paths that previously hid complete sentences. Common pinyin and lowercase rail entries appear before uppercase letters and digits. Existing design, candidate gestures, six annotation languages and optional model downloads remain.

Install over v0.6.7 to preserve data. Android 8.0+, version code 10, unchanged production signing identity. Translation and language detection can be uncertain for very short shared Chinese/Japanese text; preserving exact separators limits foreign sentence fluency. The validation record includes 16 KiB emulator reference-queue exits and an intermittent candidate-state assertion failure; successful reruns do not establish their root causes. Physical ARM64 devices, OEM apps and long-term battery/touch performance remain unverified.
