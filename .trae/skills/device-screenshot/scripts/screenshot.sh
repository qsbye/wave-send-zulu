#!/usr/bin/env bash
# 真机截图：adb screencap -> screenshots/<name>.png
# 用法: screenshot.sh [输出文件名] [设备序列号]
set -euo pipefail

NAME="${1:-android-$(date +%Y%m%d-%H%M%S).png}"
SERIAL="${2:-XPL0219C06017003}"
ADB="$(command -v adb || echo /Users/workspace/Library/Android/sdk/platform-tools/adb)"

# scripts -> device-screenshot -> skills -> .trae -> 项目根
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
mkdir -p "$ROOT/screenshots"

"$ADB" -s "$SERIAL" exec-out screencap -p > "$ROOT/screenshots/$NAME"
echo "已保存: $ROOT/screenshots/$NAME"
