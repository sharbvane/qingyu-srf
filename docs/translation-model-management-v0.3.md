# v0.3 翻译资源与模型管理

- 默认资产仍为中文输入资源、英文拼写/预测词库、CC-CEDICT 中英释义与 45 条中文 → 英文完整短语。`translation/phrase-gloss.tsv` 只有 `zh / en` 两列，日语、法语不再包含任何本地短语译文。
- 「设置 → 翻译模型管理」提供中文 ↔ 英语、日语、法语的状态、主动 Wi-Fi 下载及 SDK 管理的删除。日语、法语未下载时保持空释义，并引导下载；不下载模型也可正常使用中英文输入和中英本地释义。
- ML Kit 使用英语中转，英语整句翻译对应的下载文件实际上是中文共享模型。删除该共享模型会暂停全部整句语言对；日语或法语各自删除只移除该语言文件。管理页面明确说明共享关系。删除后本地中英文词典仍可用。
- 模型修改成功递增本机 `models_revision`。服务重新显示时调用 `refreshAfterModelChange` 清理已加载模型与译文缓存，再读取 SDK 实际已安装模型；异步任务以 generation 校验避免旧模型结果填回新的请求/缓存。
- 容量读取应用私有目录 `no_backup/com.google.mlkit.translate.models/<getModelNameForBackend()>` 下已安装文件的真实字节数（例如 `en_ja`），各语种行不重复累计中文共享文件。SDK 目录变更或无法读取时显示未提供可读取大小，不估算数字；已就绪模型仍允许通过 SDK 删除。目录布局依据项目缓存的 `common-18.11.0-runtime.jar` 中 `ModelFileHelper.getModelDirUnsafe` 与 `translate-17.0.3-runtime.jar` 中 `TranslateRemoteModel.getModelNameForBackend` 验证；`getUniqueModelNameForPersist` 带有偏好键前缀，不能用作目录。这里只读文件，删除使用 `RemoteModelManager.deleteDownloadedModel`。

`translate-17.0.3.aar` 只有各 ABI 的共用翻译 JNI 引擎与 16,710 字节模型元数据，没有随安装包内置日语/法语离线权重。不能把共用 `.so` 当作单独语言资源删除。它附带 `proguard.txt`，包含 native 方法保名和 OkHttp/Guava/protobuf 等 consumer 规则；开启 Android 的 R8 与资源压缩可削减 Java/DEX/资源体积，共用 JNI 的体积不受此步骤影响。体积变化以最终安装包实际构建结果为准。

验证入口：`python tools/check_translation_assets.py`；Android 主线程可运行 `PanelLayoutCheck.run(context)` 检查面板固定高度、所有导航 owner、滑块不被重建、候选 grid 与短窗口约束。真实 SDK 下载与删除由 Android 模拟器中的 `ImeV3Instrumentation` 模型管理分支覆盖，详见[本版验证记录](validation-v0.3.0.md)。
