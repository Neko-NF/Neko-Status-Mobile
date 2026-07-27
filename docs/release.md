# 发布与更新

## Release 内容

每个公开 Release 必须同时包含：

- GitHub 自动生成的源码归档；
- `neko-status-<version>-universal.apk`；
- 同名 `.sha256` 文件；
- 符合 [`api/update-manifest.schema.json`](../api/update-manifest.schema.json) 的 `update.json`。

客户端只从同一个 GitHub Release 精确选择 `apkAsset`，不接受清单指定任意下载 URL。
APK 上限为 250 MiB，且必须通过 SHA-256、包名、真实 `versionCode`/`versionName`、当前安装
签名和清单证书指纹校验。下载完成广播只调度唯一 WorkManager 任务，实际哈希和 APK 解析
不占用广播执行窗口；进程重启后会恢复未完成下载或重建验证任务。

## CI 发布顺序

1. 候选提交先合入受保护的默认分支，并等待 Verify Android 通过。
2. 创建并推送与 `versionName` 一致的 `v<versionName>` tag；tag 指向的提交必须是默认分支祖先。
3. 从默认分支手动运行 Publish signed Android release，输入已存在的 tag。发布工作流不响应 tag push，
   也拒绝从默认分支以外的 ref 调度。
4. 无 Secret 的 preflight 先验证 tag 格式、存在性、不可变 commit SHA 和默认分支祖先关系，再由独立
   Verify job 执行 Wrapper 校验、格式、静态分析、Lint、单测和 Debug 构建门禁。
5. 门禁成功后才进入 `release` Environment。从 GitHub Secrets 恢复临时 Release Keystore，仅在
   签名步骤作用域内提供口令，构建完成后立即删除 Keystore。
6. 从 Gradle `output-metadata.json` 提取版本，并使用 `aapt2` 与 APK 的真实包名和版本交叉校验，
   不在工作流中硬编码 `versionCode`。
7. 校验 APK 为 1 字节至 250 MiB，生成 SHA-256、证书摘要和 `update.json`，按公开 Schema 的字段、
   长度和正则约束自检，并与公开官方证书指纹比对。
8. 先创建 Draft Release，再上传并核对全部附件，最后发布并设为 latest。失败重跑可对同 tag 的
   Draft 使用 `--clobber` 恢复；如果同 tag Release 已经公开则拒绝覆盖。

第三方 Actions 固定到完整 commit SHA，Dependabot 通过 PR 提议升级；签名工作流不得引用
可移动的 major tag。Gradle Wrapper 同时通过官方 Wrapper Validation 和 8.11.1 精确 JAR
SHA-256 校验；`distributionSha256Sum` 固定 Gradle 分发 ZIP。升级 Gradle 时必须使用可信的官方
Wrapper 生成器，同时更新并复核两项指纹。

客户端使用 GitHub 的 `/repos/{owner}/{repository}/releases/latest` 接口，而该接口不会返回
标记为 prerelease 的版本。因此即使版本名包含 `alpha` 或 `beta`，需要被客户端发现的版本也
必须作为普通 Release 发布并设为 latest；版本阶段由 `versionName` 表达。

需要的 GitHub Actions Secrets：`NEKO_KEYSTORE_BASE64`、`NEKO_KEYSTORE_PASSWORD`、
`NEKO_KEY_ALIAS`、`NEKO_KEY_PASSWORD`。工作流使用 `release` Environment；长期私钥应离线
备份，绝不能写入仓库、Actions 日志或构建附件。`GH_TOKEN` 只注入最终发布步骤，签名口令只
注入 Keystore 校验和签名构建步骤。

首次创建仓库时，应确认仓库可见性为 Public、默认分支为 `main`，仓库默认 Actions 权限保持只读；
只有 Release job 声明 `contents: write`。创建名为 `release` 的 Environment，将四项签名 Secret
配置在其中，并设置可信 Required reviewer、禁止管理员绕过。由于工作流只从默认分支手动调度，
Environment 的 Deployment policy 应只允许受保护的默认分支（而不是 tag ref）。仓库还应启用
匹配 `refs/tags/v*` 的 Active Tag ruleset，限制创建、更新和删除，只允许专用发布维护者或团队
旁路；同时启用 Private vulnerability reporting。

Gradle 依赖校验元数据尚未启用。后续应在依赖集合稳定后生成并审查
`gradle/verification-metadata.xml`，以 strict dependency verification 保护签名构建；不要在
未审查大量自动生成校验值时直接启用。

## 真机发布门禁

发布前必须使用同一签名证书完成上一版本到候选版本的覆盖升级，确认应用数据保留，并验证
GitHub API、`update.json`、APK 下载、SHA-256、证书摘要和 APK 元数据一致。普通 Android
应用不能静默安装；自动化范围止于检查、下载和验证，最终安装必须由系统安装器确认。

个人仓库使用相同清单契约。APK 的当前签名集合必须与已安装版本完全一致；首版不支持证书
轮换。使用自有签名的 fork 必须先作为自己的基线安装，不能覆盖官方安装版。
