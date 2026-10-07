# v0.4.0 验证记录 · 2026-10-07

最终 Release 已通过静态审计、完整交互、基础输入回归及字体放大检查；同源 Debug 的完整交互、模型与更新边界检查也已通过。

基于现有 v0.3 项目迭代，保留中文输入优先、墨绿色和本地释义定位。实际验证环境为 Android 15 / API 35 / x86_64 模拟器，使用系统输入法、实际 InputConnection、触摸手势、系统剪贴板和真实 ML Kit 模型。没有连接真机，模拟器结果不代表真机手感、帧率、功耗或长期稳定性。

## 最终 Release 安装包

- [Qingyu-0.4.0.apk](../releases/Qingyu-0.4.0.apk)：59,836,541 字节（57.06 MiB）。
- SHA256：`24634AA1D406AF73D1FF23DCFB42947473DDA6FB0BBD791751112233F18B9BDC`。
- versionCode 4 / versionName 0.4.0；minSdk 26 / targetSdk 35；Release 非 debuggable，启用 R8 和资源压缩。
- arm64-v8a、armeabi-v7a、x86_64；签名有效，与 v0.3 相同证书，可覆盖安装。
- 比 v0.3 增加 16,596 字节（约 0.028%）。额外语言权重没有进入 APK；默认资源仍只有中文、英文词典及 45 条中译英完整短语。

静态审计通过签名、版本、ABI、14 个真实 DEX/JNI 方法及三 ABI 导出、ZIP / 自有库对齐、全部 16 个数据资产与来源一致性、词性数据、默认语言资源、官方归因标识的尺寸和 RGBA 像素检查。见 [包元数据](release-package-v0.4.0.json)，原始审计位于 `releases/qa/v0.4.0/final-package-audit.txt`。SDK 的 16 KiB ARM64 限制仍保留，不能把 ZIP 或自有库对齐理解为整个 SDK 已兼容该类真机。

