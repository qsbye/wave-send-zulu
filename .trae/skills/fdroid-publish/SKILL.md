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

编辑 `fdroid/com.airscan.qr.yml`，在 `Builds:` 列表**新增**一个构建块（保留历史版本）：

```yaml
Builds:
  # ... 历史版本保持不动 ...
  - versionName: <新版本名>
    versionCode: <新 versionCode>
    commit: <打 tag 的提交哈希前 7 位>
    subdir: app
    gradle:
      - yes
    prebuild:
      - sed -i -e '/include .:desktop./d' -e '/include .:web./d' ../settings.gradle
```

同时更新末尾：

```yaml
CurrentVersion: <新版本名>
CurrentVersionCode: <新 versionCode>
```

> `prebuild` 必须保留：仓库 `settings.gradle` 含 `:desktop`/`:web` 模块，F-Droid 构建环境配置这些模块会因 Compose Multiplatform/Wasm 工具链失败，sed 移除以仅保留 `:app`。

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
git commit -m "update com.airscan.qr to <新版本名>"
```

> 若是**首次提交**用 `New App: com.airscan.qr`；后续版本更新用 `update com.airscan.qr to <versionName>`。

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
    "title": "update com.airscan.qr to <新版本名>",
    "description": "Update AirFountainZulu to <新版本名> (versionCode <新 versionCode>).\n\n### What the app does\n<英文功能简介>\n\n### FOSS compliance notes\n<英文 FOSS 合规说明>\n\n### Build metadata\n<英文构建元数据说明>"
  }' \
  "https://gitlab.com/api/v4/projects/87775010/merge_requests" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('web_url'))"
```

> `target_project_id: 36528` = `fdroid/fdroiddata`；`projects/87775010` = `qsbye1/fdroiddata` fork。

**(d) 在 MR 中发英文评论（按 App inclusion / App update 模板）**：

首次收录用 **App inclusion template**，版本更新用 **App update template**。模板必须用英文贴到 MR 评论区：

```markdown
### The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy)

* [x] The app is released under a FOSS license.
* [x] All dependencies are FOSS.
* [x] No proprietary assets.
* [x] No non-free network services.
* [x] No tracking, advertising, or user-profiling.
* [x] The app does not promote or depend on non-free software.

### App summary
- Name, Categories, License, Source code, Application ID, Current version, Tag

### What the app does
<英文功能简介>

### FOSS compliance notes
<英文合规说明，如 ML Kit → ZXing>

### Build metadata
<subdir/prebuild/签名策略说明>

### Upstream metadata
<fastlane 位置说明>

### Other checklist
* [x] Source code publicly available
* [x] Author notified
* [x] Metadata follows style guide
* [x] Fastlane metadata provided upstream
* [x] Build verified locally
```

通过 API 发评论：

```bash
curl -s --request POST --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  --header "Content-Type: application/json" \
  --data "{\"body\": $(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$TEMPLATE")}" \
  "https://gitlab.com/api/v4/projects/36528/merge_requests/<mr_id>/notes"
```

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

## 关键坑（实测）

1. **F-Droid 强制 FOSS 依赖**：本项目已用 ZXing（Apache-2.0）替换 Google ML Kit，并移除 `kotlinx-coroutines-play-services`；新增依赖前必须确认许可证（禁止 Firebase、GMS、Play Services、任何专有库）
2. **release 签名配置已做存在性判断**：`app/build.gradle` 中 `signingConfigs.release` 仅在 `../airscan-release.keystore` 存在时启用，F-Droid 环境产出未签名 APK 由 F-Droid 统一签名——**不要把 keystore 提交到仓库**
3. **tag 必须与 versionName 完全一致**：`UpdateCheckMode: Tags` 按 tag 名匹配，不一致会导致自动更新失败
4. **fork 贡献者无法在 upstream 加 `New App` 标签**：F-Droid 维护者评审时添加，不影响 MR 进入队列
5. **`sed` 转义**：`prebuild` 的 sed 命令中 `.:desktop.` 的点号用单引号包裹避免 shell 展开
6. **中文路径构建**：本地构建需用 `build-with-timestamp` skill 暂存到全英文路径，Kotlin 守护进程会把中文路径转义成乱码
7. **MR 全程英文**：标题、描述、评论必须全英文，维护者只看英文。首次收录用 App inclusion 模板，更新用 App update 模板
8. **yamllint `comments-indentation`**：`prebuild:` 下的注释必须与 `- sed` 列表项同级缩进，不能放在父级缩进，否则 `fdroid lint` 报 `comment not indented like content`
9. **GitLab 身份验证**：免费账户未绑信用卡时 fork 的 CI pipeline 会 0 job 立即失败（`Identity verification is required in order to run CI jobs`），这不是 YAML 问题。可去 https://gitlab.com/-/profile/account 验证，或忽略——维护者会自己跑 lint/build
10. **本地 lint 必跑**：提交前用 `fdroid lint com.airscan.qr` 验证 metadata（见流程第 4 步）。类别 "not valid" 是误报

## F-Droid 合规清单（每次提交前自查）

- [ ] 源码仓库公开 + LICENSE 文件存在（MIT）
- [ ] 所有依赖均为 FOSS（无 mlkit / play-services / firebase）
- [ ] release 签名配置为 keystore 存在性判断（非硬编码必选）
- [ ] `fastlane/metadata/android/en-US/` 含 short_description（<80 字符无尾点）、full_description、icon.png、phoneScreenshots、changelogs/<versionCode>.txt
- [ ] tag 名 = versionName，指向正确提交
- [ ] `fdroid/com.airscan.qr.yml` 的 Builds 块 commit 哈希、versionName、versionCode 与 tag 一致
- [ ] `fdroid lint com.airscan.qr` 本地通过（类别 not valid 可忽略）
- [ ] MR 标题、描述、评论全英文，按 App inclusion / App update 模板填写
