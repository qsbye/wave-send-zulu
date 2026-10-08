# AirFountainZulu

基于**喷泉码（Fountain Code / LT Code）**的跨网文件传输工具——不依赖 Wi-Fi、蓝牙、USB、网络，仅用**屏幕发光 + 摄像头拍摄**即可完成文件传输。

发送端屏幕高速播放二维码流，接收端摄像头对准屏幕即可自动锁定、重组、校验并还原文件。丢帧、乱序、中途加入均无感。

## 核心特性

- **喷泉码传输**：LT 码无速率编码，任意帧序、任意丢帧、随时加入/离开
- **自描述帧**：每帧携带完整 22 字节头部，无需握手协议，中途锁定流
- **DCF2 容器**：自动保留文件名/MIME 类型，可选 gzip 压缩，SHA-256 完整性校验
- **跨平台**：Android（Jetpack Compose）+ Windows 桌面（Compose Multiplatform），两端逐位兼容
- **单文件桌面版**：.NET 4 启动器内嵌完整运行时，双击即用

## 项目结构

```
AirFountainZulu/
├── app/                        # Android 应用（Kotlin + Jetpack Compose）
│   └── src/main/java/com/airscan/qr/
│       ├── core/               # 喷泉码核心（纯 Kotlin，两端复用）
│       │   ├── Prng.kt         # SplitMix32 确定性随机数
│       │   ├── Fountain.kt     # LT 编解码器（编码/剥层解码）
│       │   ├── Wire.kt         # 自描述帧协议（22B 小端头部）
│       │   └── Container.kt    # DCF2 文件容器（49B 头 + 可选 gzip + SHA-256）
│       ├── qr/                 # ZXing 二维码生成（字节模式 ISO-8859-1，ECC-L）
│       ├── camera/             # CameraX 取景 + ML Kit 条码识别
│       ├── ui/                 # Compose 发送/接收界面
│       └── MainActivity.kt
├── desktop/                    # Windows 桌面应用（Compose Multiplatform）
│   └── src/desktopMain/kotlin/com/airscan/zulu/
│       ├── Main.kt             # 4:3 窗口（1024×768）
│       ├── SenderTab.kt        # 发送端（文件选择 + 喷泉码播放）
│       ├── ReceiverTab.kt      # 接收端（webcam 采集 + ZXing 解码 + LT 重组）
│       └── QrBitmap.kt         # 二维码渲染（与 Android 端参数一致）
├── launcher/
│   └── Launcher.cs             # .NET 4 单文件启动器源码
├── app/src/test/               # 单元测试（18 项）
├── build-with-timestamp.sh     # 一键构建脚本（robocopy 到全英文路径再构建）
└── airscan-release.keystore    # 签名密钥库
```

## 技术栈

| 组件 | 技术 |
|------|------|
| Android | Kotlin 2.0 + Jetpack Compose (BOM 2024.10.01) |
| 桌面端 | Compose Multiplatform 1.7.0 (JVM) |
| 喷泉码 | 自研 LT 编解码（与 decimen v3 线格式兼容） |
| 二维码生成 | ZXing core 3.5.3（字节模式，ISO-8859-1，纠错级 L） |
| Android 扫码 | ML Kit Barcode Scanning 17.3.0（rawBytes 逐字节还原） |
| 桌面端扫码 | ZXing QRCodeReader（CHARACTER_SET=ISO-8859-1 强制映射） |
| 桌面摄像头 | webcam-capture 0.3.12 |
| 启动器 | .NET Framework 4.x（单文件 WinExe，内嵌 zip 自解压） |

## 传输协议

### 帧格式（22 字节小端头部 + 载荷）

```
 0  u8   magic 0xD1
 1  u8   magic 0xC3
 2  u8   version = 3
 3  u8   flags
 4  u16  sessionId     每次发送随机生成
 6  u32  seq           帧序号（PRNG 种子输入）
10  u16  k             源块数
12  u16  blockLen      每帧载荷字节数
14  u32  totalLen      DCF2 容器总长度
18  u32  payloadFnv    容器 FNV-1a 哈希（完成后校验）
22  ...  blockLen 字节喷泉码编码块
```

### DCF2 容器（49 字节小端头部 + 元数据 + 数据）

