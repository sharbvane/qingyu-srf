# v0.3.0 验证记录 · 2026-10-06

基于原 v0.2 项目迭代。实际安装为 Android 15 / API 35 / x86_64 模拟器的系统输入法，使用触摸、无障碍节点、实际 InputConnection、系统剪贴板和真实 ML Kit 模型检查。没有连接真机；模拟器通过不代表真机帧率、震动手感、功耗或长期稳定性。

## 最终安装包

- [Qingyu-0.3.0.apk](../releases/Qingyu-0.3.0.apk)：59,819,945 字节（57.05 MiB）。
- SHA256：`363D182A1B498FAEA8E1DA0F97D77D6FD11CDE25A2C020EE27E3CF287EB3F4E0`。
- versionCode 3 / versionName 0.3.0；minSdk 26 / targetSdk 35；Release 非 debuggable，启用 R8 和资源压缩。
- arm64-v8a、armeabi-v7a、x86_64；APK v2 签名有效，与 v0.2 相同证书，可覆盖安装。
- 比 v0.2 减少 2,039,437 字节（3.30%）。旧版 APK 及其 SHA256 保持不变。

静态审计覆盖签名、版本、ABI、JNI 方法名与实际 DEX native 标志、三 ABI JNI 导出、ZIP / 自有库对齐、资产来源与字节一致性、中文词性数据、默认语言资源及官方标识 RGBA 像素。结果见 [包元数据](release-package-v0.3.0.json)；原始审计位于 `releases/qa/v0.3.0/final-package-audit.txt`。

默认只含中文与英文输入、释义数据，以及 45 条中译英完整短语。日语、法语模型权重不进入 APK；它们使用按需下载的文件。Google 共用翻译 JNI 仍占主要体积。

## 严重问题的根因与修复

- **候选遮挡应用**：原 `contentTopInsets` 从工具栏起算，没有给候选 header 预留应用空间。现在整个不透明输入法区域共同参与窗口 insets，候选 header 始终固定高度，父容器裁剪子视图。
- **展开后键盘消失**：原展开状态同时存在于候选视图和服务。候选清空时视图先取消展开，服务恢复条件随之跳过，而按键视图仍为 `GONE`。现在由现有面板控制单一展开状态，网格占用固定 body，按键使用 `INVISIBLE` 保留尺寸；清空、选入、旋转和关闭均走共同恢复路径。
- **预测无限延续**：点击与上滑翻译选入共用三轮上限；「X」同时使异步预测和释义请求失效。已上屏文本保留，手动输入重新启用候选。
- **模型删除后仍显示旧译文**：修改模型递增 revision，重新显示输入法时刷新模型、客户端与译文缓存；异步旧 generation 不能写回新请求。

## 实际交互结果

同一份上述 Release APK：v0.3 专项及已有交互 **32/32** 通过，随后补充的模型管理与法语整句分支 **3/3** 通过。后者两项与专项重叠，不合并成独立检查总数。报告均记录目标 APK hash。

- [Release 交互报告](test-results/v0.3.0-ime-v3-tests-release.txt)：固定候选区域、展开/收起的实际窗口边界、12 次快速展开切换和清空组合；键盘恢复及正常输入。
- 中文 Shift 一次直接上屏一个大写字母，双击锁定；恢复小写继续拼音。英文临时大写、锁定和候选大小写正确。
- 真正注入长按滑动手势选择大写、小写、数字；取消不提交，松手只提交一次。五导航同一图标关闭面板；高度、剪贴板、详情沿用对应导航 owner，没有内部返回按钮。
- 中英文预测「X」立即清空；异步回调不会恢复，手动输入重新出现候选。点击以及上滑输入译文均在第三次预测选入后停止。
- 保留九键、英文补全/显式纠错/未知拼写、中文与英文上下文预测、候选详细释义和上滑译文、横向浏览、连续高度、风格、震动设置、Emoji、文本编辑。
- 实际 105 次系统复制只保留最近 100 条；敏感内容及密码字段不留历史，清空在输入法服务重建后保持。
- [Release 模型管理报告](test-results/v0.3.0-model-management-tests-release.txt)：三种语言状态及真实文件大小；日语实际 SDK 删除、缓存失效、中文仍可输入；主动重新下载后恢复日语候选和详情；七音节「我们明天去北京」完整法语模型翻译可长按查看、上滑输入相同整句结果。

同一 Release 的基础输入回归 **17/17** 通过，覆盖候选首次出现、横竖屏保留拼音、释义开关、整句及候选翻页、中英数字快速输入顺序、连续删除、Unicode 删除、长按数字/隔音符、70 字符连续输入、空格滑动光标、密码/数字字段和深色输入。结果见 [Release 基础输入报告](test-results/v0.3.0-ime-baseline-tests-release.txt)。

