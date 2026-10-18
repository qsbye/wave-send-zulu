#!/usr/bin/env bash
# 真机录屏转 GIF：scrcpy 录制 -> ffmpeg 15fps 调色板 GIF
# 用法: gif.sh [秒数=6] [输出文件名] [设备序列号]
set -euo pipefail

SECS="${1:-6}"
NAME="${2:-android-demo-$(date +%Y%m%d-%H%M%S).gif}"
SERIAL="${3:-XPL0219C06017003}"
# 确定性解析工具路径，不受外部环境变量影响（避免沙箱缓存中的旧版 ffmpeg）
ADB="$(command -v adb || echo /Users/workspace/Library/Android/sdk/platform-tools/adb)"
SCRCPY="$(command -v scrcpy || echo /opt/homebrew/Homebrew/bin/scrcpy)"
FFMPEG="$(command -v ffmpeg || true)"
# 沙箱环境可能解析到损坏的缓存副本，先探测可用性，失败则用版本锁定路径
if [ -z "$FFMPEG" ] || ! "$FFMPEG" -version >/dev/null 2>&1; then
  FFMPEG="$(ls /opt/homebrew/Homebrew/Cellar/ffmpeg/*/bin/ffmpeg 2>/dev/null | tail -1)"
fi
FPS=15
WIDTH=360

# scripts -> device-gif-record -> skills -> .trae -> 项目根
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
mkdir -p "$ROOT/screenshots"

TMP="$(mktemp -t wavesend).mp4"
trap 'rm -f "$TMP"' EXIT

# 华为设备禁用 adb screenrecord，必须走 scrcpy
"$SCRCPY" -s "$SERIAL" --no-playback --no-audio --record="$TMP" --time-limit="$SECS"

"$FFMPEG" -y -i "$TMP" -vf "fps=$FPS,scale=$WIDTH:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  "$ROOT/screenshots/$NAME"

echo "已保存: $ROOT/screenshots/$NAME"
