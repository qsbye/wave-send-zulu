# AirScanQR 跨网传输助手（dotnet 桌面版）

把 4 个**纯光学文件传输**网页工具打包成单个 exe 的 Windows 桌面壳——不依赖网络、不安装运行时之外的任何组件，数据不离开本机。适合直接拷进受管控的工控机 / 离线环境使用：一台设备的屏幕播放二维码流，另一台设备用摄像头对准接收。

发送端屏幕高速播放二维码，接收端摄像头锁定、重组、校验并还原文件。喷泉码版丢帧、乱序、中途加入均无感。

## 核心特性

- **单文件 exe**：`dotnet publish` 自包含发布，双击即用，无需安装 .NET
- **完全离线**：页面内嵌于 exe，通过 WebView2 拦截虚拟域名加载，零网络流量
- **四种工具集成**：顺序分片版、喷泉码版、decimen 原版收发端，选项卡一键切换
- **安全上下文**：页面运行在 `https://airscan.local` 源下，`getUserMedia` 等摄像头 API 正常可用，导航白名单限制只加载内嵌资源
- **下载管理**：网页端接收的文件自动落到 `下载` 目录（重名自动追加序号），工具栏按钮直接定位到文件
- **自检模式**：`--selftest` 命令行参数自动验证 WebView2 运行时与各页面可用性

## 内嵌页面

| 页面 | 说明 |
| --- | --- |
| `airscan-standard.html` | AirScan-QR 跨网传输助手（顺序分片版，支持多码交错刷新） |
| `airscan-fountain.html` | AirScan-QR Fountain 喷泉码版（LT 码，任意帧序接收） |
| `decimen-v0.5.3-sender.html` | decimen v0.5.3 原版发送端 |
| `decimen-v0.5.3-receiver.html` | decimen v0.5.3 原版接收端 |

页面均为 SingleFile 单文件快照（脚本/样式全部内联），以 `EmbeddedResource` 形式编译进 exe，共约 3.8 MB。

## 项目结构

```
wave-send-zulu-dotnet-desktop/
├── App.cs                    # 入口：全局异常兜底、--selftest 参数
├── MainWindow.cs             # 主窗口：顶部工具栏（选项卡/刷新/下载目录/状态栏）
├── HtmlView.cs               # 单页面容器：WebView2 拦截、权限、下载、导航白名单
├── AirScanDesktop.csproj     # .NET 8 WinForms 项目文件
├── Assets/                   # 内嵌资源（4 个 HTML + 图标）
│   ├── airscan-standard.html
│   ├── airscan-fountain.html
│   ├── decimen-v0.5.3-sender.html
│   ├── decimen-v0.5.3-receiver.html
│   └── app-icon.ico / app-icon.png
└── build-with-timestamp.sh   # 一键构建脚本（时间戳版本号）
```

## 技术栈

| 组件 | 选型 |
|------|------|
| 框架 | .NET 8（`net8.0-windows`）+ WinForms，PerMonitorV2 高 DPI |
| 浏览器内核 | Microsoft.Web.WebView2 1.0.4191.47 |
| 发布方式 | 单文件自包含（win-x64），启用压缩 |
| 版本号 | `YY.M.D.H` 时间戳（如 `26.10.8.18`） |

## 工作原理

```
WebView2 导航到 https://airscan.local/<页面名>
        │
        ▼
WebResourceRequested 事件拦截该域名全部请求
        │
        ▼
从 exe 内嵌资源（EmbeddedResource）返回对应 HTML
```

关键设计：

- **零网络**：`https://airscan.local` 是虚拟域名，没有任何字节进出网卡；`NavigationStarting` 白名单仅放行 `airscan.local` / `about:` / `data:`
- **权限自动授予**：`PermissionRequested` 一律 Allow（摄像头/麦克风/剪贴板），页面完全离线运行
- **下载落盘**：`DownloadStarting` 把文件重定向到 `%USERPROFILE%\Downloads`，重名自动变为 `name (1).ext`
- **进程崩溃恢复**：`ProcessFailed` 后标记未就绪，下次切换选项卡时重新初始化
- **WebView2 用户数据**：位于 `%LOCALAPPDATA%\AirScanQR\WebView2`，多页面共享同一 Environment

## 构建

前置条件：.NET 8 SDK、Git Bash（运行构建脚本）。

```bash
# 一键构建（推荐）：单文件自包含 exe，按时间戳版本号命名并复制到项目根目录
./build-with-timestamp.sh
```

脚本核心：

```bash
VERSION="$(date +%-y.%-m.%-d.%-H)"   # 版本号：YY.M.D.H（年月日小时）

dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true \
  -p:EnableCompressionInSingleFile=true \
  -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:Version="${VERSION}" \
  -o "publish/singlefile-${VERSION}"
```

产出 `AirScanQR-<版本>.exe`。

## 使用

1. 双击 `AirScanQR-<版本>.exe`（目标机器需安装 [WebView2 运行时](https://developer.microsoft.com/microsoft-edge/webview2/)，Windows 10 20H1+ 及 Windows 11 通常已自带）
2. 顶部选项卡选择工具：
   - **跨网传输助手** / **喷泉码版**：同一页面内完成发送或接收
   - **Decimen 发送端** / **Decimen 接收端**：分别在两台设备上打开对应页
3. 发送端选择文件后屏幕播放二维码流；接收端允许摄像头权限并对准发送端屏幕
4. 接收完成的文件自动保存到 `下载` 目录，点击工具栏「下载目录」直接定位

自检（排查目标机器环境）：

```bash
AirScanQR.exe --selftest
# 依次加载各页面并探测 document.title，结果写入 %TEMP%\airscan-selftest.txt 后自动退出
```

## 限制

- 仅支持 Windows（x64）；依赖 WebView2 运行时，缺失时会弹窗提示安装
- 光传输速率受屏幕刷新率与摄像头帧率限制（实测数百 B/s ~ 数百 KB/s 量级，取决于帧大小/帧率/码数参数）
- 单文件上限随页面实现而定（decimen 为 64 MB）

## 相关项目

- [AirFountainZulu](../wave-send-zulu-air-fountain-android) — 同协议的 Kotlin 原生实现（Android + Windows 桌面），与 decimen v3 线格式兼容
- [decimen](https://github.com/bashalarmistalt/decimen-optical-transfer) — 喷泉码线格式与 DCF2 容器协议来源（内嵌发送/接收端页面的上游）
- [AirScan-QR](https://github.com/HMBSbige/AirScan-QR) — 顺序分片版与喷泉码版页面上游
