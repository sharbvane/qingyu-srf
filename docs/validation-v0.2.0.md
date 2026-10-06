# v0.2.0 验证记录 · 2026-10-06

本版在 Android 15 / API 35 / x86_64 模拟器中实际安装为系统输入法，通过真实触摸、无障碍控件和 InputConnection 验证。没有连接真机；下面的结果不代表真机手感、UI 帧率、功耗或长期稳定性。

## 最终安装包

- 文件：`releases/Qingyu-0.2.0.apk`；61,859,382 字节（58.99 MiB）。
- SHA256：`29B11C99EF6E31A45E2CDFBE650D58161C8060DE0D26E66C89188C0E0A919801`。
- versionCode 2 / versionName 0.2.0；minSdk 26 / targetSdk 35；Release 非 debuggable。
- arm64-v8a、armeabi-v7a、x86_64 三 ABI。APK v2 签名有效，与 v0.1.0 相同证书，可覆盖安装。
- ZIP 16 KiB 对齐、自有拼音库 ELF LOAD / RELRO 16 KiB 对齐；数据、许可证和来源 manifest 核验通过。
- Google 官方标识在 Release 经 AAPT 无损 PNG 编码优化，尺寸及全部 RGBA 像素保持一致。

本版静态核验：`releases/qa/v0.2.0/final-package-audit.txt`、`release-package.json`。旧版 APK、旧版包元数据及截图保留。

## 实际系统输入检查

同一份上述 Release APK：基础输入 **17/17** 通过，新增交互的未下载模型分支 **26/26** 通过。报告记录包 hash，测试读取 Android 编辑框实际文本和系统剪贴板，没有增加产品测试提交接口。

基础回归包括：第一候选出现不移动键盘；点击仅输入中文；横竖屏保留 preedit；释义开关不移动候选；整句输入；展开翻页；快速中英数字输入顺序；逐字和连续删除；Unicode emoji 删除；长按数字和拼音隔音符；70 字母连续输入；空格滑动光标；密码与数字字段；深色重启输入。

新增交互实际覆盖：

- 五图标导航、无常驻标点栏；展开只在正在输入且有候选时出现，位于候选行末尾。
- 九键 `52432` 输入「开发」，长按九键数字直接输入数字。
- `hel` 补全 hello，hello 后预测 world，两者显示中文释义；`helo` 提供 hello 建议，空格保留实际拼写；未知拼写也不丢字。
- 「中国」后预测「人」，点击继续输入「中国人」；预测词显示释义，预测行没有展开按钮。
- 长按「开发」显示完整词典义项且不提交；上滑输入 develop；横滑候选不误选。
- 日语、法语注释和上滑提交；「我爱你」完整日语译文、「我们明天去北京」整句法语译文。
- 未下载模型的「文化」日语翻译返回明确状态，原拼音保留，仍可正常选择中文。
- 高度保存任意连续值（实测 97.3%），两种风格切换、震动开关、Emoji 插入和删除。
- 全选、复制、剪切、粘贴；选择模式配合箭头实际扩展选区并仅复制选中文本。
- 105 次真实系统复制事件只保留最近 100 条；敏感标记和密码字段内容不保存。
- 历史条目可粘贴，清空后不会重新导入当前剪贴板；跨备用输入法切换及服务重建后保持清空。
- 收起后重新弹出仍正常输入「开发」；深色模式继续正常输入。

证据：`releases/qa/v0.2.0/ime-baseline-tests-release.txt`、`ime-v2-tests-release.txt`。实际渲染截图已保存为 `docs/images/*v0.2.0*.png`，没有使用生成图替代运行界面。

## 本地引擎和数据检查

