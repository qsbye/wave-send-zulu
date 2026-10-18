#!/usr/bin/env bash
# macOS 构建带时间戳的签名 release APK
# 中文路径会损坏 Kotlin 编译守护进程路径 -> rsync 到全英文暂存目录构建
# 用法: build-mac.sh   （可选环境变量: AFZ_STAGE 暂存目录, JAVA_HOME, ANDROID_HOME）
set -euo pipefail

# scripts -> build-with-timestamp -> skills -> .trae -> 项目根
SRC_DIR="$(cd "$(dirname "$0")/../../../.." && pwd)"
STAGE_DIR="${AFZ_STAGE:-/tmp/wavesend-build}"

# ---- 工具链探测（必须为 JDK 17，宿主 shell 可能预置了 JDK 8） ----
java_ok() { [ -n "$1" ] && [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "17'; }
if ! java_ok "${JAVA_HOME:-}"; then
  JAVA_HOME=""
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/Library/Java/JavaVirtualMachines/"jbr-*/Contents/Home \
           "$HOME/Library/Java/JavaVirtualMachines/"*17*/Contents/Home; do
    if java_ok "$c"; then JAVA_HOME="$c"; break; fi
  done
  [ -n "$JAVA_HOME" ] || JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)" || {
    echo "未找到 JDK 17"; exit 1; }
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE="$(command -v gradle || true)"
[ -z "$GRADLE" ] && GRADLE="$(ls "$HOME"/.gradle/gradle-*/bin/gradle 2>/dev/null | sort -V | tail -1)"
[ -n "$GRADLE" ] && [ -x "$GRADLE" ] || { echo "未找到 gradle"; exit 1; }

if [ ! -d "${ANDROID_HOME:-/nonexistent}" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

timestamp="$(date +%Y%m%d-%H%M%S)"

echo "==> [1/3] rsync 同步源码到全英文路径 $STAGE_DIR (JAVA_HOME=$JAVA_HOME, gradle=$GRADLE)"
# build/ 不参与镜像，保留暂存目录的增量构建缓存
rsync -a --delete \
  --exclude .gradle --exclude .kotlin --exclude build \
  --exclude '*.apk' --exclude screenshots --exclude .trae \
  "$SRC_DIR/" "$STAGE_DIR/"

echo "==> [2/3] 单元测试 + 签名 release 构建"
cd "$STAGE_DIR"
"$GRADLE" -Pkotlin.compiler.execution.strategy=in-process --console=plain \
  :app:testDebugUnitTest :app:assembleRelease

echo "==> [3/3] 复制 APK 回项目根目录"
dest="$SRC_DIR/AirFountainZulu-release-${timestamp}.apk"
cp app/build/outputs/apk/release/app-release.apk "$dest"
echo "==> 完成: $dest"
ls -lh "$dest"
