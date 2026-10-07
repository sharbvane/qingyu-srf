# v0.5.0 验证记录 · 2026-10-07

正式 Release 已通过静态审计、原 v0.4 数据保留覆盖升级、53 项完整输入法交互、17 项基础输入及字体 1.3 倍的 14 项专项检查，相关截图已完成视觉复核。v0.5.0 已发布至 GitHub，原 v0.4 APK 保留；三个未学习整句的语义排序缺口继续公开记录。

## 最终 Release 安装包

[Qingyu-0.5.0.apk](../releases/Qingyu-0.5.0.apk)：93,684,139 字节，约 93.7 MB / 89.34 MiB；SHA-256 为 `76D70841DA75BF54FF4C65865B1F415F53E2D843E162E978EEDA40453882102B`，另有[校验文件](../releases/Qingyu-0.5.0.apk.sha256)。现代中文词库扩大覆盖，安装包由 v0.4 的 59,836,541 字节增加 33,847,598 字节，约 56.57%；这次增长来自中文数据扩充，APK **未预装额外外语翻译模型**。

| 项目 | 当前状态 |
| --- | --- |
| 正式 APK 身份 | 已确认上述字节数与 SHA-256，原 v0.4 APK 保留 |
| APK 版本、ABI、R8/JNI、资源与签名静态审计 | 通过；versionName 0.5.0 / versionCode 5 / minSdk 26 / targetSdk 35；arm64-v8a、armeabi-v7a、x86_64；[原始审计](release-package-v0.5.0.json) |
| 原 v0.4 → 正式 v0.5 系统覆盖安装 | API 35 / x86_64 模拟器 `install -r` 成功；UID、设置、私有文件及原用户词库字节保持 |
| 最终 Release 实际 IME 完整回归 | 53 项通过，系统字体 1.0，含已下载模型分支 |
| 最终 Release 基础输入、横竖屏 | 17 项通过 |
| 字体 1.3 倍、最终截图与视觉复核 | 14 项专项通过；字体 1.0 / 1.3 输入、展开和空闲画面已复核，系统字体已恢复 1.0 |
| GitHub 新版本发现、APK 下载到安装的完整链路 | 未完成：v0.5 发布后应用内尚无更高版本可供下载 |

正式 APK 在 API 28+ 使用独立 Release 证书 `1d6872a840fe42468cdc5af2d11eeee87455c4b635e2fd2b60168b082c4e0cf5`，v3 签名轮换证明继承 v0.4 身份；API 26–27 保留同证书 v2 兼容签名。静态审计通过分平台签名、18 个数据资产、14 个真实 DEX/JNI 方法及三 ABI 导出，并保留 16 KiB ARM64 SDK 降级限制。

[覆盖前种子记录](test-results/v0.5.0/migration-seed.txt)以原 v0.4 正式 APK 为目标设置应用私有哨兵；[覆盖后记录](test-results/v0.5.0/migration-verify.txt)确认新当前证书、已认证旧签名历史、相同应用 UID、私有偏好 / 文件及用户词库字节。该结果来自 API 35 / x86_64 模拟器，不代表 Android 8 或其他厂商真机均已完成实际覆盖测试。

独立密钥、兼容密钥、签名轮换证明及私有备份的[恢复检查](test-results/v0.5.0-signing-recovery-check.txt)通过。首次 v0.4 → v0.5 在手机上打开本地 APK，经系统安装器覆盖安装，**不先卸载**；旧更新器无法识别首次新证书轮换，后续使用稳定 Release 身份。保存、加密异地备份和恢复步骤见[正式签名说明](release-signing.md)。

## 中文输入与学习

最终语料在此前 389 例上增加 7 个 ü / v 拼音用例，共 **396 例**。同一语料、同一实际 Java → JNI 解码器与 Android SQLite 查询链的结果如下；它是固定回归集，不是随机抽样准确率。

| 候选指标 | v0.4 原词库 | v0.5 现代词库与候选合并 |
| --- | ---: | ---: |
| 396 例第一候选 | 335 | 366 |
| 396 例前五候选 | 350 | 393 |
| 385 例正常词、多音节、歧义及 ü / v 输入前五 | 347 | 385 |
| 5 例轻微误拼前五 | 0 | 5 |
| 6 例未学习整句前五 | 3 | 3 |

