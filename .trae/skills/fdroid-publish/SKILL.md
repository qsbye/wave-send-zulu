---
name: fdroid-publish
description: 将 AirFountainZulu 新版本发布/更新到 F-Droid。当用户要求 提交F-Droid/更新F-Droid/发布到F-Droid/fdroid MR/fdroid 提交 时使用。覆盖版本号 bump、fastlane changelog、打 tag、fdroiddata 分支推送与 MR 创建全流程。
---

# 发布 / 更新到 F-Droid

本 skill 适用于 AirFountainZulu（applicationId `com.airscan.qr`）向 F-Droid 主仓库提交新版本。

## 前置条件

- 源码仓库：`https://github.com/qsbye/wave-send-zulu`，分支 `ggwave-fountain-android`
- F-Droid metadata 模板：项目根 `fdroid/com.airscan.qr.yml`
- fastlane 元数据：`fastlane/metadata/android/en-US/`
- fdroiddata fork：`qsbye1/fdroiddata`（已 fork 自 `fdroid/fdroiddata`）
- GitLab PAT（`api` 权限域），通过用户输入提供，不持久化

## 流程

### 1. 源码侧准备新版本

```bash
cd /Users/workspace/Desktop/2026年10月/WaveSend/wave-send-zulu-air-fountain-android
```

**(a) 升级版本号** — 编辑 `app/build.gradle`：

```groovy
defaultConfig {
    applicationId 'com.airscan.qr'
    versionCode 27          // 必须递增整数
    versionName '26.10.18.1' // 任意字符串，建议与 tag 一致
}
```

**(b) 新增 fastlane changelog** — 文件名必须等于 `versionCode`：

```bash
echo "<本次版本变更说明（≤500 字符）>" > fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
```

**(c) 本地验证构建与单测**（macOS 用 JBR 17）：

```bash
export JAVA_HOME="/Users/workspace/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
gradle :app:testDebugUnitTest :app:assembleRelease --no-daemon
```

确认 release APK 可用 `apksigner` 验证签名（本地有 keystore 时）。

**(d) 提交并打 tag**：

```bash
git add -A
git commit -m "release: version <versionName> (versionCode <versionCode>)"
git tag -a <versionName> -m "Release <versionName>"
git push github ggwave-fountain-android
git push github <versionName>            # 远程已有同名 tag 时加 --force
```

> **关键**：F-Droid 的 `UpdateCheckMode: Tags` 依赖与 `versionName` 完全一致的 tag 名。tag 必须指向包含该版本 `build.gradle` 的提交。

### 2. 更新 fdroiddata metadata

编辑 `fdroid/com.airscan.qr.yml`：

- **首次收录**：`Builds:` 列表**只保留最新一个构建块**（官方要求 "Only the latest versions should be kept in the metadata before it's merged"，且不得包含 disabled 版本）
- **后续更新**：在 `Builds:` 列表追加新构建块，历史版本保持不动

**必备字段清单**（统计自 2026-10 近期合并的 6 个 New App MR，出现频率 6/6）：`Categories`、`License`、`AuthorName`（易漏！官方 checklist 硬性要求，可用网名）、`SourceCode`、`IssueTracker`、`AutoName`、`RepoType`、`Repo`、`Builds`、`AutoUpdateMode`、`UpdateCheckMode`、`CurrentVersion`、`CurrentVersionCode`。高频可选：`Changelog`（4/6，指向 releases 页）、`Binaries`+`AllowedAPKSigningKeys`（5/6，RB 已成主流）。

> **⚠️ Gradle Wrapper 必须存在**：F-Droid 的 `gradlew-fdroid` 读取 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 来从 gradle-transparency-log 选择 Gradle 版本。**没有 wrapper 文件会直接报 `No suitable gradle version found` 导致 build 失败**。本项目 AGP 8.7.3 要求 Gradle 8.10+，使用 8.11.1（在透明度日志中）。生成方式：`gradle wrapper --gradle-version 8.11.1 --distribution-type bin`，提交 `gradle/wrapper/gradle-wrapper.properties`、`gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat`。F-Droid 构建时会删除并重新验证 `gradle-wrapper.jar`（安全机制），属正常行为。

