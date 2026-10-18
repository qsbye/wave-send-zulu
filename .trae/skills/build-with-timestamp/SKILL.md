---
name: build-with-timestamp
description: 跨开发机构建带时间戳后缀的签名 release APK。当用户要求 构建 APK/release 构建/打包/构建并安装真机/执行 build-with-timestamp 时使用。Windows 与 macOS 均支持，构建含单元测试门禁。
---

# 构建带时间戳的 Release APK

## 按开发机分派

**Windows（Git Bash）** — 直接执行项目根的既有脚本：

```bash
bash build-with-timestamp.sh
```

内部流程：robocopy 镜像到全英文 `D:/afz_build`（规避中文路径）→ `tools/` 内置 JDK17+Gradle8.13 跑单测与 `:app:assembleRelease` → `:desktop:createDistributable` 桌面分发版 → APK 复制回项目根（带时间戳）。

**macOS** — 执行本 skill 的脚本：

```bash
scripts/build-mac.sh
```

内部流程：rsync 镜像到全英文 `/tmp/wavesend-build`（可用 `AFZ_STAGE` 覆盖）→ 探测 JDK（Android Studio JBR 优先，回退 `/usr/libexec/java_home -v 17`）与 Gradle（PATH → `~/.gradle/gradle-*`）→ 单测 + 签名 release 构建 → APK 复制回项目根（带时间戳）。`build/` 不参与镜像，暂存目录保留增量缓存，二次构建很快。

## 关键坑（实测）

1. **中文路径（如 `2026年10月`）必须暂存到全英文路径再构建**——Kotlin 编译守护进程会把路径转成 `\uXXXX` 乱码导致类路径损坏，两个平台都因此走暂存目录
2. macOS 构建加 `-Pkotlin.compiler.execution.strategy=in-process`，进一步绕开守护进程
3. 签名密钥为项目根 `airscan-release.keystore`（相对路径引用，随镜像自动同步）
4. 构建后如需安装：`adb install -r <apk>`；若手机上装过其他签名的版本需先卸载
