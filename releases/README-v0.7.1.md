# 轻语输入法 v0.7.1

保持轻语现有简洁的浅色与深色界面，本轮集中改善文本编辑、候选更新与整句翻译。

- **文本编辑面板**：左侧圆形方向盘支持上下左右移动光标，中央「选择」切换选区调整，下方提供「行首」「行尾」。右侧为两列四行，依次是「全选／退格」「复制／撤回」「粘贴／翻译」「剪切／剪贴板」。保留长按连续删除、逐步撤回与原位置翻译替换，并适配键盘高度与屏幕方向。
- **候选稳定性**：修复输入和删除时先清空候选、再等待解码结果的更新方式。同一模式下保留已有候选与匹配释义，最新结果到达后更新；相同释义批次不反复取消和加载。相同候选的更新保留触控与滚动状态，点击旧画面的候选会按最新结果重新核对词语，避免使用过期序号。
- **整句翻译**：外语选区按完整短语交给端侧模型，改善逐词翻译造成的语序问题。普通单词间的单个空格随目标语言自然调整；重复空格、Tab、NBSP、换行、原标点、数字、Emoji、链接、邮箱及可识别代码继续保留。
- **中日混合内容**：中文紧贴日语假名时，结合独立汉字段及相邻假名上下文保守识别；只有两者均可信识别为中文的片段才翻译，日语或不确定片段保留原样。未选区仍只翻译中文；中文或混合选区只翻译其中中文；可信纯外语选区使用已安装模型译为中文。失败、原文或选区变化、请求取消时保留原文，成功替换仍可撤回。
- **保留既有功能和数据**：九键连续选择、候选长按与上滑、六种释义语言、个人词频及应用内更新继续保留。包名、用户数据格式与独立 Release 签名不变，额外翻译模型仍按需下载；普通候选栏与展开列表继续不显示 Google 品牌栏。

[下载正式 APK](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk) · [SHA-256 校验文件](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk.sha256) · [验证记录](https://github.com/sharbvane/qingyu-srf/blob/v0.7.1/docs/validation-v0.7.1.md) · [编辑规则与边界](https://github.com/sharbvane/qingyu-srf/blob/v0.7.1/docs/text-editing.md)

Android 8.0+，版本代码 **11**，APK 大小 **105,805,000 字节**。使用原独立 Release 签名，签名与覆盖升级检查已通过。从 v0.7.0 直接覆盖安装，无需先卸载，保留现有设置、用户词典与学习数据。首次安装后，打开轻语并通过系统设置启用、切换输入法。

SHA-256：`2e634fe7bce5afae00745d64520bc007476ef9da0ec362fd8fd265f91bd0ffe8`。

4KB 和 16KB 模拟器的正式包输入、编辑及六语言翻译回归已完成；16KB 首轮仍发生过运行时崩溃，复测通过不排除偶发风险，详情见验证记录。ARM64 真机、厂商应用及长期使用的帧率、耗电与触控手感尚未验证。极短中日共用汉字仍有识别歧义；不确定内容保留原文，模拟器检查不替代真机验收。

## English

v0.7.1 refines text editing, candidate updates and sentence translation while retaining Qingyu's existing light and dark design.

- **Editor panel:** A circular direction pad moves the cursor in four directions. Its center toggles selection, with line-start/end shortcuts below. The right-hand two-column, four-row grid contains Select all / Backspace, Copy / Undo, Paste / Translate, and Cut / Clipboard. Repeating backspace, ordered undo and anchored translation replacement remain, with layouts that adapt to keyboard height and orientation.
- **Candidate stability:** Pending input and deletion queries retain existing candidates and matching annotations instead of clearing them first. Repeated annotation batches are deduplicated. Same-candidate refreshes preserve gestures and scrolling; taps on retained candidates re-resolve the selected word against the latest result rather than reusing a stale decoder index.
- **Sentence translation:** Foreign selections enter the on-device model as complete phrases. Ordinary single spaces between words follow natural target-language spacing. Repeated spaces, tabs, NBSP, newlines, original punctuation, digits, emoji and protected links, email addresses and recognizable code are preserved.
- **Mixed Chinese/Japanese:** Glued Han/kana content is checked using both the isolated Han span and its adjacent kana context. Only spans confidently identified as Chinese in both checks are translated; Japanese or uncertain spans remain unchanged. Unselected and mixed text retain the Chinese-only rule; confidently identified foreign selections require installed models for translation into Chinese. Failed, cancelled or stale requests retain the original, and successful replacements remain undoable.
- **Existing features and data:** Nine-key continuous selection, candidate gestures, six annotation languages, personalization and in-app updates remain. Package identity, user-data formats and production signing identity are unchanged. Additional models remain optional downloads; compact and expanded candidate areas retain their clean layout without the Google brand strip.

[Download APK](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk) · [SHA-256 file](https://github.com/sharbvane/qingyu-srf/releases/download/v0.7.1/Qingyu-0.7.1.apk.sha256) · [Validation](https://github.com/sharbvane/qingyu-srf/blob/v0.7.1/docs/validation-v0.7.1.md) · [Editing rules](https://github.com/sharbvane/qingyu-srf/blob/v0.7.1/docs/text-editing.md)

Android 8.0+, version code **11**, APK size **105,805,000 bytes**. The unchanged production signature and upgrade checks passed. Install over v0.7.0 without uninstalling to retain settings, dictionaries and learned input data. On a first installation, open Qingyu and enable/select it through Android's system settings. The SHA-256 digest is listed above.

Release input, editing and six-language translation checks completed on 4KB and 16KB emulators. The 16KB environment also had runtime crashes before a successful rerun; intermittent risk remains, as documented in the validation record. Physical ARM64 devices, OEM apps and long-term frame rate, battery use and touch feel remain unverified. Very short shared Chinese/Japanese text can be ambiguous; uncertain content stays unchanged. Emulator checks do not establish physical-device compatibility.
