# Lynk10 EV Remote View

基于 EVCam 思路实现的领克 10 EV 四路相机远程抓拍测试版。

- 四路 Camera2 `TextureView + SurfaceTexture` 常驻预览
- 远程请求时读取当前预览帧并压缩为 JPEG
- 不包含签名私钥或远程服务凭据
- GitHub Actions 自动生成可安装的 Debug APK

## 下载 APK

打开仓库的 **Actions** 页面，进入最新的 **Build Lynk10 APK** 任务，在
Artifacts 下载 `Lynk10EV-APK`。

## 本地构建

需要 JDK 17 和 Android SDK 36：

```bash
gradle :lynk10app:assembleDebug
```

项目基于 EVCam，许可证见 `LICENSE`。
