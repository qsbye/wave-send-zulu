---
name: device-gif-record
description: 录制 Android 真机屏幕数秒并生成 15fps 调色板压缩 GIF 到项目 screenshots 目录。当用户要求 录屏生成 GIF/持续截图生成动图/15Hz 截图/6 秒 GIF 时使用。单张静态截图改用 device-screenshot。
---

# 真机录屏转 GIF

执行本 skill 目录下的脚本，默认录 6 秒、输出 15fps GIF（约 3MB）：

```bash
scripts/gif.sh [秒数=6] [输出文件名] [设备序列号]
```

关键约束（本会话实测）：

- 华为 TAS-AN00 禁用了 adb `screenrecord`（"inaccessible or not found"），必须用 **scrcpy** 录制（`/opt/homebrew/Homebrew/bin/scrcpy`，v5 支持 `--time-limit`）
- 转码用 homebrew ffmpeg，双调色板管线：`fps=15, scale=360:-2, palettegen(max_colors=128) + paletteuse(bayer 抖动)`，体积约为原始帧序列的 1/10
- 串行 `adb screencap` 循环达不到 15Hz（单帧约 400ms），不要用该方案凑帧
- 生成后建议抽中间帧（`ffmpeg -i x.gif -vf select=eq(n\,45) /tmp/check.png`）用 Read 验证内容
