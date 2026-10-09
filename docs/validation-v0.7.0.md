# v0.7.0 验证记录

正式包：`Qingyu-0.7.0.apk`，版本代码 10，105,805,000 字节。
SHA-256：`b8b642ce9a449c9db4259981b9b8e100541a167fa480c73b39519f21e50d3e14`。
相较 v0.6.7 增加 3,351,002 字节，主要新增端侧语言识别 SDK；额外语言翻译权重仍不预置。

本轮使用项目独立的 Android 15 / API 35 模拟器，没有操作用户手机。模拟器通过不等于 ARM64 真机验收。

## 构建、签名与覆盖升级

Java 17 / Gradle 8.9 / SDK 35 / NDK r28b；核心检查、Release R8、正式签名与 Android 测试 APK 构建通过。包名仍为 `com.qingyu.ime`，独立 Release 证书、Android 8 兼容证书和签名谱系保持不变。ZIP 16KB 对齐及九个原生库的 LOAD/RELRO 审计通过；ML Kit 官方库按固定摘要校验，未修改其二进制。新共享语言识别资源为 315,520 字节，不是额外语言翻译模型。

从原 v0.6.7 正式包覆盖安装本轮 v0.7.0，`RELEASE_MIGRATION_VERIFY_PASS` 通过。应用 UID、私有偏好与文件哨兵、已有用户词典和三个个性化学习文件的字节均保留，检查不记录词典或学习内容。该回归在最后的全拼候选边筛选修复前完成；最终包签名与存储格式未变。没有卸载、清数据或迁移学习格式。

## 输入与学习

核心检查、真实 Android SQLite 词库检查及九键图检查覆盖全拼、首字母、混拼、连续选择、回退和损坏资源拒绝。现代词库及 599,320 条读音的九键图保持原字节。

本轮数据测试中，全拼 `woyaokanduanshipin` 首选「我要看短视频」；`wykdsp` 位于第 8 项，`woyaokdsp` 位于第 4 项。`mthj` / `mingtianhj` 首选「明天回家」，`kfxm` 首选「开发项目」，`gongzuojh` 首选「工作计划」。最后回归发现合法全拼中的简拼词边耗尽组句预算，收窄全拼路径后，新增「我们明天去北京」「我们明天再讨论」「晚上我们一起吃饭」「你现在有时间吗」「请帮我打开设置」五条 7 个及以上音节的全拼均为首选，选入和预览完整消费原拼音。没有逐个补词或提高搜索预算。复杂简拼重码仍需要选择字母或音节，不宣称所有整句简拼均首位命中。

`kd → 快点` 数据检查：单次选择保持第 16 项，12 次后升至第 2 项，关闭重开词典后仍为第 2 项。新短语、分段词句及禁用学习的字节保持检查通过。最终数据回归的 200 次预热九键查询共 732.39 ms；这些是模拟器批量查询数据，不是手机按键延迟或帧率承诺。实际键盘学习验证另检查真实九键选词、落盘和重新打开输入界面后的排序，以及中文预测一次选择只计数一次。

加强真实计数断言后发现，收起输入视图不一定结束输入会话，原保存回调可能尚未运行。本轮补齐非结束会话的收起保存，在解码线程按序写入。修复后的完整 Debug 键盘回归通过 42 组检查，真实 12 次选择使 `kd → 快点` 持久计数从 150 增至 162，重开后仍在前 3 项；一次中文预测的全局及上下文计数各只增加 1。此 Debug 运行早于最后的全拼词边筛选收窄，其编辑、翻译与保存实现与最终包一致。

## 编辑与翻译保护

编辑历史检查覆盖候选提交、原始拼音、输入、删除、剪切、粘贴、替换、Emoji、选区、外部改动、提交失败和容量边界。历史只在当前会话内存中保存；宿主不能提供可信全文时禁用不安全的撤回与翻译替换。密码字段不读取编辑历史。

