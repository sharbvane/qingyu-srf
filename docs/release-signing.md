# 正式 Release 签名与恢复

v0.5 起使用独立的 RSA 4096 / SHA256withRSA Release 密钥，证书有效期 100 年；Gradle Release 已移除 `signingConfigs.debug`。正式打包入口为 `scripts/build.ps1 -Variant Release`：先检查身份和文件权限，再构建、附加官方签名轮换证明、验证各 Android 版本的证书，最后复制 APK 到 releases。不要发布单独执行 Gradle 后、尚未经过此入口验证的中间 APK。

## 覆盖升级

Android 9 / API 28 及以上使用新 Release 证书，通过 APK Signature Scheme v3 的 proof-of-rotation 证明继承旧版本身份，保留安装数据。Android 8 / API 26–27 不支持密钥轮换，因此同一个 APK 的 v2 签名保留旧证书作为兼容路径；这不是 Gradle debug signing 配置。只要继续支持 Android 8，必须同时保存这枚兼容私钥。

公开证书与 lineage 校验值保存在 [release-signing-policy.json](release-signing-policy.json)：

| 用途 | 证书 SHA-256 |
| --- | --- |
| 正式 Release，API 28+ | `1d6872a840fe42468cdc5af2d11eeee87455c4b635e2fd2b60168b082c4e0cf5` |
| 旧版本兼容，API 26–27 | `12794d0f0be3a864281e828ad389f8e71bb725e196cb4bf65c4937069452d6d9` |

旧 v0.4 的应用内更新器只比较当前证书，无法识别新证书的轮换证明。因此首次 v0.4 → v0.5 应使用提供的 APK，经系统安装器覆盖安装；不需要先卸载。v0.5 更新器支持向前继承的单 signer 签名历史，并继续拒绝无关证书、逆向回退和多 signer 包。系统最终仍决定是否准许安装，实际设备升级结果见该版本验证记录。

官方机制与命令参考：[apksigner](https://developer.android.com/tools/apksigner)、[APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3)。打包使用 `--rotation-min-sdk-version 28`，保留旧版本 installed-data 能力，禁止旧证书 rollback 能力。

## 私有材料与备份

`release-signing/` 已被 Git 忽略，ACL 仅准许当前 Windows 用户、SYSTEM 和管理员访问。脚本只通过临时进程环境变量向 keytool / apksigner 传递密码，并在 finally 恢复；密码不放在命令行、构建日志或文档中。

| 文件 | 用途 |
| --- | --- |
| `qingyu-release.p12` | 新正式密钥，以 384 位随机密码保护 |
| `qingyu-legacy.p12` | 从原 debug.keystore 导入的兼容密钥副本，以同样的强密码重新加密；原文件不变 |
| `signing.properties` | 本地签名凭据，禁止提交或公开 |
| `qingyu.lineage` | 旧 → 新的签名轮换证明 |
| `backups/Qingyu-signing-20261007-165923.zip` 与 `.sha256` | 已创建的独立可移植备份 |

备份 ZIP 包含两枚私钥、lineage、密码文件及公开校验策略，外层 ZIP **没有额外加密**，只依赖本地受限 ACL；持有这个完整备份即可签发应用更新。请把 ZIP 和 SHA-256 校验文件另存到加密离线介质或可信密码库中，不要放入 releases、GitHub、网盘公开分享或发送给测试用户。本机目录损坏、磁盘丢失和勒索软件均可能影响同盘备份，因此同盘备份不替代异地备份。

初始化只执行一次，发现已有目录或公开身份策略立即拒绝；构建从不自动生成替代密钥：

```powershell
& scripts/release-signing.ps1 -Action Initialize
& scripts/release-signing.ps1 -Action Check
& scripts/release-signing.ps1 -Action Backup
```

在新电脑准备项目工具链、JDK 17 和同一 Git 代码后，把备份 ZIP 与其 SHA-256 sidecar 放在安全位置。当 `release-signing/` 不存在时运行恢复命令；脚本验证 ZIP 哈希、限制条目路径、匹配公开身份策略，重新建立当前用户的受限 ACL，再验证密码、证书和 lineage。已有目录一律拒绝覆盖，避免误换签：

```powershell
& scripts/release-signing.ps1 -Action Restore -BackupFile '安全备份位置\Qingyu-signing-20261007-165923.zip'
& scripts/release-signing.ps1 -Action Check
& scripts/build.ps1 -Variant Release
```

恢复过程与失败边界有可重复检查：

```powershell
& scripts/check-release-signing.ps1
```

检查会在 project-local `.tools/signing-restore-check/` 临时恢复备份，确认两枚证书、凭据、lineage 与私有 ACL，验证重复初始化被拒绝、密钥缺失时失败且不重造、旧 APK 和原 debug key 不变，最后清理自己创建的临时目录。最终 APK 另由 `tools/audit-release-apk.py` 校验证书、v2 / v3 分平台签名及 APK 内的轮换证明。
