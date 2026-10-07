# 应用内更新

`更多 → 检查更新` 与设置中的同名入口打开应用内更新页；`项目主页` 打开已核验的 Git remote 对应页面 [sharbvane/qingyu-srf](https://github.com/sharbvane/qingyu-srf)。页面保留墨绿色、系统字体、标准返回和系统安全区，按钮最小 48 dp，内容可按字体缩放换行。

检查仅由用户点击触发，后台读取官方仓库公开 [latest Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)，不附带输入内容、凭据或设备标识。仅接受正式三段版本号、已上传且命名为 `Qingyu-<版本>.apk` 的 HTTPS 官方仓库资产。界面显示版本号、原始更新说明和实际资产大小；离线、HTTP 403/429 请求限制、未发布 APK 均给出明确状态和重试入口。

下载沿用系统 [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)，仅写入应用专属外部文件目录 `updates/update.apk`，无需广泛存储权限。下载 ID 与发布信息保存在应用私有偏好，下载在后台继续；页面恢复后查询同一任务，不重复创建。取消会移除系统任务和临时 APK。下载仅在进行中显示系统通知，禁用完成 APK 的系统直开通知，并隐藏旧 Android 系统下载列表入口，防止绕过应用的校验流程（[Android 8.1 DownloadReceiver 源码](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/android-8.1.0_r1/src/com/android/providers/downloads/DownloadReceiver.java)）。前台下载完成后校验并进入安装；后台完成时返回更新页再继续，遵守 Android 后台启动限制。

安装前在后台将外部下载文件复制到应用内部私有 `files/updates/` 的临时文件，核验这份私有快照的资产字节数、GitHub 提供的 SHA-256（若存在）、APK 包名、发布版本名、比已安装版本更大的 versionCode 和同一签名，再原子重命名为私有 `verified.apk`。失败删除临时快照；取消或确认版本已覆盖后，清理外部原始 APK、私有安装副本与临时文件。其他拥有旧 Android 存储权限的应用即使改写外部下载文件，也不能改变已验证并交给安装器的私有副本。校验不通过阻止安装并提供删除/重新下载。安装通过 [ACTION_VIEW](https://developer.android.com/reference/android/content/Intent#ACTION_VIEW) 和 APK MIME 交给 Android；只读、非导出的 ContentProvider 仍仅临时授予 `/update.apk`，但读取的是内部私有 `verified.apk`，拒绝外部文件、目录、路径穿越及写入。Android 8+ 需要用户首次允许本应用安装未知来源，返回后再校验并继续；系统安装确认保留，无法静默安装。

Debug 专用可运行检查：`AppUpdateInstrumentation`，成功标记 `ALL_APP_UPDATE_CHECKS_PASS`。检查稳定版解析、数字版本比较、恶意 URL、大小限制、签名不一致、摘要不匹配、当前 APK 禁止降级、安装 Intent、Provider 路径边界、真实私有 APK 读取和外部原始文件改写隔离、失败快照删除、下载持久恢复与取消。检查不发起下载、不启动安装、不改远程仓库。真实 GitHub 新版发现、APK 网络下载和最终覆盖安装需有比设备上当前安装版本更新的正式发布资产；仅通过边界检查不能声称已完成该在线流程。v0.4.0 发布前的验证记录见 [v0.4.0 验证文档](validation-v0.4.0.md)。

2026-10-07 发布前的开发机公开 API 曾返回 HTTP 403（rate limit exceeded）；这是 GitHub 端限流，已作为界面可恢复失败处理。