v0.4.0 已发布到 [GitHub Release](https://github.com/sharbvane/qingyu-srf/releases/tag/v0.4.0)，并提供 APK 与 SHA-256 校验文件。

## 改动与实际交互结果

[最终 Release 完整交互报告](test-results/v0.4.0-ime-v4-tests-release.txt) **45/45** 通过，目标为上述 `24634AA…` 安装包。[同源 Debug 完整交互报告](test-results/v0.4.0-ime-v4-tests-debug.txt) **50/50** 通过，目标 SHA256 为 `78365E8B7B984264B3F87BD00DB8C11FC7529FDA10CE152362CECD345AC5B650`。Debug 额外覆盖五组内部 API 边界；不把两种构建的结果相加。以下行为已完成实际交互复核，包含顶部手势修复。

- **顶部布局**：整体固定为竖屏 100 dp、横屏 92 dp。空闲时较矮空候选栏与 48 dp 导航共用空间；输入时候选覆盖导航，结束后恢复，按键位置不跳动。原始拼音在左上方凸出并紧贴键盘，候选栏不再绘制拼音；选入后提示消失，密码字段没有提示。
- **回车原始拼音**：输入中直接提交原字母，不选中文、不触发发送；保留已选分段对应的编码、显式隔音符和快速排队输入。没有组合串时继续使用正常换行或编辑器动作。
- **分段预览闪回**：点击候选此前提前清空 preview，导航会短暂恢复，再被引擎返回的剩余组合串覆盖。现在保留 preview，直到串行引擎确认分段选择或整串完成；原始编码单独用于提示和回车，session / revision 丢弃过期结果。
- **顶部动效手势冲突**：高度动画每帧触发候选视图尺寸变化，取消正在进行的预测上滑。改为一次设置最终触控几何，以 110 ms 原生透明度过渡表达状态；固定顶部空间与按键位置保留。最终 Release 与同源 Debug 中点击及上滑预测均通过三轮上限检查。
- **纵向展开**：网格占用已有键盘 body，上下拖动及惯性浏览后能选择后续候选；不再左右翻页。纵向滚动不误触翻译；展开列表通过长按释义页输入译文，普通候选栏上滑译文保留。快速展开/收起及清空不会丢失键盘。
- **新语言**：德语、俄语、西班牙语真实模型释义出现，长按可查看，上滑输入对应结果；六语言管理行均显示就绪状态及实际正数文件大小。首次明确选择非英语释义语言发起 Wi-Fi 按需下载，正常打字不发起下载。
- **删除与恢复**：德语 SDK 删除后清理对应的解包模型残留及暂存目录，实测空间释放、旧译文缓存失效，中文输入继续。明确重新下载后恢复释义、详情和译文选入。容量取实际已安装与暂存文件字节，不写死模型大小，也不以下载体积代替占用。
- **更新入口**：更多中的检查更新进入应用内更新页，返回后输入法继续工作。更新页与项目主页功能说明见 [应用内更新](in-app-updates.md)。
- **已有功能回归**：中文大小写及长按滑动、三轮预测与 X 清空、九键、英文补全/显式纠错/未知拼写、中文预测、候选详情和译文、中英混输、连续高度、键盘风格、震动开关、Emoji、文本选择/复制/剪切/粘贴、最近 100 条剪贴板、敏感/密码排除、清空后服务重启、深色输入和日法模型交互均通过。
- **直接边界检查**：最新 Debug 同时覆盖纵向拖动/惯性和无障碍滚动、取消/长按与视口边界、字母快捷触摸状态、候选尺寸与低饱和词性对比度、固定面板 owner/连续高度，以及本地词典和模型译文的来源标识。这些内部 API 检查与实际 UI 检查共计上述 50 项，不另加重复总数。

[最终 Release 基础输入报告](test-results/v0.4.0-ime-baseline-tests-release.txt) **17/17** 通过，使用同一 `24634AA…` 安装包，包含横竖屏组合保留、释义开关、候选展开滚动选入、快速中英数字顺序、连续输入、Unicode 删除、连续退格、长按数字、空格光标、密码和数字字段、深色重启。该基础检查与上述完整交互报告分别记录，不把重复功能叠加为总数。

[字体 1.3 倍 Release 报告](test-results/v0.4.0-ime-v4-tests-font-1.3-release.txt) **6/6** 通过，报告在开跑时记录 `System font scale: 1.3`，目标仍为同一 `24634AA…` 安装包。该分支复核顶部空间与按键几何、原始回车的三项行为、纵向候选拖动、拼音提示与密码字段、更新入口；未重复跑全部模型或旧版功能，不将六项等同完整字体适配或真机无障碍验收。

## 模型与更新边界检查

[Debug 模型 API 报告](test-results/v0.4.0-translation-model-tests-debug.txt) 在上述同一 Debug hash 下重新通过。真实中文 → 英语、日语、法语、德语、俄语、西班牙语完整句子及英文 → 中文推理通过，原始译文和实际模型文件大小在报告中。四个 ABI / 页大小守卫分支通过：64 位 ARM / 4 KiB、64 位 ARM / 16 KiB、x86_64 / 16 KiB、32 位进程；这些分支检查不代表四种真机测试，也不评价模型语义准确率。

[Debug 更新边界报告](test-results/v0.4.0-app-update-tests-debug.txt) 在同一 Debug hash 下 **7 组**重新通过：正式版本与可空 JSON 元数据、数字版本比较、官方 HTTPS 资产范围、大小/摘要/签名与禁止降级、安装 MIME 和临时只读授权、真实 APK / 系统安装器解析、下载记录恢复与取消。报告包含内部私有 APK 的原子发布、外部原始文件改写隔离及失败快照清理。辅助 API 与最终 Release UI 结果分开记录，不混合为同一安装包的总数。

更新下载由系统 DownloadManager 持久化；不使用可直接打开完成 APK 的通知。安装前把外部下载复制为内部私有快照，核验包名、发布版本、versionCode、当前签名和可用的官方摘要后原子发布；Provider 只读私有 `verified.apk`，不交付可被旧 Android 存储权限改写的外部文件。取消、失败和更新完成会清理相应文件。未知来源授权及系统安装确认保留，不能静默安装。

真实 GitHub 新版发现、APK 网络下载及覆盖安装尚未完整验收。测试时 v0.4.0 尚未发布，没有可用于升级验证的新版资产，且开发机公开 API 曾返回 HTTP 403 限流；现已发布的 v0.4.0 可供下载安装，但同版本不会触发应用内更新。入口、可恢复失败、安装器解析和安全边界已通过检查，后续版本发布时仍需验证真实在线升级。

## 验证范围与重跑

最终 Release 的六张真实模拟器截图已完成一次批量视觉检查，覆盖空候选栏与导航、[浅色输入](images/keyboard-v0.4.0-light.png)、[深色输入](images/keyboard-v0.4.0-dark.png)、纵向展开网格、1.3 倍字体输入及更新页。拼音提示与键盘贴边相连，候选和释义均位于固定区域内；本轮未发现需要继续修改的视觉缺陷。检查完成后系统字体已恢复为 1.0。该结果仅覆盖上述模拟器画面，真机及线上新版覆盖安装限制仍如前文所述。

模型检查使用模拟器虚拟 Wi-Fi 与用户明确下载动作。Google 官方 17.0.3 ARM64 库仍有 RELRO 末端未按 16 KiB 对齐的问题；64 位 ARM 且页大小大于 4 KiB 时禁止该 SDK 下载与推理，本地中英文输入、释义继续可用，详见 [SDK 来源](../third_party/mlkit/SOURCE.md)。

真机单手命中、震动与动效手感、OEM / 富文本应用、高频跨 App、功耗、内存、帧率及长期稳定性仍需安装体验。辅助模型/更新 API 检查只在 Debug 运行；最终 Release 使用实际系统 IME UI 检查验收。失败探索记录保留在 `releases/qa/v0.4.0/`，不与通过报告混淆。

```powershell
pwsh -File scripts/build.ps1 -Variant Debug -Test -AndroidTest
pwsh -File scripts/start-emulator.ps1
pwsh -File scripts/test-android.ps1 -Variant Debug -Runner TranslationModelInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Debug -Runner AppUpdateInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Debug -Runner ImeV4Instrumentation -SkipBuild -ModelsAvailable
pwsh -File scripts/build.ps1 -Variant Release -Test -AndroidTest
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeSmokeInstrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Variant Release -Runner ImeV4Instrumentation -SkipBuild -ModelsAvailable
python tools/audit-release-apk.py releases/Qingyu-0.4.0.apk releases/Qingyu-0.3.0.apk
```

模型相关检查会主动下载及删除/重新下载对应测试语言；输入法检查会创建测试编辑框与系统剪贴板内容。普通安装体验只需要最终 Release APK。
