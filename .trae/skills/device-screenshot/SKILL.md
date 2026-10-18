---
name: device-screenshot
description: 截取 Android 真机当前屏幕并保存为 PNG 到项目 screenshots 目录。当用户要求 真机截图/截图手机屏幕/保存当前画面 时使用。录屏或生成 GIF 动图时改用 device-gif-record。
---

# 真机截图

执行本 skill 目录下的脚本即可，参数可省略（默认设备 XPL0219C06017003，文件名自动带时间戳）：

```bash
scripts/screenshot.sh [输出文件名] [设备序列号]
```

要点：

- 使用 `adb exec-out screencap -p`，输出重定向到 `screenshots/<名称>.png`（目录自动创建）
- 脚本以自身路径定位项目根（`.trae/skills/device-screenshot/../../`）
- 截图完成后用 Read 工具打开 PNG 验证内容是否符合预期（如某开关状态、某卡片文案）
- 单张截图约 1-2MB；文件名建议语义化（如 `android-qr-lock.png`）便于 README 引用