实际 Release 截图：[浅色](images/keyboard-v0.3.0-light.png)、[深色](images/keyboard-v0.3.0-dark.png)。完整场景截图保存在 `releases/qa/v0.3.0/`；未用生成图片替代运行界面。

## 边界、数据与模型检查

- [Debug 交互及边界报告](test-results/v0.3.0-ime-v3-tests-debug.txt)：**37/37** 通过，含前三项直接触摸/布局检查以及模型来源标识检查；另外覆盖了上述实际系统交互。目标 Debug SHA256：`BF27BCC9B9903686D2BE4F703F97CCE617E67AAE98830F362C4ECC05B8266D2D`。
- 直接检查窄屏 q/p 边缘滑动、取消/模式切换/重置、连续退格与空格光标；EXACT / AT_MOST 父尺寸；最低高度与横屏网格真实 FontMetrics 不越界；预测「X」与候选无重叠；所有面板 owner 和滑块不被重建。
- 五组词性颜色在浅色、深色及按下背景上的最低对比度为 **4.58:1**。120,355 个中文词中 87,160 个有 jieba 真实默认词性标签；未知词、英文及无法标注的整句使用中性颜色，未随机猜词性，也未实现语境消歧。
- 核心检查通过：翻译异常隔离、缺词留空、跨线程快照不可变。
- [输入数据结果](input-language-results.txt)：SCOWL 121,167 词形、1,000 次英文补全/纠错/预测、Android SQLite 200 次九键查询、真实词性/未知标签、词频学习及隐私分支通过。英文检查合计 1,274.74 ms、九键合计 68.34 ms；这是批量引擎检查，不能作为触摸至显示延迟或 FPS。
- `python tools/check_translation_assets.py` 通过：45 条完整中文 → 英文短语，两列格式，无内置日语/法语短语。
- [Debug 模型 API 报告](test-results/v0.3.0-translation-model-tests-debug.txt)：真实中译英、中译日、中译法及英译中完整句子推理通过，附原始译文；ARM64/4KiB、ARM64/16KiB、x86_64/16KiB、32 位进程四个模型守卫分支通过。它们不是四种真机验证，也不评价译文语法/语义准确率。

直接视图/模型 API 辅助检查仅在 Debug 运行，避免依赖 R8 优化后的内部接口；最终 Release 行为通过实际 IME UI 检查。探索过程中失败的记录保留在 QA 目录；其中 Release 模型 API 辅助程序的 `NoSuchMethodError` 是测试程序对优化后内部构造器的调用失败，现脚本明确限制它使用 Debug，不是正式输入法运行路径。

## 兼容性与实机范围

Google 官方 17.0.3 ARM64 翻译库的 RELRO 末端仍不满足 16 KiB 页对齐。在 64 位 ARM 且页大小大于 4 KiB 的进程，本版禁用 SDK 模型下载与推理，显示状态；拼音、英文、本地中英释义继续使用。不能将 ZIP / 自有库对齐结果理解为整个 SDK 完全兼容 16 KiB，详见 [SDK 来源](../third_party/mlkit/SOURCE.md)。

模型下载检查使用模拟器虚拟 Wi-Fi；Google 下载直连受本机网络限制时，临时使用本机已有代理，仅设置在测试模拟器中，测试结束清除。APK 没有内置此代理。模型管理、容量计算和共享中文模型说明见 [模型管理记录](translation-model-management-v0.3.md)。

尚待真机体验：单手和双拇指命中、震动/动画手感、OEM 与第三方富文本应用、高频跨 App、内存/功耗/帧率、长时间稳定性。未因此改动现有核心输入引擎或产品定位。

## 重跑

```powershell
pwsh -File scripts/build.ps1 -Variant Debug -Test -AndroidTest
pwsh -File scripts/start-emulator.ps1
pwsh -File scripts/test-android.ps1 -Variant Debug -Runner TranslationModelInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Debug -Runner ImeV3Instrumentation -SkipBuild -ModelsAvailable
pwsh -File scripts/build.ps1 -Variant Release -Test -AndroidTest
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeSmokeInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeV3Instrumentation -SkipBuild -ModelsAvailable
python tools/audit-release-apk.py releases/Qingyu-0.3.0.apk releases/Qingyu-0.2.0.apk
```

模型相关检查会在指定模拟器/设备主动发起 Wi-Fi 模型下载和日语删除再下载；输入法检查会创建测试编辑框和剪贴板内容。脚本使用与目标构建类型匹配的 androidTest APK；普通安装体验只使用 Release APK。