原生输入框和独立进程 Chromium textarea 均通过系统 InputConnection 测试删除、逐步撤回及原范围翻译替换。浏览器全文提取使用 `partialStartOffset == -1` 判断，不因 Chromium 的正数 `partialEndOffset` 拒绝全文；仍保留全文、选区及外部修改交叉校验。[Android 接口规范](https://developer.android.com/reference/android/view/inputmethod/ExtractedText#partialStartOffset)，[Chromium 实现](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java#480)。临时诊断日志已删除。

文本保护检查覆盖中文、外语、中外混合选区、标点、数字、Emoji、换行、空白、URL、邮箱、代码与日语假名连续片段。失败不返回可替换文本；替换前再次核对原文和选区。模型代际变化、取消和新请求以受控回调测试验证完整请求失效。UI 外部追加案例只证明追加未丢失，迟到结果安全结合锚点及取消测试验证。

编辑与翻译实现一致的 Debug 包，直接模型检查 `ALL_EDITOR_TRANSLATION_CHECKS_PASS` 在 4KB 与 16KB 环境均通过：真实离线识别中文及六种外语；六种中文译外语和外语选区译中文；取消与模型代际变化的回调保护。4KB 环境还实际删除法语模型，验证不替换、不自动下载，明确重新下载后恢复就绪及可用译文。直接辅助接口的测试 APK 与正式包区分记录。

编辑请求最多 4,096 个 UTF-16 单元、64 个文字片段、45 秒。严格保留分隔符会限制外语整句自然度；极短文本和中日共用汉字存在识别歧义。规则见 [文本编辑与内容保护](text-editing.md)。

## 正式包与测试环境

最终正式 APK 的 `ImeV6Instrumentation` 在 API 35 / x86_64 / 4KB 页 / 字体 1.0 通过全部 56 组检查，包括现代词、误触纠错、连续整句、40 轮选词删除、12 次切换 Android 设置应用、候选展开、多语言释义同步，以及日语和德语模型实际删除/重新下载。此前失败的七音节「我们明天去北京」法语详情、候选注释和上滑输入在最终包中通过。

基础 `ImeSmokeInstrumentation` 同一最终包在 4KB 环境重跑通过全部 17 项：旋转、释义开关、70 字连续输入、中英数字提交次序、Unicode 与连续删除、光标、密码及数字框。首轮在旋转后清空并切换释义开关，再次输入 `xiangmu` 时只显示前缀候选；重跑通过，尚未确定具体原因，偶发状态失同步仍需真机排查。

最终正式 APK 的 `ImeV7Instrumentation` 在 API 35 / x86_64 / 4KB 页 / 字体 1.0，以及官方 `google_apis_ps16k` / 实测 16384 字节页 / 字体 1.3，分别通过全部 37 组检查。包含既有九键布局、大小写与数字滑选、连续音节、简拼/全拼/混拼、分词、回退、滚动、旋转和密码测试，以及新编辑面板、原生输入框和独立进程浏览器测试。

失败证据保留。API 35 的 16KB 镜像 revision 5 曾在旧版轻语自动服务和系统 Gboard、Messages、Android System Intelligence 中出现 ART / 引用队列故障；本轮也出现引用队列退出。全拼词边修复前的正式包通过 16KB 的 37 组检查，六语言辅助检查亦通过，但随后更长的 `ImeV6Instrumentation` 在通过 14 组后再次因 `ReferenceQueueDaemon` / `ReferenceQueue.enqueuePending:239` 退出。最终包另有一轮在镜像冷启动低内存时被系统 `lowmemorykiller` 终止，尚未开始检查，该轮不计作通过；启动负载平稳后，同一最终包完成上述 37 组检查。引用队列问题的具体根因未确认，没有为其修改 SDK 或原生库，16KB 长时间运行问题仍未排除。4KB 一次并发负载下的九键点击检查失败，同一包单独重跑通过；不能据此承诺真机触控延迟或长时间稳定性。

## 复现与边界

```powershell
pwsh -File scripts/build.ps1 -Variant Release -Test -AndroidTest
pwsh -File scripts/test-android.ps1 -Serial <设备序列号> -Variant Release -Runner ImeV7Instrumentation -SkipBuild -ModelsAvailable
pwsh -File scripts/test-android.ps1 -Serial <设备序列号> -Variant Release -Runner ImeV6Instrumentation -SkipBuild -ModelsAvailable
pwsh -File tools/test-input-data.ps1 -Serial <设备序列号>
python tools/audit-release-apk.py
```

直接模型辅助接口使用同源 Debug 包测试；它不作为发布 APK。正式包通过真实键盘与面板验证 R8 后的行为。完整本地证据在忽略的 QA 目录中，公开静态报告为 [release-package-v0.7.0.json](release-package-v0.7.0.json)，数据摘要为 [input-language-results.txt](input-language-results.txt)。

未验证 ARM64 真机、各厂商聊天应用、单手触感、震动、长期耗电及内存增长。复杂简拼与整句排序仍有改进空间；没有替换既有全拼引擎或清除旧学习记录。