```
 0  4B   magic "DCF2"
 4  u8   compression  0=none 1=gzip
 5  u16  nameLength
 7  u16  typeLength
 9  u32  fileLength        原始文件长度
13  u32  transmittedLength 实际承载长度
17  32B  sha256(原始文件)
49  ...  name UTF-8 + type UTF-8 + transmitted bytes
```

## 构建

### 前置条件

- JDK 17（已内置在 `tools/jdk-17.0.2/`）
- Gradle 8.13（已内置在 `tools/gradle-8.13/`）
- Android SDK 34（通过 `local.properties` 指向）
- Git Bash（运行构建脚本）

### 一键构建（推荐）

```bash
./build-with-timestamp.sh
```

流程：robocopy 镜像到全英文暂存目录 `D:\afz_build` → 单元测试 → 签名 release APK → 桌面分发版 → APK 回拷项目根目录（带时间戳后缀）。

### 手动构建

```bash
export JAVA_HOME="$PWD/tools/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

# Android APK（签名 release）
./tools/gradle-8.13/bin/gradle.bat :app:assembleRelease --no-daemon

# 桌面分发版（免安装目录）
mkdir -p build/wix311   # 绕过 WiX 校验（打 MSI 才需要真正的 WiX）
./tools/gradle-8.13/bin/gradle.bat :desktop:createDistributable -x downloadWix -x unzipWix --no-daemon

# 单元测试
./tools/gradle-8.13/bin/gradle.bat :app:testDebugUnitTest --no-daemon
```

> **注意**：项目路径含中文时 Gradle 测试进程的类路径会乱码导致 `ClassNotFoundException`，构建脚本通过 robocopy 到 `D:\afz_build` 规避此问题。

### 打包单文件启动器

```powershell
# 1. 压缩桌面分发版
Compress-Archive -Path "D:\afz_build\desktop\build\compose\binaries\main\app\AirFountainZulu\*" `
    -DestinationPath "$env:TEMP\afz_payload.zip" -CompressionLevel Optimal

# 2. 用 .NET 4 csc 编译（zip 嵌入为资源）
& "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe `
    /platform:anycpu /optimize+ `
    /out:AirFountainZulu-launcher.exe `
    /resource:"$env:TEMP\afz_payload.zip,payload.zip" `
    /r:System.dll /r:System.Core.dll /r:System.IO.Compression.dll `
    /r:System.IO.Compression.FileSystem.dll /r:System.Windows.Forms.dll `
    launcher\Launcher.cs
```

## 使用

### Android ↔ Android

1. 发送端打开 App → 选择文件 → 点击「开始发送」
2. 接收端打开 App → 切到「接收」→ 摄像头对准发送端屏幕
3. 进度条走满后点击「保存」

### Windows 桌面版

1. 双击 `AirFountainZulu-26.10.8.18-launcher.exe`（首次运行自动释出到 `%LOCALAPPDATA%\AirFountainZulu\`）
2. 发送：选择文件 → 开始发送；接收：开始接收 → 摄像头对准屏幕
3. 接收完成后点击「保存…」

### 参数说明

| 参数 | 选项 | 说明 |
|------|------|------|
| 帧大小 | 500B / 1000B / 1465B | 每帧二维码载荷字节数，越大吞吐越高但识别率下降 |
| 帧率 | 10 / 15 / 24 / 30 fps | 播放速度，高帧率需要更好的摄像头 |

## 限制

- 单文件上限 **64 MB**（`Container.MAX_FILE_BYTES`）
- 最大帧载荷 1465B（二维码字节模式 ECC-L 容量约 2953B，留有余量）
- Windows 桌面版打包 MSI 安装包需要 WiX 工具链（本机网络受限时跳过，免安装版不受影响）
- Android 需授权摄像头权限；桌面版需摄像头硬件

## 参考项目

- [decimen](https://github.com/spite-triangle/decimen) — 喷泉码线格式与 DCF2 容器协议来源
- [AirScan-QR](https://github.com/HMBSbige/AirScan-QR) — 普通 QR 顺序分片参考
- [airgapped-qr-code-transfer](https://github.com/Ruk33/airgapped-qr-code-transfer) — 气隙传输参考
