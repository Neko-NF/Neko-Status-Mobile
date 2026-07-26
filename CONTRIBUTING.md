# 参与开发

Neko Status Mobile 使用 JDK 17、Android SDK 36、Kotlin 与 Jetpack Compose。开始开发前先阅读
[`docs/architecture.md`](docs/architecture.md)、[`docs/protocol.md`](docs/protocol.md) 和
[`docs/troubleshooting.md`](docs/troubleshooting.md)。

## 必须遵守

1. Token、JWT、设备密钥、签名私钥与密码不得写入源码、普通偏好、日志、截图或 Git 历史。
2. Release 构建必须使用 CI Secret 注入签名参数；缺少参数时构建应当失败。
3. 不伪造服务端数据。未知或未支持的能力必须由 `ServerCapabilities` 门控并显示明确状态。
4. 新增或修改接口时，同步更新 OpenAPI、黄金请求样例和 MockWebServer 契约测试。
5. 遇到耗时、反直觉或可能重复发生的问题时，必须在同一变更中更新
   [`docs/troubleshooting.md`](docs/troubleshooting.md)，写清现象、根因、解决方法和预防措施。
6. 阶段验收结果、真机型号、系统版本和未决服务端能力必须记录在
   [`docs/roadmap.md`](docs/roadmap.md)，未通过不得勾选完成。

## 本地验收

```powershell
./gradlew.bat ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

真机变更还应至少覆盖安装、启动、权限拒绝、服务启停、锁屏/息屏、切网和断网恢复。
