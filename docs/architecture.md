# 架构

`app` 只拥有 Android 组件、导航、前台服务、小组件和更新入口。领域模型位于
`core:model`；网络契约位于 `core:network`；安全存储、DataStore、仓储和能力探测位于
`core:data`；诊断日志位于 `core:database`；可复用 Compose 令牌和组件位于
`core:designsystem`。每个 `feature:*` 模块只暴露自己的状态、ViewModel 和屏幕。

UI 通过 ViewModel 和 `StateFlow` 单向读取状态。网络调用统一经 Retrofit/OkHttp，服务端
未支持的扩展端点由缓存的 `ServerCapabilities` 门控。`ReportingService` 是唯一连续运行的
服务，WorkManager 与广播只负责合规恢复。

凭据的所有权分离为认证会话 JWT、设备上报密钥和预留的小组件令牌。诊断日志在写入 Room
前进行脱敏；普通 DataStore 不存储任何敏感值。

## 小组件边界

小组件展示关注用户及其远程设备，不展示本机上报服务状态。`app` 模块拥有两个原生
`RemoteViews` Provider：4×2 `NekoWidgetReceiver` 用于紧凑状态列表，4×4
`NekoSnapshotWidgetReceiver` 用于单台设备的截图、应用、电池和媒体信息。采用
`RemoteViews` 是基于 ColorOS Launcher 真机兼容结果；Stitch 画布只提供视觉参考。

两种 Provider 共享 `WidgetSettingsStore`、`WidgetFeedStore` 与 `WidgetRefreshWorker`。
WorkManager 的周期下限保持 15 分钟；手动刷新使用唯一一次性工作，成功或失败后都刷新两种
Provider。截图只预热当前选中设备，按比例解码并限制尺寸，避免超过 Launcher 的
`RemoteViews` Binder 位图预算。已有 4×2 实例不会被静默迁移或调整为 4×4。

## 更新边界

`SettingsRepository` 持久化自动检查、自动下载及官方/个人 GitHub 仓库选择；`UpdateManager`
提供应用内状态流，并由 WorkManager 执行 24 小时联网周期检查和带节流的启动检查。Release
清单使用资产名而不是任意 URL。下载由系统 `DownloadManager` 完成，应用随后验证摘要、包名、
真实版本和签名，再把已验证文件交给系统安装器。安装权限授权后仍由用户点击继续安装。