`anzhuo → 安卓`、`dangang → 单杠`及常见现代词已命中前列；合法切分和显式隔音符仍保留。纠错候选不改写用户原始拼音，回车可提交原字母，点击候选才提交中文。模型、词库来源、逐例排名和重跑命令见[中文质量记录](chinese-quality-v0.5.md)、[v0.4 原始输出](chinese-quality-baseline-v0.4.txt)、[v0.5 原始输出](chinese-quality-v0.5.txt)。

仍公开保留三个未学习整句的语义缺口：`qingsaomazhifu → 请扫码支付`、`woxiangdawangyueche → 我想打网约车`、`woyaokanduanshipin → 我要看短视频`。相关单词存在，完整拼音图搜索也没有耗尽查询预算；缺少上下文语料评分，错误组句仍排在前面。[严格协议原始记录](test-results/v0.5-chinese-quality-strict.txt)保留三个失败和非零退出；最新 [Acceptance 输出](chinese-quality-v0.5.txt)区分 393 项日常必测与 3 项评估用例，前者全部通过、后者全部未达标，所有 CASE 与整体 366 首项 / 393 前五统计完整保留。**不能写成全部 396 例通过**。本轮没有为这三句添加排序特例，也不保证任意未学习长句的语义首选。

[真实 SQLite 与 native 学习报告](input-language-results.txt)验证：`kd → 快点`初始第 15 位，一次选择仍第 15 位，累计 12 次升至第 1 位，保存并重开后保留。正规编码的前两次选择没有额外编码词频加分；新增组合短语和非规范错拼别名需累计至少 3 次才作为直接候选，避免一次误选立即成为固定首选。全拼、简拼和已选正规读音关联保存。

分段选择原生候选“我要看”再选择“短视频”三轮后，完整 `woyaokanduanshipin` 与 `wykdsp` 均可召回“我要看短视频”，重开后保留；每轮后缀词和完整句各计一次。预览、密码及关闭个性化学习期间不新增计数，已有排名保留。另有[原生用户词升级检查](chinese-upgrade-results.txt)：旧词库生成的用户词在新词库重开后仍可完整提交，预览和禁止学习期间用户文件字节不变。

原生池耗尽边界已修复；[native 报告](engine-smoke-results.txt)和[Java/JNI 报告](engine-adapter-results.txt)通过原有随机输入、逐键删除、分段选择、64 字符组合、完整预览和快照恢复检查。原生词库与现代 SQLite 是独立资源；现代 SQLite 安装或打开失败时保留 native 中文输入及原有英文 / 九键辅助数据。

## 已完成的最终 Release 实际检查

报告均来自 Android 15 / API 35 / x86_64 模拟器，目标均为上述 `76D70841…82102B` 正式 APK；完整与基础回归字体为 1.0，字体专项为 1.3。

| 检查 | 实际结果与范围 | 原始报告 |
| --- | --- | --- |
| v0.5 实际 IME 与父版本回归 | 53 项通过；真实系统 IME、InputConnection、触摸、剪贴板和已下载模型 | [ImeV5Instrumentation Release](test-results/v0.5.0/ime-v5-release.txt) |
| 基础输入 | 17 项通过；横竖屏组合保留、70 字符连续分段输入、中英数字顺序、Unicode 删除、密码 / 数字字段和深色重启 | [ImeSmokeInstrumentation Release](test-results/v0.5.0/ime-baseline-release.txt) |
| 字体 1.3 倍专项 | 14 项通过；v0.5 的 8 项输入检查与 v0.4 的 6 项布局 / 原始回车 / 展开 / 更新入口检查，未重复完整模型回归 | [ImeV5Instrumentation 字体专项](test-results/v0.5.0/ime-v5-font-1.3-release.txt) |

实际 IME 已验证现代词与纠错提交、原始拼音回车、整句候选、12 次 `kd` 选择、12 次切换到系统设置及返回、40 轮“单杠”输入 / 选择 / 删除。父版本回归覆盖候选布局和纵向展开、导航与手势、英文 / 九键 / 预测、基础文本编辑及模型管理。键盘和展开列表不显示 Google 品牌条，也不显示模型译文；本地中英释义保留，模型译文仅在带来源标识的详情页展示并经明确确认选入。完整、基础及字体专项有重叠功能，各自记录，不相加为新的验收总数。