metadata **不写任何注释**（rewritemeta 会剥离，且中文注释不合社区惯例）；单条命令的 `prebuild`/`build` 写单行流式（`prebuild: sed ...`），这是 rewritemeta 的规范化输出。

```yaml
Builds:
  - versionName: <新版本名>
    versionCode: <新 versionCode>
    commit: <打 tag 的完整 40 位提交哈希>   # 官方要求 full hash，不是短哈希
    subdir: app
    gradle:
      - yes
    prebuild: sed -i -e '/include .:desktop./d' -e '/include .:web./d' ../settings.gradle
    scandelete:
      - <源码中需排除的 ZIP/JAR/二进制文件路径>
```

> **`scandelete` 必须处理所有非源码归档文件**：F-Droid scanner 会拒绝源码中的 `.zip`/`.jar`/`.apk` 等归档文件（可能隐藏预编译二进制或专有代码），报 `Found ZIP file archive at ...` 并使 build 失败。本项目当前需排除：`docs/AI-QR-Code-Decoder-main/qrcode.zip`（QR 测试图片集）、`poster/Skill2Poster-main.zip`（参考工具归档）。新增非源码归档时务必同步加入 `scandelete`。

同时更新末尾：

```yaml
CurrentVersion: <新版本名>
CurrentVersionCode: <新 versionCode>
```

> `prebuild` 必须保留：仓库 `settings.gradle` 含 `:desktop`/`:web` 模块，F-Droid 构建环境配置这些模块会因 Compose Multiplatform/Wasm 工具链失败，sed 移除以仅保留 `:app`。
>
> **`UpdateCheckMode: Tags` 的正则过滤**：本仓库存在历史 tag `v26.10.8.18-airfountain`（v 前缀），与现行纯数字 tag 格式混存。必须用 `UpdateCheckMode: Tags ^[0-9.]+$` 限定只匹配纯数字点分 tag，否则自动更新可能匹配到旧 tag。凡仓库有杂散历史 tag 的都需加正则（参考 Palimpsest 的 `Tags ^v[\d.]+$`）。

### 3. 推送到 fdroiddata 并提 MR

**(a) 浅克隆 fdroiddata**（如已有 `/tmp/fdroiddata` 可复用，先 `git fetch` + `git checkout master` + `git pull`）：

```bash
cd /tmp && rm -rf fdroiddata
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata
git checkout -b com.airscan.qr
```

**(b) 复制 metadata 并提交**：

```bash
cp <项目根>/fdroid/com.airscan.qr.yml metadata/com.airscan.qr.yml
git add metadata/com.airscan.qr.yml
git commit -m "update AirFountainZulu to <新版本名>"
```

> 若是**首次提交**用 `New App: AirFountainZulu`；后续版本更新用 `update AirFountainZulu to <versionName>`。

**(c) 用 GitLab PAT 推送并建 MR**：

```bash
export GITLAB_TOKEN="<用户提供的 PAT>"
git remote add fork "https://oauth2:${GITLAB_TOKEN}@gitlab.com/qsbye1/fdroiddata.git"
git push fork com.airscan.qr
# 建完 MR 后立即移除 fork remote（防止 token 留在 git config）
git remote remove fork
```

通过 API 创建 MR（**标题、描述必须全英文**）：

```bash
curl -s --request POST --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  --header "Content-Type: application/json" \
  --data '{
    "source_branch": "com.airscan.qr",
    "target_branch": "master",
    "target_project_id": 36528,
    "title": "update AirFountainZulu to <新版本名>",
    "description": "Update AirFountainZulu to <新版本名> (versionCode <新 versionCode>).\n\n### What the app does\n<英文功能简介>\n\n### FOSS compliance notes\n<英文 FOSS 合规说明>\n\n### Build metadata\n<英文构建元数据说明>"
  }' \
  "https://gitlab.com/api/v4/projects/87775010/merge_requests" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('web_url'))"
```

> `target_project_id: 36528` = `fdroid/fdroiddata`；`projects/87775010` = `qsbye1/fdroiddata` fork。

**(d) MR 描述与评论（按官方模板，全英文）**：

首次收录使用 fdroiddata 官方 **App inclusion 模板**（参考成功先例 [MR 52314](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/52314)，6 个 commit / 14 条 pipeline / 5 天合入）。模板结构：

