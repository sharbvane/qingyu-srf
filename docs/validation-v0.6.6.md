# v0.6.6 验证记录

正式包：`releases/Qingyu-0.6.6.apk`，版本代码 8，96,441,001 字节。
SHA-256：`88af583f5d2c6b0134d245eca6128c1b3dcc5ad5d506515ad88f63d016a5d9dc`。
以下实际检查均针对本轮代码；正式 APK 检查针对上述最终摘要。设备是本项目独立模拟器，没有操作用户手机。

## 构建与签名

- Java 17 / Gradle 8.9，Android SDK 35，NDK r28b；`:core:checkCore`、Release R8、正式签名及测试 APK 构建通过。
- 包名仍为 `com.qingyu.ime`，版本代码 7 → 8；正式签名不变，API 26–27 兼容签名及 API 28+ 签名谱系检查通过。
- 从已发布的原始 v0.6.5 覆盖安装，原应用 UID、私有设置哨兵、原生用户词典及已有中英文学习文件字节均保留。未打印用户词典或学习记录内容。
- 静态审计：APK 16KB ZIP 对齐；三种 ABI 的自编译库 LOAD/RELRO 对齐；官方 64 位模型库 LOAD 与整页 RELRO 保护范围检查通过，官方库没有改写。中英资源和按需模型边界保持不变。

## 九键与输入

- Android 15 / API 35 / x86_64 / 4KB 页 / 系统字体 1.0：`ImeV66Instrumentation` 17 组实际 IME 检查通过。
- 相同最终 APK 在 16KB 模拟器、系统字体 1.3 再次运行全部 17 组九键检查通过，结束后恢复字体 1.0。4KB 模拟器的基础 IME 17 组检查也通过，覆盖连续输入、删除、光标、旋转及输入框类型。
- 其中 13 组保留 v0.6.5 的布局、实时拼音、分词、部分选词、清空、确认、大小写/数字滑选、取消、模式切换、英文、全拼回车、连续退格、深色、横屏和密码字段检查。
- 新增连续选择 `ni → hao → shi → jie`，整段拼音保持 composing；回退和全部选完后的退格重选，不删除原数字编码；仅点击候选后提交「你好世界」。未选尾部删除/重输保留首音节。
- 真实 `7426` 的读音超过四项，固定侧栏可滚动并选择屏外 `qiao`，滚动不选词、不上屏、不泄露数字，主键盘尺寸与位置保持固定。
- `KeyboardTouchCheck` 在正式 IME 回归中通过：八项读音滚到底/反向滚动、越界和多点取消、侧栏间隙、固定几何、回退，以及读音更新期间原长按浮层保持稳定。
- 真实 Android SQLite 回归通过 `NINE_KEY_V065_CHECKS_PASS`、`NINE_KEY_V066_CHECKS_PASS` 和 `ALL_INPUT_DICTIONARY_CHECKS_PASS`，覆盖明确隔音符、完整读音、部分选词后保留约束、罕见读音、现代词及学习记录。200 次预热九键查询共 75.15ms；这是模拟器批量查询时间，不是手机按键延迟。

## 翻译与 16KB

- 新建官方 API 35 `google_apis_ps16k` x86_64 模拟器，运行 `getconf PAGE_SIZE` 实测 16384。
- Debug `TranslationModelInstrumentation`：包内 ARM64/x86 的 16KB 布局、32 位 4KB 布局，以及未知 ABI、截断、溢出、端序、对齐、可写/可执行区域冲突等检查通过。
- 此 16KB 设备实际下载中文枢纽与日/法/德/俄/西模型，英语翻译及其余五种语言的长句翻译、详细释义、真实下载字节、模型删除重下与繁忙恢复全部通过 `ALL_MODEL_CHECKS_PASS`。
- 随后在同一 16KB 设备安装最终正式 APK，`ImeV6Instrumentation` 的 56 组实际检查通过：全拼、常用词、纠错、整句组词、学习、跨 App、词性、导航、预测上限、候选展开、剪贴板、六语言同步、模型管理及上下滑动翻译。普通候选与展开候选无 Google 品牌行；长按详情仍保留模型来源。
- 16KB ARM64 支持的依据是官方发布记录、未修改的库文件及 AOSP 加载器结构检查，**没有 ARM64 真机运行证据**。详细依据和准确边界见 [SDK 说明](../third_party/mlkit/SOURCE.md)。

## 复现与本地证据

```powershell
pwsh -File scripts/build.ps1 -Variant Release -Test -AndroidTest
pwsh -File scripts/test-android.ps1 -Serial <设备序列号> -Variant Release -Runner ImeV66Instrumentation -SkipBuild
pwsh -File scripts/test-android.ps1 -Serial <已装好模型的设备序列号> -Variant Release -Runner ImeV6Instrumentation -SkipBuild -ModelsAvailable
pwsh -File tools/test-input-data.ps1 -Serial <设备序列号>
python tools/audit-release-apk.py
```

完整报告和截图在项目本地 `releases/qa/v0.6.6/`，默认不提交到 GitHub；摘要、资源摘要及正式包校验文件可公开。早期测试注册和异步候选等待问题保留在失败记录中，修正测试后重新运行通过，未把失败结果计为通过。

仍未验证 ARM64/16KB 真机、各手机厂商下载服务差异、震动与单手触控手感，以及长时间耗电/内存表现。没有扩充词库或进行无关架构重构。
