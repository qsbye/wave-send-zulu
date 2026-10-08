#!/usr/bin/env bash
# AirFountainZulu 带时间戳的签名 release 构建（Git Bash / MSYS2）
#
# 中文路径下 Gradle 测试进程类路径会乱码，因此：
#   1. robocopy 镜像项目到全英文暂存目录 D:\afz_build
#   2. 在暂存目录里跑单元测试 + 构建签名 release APK
#   3. 构建 Windows 桌面分发版（免安装目录）
#   4. 把 APK 复制回项目根目录，文件名带时间戳后缀
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAGE_DIR="D:/afz_build"

export JAVA_HOME="$SRC_DIR/tools/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE="$SRC_DIR/tools/gradle-8.13/bin/gradle.bat"

timestamp="$(date +%Y%m%d-%H%M%S)"

echo "==> [1/3] robocopy 同步源码到全英文路径 $STAGE_DIR"
SRC_WIN="$(cygpath -w "$SRC_DIR")"
STAGE_WIN="$(cygpath -w "$STAGE_DIR")"
set +e
robocopy "$SRC_WIN" "$STAGE_WIN" /MIR \
    /XD .gradle .kotlin build tools \
    /XF "*.apk" \
    /NFL /NDL /NJH /NP
rc=$?
set -e
# robocopy 退出码 0-7 均为成功
if [ "$rc" -ge 8 ]; then
    echo "robocopy 失败，退出码 $rc"
    exit "$rc"
fi

echo "==> [2/4] 单元测试 + 构建签名 release APK"
cd "$STAGE_DIR"
"$GRADLE" --no-daemon --console=plain :app:testDebugUnitTest :app:assembleRelease

echo "==> [3/4] 构建 Windows 桌面分发版"
# WiX 用于打 MSI 安装包，免安装分发不需要；本机离线时放个空目录绕过插件校验
mkdir -p "$STAGE_DIR/build/wix311"
"$GRADLE" --no-daemon --console=plain :desktop:createDistributable -x downloadWix -x unzipWix

echo "==> [4/4] 复制 APK 回项目根目录"
src="$STAGE_DIR/app/build/outputs/apk/release/app-release.apk"
dest="$SRC_DIR/AirFountainZulu-release-${timestamp}.apk"
cp "$src" "$dest"

echo "==> 完成: $dest"
ls -lh "$dest"
echo "==> 桌面分发版: $STAGE_DIR/desktop/build/compose/binaries/main/app/AirFountainZulu"