- 纯 Java 核心检查通过：翻译异常隔离、缺词留空、跨线程快照不可变。
- 真实 AOSP / JNI：6,285 次搜索、1,000 次输入删除循环、100 组随机 64 字符输入删除；候选整句预览跨原生九音节边界、保留已选前缀、非法尾部、候选 ID 与分组退格，预览不学习。
- SCOWL 121,167 个词形，1,000 次英文补全/纠错/预测检查通过；Android SQLite 中 200 次九键查询通过；完整释义与三语言本地短语资产检查通过。
- 此前本轮引擎微基准：Java/JNI 搜索 p95 0.503 ms；纯 native p95 0.251 ms；200 次九键查询合计 97.45 ms。它们不是手机触摸至显示的总延迟或动画 FPS。

详细结果：`docs/engine-smoke-results.txt`、`docs/engine-adapter-results.txt`、`docs/input-language-results.txt`。

## 实际端侧模型

同一份 Release 的 `TranslationModelInstrumentation` 已通过：真实下载中文、日语、法语所需模型；本地短语表没有的「今天下午我想和朋友一起喝咖啡。」分别产生英语、日语、法语模型译文；英文完整句子也产生中文模型译文。结果来源为 `Google Translate · 端侧翻译`，没有用逐词拼接或预写词典回答替代通用翻译。

报告：`releases/qa/v0.2.0/translation-model-tests-release.txt`。这些检查确认模型实际成功工作，不评价句子译文的语法或语义准确率。

下载后同一 Release 的界面流程也 **26/26** 通过：日语「文化」长按实际显示 Google 模型来源与官方标识，操作按钮标识正确，查看不提交，上滑输入真实模型译文；剪贴板、重建及深色检查继续通过。单独报告 `ime-v2-tests-with-models-release.txt`，没有覆盖未下载模型分支证据。

另关闭模拟器 Wi-Fi 和移动数据，前后均确认 **Active default network: none**，重新运行模型检查。缓存模型在 2.2 秒内完成中译英、中译日、中译法和英译中的完整句子推理，`ALL_MODEL_CHECKS_PASS`。独立报告 `translation-model-tests-offline-release.txt` 包含断网条件、相同 APK hash 和原始模型输出；在线报告保留。测试结束已恢复模拟器网络并清除临时代理配置。

模型守卫的四个逻辑分支也通过：ARM64/4KiB 允许，ARM64/16KiB 禁止，x86_64/16KiB 允许，32 位进程允许。它们是逻辑检查，不是相应四种真机验证。

初始模拟器 Google 服务直连失败；连接虚拟 Wi-Fi 后，使用本机已有代理仅配置该模拟器，完成模型下载。APK 不内置该代理，也没有将其设为产品设置。手机首次下载需能够连接 Google 模型下载服务。

## 兼容性与实机范围

Google 官方 17.0.3 ARM64 翻译库的 RELRO 末端不满足 16 KiB 页对齐。在 64 位 ARM 且页大小大于 4 KiB 的进程，本版禁用 SDK 模型下载和推理，并显示状态；拼音、英文、本地词典和三语言常用短语继续使用。不能将 ZIP 和自有库对齐结果表述为整个 SDK 完全兼容 16 KiB。详见 `third_party/mlkit/SOURCE.md`；受影响真机未实测。

仍需真机验证：双拇指命中与震动手感、OEM/微信/富文本兼容性、高频跨 App、内存/功耗/帧率、长时间稳定性。预测是词频及轻量上下文模型，译文也可能不准确。Google SDK 的网络元数据范围在来源说明和安装说明中列明。

## 重跑

```powershell
pwsh -File scripts/build.ps1 -Variant Release -Test -AndroidTest
pwsh -File scripts/start-emulator.ps1
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeSmokeInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeV2Instrumentation -SkipBuild
# 以下会明确发起 Wi-Fi 模型下载；先完成未下载模型分支检查。
pwsh -File scripts/test-android.ps1 -Variant Release -Runner TranslationModelInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeV2Instrumentation -SkipBuild -ModelsAvailable
```

测试会在指定模拟器/设备安装 APK 和测试包、选择轻语，并创建测试编辑框与测试剪贴板内容。一般安装体验只需使用 Release APK。
