# 重构路线图与验收记录

状态规则：只有必做项、验收命令和真机结果全部通过，阶段才能标记完成。遇到可复现问题时同步更新 [`troubleshooting.md`](troubleshooting.md)。

## 阶段 0：安全与工程基线

- [x] 初始化 Git、Gradle Wrapper、版本目录与多模块结构
- [x] Release 缺少签名环境变量时失败
- [x] 本机配置与凭据进入忽略列表
- [ ] 撤销旧工程中已暴露的 GitHub Token（需要仓库所有者在 GitHub 完成，旧仓保持只读）
- [ ] CI、发布、安全、协议与架构文档验收

验收：`./gradlew.bat projects --offline --no-daemon --console=plain` 已于 2026-07-26 通过。

## 阶段 1：设计系统与完整前端骨架

- [x] Stitch 移动端 2.0 概念（独立 `Neko Status Reporter` 项目；未使用 `Main Status Dashboard`）
- [ ] Figma 令牌、组件和关键屏幕
- [ ] Compose 全导航与全部状态
- [ ] Roborazzi 多主题、多宽度和字体倍率截图

本次设计落实（2026-07-26）：已将第二轮 Stitch 的 Android Material 3 概览方向映射到 Compose，采用水青主操作、薄荷成功态、8dp 信息面板、12dp 操作控件和 20dp 页面边距。未采用其桌面遥测宽屏版式；保留 Android 原生底部/侧边导航、返回与权限语义。

验收：`./gradlew.bat :app:assembleDebug ktlintCheck`、`./gradlew.bat testDebugUnitTest`、`./gradlew.bat lintDebug detekt` 均于 2026-07-26 通过。

## 阶段 2：认证与设备接入闭环

- [ ] 登录、注册、会话恢复和资料
- [ ] 自动设备密钥、扫码配对、手动密钥和服务器配置
- [ ] 分步权限引导

## 阶段 3：状态上报与保活闭环

- [ ] 单一 `specialUse` 前台服务和持久通知
- [ ] 电池、屏幕、前台应用和媒体采集
- [ ] 串行上报、最新快照、退避和终止错误处理
- [ ] WorkManager/广播合规恢复
- [ ] 关注状态小组件完整验收
  - [x] 4×2 原生 `RemoteViews` 展示关注用户及其远程设备状态，不显示本机上报服务
  - [x] 空/加载/鉴权失效/能力关闭/离线缓存状态
  - [x] 空、低、中、高、满与充电电池矢量图标（不使用 Emoji）
  - [x] 独立 4×4 截图 Provider、设备选择、截图缓存与等比尺寸限制
  - [x] Online/Away/Offline 圆点加文字状态、桌面刷新进度反馈和系统添加请求反馈
  - [ ] 真机添加 4×4，覆盖有截图、无截图、离线缓存和 Launcher 位图预算

## 阶段 4：扩展、更新与发布

- [ ] 功能门控的历史、公告、设备和关注动态
- [x] 无 Token GitHub Releases 更新、SHA-256 与签名校验
- [x] CI 发布 APK、校验和、更新清单和变更日志

