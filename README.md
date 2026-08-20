# Lynk10 EV Remote View 1.04

基于 EVCam 思路实现的领克 10 EV 四路相机远程抓拍，以及配套的 NAS 中转服务。

- 车机维持四路 Camera2 `TextureView + SurfaceTexture` 预览；
- 手机向 NAS 发起请求，车机读取当前四路画面并回传 JPEG；
- 主手机首次绑定后使用浏览器设备私钥免密访问；
- 其他设备使用网页密码临时访问，会话最长 30 分钟；
- 车机密钥与网页密码完全分离，照片仅在内存中转发、不保存历史。

## 下载 APK

打开仓库的 **Actions** 页面，进入最新的 **Build Lynk10 APK**，在 Artifacts 下载
`Lynk10EV1.04`。压缩包内文件固定为 `Lynk10EV1.04.apk`。

这是 GitHub 自动生成的 Debug APK。如果车机里已经装有不同证书签名的同包名应用，需先
卸载旧版再安装。

## NAS 部署

进入 `server` 目录，将 `.env.example` 复制成 `.env`，至少设置：

```text
VEHICLE_TOKEN=仅供车机使用的32位以上随机字符串
OWNER_PASSWORD=与车机密钥不同的网页强密码
TUNNEL_TOKEN=Cloudflare Tunnel Token
```

然后运行：

```bash
docker compose up -d --build
```

详细绑定、密码哈希、换机重置和绿联 NAS 说明见 [server/README.md](server/README.md)。

## 本地构建

需要 JDK 17、Android SDK 36 和 Node.js 22：

```bash
npm --prefix server ci
npm --prefix server test
gradle :lynk10app:assembleDebug
```

项目基于 EVCam，许可证见 `LICENSE`。
