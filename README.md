# AirFountainZulu

基于**喷泉码（Fountain Code / LT Code）**的跨网文件传输工具——不依赖 Wi-Fi、蓝牙、USB、网络，仅用**屏幕发光 + 摄像头拍摄**即可完成文件传输；还可开启**声波通道**（扬声器 + 麦克风）双通道并行互补。

发送端屏幕高速播放二维码流，接收端摄像头对准屏幕即可自动锁定、重组、校验并还原文件。丢帧、乱序、中途加入均无感。开启声波后，同一喷泉码帧同时经二维码与声波两条信道发送，任一信道丢帧由另一条补齐。

## 核心特性

- **喷泉码传输**：LT 码无速率编码，任意帧序、任意丢帧、随时加入/离开
- **自描述帧**：每帧携带完整 22 字节头部，无需握手协议，中途锁定流
- **DCF2 容器**：自动保留文件名/MIME 类型，可选 gzip 压缩，SHA-256 完整性校验
- **声波通道**：纯 Kotlin 全量移植 [ggwave](https://github.com/ggerganov/ggwave)，大帧拆 64B 声波段传输、接收端重组还原原始帧，与二维码通道共用同一 LT 解码器
- **声波可视化与省电开关**：收发两端实时贝塞尔频谱包络曲线（Catmull-Rom 平滑），接收页附进度详情（进度/新帧/重复/冗余/会话/块数）与剩余时间预计；接收端可关闭摄像头改纯声波接收省电，卡片 80% 半透明便于观察二维码
- **跨平台**：Android（Jetpack Compose）+ Windows 桌面（Compose Multiplatform），两端逐位兼容，声波协议亦可跨端互通
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
│       │   ├── Container.kt    # DCF2 文件容器（49B 头 + 可选 gzip + SHA-256）
│       │   ├── ggwave/         # ggwave 纯 Kotlin 全量移植
│       │   │   ├── Gf.kt           # GF(2^8) 有限域
│       │   │   ├── ReedSolomon.kt  # RS 前向纠错（Berlekamp-Massey + Forney）
│       │   │   ├── Rdft.kt         # Ooura 实数 FFT
│       │   │   ├── Resampler.kt    # sinc 插值重采样
│       │   │   └── GGWave.kt       # 调制/解调引擎（12 协议 + DSS）
│       │   └── audio/
│       │       └── AudioSegment.kt # 喷泉码帧 ↔ 64B 声波段 分段/重组协议
│       ├── qr/                 # ZXing 二维码生成（字节模式 ISO-8859-1，ECC-L）
│       ├── camera/             # CameraX 取景 + ML Kit 条码识别
│       ├── audio/              # AudioTrack 扬声器发送 + AudioRecord 麦克风接收
│       ├── ui/                 # Compose 发送/接收界面（含声波开关）
│       └── MainActivity.kt
├── desktop/                    # Windows 桌面应用（Compose Multiplatform）
│   └── src/desktopMain/kotlin/com/airscan/zulu/
│       ├── Main.kt             # 4:3 窗口（1024×768）
│       ├── SenderTab.kt        # 发送端（文件选择 + 喷泉码播放 + 声波开关）
│       ├── ReceiverTab.kt      # 接收端（webcam + 声波接收，双通道重组）
│       ├── DesktopAudio.kt     # javax.sound.sampled 声波收发（与 Android 同协议）
│       └── QrBitmap.kt         # 二维码渲染（与 Android 端参数一致）
├── launcher/
│   └── Launcher.cs             # .NET 4 单文件启动器源码
├── app/src/test/               # 单元测试（54 项：喷泉码/容器 + RS/FFT/ggwave 往返/段重组）
├── build-with-timestamp.sh     # 一键构建脚本（robocopy 到全英文路径再构建）
└── airscan-release.keystore    # 签名密钥库
```

## 技术栈

| 组件 | 技术 |
|------|------|
| Android | Kotlin 2.0 + Jetpack Compose (BOM 2024.10.01) |
| 桌面端 | Compose Multiplatform 1.7.0 (JVM) |
| 喷泉码 | 自研 LT 编解码（与 decimen v3 线格式兼容） |
| 声波调制 | ggwave 纯 Kotlin 移植（16 进制音调 FSK + RS 纠错，MIT） |
| Android 声波 | AudioTrack（MODE_STREAM 发送）+ AudioRecord（VOICE_RECOGNITION 源接收，无 AGC/降噪）48kHz 单声道 PCM16 |
| 桌面声波 | javax.sound.sampled（SourceDataLine / TargetDataLine），与 Android 线格式一致 |
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

## 通信原理

### 分层模型：信道 → 调制 → 编码

| 层次 | 光信道（屏幕 → 摄像头） | 声波信道（扬声器 → 麦克风） |
|------|------|------|
| 物理信道 | 可见光亮度场（屏幕逐帧刷新） | 空气声波：可听档约 1.9–10.8 kHz，超声档 15–23.9 kHz |
| 调制方式 | 二维码（二维空间并行编码） | 多频 FSK（每符号 16 选 1 音调，6 音并行） |
| 帧内纠错 | QR Reed-Solomon（纠错级 L） | RS(64, 24)，每段可纠 12 字节 |
| 帧封装 | 22B 头 + 块（一个二维码 = 一帧） | 64B 声波段（14B 段头 + 50B 载荷），≤32 段拼回一帧 |
| 帧级编码 | LT 喷泉码（两信道共用） | LT 喷泉码（两信道共用） |

**分工哲学**：帧内纠错（QR RS / 声波 RS）保证"收到的帧必然无误码"；喷泉码保证"任意凑够 k 帧即可还原"。信道只需多数帧送达、允许整帧丢失——这也是双通道并行互补的基础：二维码与声波各自收到的帧都进同一个 `LTDecoder`，一边被遮挡/受干扰时另一边自动补帧。

### 光信道：二维码调制 / 解调

- **调制**：喷泉码帧（22B 头 + 块）→ ZXing 生成二维码（字节模式，ISO-8859-1 保证字节与码字 1:1 映射）→ 屏幕以 10–30 fps 逐帧刷新
- **解调**：CameraX 逐帧取景 → ML Kit `rawBytes` 直接还原原始字节（不经文本转换）→ `classifyFrame` 校验 magic/版本 → `LTDecoder.addFrame`
- 二维码自带 Finder 图案定位与 RS 纠错；整帧解码失败即视为丢帧，由喷泉码吸收

### 声波信道：ggwave 多频 FSK 调制 / 解调

**分段**：喷泉码帧（522–1487B）远超 ggwave 单次传输容量，故拆成 64B 声波段：

```
 0  u8   magic 'A' (0x41)
 1  u8   magic 'S' (0x53)
 2  u8   version = 1
 3  u8   flags       低 5 位 = 段总数-1（1..32）
 4  u16  sessionId   与喷泉码帧头一致
 6  u32  seq         喷泉码帧序号
10  u16  frameLen    原始帧总长
12  u8   segIndex
13  u8   reserved
14  50B  原始帧切片（末段不足补 0）
```

**调制（Tx）**：

1. 64B 段 → RS(64, 24) 加纠错得 88B 码字
2. 码字按 3B/块分组（88B → 30 块）；每字节拆高低 4bit 得 6 个十六进制符号
3. 第 j 槽位符号 d 发送频率 `f = 46.875·freqStart + 93.75·(16j + d)` Hz（48 kHz / 1024 点 FFT，bin 间隔 46.875 Hz；槽位间间隔 93.75 Hz，符号间无混叠）
4. 6 路单音叠加合成 1024 样本一帧（21.3 ms），各音调相位按位序错开 πk/24 降低峰均比；同一块重复 6 帧（Fast 档），波形边缘 15% 余弦淡入淡出防爆音
5. 档位：可听 `freqStart=40`（1875 Hz 起）、超声 `freqStart=320`（15 kHz 起）；固定帧长模式无起止标记音开销

**解调（Rx）**：

1. 麦克风流按 1024 样本滑窗 → Ooura 实数 FFT → 功率谱（4 帧历史叠加）
2. 每槽位在其 16 个候选 bin 上取 argmax 还原符号，跨 6 帧多数投票（>3 帧一致才采纳）→ 3B/块 → 30 块拼齐 88B
3. RS 解码纠错 → 校验通过得 64B 段 → 段头字段校验 → `Reassembler` 拼齐同一帧的全部段 → 还原原始喷泉码帧 → 复用二维码的 `onFrame` 入口
4. 接收端只启用目标协议（可听或超声单档）参与检测，显著降低误检率

**吞吐定位**：单段 64B 约 3.8 s（净载荷约 13 B/s），仅作兜底通道——主力吞吐由二维码承担，声波负责暗光、角度受限、摄像头无法对准屏幕时补帧；两台设备无需屏幕互相可见，隔墙也可经声波传完。

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

**开启声波双通道**：发送端在参数卡打开「声波发送（扬声器）」开关；接收端打开「声波接收（麦克风）」开关（首次弹出麦克风权限）。开启后二维码与声波同时收发，状态卡会显示声波信号检测情况。两档位均可选：可听声波（人耳可闻）或超声波（约 15 kHz，安静环境近距离使用）。

**声波可视化与省电**：声波开启后，收发两端均实时显示贝塞尔频谱包络曲线（信号活动绿色）；接收端声波卡附进度详情（进度/新帧/重复/冗余、会话与块数）。接收页可单独关闭「摄像头接收」开关改为纯声波接收——适合暗光、摄像头无法对准屏幕或长时间待机省电的场景；此时隔墙也可由声波独立完成传输（吞吐约 13 B/s，大文件耗时较长）。

**剩余时间预计**：接收页实时显示两类倒计时——①「预计剩余约 X 秒」显示在进度条下方，按最近 6 秒窗口的实际唯一帧到达速率外推，随二维码/声波任一通道的实际吞吐自动校准；②声波锁定预计（如「预计 28s 后锁定」）——声波需收齐一帧的全部段（每段约 3.8 s）才能建立喷泉码流，锁定前按剩余段数 × 单段耗时倒计时，这是协议机制而非卡死。接收界面卡片为 80% 半透明，方便透过卡片观察镜头对准屏幕的情况。

### Windows 桌面版

1. 双击 `AirFountainZulu-26.10.8.18-launcher.exe`（首次运行自动释出到 `%LOCALAPPDATA%\AirFountainZulu\`）
2. 发送：选择文件 → 开始发送；接收：开始接收 → 摄像头对准屏幕
3. 接收完成后点击「保存…」

桌面端同样提供「声波发送/接收」开关（可听/超声两档），与 Android 端协议一致，可跨端互通。

### 参数说明

| 参数 | 选项 | 说明 |
|------|------|------|
| 帧大小 | 500B / 1000B / 1465B | 每帧二维码载荷字节数，越大吞吐越高但识别率下降 |
| 帧率 | 10 / 15 / 24 / 30 fps | 播放速度，高帧率需要更好的摄像头 |
| 声波档位 | 可听声波 / 超声波 | 可听档穿透性好；超声档人耳不可闻但易被阻挡、依赖扬声器高频响应 |

## 限制

- 单文件上限 **64 MB**（`Container.MAX_FILE_BYTES`）
- 最大帧载荷 1465B（二维码字节模式 ECC-L 容量约 2953B，留有余量）
- 声波通道仅支持 ≤1600B（含 22B 头）的帧，且吞吐仅约 13 B/s，定位为兜底通道
- 超声档依赖扬声器/麦克风高频响应，多数笔记本麦克风 16 kHz 以上衰减严重，建议优先可听档
- Windows 桌面版打包 MSI 安装包需要 WiX 工具链（本机网络受限时跳过，免安装版不受影响）
- Android 需授权摄像头权限（扫码）与麦克风权限（声波接收）；桌面版需摄像头硬件

## 参考项目

- [decimen](https://github.com/spite-triangle/decimen) — 喷泉码线格式与 DCF2 容器协议来源
- [ggwave](https://github.com/ggerganov/ggwave) — 声波数据调制/解调库（MIT），本项目纯 Kotlin 全量移植并保持线格式兼容
- [AirScan-QR](https://github.com/HMBSbige/AirScan-QR) — 普通 QR 顺序分片参考
- [airgapped-qr-code-transfer](https://github.com/Ruk33/airgapped-qr-code-transfer) — 气隙传输参考
