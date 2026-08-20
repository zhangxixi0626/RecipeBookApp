# 领克10远程监看中转服务

这个服务只转发当前抓拍，不落盘，也不保存历史图片。

## 本机启动

```powershell
npm.cmd install
$env:VEHICLE_TOKEN="replace-with-a-random-token-at-least-32-chars"
$env:OWNER_PASSWORD="replace-with-a-different-strong-password"
npm.cmd start
```

浏览器打开 `http://127.0.0.1:8295`。车机本地联调可将服务器地址填写为
`ws://127.0.0.1:8295/ws/vehicle`，并使用ADB反向端口：

```powershell
adb reverse tcp:8295 tcp:8295
```

公网使用时必须放在HTTPS反向代理后面。车机服务器地址填写
`wss://你的域名/ws/vehicle`，手机直接打开同一域名即可。设备绑定依赖浏览器安全环境，
不要通过公网HTTP地址使用。

车机端先填写按“前、后、左、右”排序的4个Camera ID。打开应用后会自动开始守候。
网页只有同时检测到“车辆在线”和“相机已布防”才允许发起抓拍。
车机重启后网络通道会尝试恢复，但Android 14及以上不允许从开机广播直接启动后台
相机服务，因此车机重启后需要重新打开一次应用。

运行时消息链路为：网页 `/ws/viewer` -> relay -> 车机 `/ws/vehicle` -> 四张JPEG
原路返回。relay只在内存中转发，不保存画面。WebDAV仅用于相机ID诊断报告和样图，
不参与正式远程抓拍。

`VEHICLE_TOKEN` 只供车机连接中继，`OWNER_PASSWORD` 只供网页绑定或临时登录，二者
必须设置成不同内容，不要写进源码或发到聊天群。主手机只在首次绑定时输入一次网页密码，
之后使用浏览器内不可导出的设备私钥自动验证。

## NAS Docker + Cloudflare Tunnel

最省事的做法是让 Compose 同时运行中继和 `cloudflared`。先把
`.env.example` 复制为 `.env`，填写以下配置：

- `VEHICLE_TOKEN`：自己生成的32位以上随机密钥，只填写到车机应用；
- `OWNER_PASSWORD`：与车机密钥不同的强密码，只用于网页访问；
- `TUNNEL_TOKEN`：Cloudflare 控制台给出的 Tunnel Token。

生产环境建议不保存网页明文密码。先生成哈希：

```powershell
npm.cmd run hash-password -- "你的网页访问密码"
```

把输出填入 `.env` 的 `OWNER_PASSWORD_HASH`，并将 `OWNER_PASSWORD` 留空。服务会优先
使用哈希配置。

然后运行：

```powershell
docker compose up -d --build
```

在 Cloudflare Tunnel 的 Public Hostname 中，把服务地址填写为：

```text
http://relay:8295
```

例如公共域名是 `car.example.com`，车机服务器地址就是：

```text
wss://car.example.com/ws/vehicle
```

手机浏览器打开 `https://car.example.com/`。

第一次用主手机打开会显示“绑定这台手机”。输入一次 `OWNER_PASSWORD` 后，服务器记录
该浏览器的设备公钥；同一手机以后会自动进入。其他设备可输入 `OWNER_PASSWORD` 临时访问，
会话最长30分钟，关闭浏览器后也会失效。不要清理主手机上这个域名的浏览器数据，也不要
使用无痕模式，否则设备私钥会丢失，需要重新绑定。

要更换主手机，可执行：

```powershell
docker compose exec relay npm run reset-device
docker compose restart relay
```

监看页会恢复到未绑定状态。也可以在停止 Compose 项目后删除 `device_state` 数据卷再重建。
软件仓库不受设备绑定限制，车机仍可打开 `/downloads.html` 下载文件。

把需要提供下载的软件放入 `downloads` 目录，然后打开：

```text
https://car.example.com/downloads.html
```

页面会自动列出目录里的所有普通文件，不需要修改配置或重建镜像。下载按钮直接请求
`/download/file/文件ID`，不再经过新窗口和 302 跳转。响应行为恢复为车机已经实测可用的
旧版本：`Content-Type: application/octet-stream`，下载文件名统一伪装为 `.bin`，同时支持
断点续传。四向照片监看功能与这条下载链互相独立，恢复旧下载链不会移除监看功能。

新增或删除文件后，刷新软件仓库页面即可。旧的 `/download/app` 单文件入口仍然保留，
并会在原固定文件不存在时自动选择下载目录中最新的文件。

这个入口只解决浏览器下载。车机是否允许安装，仍由“移动空间”、应用白名单和
系统签名策略决定。

如果 NAS 已经有另一个 `cloudflared` 容器，不想重复创建 Tunnel 容器，可改用：

```powershell
docker compose -f compose-relay-only.yaml up -d --build
```

这时 Cloudflare 中的服务地址填写 `http://NAS局域网IP:8295`。不要在路由器上把
8295端口映射到公网。

服务容器使用只读文件系统，图片只在内存中转发。`.env` 已被 Docker 构建上下文
排除，不要把它打包上传或发给别人。
