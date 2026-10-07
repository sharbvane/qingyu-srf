# v0.4 翻译模型管理

默认资源保持中文与英文词典，以及 45 条完整中英短语。日语、法语、德语、俄语、西班牙语均使用用户主动启用后下载的 ML Kit 端侧模型；查看本地状态不触发下载。新语言沿用现有下载、删除、缓存失效和版本通知流程。

## 实际删除残留的根因

本轮 Debug 模拟器检查发现：德语删除成功后，SDK 报告未下载，模型版本通知已递增，但该语种仍占用约 11.6 MiB。只读检查 `no_backup/com.google.mlkit.translate.models/de_en` 显示，`merged_dict_*.bin` 已删除，`translate_deen` 与 `translate_ende` 子目录中仍保留 26 个 `.pb`、`.bipe`、`.model`、`.vocab` 文件。这是真实残留，不能把容量读数改成零来掩盖。

对项目实际缓存的 `translate-17.0.3-runtime.jar` 执行 `javap -p -c`，追踪 `zzam.deleteDownloadedModel → zzab.zze → zzh.zzf` 后确认：SDK 删除逻辑枚举 `zzac.zza` 的平面词典文件，并通过 `zzae.zzg` 删除平面 rapid-response 文件；未递归清理已经解包的神经模型目录。

## 修复范围

- 先通过 `RemoteModelManager.deleteDownloadedModel` 取消该模型下载、更新 SDK 状态；成功后清理所选语言的残留目录及同名临时目录。
- 目录名从 SDK 的 `TranslateRemoteModel.getModelNameForBackend()` 获取，例如德语是 `de_en`，不能假定全部以 `en_` 开头。
- 清理范围仅为应用 `no_backup/com.google.mlkit.translate.models/<该语种>` 和 `temp/<该语种>`。校验规范路径，拒绝目录别名或符号链接指向别处；递归过程中不跟随子项符号链接。
- 文件清理失败会报告失败，并使缓存失效；不会显示虚假的零容量。容量统计读取实际安装与临时文件字节，无法读取时返回未知。
- 删除英语整句模型实际处理中文共享模型；其他语言的模型状态随之失效，本地中文输入与中英文词典仍可使用。

检查入口为现有 `TranslationModelInstrumentation` 和新增 `LanguageV4Check`。后者通过实际 IME 界面确认德/俄/西释义与上滑输入、六语言状态与正容量、德语删除后的零容量和正常中文输入，以及显式重新下载后的恢复。该记录描述已定位问题与修复设计；最终通过范围以本版实际运行报告为准。
