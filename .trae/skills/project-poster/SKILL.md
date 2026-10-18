---
name: project-poster
description: 为 AirFountainZulu 项目生成宣传海报（HTML 固定画布 → Playwright 导出高清 PNG）。当用户要求 项目海报/宣传海报/海报设计/项目介绍图/GitHub banner/社交媒体分享图 时使用。方法论改编自 poster/Skill2Poster-main（学术论文海报技能集）。
---

# 项目宣传海报

把海报当作**压缩的论证**而非装饰性介绍：为什么这个问题重要 → 项目改变了什么 → 凭什么可信 → 结果如何证明。

## 产物与目录

- 输出到项目根 `poster/` 目录：`airfountain-poster-<variant>.html` 与同名 `.png`
- HTML 为唯一事实源（文字/数字/截图全部真实），PNG 由 Playwright 从 HTML 导出
- 图像生成（生图）仅可用于前期概念草图；其输出的文字、数字、二维码一律视为占位，不得进入成品

## 第一步：素材盘点

先读真实素材，禁止编造数字与功能：

- `README.md` — 项目定位一句话、核心特性列表、技术栈表、限制说明
- `screenshots/` — 真机截图（android-main.png、android-qr-lock.png、android-demo.gif 等），海报用 PNG 帧，GIF 取代表帧
- 版本信息：`git tag --sort=-creatordate | head -1` 与 `gh release view <tag> --repo qsbye/wave-send-zulu`
- 仓库链接：`https://github.com/qsbye/wave-send-zulu/tree/ggwave-fountain-android`（**必须带分支名**，见行动区链接规则）
- 高信号指标候选（只取可验证的）：三端逐位兼容 / 72 项单测 / 双信道互补（光 + 声）/ 零网络气隙传输 / F-Droid FOSS 合规 / 4.2MB 接收实测

## 第二步：选规格（固定 CSS 画布，禁止视口缩放字号）

| variant | 画布 CSS px | 用途 |
|---------|------------|------|
| wide    | 1920×1080  | 社交媒体分享图、README banner（deviceScaleFactor 2 导出 4K） |
| a0      | 3179×4494  | A0 竖版学术/展会海报（96dpi；150dpi 导出用 deviceScaleFactor 150/96） |
| card    | 1200×675   | 卡片式简介图（微信群/朋友圈，deviceScaleFactor 2） |

## 第三步：内容架构（wide/横版默认流）

1. **标题区**：应用名 AirFountainZulu + 一句话定位（"屏幕发光 + 摄像头拍摄 即可传文件"）+ 版本号徽章
2. **指标条**：4 张高信号指标卡（图标 + 大数值 + 一行对比），数值必须来自素材盘点
3. **主张条**：一句话核心主张（如"丢帧、乱序、中途加入均无感——喷泉码让信道只需多数帧送达"）
4. **截图墙**：2×2 或 1 大 3 小放置真实截图，每张配一句图注说明看点（蓝色锁定框/频谱包络/进度详情）
5. **机制区**：分层模型简表（信道 → 调制 → 帧级编码）或三级解码链流程，直接改编 README 的表格
6. **底部行动区**：GitHub 二维码（≥120px 可扫）+ 仓库 URL + 构建/安装一句话

## 排版规则（改编自 Skill2Poster）

- 阅读顺序自上而下、自左而右；分栏内容列底对齐，底部留白要有意图而非意外
- 字重 460–650，避免 800/900 重型展示字；letter-spacing 恒为 0，禁负字距
- 正文全海报统一字号；图注/小注不得缩到明显小于正文；标题与副标题视觉关联（忌超大标题配迷你副标题）
- 每个图表/截图必须有说明"看什么"的图注；无解释的装饰块一律删除
- 空白处理顺序：补缺的关键结果 → 放大现有图 → 给相邻节加有据短句 → 删冗余再平衡；**禁止用口水话填空白**
- 嵌套卡片不套卡片；同类指标卡必须视觉平行
- flex 分栏记得 `flex-shrink: 0`，否则面板会被压缩出隐藏溢出

## 视觉主题（大地色系）

- 底色米色/亚麻 `#f4ede2`，卡片奶白 `#faf6ef`、描边 `#e0d4c2`；标题深棕 `#4a3728`，次级文字 `#8a7a66`
- 强调色一主一辅：赤陶土 `#b06a4f`（光信道/主 CTA）+ 橄榄绿 `#6b6242`（声波/成功项）；辅以奶茶 `#d9c7b2`、驼色 `#c19a6b` 过渡
- 背景装饰用赤陶土/橄榄绿的贝塞尔光波/声波曲线，呼应双信道；截图卡用暖棕描边跳出米色底
- 全海报禁用高饱和荧光色，保持低饱和、中低明度的大地质感（参考 docs/ 大地色系配色指南）

## 行动区链接规则

- **项目 GitHub 链接必须携带分支名**：本分支 `ggwave-fountain-android` 未合并到 main，直接给仓库主页会看不到本项目最新代码。统一使用 `https://github.com/qsbye/wave-send-zulu/tree/ggwave-fountain-android`，并在链接旁标注分支名；合并 main 后才可改回仓库主页

## 第四步：渲染与 QA（必须实际渲染，不得凭空声称正确）

用 Playwright（`npx playwright screenshot --viewport-size=<宽>,<高>`）或浏览器插件渲染 HTML，检查：

- `document.body.scrollWidth === 画布宽`、`scrollHeight === 画布高`
- 每个 panel `scrollHeight <= clientHeight + 2`（无内容溢出）
- 各列最后一个 panel 底端 y 坐标近似相等
- 无文字贴边、无图注/表行被裁剪；二维码尺寸可扫
- 导出 PNG 后用 Read 工具目检：文字清晰、无错字、数字与 README 一致

## 第五步：交付

报告产物链接（poster/ 下 HTML + PNG）与 QA 结果（画布尺寸、无溢出、列底对齐）。需要提交入库时产物与技能一并提交。

## 参考资料

- 方法论完整版：`poster/Skill2Poster-main/academic-poster-builder/SKILL.md`（A0 学术海报细节、QA checklist）
- 生图概念稿工作流：`poster/Skill2Poster-main/paper-poster-imagegen/SKILL.md`（仅用于前期布局探索）
- GitHub 二维码可用 `python3 -c "import qrcode"` 生成（无则 `pip3 install qrcode`），内容为仓库 URL