已检查同一正式 APK 的[字体 1.0 输入](test-results/v0.5.0/font-1.0-pinyin.png)、[字体 1.0 展开](test-results/v0.5.0/font-1.0-expanded.png)、[字体 1.3 输入](test-results/v0.5.0/font-1.3-pinyin.png)与[字体 1.3 空闲](test-results/v0.5.0/font-1.3-idle.png)画面：候选、释义无越界，无品牌栏，长释义省略，按键位置保持。检查后系统字体恢复为 1.0；这些模拟器画面不等同于所有字体比例或厂商真机适配。

## 已完成的独立 Debug 检查

环境为 Android 15 / API 35 / x86_64 模拟器，系统字体 1.0。以下报告不是最终 Release 的验收结果。

| 检查 | 实际结果与范围 | 原始报告 |
| --- | --- | --- |
| v0.5 实际 IME 与父版本回归 | 58 项通过；真实系统 IME、InputConnection、触摸、剪贴板和已下载模型 | [ImeV5Instrumentation](test-results/v0.5.0/ime-v5-debug.txt) |
| 应用内更新 | 11 组边界与缓存检查通过，另一次真实官方网页 / APK HEAD 回退通过；未下载 APK | [AppUpdateInstrumentation](test-results/v0.5.0/app-update-debug.txt) |
| 模型专项 | 六语言模型实际推理、目录与非法下载边界、ABI / 页大小守卫通过 | [TranslationModelInstrumentation](test-results/v0.5.0/translation-model-debug.txt) |

IME 与更新报告的 Debug APK SHA-256 为 `D3E1E6C9140030972AEF9CD7B068BF5B3E3CA7EF2F51B9CE4A49CE651DBD0824`。模型专项来自另一 Debug 构建，SHA-256 为 `1E25EF138D06F1D8E59BE07DE2971A6F363DDA91C61BCF147D78AC085106C297`。Debug 的 58 项含 5 项内部 API 直接检查；不与 Release 的 53 项相加，也不将不同构建的检查合并为某一 APK 的总通过数。

更新的真实回退取得官方 v0.4.0、59,836,541 字节与对应 SHA-256，证明 403 回退的生产解析和 HEAD 路径可运行，不能据此宣称新版本网络下载安装已验收。摘要、包名、版本、单向签名继承、私有安装快照、Provider 只读边界均保留。16 KiB ARM64 的 SDK 安全降级继续限制模型下载 / 推理，本地中英文主体保留；守卫条件断言不代表已测试相应真机，详见 [SDK 记录](../third_party/mlkit/SOURCE.md)。

## 性能与验证范围

| 证据层级 | 测量及限制 |
| --- | --- |
| 宿主 Java 内存微基准 | 纠错辅助器 1,000 次建议及英文 1,000 次调用通过；不包含 Android SQLite、JNI、触摸或屏幕绘制，不代表手机按键响应 |
| Android 串行候选引擎，最新 Acceptance 样本 | 2,835 次增量搜索中位 0.411 ms、95 分位 14.604 ms、最大 133.812 ms；[实际 JNI / SQLite 输出](chinese-quality-v0.5.txt)，不含完整 IME 端到端延迟 |
| Android 串行候选引擎，此前严格协议样本 | 同一固定语料的 2,835 次搜索中位 0.389 ms、95 分位 14.218 ms、最大 27.573 ms；[独立归档](test-results/v0.5-chinese-quality-strict.txt)，不作为最新数字，共享负载和调度会影响两次结果 |
| 安装资产与首次加载 | [独立 filesDir 冷开样本](test-results/v0.5-input-startup.txt)约 1,087 ms，暖开约 504 ms，两次 `dangang` 第一项为“单杠”；使用已安装 APK 资产、后台优先级，未清空 OS 磁盘缓存，不是多设备冷启动统计 |
| 实际系统 IME | Release 53 项完整交互、17 项基础输入、14 项字体专项，Debug 58 项分别通过；没有测量触控到显示的帧时延、帧率、功耗或长期内存趋势 |
| 真机 | 未连接真机；OEM / 富文本应用、16 KiB ARM64 实机、高频跨 App 长时间输入、震动与单手手感待安装体验 |

应用内检查、下载并安装后续高版本的完整链路待 v0.6 或后续版本发布时再验证；三个未学习整句失败保持可见，不因日常 Acceptance、APK 构建或交互检查通过而删除。