公开发布验证（2026-07-27）：[`v2.0.0-alpha.3`](https://github.com/Neko-NF/Neko-Status-Mobile/releases/tag/v2.0.0-alpha.3)
已作为普通 Release 公开并设为 latest，而不是 GitHub prerelease。发布
[运行 30250919010](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30250919010)
的 preflight、无密钥 verify 和签名 release 均成功；默认分支
[Verify 运行 30250585317](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30250585317)
成功。远程重新下载的 APK、`.sha256` 和 `update.json` 已交叉核对：版本为
`2.0.0-alpha.3` / `2000003`，包名为 `com.nekonf.nekostatus`，APK 大小为
27,097,665 字节，SHA-256 为
`69eead96d3d857b97770365027b539ba74e3fde2c90a5d4412ffab9bc445ef13`；GitHub
资产大小与 digest、清单、校验文件、APK 元数据和签名证书一致。

安装交接发布验证（2026-07-27）：[`v2.0.0-alpha.4`](https://github.com/Neko-NF/Neko-Status-Mobile/releases/tag/v2.0.0-alpha.4)
已公开并设为 latest。默认分支
[Verify 运行 30256640887](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30256640887)
和签名[发布运行 30256916707](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30256916707)
均成功。公开 APK 为 `2.0.0-alpha.4` / `2000004`、27,099,465 字节，SHA-256 为
`8ec28ba6933b2537c5f27797764894f969af2bd4928c985fb7bd96a32868f136`；公开下载后的
GitHub digest、`.sha256`、`update.json`、APK 元数据和官方证书均一致。

账号隔离与微件切换发布验证（2026-07-31）：[`v2.0.0-alpha.5`](https://github.com/Neko-NF/Neko-Status-Mobile/releases/tag/v2.0.0-alpha.5)
已公开并设为 latest。默认分支
[Verify 运行 30603767793](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30603767793)
和签名[发布运行 30603963520](https://github.com/Neko-NF/Neko-Status-Mobile/actions/runs/30603963520)
均成功。公开 APK 为 `2.0.0-alpha.5` / `2000005`、27,338,429 字节，SHA-256 为
`aeaf3192588fa947153706a4541f87888e476d13431fa68a643bf6e27bcaa7b8`；重新下载后的
GitHub digest、`.sha256`、`update.json`、APK 元数据和官方证书均一致。

API 36 模拟器中的公开 `2.0.0-alpha.3` 已在应用内找到、下载并验证公开 `alpha.4`，授权后
Google 系统安装器显示“要更新此应用吗？”。旧 `alpha.3` 的下载完成界面停滞需要重启恢复，
且授权返回后需要再次点击安装；这两点均已在 `alpha.4` 修复。最终安装按本轮范围取消，所以
仅将“公开更新入口能够拉起安装器”记为通过，覆盖安装、数据保留和实体机升级项目保持未完成。

## 真机验收

- 目标：一台 ColorOS Android 16 / API 36 实体设备；序列号和设备截图不写入公开仓库。
- [ ] 安装、重启、息屏、锁屏、切网、断网恢复、进程回收、通知关闭、Doze、媒体切换
- [ ] 12 小时稳定网络浸泡测试
- [ ] 记录 ColorOS 后台限制；系统“强行停止”后不绕过 Android 规则

截至 2026-07-27，本轮发布链路仅连接 API 36 模拟器，实体设备未连接。模拟器可以验证版本、
签名、Intent 和应用状态，但不等于 ColorOS 实体机验收；因此以上真机项目保持未完成。

截至 2026-07-31，`alpha.5` 已完成公开资产下载、哈希、清单、包元数据和签名复核；尚未从
上一公开 latest 完成应用内覆盖升级，也未完成 ColorOS 实体机升级，真机项目继续保持未完成。

模拟器发布局部验证（2026-07-27）：从 GitHub 远程下载的正式 `2.0.0-alpha.3` APK 已覆盖安装
到 API 36 模拟器，版本更新为 `2000003`，登录会话得到保留。随后公开 `alpha.3` 已通过应用内
入口把公开 `alpha.4` 交给 Google 系统安装器，但最终安装已取消；这仍不等于实体机升级通过。

已完成的局部验证（2026-07-26）：Debug APK 覆盖安装并冷启动成功。截图确认概览分组、唯一水青主操作和水青导航选中态正常；未登录、未触发真实上报，故认证、权限、锁屏、网络和媒体流程仍保持待验收。真机截图只保留在被忽略的本地产物目录。

小组件局部验证（2026-07-26）：4×2 与 4×4 实例均已在 ColorOS Launcher 显示。
4×2 展示测试关注用户的远程设备、应用图标、正式电池图标、圆点加文字状态和更新时间；
4×4 展示所选远程设备截图，未再出现“无法显示内容”。两个 Provider 点击刷新后均立即显示
水青色进度环；Worker 成功结束后恢复刷新图标，日志未发现
`RemoteViews`、`InflateException` 或 `TransactionTooLargeException`。4×2 与 4×4 的
`RemoteViews` 位图内存分别约为 100 KB 和 646 KB。应用内“添加 4×4 截图微件”已触发
ColorOS 系统添加流程并显示提交反馈。真机截图不进入公开仓库。
仍需补齐无截图和离线缓存的独立场景覆盖，因此阶段 3 保持未完成。

导航局部验证（2026-07-26）：从概览进入“检查系统权限”后切换到“动态”，停留超过 2 秒仍
保持在动态页，没有恢复到权限页。

## 未决服务端能力

- 历史、公告、关注动态、多设备小组件端点需以探测和契约测试结果为准。
- 404/501 必须关闭对应能力，正式包不得显示假数据。