```markdown
## Abstract

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

<一段式功能简介：做什么、用什么信道、要哪些权限。截图用 <img src="raw.githubusercontent 直链" width="200"> 内联并排 3-4 张>

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

<差异化动机：同类官方应用的问题（闭源/联网/账号），本应用的独特性，F-Droid 中无同类>

## Checklist

### Policy
* [x] The app complies with the [inclusion criteria](...).
* [x] The original app author has been notified (and does not oppose the inclusion). I am the author.
* [x] The upstream app source code repo contains the app metadata in a Fastlane folder structure（注明实际路径与内容清单）.

### Docs
* [x] Please read the guide first...
* [x] Please make sure your metadata follows the best practice in our templates.
* [x] Please read the Build Metadata Reference and make sure your metadata is valid.
* [x] Please read the Quick Start Guide.

### Merge Request Setup
* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected.
* [x] Please read our Git guide...（无冲突则不 rebase）
* [x] All related fdroiddata and RFP issues have been referenced（无则写 there are none）.
* [x] Please only submit one app in one MR.

### Metadata
* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file（注明 fdroid lint / rewritemeta 本地通过的 fdroidserver 版本）.
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images that should be provided upstream.
* [x] Releases are tagged and auto update is enabled（注明 tag 格式约定）.
* [x] There is an issue tracker and contact info of the author.
* [x] An AuthorName must be added（可以是网名）.
* [x] External repos are added as git submodules instead of srclibs（无则写 there are none）.
* [x] Setup abi split if the APK is large（不需要时给出理由：APK 大小、native 库占比）.
* [x] Only the latest versions should be kept in the metadata before it's merged.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash.

### Pipeline
* [ ] All pipelines should pass.   ← 新账户无法跑 CI 时保持未勾，按下述方式说明
* [x] All warnings and errors in the Reports tab should be fixed or explained（注明有几条 info notes，并承诺在评论中逐条解释）.
* [x] GitLab asks this new account for identity verification before it runs CI, so the fork's pipeline failed without starting any job. Could a maintainer please trigger the CI?
```

**要点**：每个 checkbox 后面不是纯打勾，而是附一句本项目针对性的证据/说明（52314 的每个勾选都带具体信息，如 fastlane 路径、APK 大小与 ABI 理由、tag 约定）。

后续版本更新 MR 描述可简化（"Update X to vY (versionCode Z)" + changelog 摘要），但 Pipeline 项的处理方式相同。

**(e) Pipeline 触发后主动解释 Reports tab**：

维护者触发 CI 后，第一时间在评论逐条解释 Reports tab 中的 info notes（即使无 warning/error）——权限用途（如 CAMERA/RECORD_AUDIO）、AndroidX 自动注入的权限、ABI/native 库大小与不做 abi split 的理由、R8/minify 标记等。52314 作者用一条结构化评论解释 6 条 info notes 后 MR 即进入 review-requested 队列。

### 失败案例（已关闭 MR 的常见死因，2026-10 抽样）

提交前对照自查。被关闭不等于终结——多数死因修复后可重新打开或重开 MR 并引用旧编号。

| 死因 | 案例 | 预防 |
| --- | --- | --- |
| **MR 描述未使用官方 App inclusion 模板** | !52810 XMR Rates：自定义格式描述，关闭留言 "there is no app inclusion template, please edit and include it then re-open MR"；!53108（本项目）同样因 "Merge Request template is not followed" 被关 | 描述必须照搬官方模板结构（Abstract 两问 + Checklist 五节）。**把模板放在评论里不算数，必须在 MR 描述正文**；自定义结构再完整也会被关 |
| **fdroiddata fork 不是 public** | !52985 Ash Launcher：一句话关闭 "Your fdroiddata repo is not public" | 建 fork 后立刻检查仓库可见性为 Public；MR 源分支不要设 protected |
| **应用成熟度/品类不适合** | !52481 Lichen：模板填写完美、CI 身份验证话术正确，仍被拒——"太不成熟、没有用户、F-Droid 已有太多同类 messager" | 同质化品类（聊天/笔记/启动器）有隐性门槛；小众工具+清晰差异化动机通过率更高。此类拒绝不可逆，不必纠缠 |

教训：**模板是入场券不是保险单，但不用模板是 24 小时内必关的速死原因**（52810 存活 1 天，53108 存活约 4 小时即被关闭）。被关后按关闭留言修复描述，重新打开 MR。

