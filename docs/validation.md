# 0.1.0 验证记录 · 2026-10-05

这是可以安装并设为系统输入法的 Android MVP。下列项目均为本轮实际执行；没有连接真机，
不能据此承诺真机长期使用、商业输入法级候选质量或任意 App 的兼容性。

## 构建和安装

- JDK 17 / Gradle 8.9 / AGP 8.7.3 / SDK 35 / NDK 28.1 / CMake 3.22.1。
- 原生源码构建 arm64-v8a、armeabi-v7a、x86_64 三 ABI。
- Android 15 / API 35 / x86_64 模拟器，WHPX，加速启动并实际安装 APK。
- 包名 `com.qingyu.ime`；通过系统 IME 注册、启用、选定，实际在 Android 编辑框输入。
- APK v2 签名验证；16 KiB ZIP 对齐及原生 ELF LOAD 段对齐检查。
- APK 内具有完整 GPL-3.0、Apache-2.0、AOSP NOTICE、CC-CEDICT 来源和许可信息。
- SQLite 英文词典 111,645 条，3,678,208 字节；下载快照及衍生数据库 SHA256 均记录。

安装包 SHA256 以 `releases/Qingyu-0.1.0.apk.sha256` 为准。
Windows 原生工具使用临时 ASCII 盘符，缓存/工具/构建临时目录保留在 `.tools`。
最终包结构、对齐、词典校验：`docs/release-package.json`。

## 实际系统输入测试

`app/src/androidTest/.../ImeSmokeInstrumentation.java` 使用 Android UiAutomation 注入按键和手势触摸，
并通过无障碍虚拟节点的 ACTION_CLICK 操作候选与控件。它调用系统 InputConnection，
读取编辑框实际文本，没有在产品中增加测试提交入口。

Debug 与最终 `Qingyu-0.1.0.apk` 均安装并执行相同流程，17 项全部通过（`ALL_IME_CHECKS_PASS`）：

最终 Release 于 2026-10-05 重装并复测，SHA256：
`2711720EC759DFC4E66B6FEA11A6A979F1EE40D2B128EF45E4BDE0B85EC269CD`。
另在正式设置页实际触摸练习输入框并输入「开发」，确认输入框保持可见。

1. 点「开发」只上屏「开发」，不含拼音或 develop。
2. 拼音输入中横竖屏切换后选词，不出现 kaifa开发 重复上屏。
3. 「项目」显示 project；关闭释义时候选 bounds 不变，中文仍可选。
4. 整句 `zhongguorenmin` 上屏「中国人民」。
5. 候选展开、下一页、选词并自动收起。
6. 快速拼音、空格、中英切换、字母、数字顺序正确（你好abc12）。
7. 拼音删除一字母后补回，可继续选词。
8. 已提交 emoji 按 Unicode code point 删除，避免残留代理字符。
9. 按住退格连续删除。
10. 长按 q 输入 1。
11. 长按中文逗号输入隔音符，`xi'an` → 西安。
12. 连续输入 70 个字母超出单段限制后自动分段完成，无丢字符。
13. 空格滑动能移动编辑器光标并在中间插字。
14. 密码字段保持直接字母输入，语言切换不能启用中文候选。
15. 密码可以输入 !、*、=、双引号等 ASCII 符号。
16. 数字字段自动显示数字键盘并输入 123。
17. 深色模式重启输入后仍显示 design，并正确输入「设计」。

测试摘要：`docs/test-results/ime-smoke-v0.1.0.txt`。实际键盘截图：`docs/images/keyboard-light.png`、`keyboard-dark.png`。
这些截图来自模拟器实际绘制，没有通过图片生成伪造产品界面。

## 中文引擎与翻译隔离

- 真实本地 AOSP 字典：你好、开发、项目、设计、中文、输入法、再见等查询并选词。
- 分段选「中国」后删除/补字，完成「中国人民」。
- 用户字典保存/重新打开；关闭学习后文件 hash 不变。
- 原生/Java 线程所有权与单实例边界。
- 长于九音节的输入分段完成；非法拼音尾部保持可编辑。
- 64 个重复元音；100 组随机 64 字符序列及逐字删除，不崩溃、不截掉原文。
- 1,000 次输入/删除循环，6,285 次查询。
- 纯 Java 核心验证：某条释义抛异常不阻止其他释义；缺词留空；中文候选不被修改；
  跨线程快照及翻译结果不可变。

详细结果：`docs/engine-smoke-results.txt`、`docs/engine-adapter-results.txt`。
最新独立运行，原生查询 p95 约 0.219 ms；Java/JNI 加至多 128 个候选快照 p95 约 0.377 ms。
这是 x86_64 模拟器上的引擎微基准；并发负载会改变数值。
**它不代表手机按键到屏幕的总延迟或 UI 帧率。**

## 尚未实测的验收范围

- 真机震动强度、手指命中、双拇指高速触摸、键帽体验与不同 DPI。
- 真机冷启动/切输入法端到端延迟，PSS、功耗、热量、动画 FPS 和长时间稳定性。
- 微信、浏览器、富文本编辑器、不同 OEM、高频跨 App 切换的兼容性矩阵。
- 系统主题变化、各厂商三键导航/手势导航、折叠屏、平板及极大字体。
- TalkBack 虚拟节点已实现且自动测试使用，但真实读屏操作仍需实机验收。
- 现代词汇、网络词、错拼容错与个性化排序，需要基于真实使用继续迭代。

## 重跑

```powershell
pwsh -File scripts/build.ps1 -Test
pwsh -File scripts/start-emulator.ps1
pwsh -File scripts/test-android.ps1
pwsh -File scripts/test-android.ps1 -Variant Release -SkipBuild
pwsh -File tools/test-engine.ps1 -Serial emulator-5554 -Abi x86_64
```

系统输入测试会在指定模拟器/设备安装所选版本与测试包、选定轻语，并使用测试输入框；
默认验证 Debug，`-Variant Release` 验证 releases 中的安装包，`-SkipBuild` 使用现有产物。
正常安装体验应使用 `releases/Qingyu-0.1.0.apk`。
