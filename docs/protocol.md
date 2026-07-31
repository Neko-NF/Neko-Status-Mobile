# 协议

认证使用 `/api/auth/login`、`/api/auth/register`、`/api/auth/me`、`/api/auth/profile` 和
`/api/auth/device-key`。设备接入使用 `/api/pair/handshake` 与 `/api/device/validate`。
状态通过 `POST /api/v2/status/report` 发送 multipart：`Authorization: Bearer <deviceKey>`、
`data` JSON 和可选 `file` PNG 图标。

资料页以会话 JWT 调用 `GET /api/auth/me` 展示当前资料，并通过 `PUT /api/auth/profile`
更新用户名、邮箱、头像或密码。头像使用经过本地缩放压缩的 JPEG data URI；修改密码时仅发送
`currentPassword` 与 `newPassword`，密码不做裁剪或持久化。更新成功后必须同步可能轮换的
会话令牌、用户资料及小组件用户名；401 或 403 会触发完整账号边界清理。

`ScreenState.ON` 映射为 `online/on`，`LOCKED` 映射为 `away/locked`，`OFF` 映射为
`offline/off`。401、403、404 是终止错误，必须停止服务并清除认证；429 读取
`Retry-After`；5xx 和网络错误使用指数退避。

OpenAPI 与请求样例位于 `api/`，修改接口时必须同步更新它们和 MockWebServer 测试。

## 关注状态小组件

小组件不是本机上报状态的镜像。客户端先以设备密钥调用 `POST /api/widget/token` 换取独立
小组件令牌，再以 `Authorization: Bearer <widgetToken>` 调用 `GET /api/v2/widget/status`，
读取当前账号可见的关注用户及其远程设备。响应同时兼容 `data.users` 与旧版顶层 `users`；
设备应用、电池、媒体和截图字段同时兼容设备平铺字段与嵌套 `status` 字段。

4×2 状态微件最多显示两行关注用户或所选用户的一至两台首屏设备，不显示截图。其余在线
设备按页排列，用户可通过微件右上角的图标按钮切换；每个微件实例独立保存当前页，设置页
可隐藏该按钮。用户主动打开“显示设备截图”后，设置页强制选择单个用户和设备，并请求添加
独立 4×4 截图微件；当多台设备有截图时，4×4 使用相同的切换入口。Android 无法静默把已有
4×2 实例扩成 4×4，因此两种尺寸使用不同 Provider。

退出、认证撤销、服务器切换或账号身份变化时，客户端会停用刷新并清除账号相关的显示范围、
目标设备、状态 Feed、图片缓存和微件页码；刷新间隔、主题、透明度等设备级外观偏好可保留。

截图字段为 `screenshotUrl`、`screenshotThumbnailUrl` 和 `screenshotUpdatedAt`。客户端下载时
优先使用缩略图，以小组件 Bearer 头鉴权，不把令牌写入 URL；原生上报仍不包含 Android
截图。无新数据或网络错误时可显示应用私有缓存，并明确标注“离线缓存”。
