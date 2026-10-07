# 应用内更新

`更多 → 检查更新` 与设置中的同名入口打开应用内更新页；`项目主页` 打开已核验的 Git remote 对应页面 [sharbvane/qingyu-srf](https://github.com/sharbvane/qingyu-srf)。页面保留墨绿色、系统字体、标准返回和系统安全区，按钮最小 48 dp，内容可按字体缩放换行。

检查仅由用户点击触发，后台读取官方仓库公开 [latest Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)，不附带输入内容、凭据或设备标识。仅接受正式三段版本号、已上传且命名为 `Qingyu-<版本>.apk` 的 HTTPS 官方仓库资产。界面显示版本号、更新说明和实际资产大小；离线、HTTP 403/429 请求限制、未发布 APK 均给出明确状态和重试入口。

v0.5 将成功发布信息、检查时间、ETag / Last-Modified 保存在应用私有偏好；一分钟内重复检查直接使用刚刚取得的结果，之后使用条件请求，304 复用与对应验证器一起保存的 API JSON。未认证请求的额度按公网 IP 共享；**ETag 本身不会令未认证请求免额度**，不要求用户提供 token、不在安装包中放凭据。403/429 按 Retry-After 秒数或 HTTP 日期，以及剩余额度为零时的 X-RateLimit-Reset 冷却；无有效头时从一分钟逐次翻倍，有效等待上限一天。冷却期不重复打 API，失败后的连续点击也至少相隔一分钟；提示剩余时间，保留的旧版本信息明确标记尚未确认最新。遵循 [GitHub 官方请求和限流建议](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#handle-rate-limit-errors-appropriately)。

API 受限时，当前这次检查转为只读访问本项目官方 `/releases/latest`，验证其跳转为本仓库正式版本标签，再读取同一发布页的说明与资产行。必须取得对应 APK 的官方 SHA-256，且以 HEAD 请求取得精确字节数；网页显示的四舍五入容量不用于校验。HEAD 只允许 HTTPS 的本仓库 APK 链接和 `release-assets.githubusercontent.com`，最多五次请求，不读取 APK 内容。随后仍使用与 API 一致的解析、下载和校验链；不使用第三方镜像，不以网页回退为由跳过摘要或签名检查。网页结构改变、缺少摘要、非法标签、大小未知、异常跳转时安全失败；这是有明确边界的补救路径，若 GitHub 页面长期不稳定再采用维护的官方发布清单。所有请求均在工作线程，关闭页面后不启动定时检查。

下载沿用系统 [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)，仅写入应用专属外部文件目录 `updates/update.apk`，无需广泛存储权限。下载 ID 与发布信息保存在应用私有偏好，下载在后台继续；页面恢复后查询同一任务，不重复创建。取消会移除系统任务和临时 APK。下载仅在进行中显示系统通知，禁用完成 APK 的系统直开通知，并隐藏旧 Android 系统下载列表入口，防止绕过应用的校验流程（[Android 8.1 DownloadReceiver 源码](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/android-8.1.0_r1/src/com/android/providers/downloads/DownloadReceiver.java)）。前台下载完成后校验并进入安装；后台完成时返回更新页再继续，遵守 Android 后台启动限制。

安装前在后台将外部下载文件复制到应用内部私有 `files/updates/` 的临时文件，核验这份私有快照的资产字节数、GitHub 提供的 SHA-256（API 若存在；网页回退必须存在）、APK 包名、发布版本名、比已安装版本更大的 versionCode 和授权签名，再原子重命名为私有 `verified.apk`。API 28+ 只接受单签名 APK：当前证书相同，或由系统 PackageManager 校验的 APK v3 签名历史从**当前已安装的证书**向新证书继承；历史末尾须为候选当前证书，拒绝仅有共同旧祖先的分叉、反向旧证书、重复/异常历史和多签名 APK。API 26–27 保持证书严格相同。系统提供的 [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo#getSigningCertificateHistory()) 源自 APK 的签名证明，不接受 Release JSON 自报的证书或信任任意历史交集；最终安装权限和证明能力仍由 Android 安装器确认。

失败删除临时快照；取消或确认版本已覆盖后，清理外部原始 APK、私有安装副本与临时文件。其他拥有旧 Android 存储权限的应用即使改写外部下载文件，也不能改变已验证并交给安装器的私有副本。校验不通过阻止安装并提供删除/重新下载。安装通过 [ACTION_VIEW](https://developer.android.com/reference/android/content/Intent#ACTION_VIEW) 和 APK MIME 交给 Android；只读、非导出的 ContentProvider 仍仅临时授予 `/update.apk`，但读取的是内部私有 `verified.apk`，拒绝外部文件、目录、路径穿越及写入。Android 8+ 需要用户首次允许本应用安装未知来源，返回后再校验并继续；系统安装确认保留，无法静默安装。

v0.4 已安装的旧更新器只比较当前证书，不能在服务端修复；第一次进入独立 Release 证书的 v0.5 在 API 28+ 需手动打开已提供的 APK，由 Android 识别正式轮换证明并保留数据覆盖安装。v0.5 的更新器支持后续合法证书链。API 26–27 的正式包沿用兼容 v2 证书，具体签名保存和备份方法见项目 Release 签名说明。

Debug 专用可运行检查：`AppUpdateInstrumentation`，成功标记 `ALL_APP_UPDATE_CHECKS_PASS`。11 组默认离线检查覆盖稳定版解析、数字版本比较、恶意 URL、大小限制、网页资产摘要行与精确容量、HEAD 跳转边界、限流头和冷却、签名轮换拒绝分叉/逆向/异常历史、摘要不匹配、当前 APK 禁止降级、安装 Intent、Provider 路径边界、真实私有 APK 读取和外部原始文件改写隔离、失败快照删除、下载持久恢复与取消、持久缓存避免重复网络。

默认不联网；追加 `-e webcheck true` 会通过真实生产回退方法只读检查官方网页元数据与 APK HEAD（第 12 组）。检查不发起 APK 下载、不启动安装、不改远程仓库。示例：`adb shell am instrument -w -e webcheck true com.qingyu.ime.test/com.qingyu.ime.AppUpdateInstrumentation`，需先安装匹配 Debug APK 与 Debug 测试包。真实 GitHub 新版发现、APK 网络下载和最终覆盖安装需有比设备上当前安装版本更新的正式发布资产；仅通过边界或只读在线检查不能声称已完成最终覆盖安装。v0.4.0 发布前的验证记录见 [v0.4.0 验证文档](validation-v0.4.0.md)。

2026-10-07 开发机公开 API 实际返回 HTTP 403、剩余额度 0；同一次只读核验官方网页回退成功取得现有 v0.4.0、59,836,541 字节、与本地原 APK 相同的 SHA-256，未下载 APK。见 [主机在线元数据证据](test-results/v0.5-update-web-metadata-host.json)，此文件说明它不代表 Android 实际运行或覆盖安装；本轮 APK 运行结果以最终验证记录为准。