### 4. 本地 lint 验证 metadata（提交前必须）

```bash
python3 -m venv /tmp/fdroid_env && /tmp/fdroid_env/bin/pip install fdroidserver
mkdir -p /tmp/fdroidlint/metadata
cp fdroid/com.airscan.qr.yml /tmp/fdroidlint/metadata/com.airscan.qr.yml
cat > /tmp/fdroidlint/config.yml <<'EOF'
repo_url: https://example.com/fdroid/repo
repo_name: Test
EOF
cd /tmp/fdroidlint && /tmp/fdroid_env/bin/fdroid lint com.airscan.qr
```

> 类别 `Connectivity`/`System` 报 "not valid" 是误报（本地未加载 fdroiddata categories 配置），实际是有效类别。

### 5. 本地 buildserver 验证 build（强烈推荐，不依赖 GitLab CI）

GitLab 免费账户需身份验证才能跑 CI（见坑 9）。在请求维护者触发 CI 前，**必须**用 F-Droid 官方 buildserver 镜像本地验证完整 build 流程，提前发现 wrapper/scandelete 等问题。

```bash
# 准备工作区
WORK=/tmp/fdroid-local-build
rm -rf "$WORK" && mkdir -p "$WORK/metadata" "$WORK/build"
cp <项目根>/fdroid/com.airscan.qr.yml "$WORK/metadata/com.airscan.qr.yml"
# 拉取镜像（1.5GB+）
docker pull registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
# 运行完整 build（Apple Silicon 需 --platform linux/amd64，QEMU 模拟较慢）
docker run --rm --platform linux/amd64 \
  -v "$WORK:/workspace" -w /workspace \
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie \
  bash -c '
    export ANDROID_HOME=/opt/android-sdk
    export PATH=/opt/android-sdk/tools:/opt/android-sdk/platform-tools:$PATH
    export fdroidserver=/home/vagrant/fdroidserver
    rm -rf $fdroidserver
    git clone --shallow-since=2026-07-13 https://gitlab.com/fdroid/fdroidserver.git $fdroidserver
    git -C $fdroidserver config transfer.fsckObjects true
    git -C $fdroidserver checkout -B master a35fdfddd9c66823987a410566a6101186e39c84
    git -C $fdroidserver pull origin master --ff-only
    export PATH="$fdroidserver:$PATH"
    export PYTHONPATH="$fdroidserver:$fdroidserver/examples"
    export PYTHONUNBUFFERED=true serverwebroot=/tmp
    sdkmanager "platform-tools" "build-tools;31.0.0" "platforms;android-34"
    fdroid build --verbose --on-server --no-tarball com.airscan.qr:<versionCode>
  '
```

成功标志：日志出现 `BUILD SUCCESSFUL` 和 `INFO: Successfully built com.airscan.qr:<vc>`。产物在 `$WORK/unsigned/`。

> 注意：buildserver 镜像为 amd64，在 Apple Silicon 上通过 QEMU 运行，gradle 构建约需 6-10 分钟。

## 关键坑（实测）

### 扫描器（fdroid scanner + SUSS 签名库）

CI 中 scanner job 用 [fdroid-suss](https://gitlab.com/fdroid/fdroid-suss)（Suspicious or Unwanted Software Signatures，AGPL）签名库扫描源码与产物：

- `code_signatures`：匹配 APK 内的 smali 类路径（如 `com/google/firebase`），命中即 **error，pipeline 失败**
- `gradle_signatures`：匹配构建文件中的依赖坐标（如 `com.example:ads`）
- `warn_*_signatures`：同类匹配但只警告
- 每个签名条目带 `license` 字段（FOSS 用 SPDX 标识，专有用 `NonFree`）

应对：

1. **提交前本地预跑** `fdroid scanner <appId>`（与 lint 同一套 fdroidserver 环境），或直接对照 suss 签名库 grep 自己的 `build.gradle` 依赖坐标
2. 缓存/测试数据目录用 `scandelete` 排除（参考 Launch-R 排除 `.pub-cache`、Palimpsest 排除 `test-vectors`），防止 scanner 对下载缓存里的 jar 告警
3. 本项目历史上 ML Kit → ZXing 的替换正是为了躲开 scanner 对 `com/google/mlkit` 的 code_signatures 命中——**专有依赖必须在提交前换完，不要指望 scanner 误报**



1. **F-Droid 强制 FOSS 依赖**：本项目已用 ZXing（Apache-2.0）替换 Google ML Kit，并移除 `kotlinx-coroutines-play-services`；新增依赖前必须确认许可证（禁止 Firebase、GMS、Play Services、任何专有库）
2. **release 签名配置已做存在性判断**：`app/build.gradle` 中 `signingConfigs.release` 仅在 `../airscan-release.keystore` 存在时启用，F-Droid 环境产出未签名 APK 由 F-Droid 统一签名——**不要把 keystore 提交到仓库**
3. **tag 必须与 versionName 完全一致**：`UpdateCheckMode: Tags` 按 tag 名匹配，不一致会导致自动更新失败
4. **fork 贡献者无法在 upstream 加 `New App` 标签**：F-Droid 维护者评审时添加，不影响 MR 进入队列
5. **`sed` 转义**：`prebuild` 的 sed 命令中 `.:desktop.` 的点号用单引号包裹避免 shell 展开
6. **中文路径构建**：本地构建需用 `build-with-timestamp` skill 暂存到全英文路径，Kotlin 守护进程会把中文路径转义成乱码
7. **MR 全程英文**：标题、描述、评论必须全英文，维护者只看英文。首次收录用 App inclusion 模板，更新用 App update 模板
8. **yamllint `comments-indentation`**：`prebuild:` 下的注释必须与 `- sed` 列表项同级缩进，不能放在父级缩进，否则 `fdroid lint` 报 `comment not indented like content`
9. **GitLab 身份验证 → 主动请求维护者触发 CI**：免费账户未绑信用卡时 fork 的 CI pipeline 会 0 job 立即失败（`Identity verification is required in order to run CI jobs`），即使账户有 400 分钟 quota 也无法使用。**不要干等或反复重试**——成功先例 MR 52314 的做法：在 MR 描述 Pipeline 项直接写明原因并请求 "Could a maintainer please trigger the CI?"，维护者（linsui）响应很快，每次修复后再评论礼貌请求重新触发即可。**替代方案**：用本地 buildserver Docker 镜像验证 build（见流程第 5 步），不依赖 GitLab CI
10. **本地 lint 必跑**：提交前用 `fdroid lint com.airscan.qr` 验证 metadata（见流程第 4 步）。类别 "not valid" 是误报
11. **`rewritemeta` 折行因 ruamel.yaml 版本而异**：长字段（`Binaries`/`AllowedAPKSigningKeys`）的折行方式，本地新版 ruamel.yaml（0.19.1）与 CI 的 Debian 版（0.18.10）不同——以 CI job `fdroid rewritemeta` 的 diff 输出为准逐字节对齐，不要凭本地结果猜
12. **`.gitattributes` 强制 LF**：Windows 检出会把文本资产转成 CRLF，导致 CI 构建产物与本地不一致（52314 的首次 RB 失败根因是字体 license 文件行尾差异）。确保仓库根 `.gitattributes` 含 `* text=auto eol=lf`
13. **关闭 AGP 的 Dependency metadata 签名块**：AGP 默认向 APK 写入 Google Play 的 dependency 签名块，`check apk` job 会失败。在 `app/build.gradle` 的 `android {}` 内加：
    ```groovy
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    ```
14. **帮助 review 其他待审 MR 可加速自己的审核**：维护者明确表示 review 队列很长，参与 [reviewing new apps](https://gitlab.com/fdroid/wiki/-/wikis/Internal/Reviewing-new-apps) 能加快整体流转
15. **GitLab MR API 用 target project ID**：MR 存在于目标项目 `fdroid/fdroiddata`（ID `36528`），而非 fork（`87775010`）。发评论/查状态用 `https://gitlab.com/api/v4/projects/36528/merge_requests/<iid>/notes`，用 fork ID 会 404
16. **GitHub Release 必须与 yml commit 对齐**：F-Droid `UpdateCheckMode: Tags` 依赖 tag，GitHub Release 也应指向同一 tag/commit。若最新 Release 版本落后于 yml，需为当前 tag 创建 GitHub Release（`gh release create <tag> <apk> --title "..." --notes "..." --target <branch>`），并确认 tag 与 yml `commit` 完全一致
17. **aapt2 daemon 启动死锁**：本地 Gradle 构建偶尔出现多个 WorkerExecutor 线程卡在 `Aapt2DaemonImpl.startProcess`（0 CPU、无产出）。处理：杀 gradlew + aapt2 进程，`gradle --stop`，清理 `~/.gradle/caches/**/*.lock`，用 `--no-daemon` 重跑

## 进阶：可复现构建（Reproducible Builds）

维护者会要求添加 `Binaries` + `AllowedAPKSigningKeys` 启用 RB。F-Droid 直接分发作者签名的 APK（用户可在 GitHub/F-Droid 版本间互换无需重装）。

1. metadata 增加（顶层字段，不在 Builds 块内）：
   ```yaml
   Binaries: https://github.com/<owner>/<repo>/releases/download/%v/<app>-%v.apk
   AllowedAPKSigningKeys: <release 证书 SHA-256，小写无冒号>
   ```
   - `Binaries` URL 必须能用 `%v`（versionName）和/或 `%c`（versionCode）推导，文件名须与 GitHub Release 资产完全一致
   - `AllowedAPKSigningKeys` 获取：`apksigner verify --print-certs app.apk | grep SHA-256 | awk '{print $NF}' | tr -d ':' | tr 'A-Z' 'a-z'`
2. **GitHub Release 的 APK 必须用 Linux buildserver 环境构建**（不要用 macOS 构建的 APK）：
   - 用流程第 5 步的 Docker buildserver 构建，取 `unsigned/com.airscan.qr_<vc>.apk` 或 `build/<app>/app/build/outputs/apk/release/app-release-unsigned.apk`
   - 用 release keystore 签名后上传到 GitHub Release（`gh release upload <tag> <apk> --repo <owner>/<repo>`）
   - 原因：F-Droid CI 在 Linux 构建，macOS 构建的 APK 字节级不一致会导致 RB 失败
3. **本地验证**：用 CI 同一 commit 构建 release APK，与 GitHub Release 的签名 APK 执行 `apksigcopier compare local.apk released.apk`，通过后才在 MR 勾选 RB 项
4. 前置条件：坑 12（LF）、坑 13（关闭 dependenciesInfo）必须先修复，否则字节级比对必然失败

> 本项目已启用 RB：`Binaries` 指向 GitHub Release，`AllowedAPKSigningKeys` = `2fb54f5c...`。签名 keystore（`airscan-release.keystore`）务必备份，丢失则无法更新 RB 版本。

## F-Droid 合规清单（每次提交前自查）

- [ ] 源码仓库公开 + LICENSE 文件存在（MIT）
- [ ] 所有依赖均为 FOSS（无 mlkit / play-services / firebase）
- [ ] release 签名配置为 keystore 存在性判断（非硬编码必选）
- [ ] `gradle/wrapper/` 存在（`gradle-wrapper.properties` + `gradle-wrapper.jar` + `gradlew` + `gradlew.bat`），Gradle 版本匹配 AGP 且在 F-Droid 透明度日志中
- [ ] `fastlane/metadata/android/en-US/` 含 short_description（<80 字符无尾点）、full_description、icon.png、phoneScreenshots、changelogs/<versionCode>.txt
- [ ] tag 名 = versionName，指向正确提交；GitHub 最新 Release 与 yml commit 一致
- [ ] `fdroid/com.airscan.qr.yml` 的 Builds 块 commit 为完整 40 位哈希、versionName/versionCode 与 tag 一致；`scandelete` 覆盖所有非源码 ZIP/JAR；首次收录只保留最新一个 Builds 块
- [ ] `fdroid lint com.airscan.qr` 本地通过（类别 not valid 可忽略）；`fdroid rewritemeta` 无 diff
- [ ] **本地 buildserver Docker 验证 `fdroid build` 通过**（见流程第 5 步），出现 `BUILD SUCCESSFUL`
- [ ] `.gitattributes` 含 `* text=auto eol=lf`；`dependenciesInfo { includeInApk = false; includeInBundle = false }` 已配置
- [ ] MR 标题、描述、评论全英文，按官方 App inclusion 模板填写（每勾选附本项目具体证据）
- [ ] MR 描述 Pipeline 项已写明 GitLab 身份验证问题并请求维护者手动触发 CI
