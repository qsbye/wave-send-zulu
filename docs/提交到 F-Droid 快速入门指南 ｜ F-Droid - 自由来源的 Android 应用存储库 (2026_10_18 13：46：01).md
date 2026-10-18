## 提交到 F-Droid 快速入门指南

这是为 F-Droid 打包应用的逐步指南。这显然是让一个应用进入主存储库的最佳方法，因为可以为应用的元数据提供直接的合并请求，便于维护者行事。

## 准备和合规检查

提议将应用上架 F-Droid 前，应仔细检查是否符合 [上架政策](https://f-droid.org/zh_Hans/docs/Inclusion_Policy) 。这里有一份简短的检查清单。

1. 应用应当有公开的源代码存储库和 FOSS 许可证文件。请确认
   存储库中有真实的最新源代码，而非无意义的某些虚设文件。
2. 应用应当只有 FOSS 依赖。Firebase 和 GMS 是不被接受的非 FOSS
   库的一些显著例子。如果没有这些非自由库应用能够（部分）工作，
   请创建没有这些库的版本。构建工具也应当是 FOSS 的。如果需要
   专有的 IDE，那么这样的应用无法上架。F-Droid 从命令行工具构建，
   构建教程帮助贡献者。
3. 已经通知了应用作者（且作者不反对上架）。如你并非作者，
   请在应用的代码存储库那里新开 Issue，请求作者的许可。
4. [描述的元数据文件](https://f-droid.org/zh_Hans/docs/All_About_Descriptions_Graphics_and_Screenshots)
   被添加到存储库中。这是个简单的结构，有一些文本文件和图片，
   在上架前应始终先添加它们。虽然文件夹结构遵循 Fastlane/Triple-T，
   但无需实际加工处理。

满足这些条件后，此应用就准备好上架了。

### 上游元数据

应用的上游 Git 存储库中的每个官方 release 提交都应该有一个标签。比如，如果应用的 AndroidManifest 包含
`versionName: '1.0'`，则该提交需要一个 `v1.0` 标签。我们强烈鼓励在应用的源码库中也添加元数据：

* *fastlane/metadata/android/en-US/short\_description.txt* （少于 80 个字符，无拖尾点）
* *fastlane/metadata/android/en-US/full\_description.txt*
* *fastlane/metadata/android/en-US/images/icon.png*
* *fastlane/metadata/android/en-US/images/phoneScreenshots/1.png*
* *fastlane/metadata/android/en-US/images/phoneScreenshots/2.png*

如 AndroidManifest 包含 `versionCode: 123`, 对于这个版本中的新变化应该有相应的解释：

* *fastlane/metadata/android/en-US/changelogs/123.txt* (最多 500 个字符)

浏览 [F-Droid
客户端元数据](https://gitlab.com/fdroid/fdroidclient/-/tree/master/metadata/en-US)
目录查看真实世界样例。

也支持其他格式和位置的
[描述、图像和截屏](https://f-droid.org/zh_Hans/docs/All_About_Descriptions_Graphics_and_Screenshots/)。比如，*metadata/en-US\_目录可以是
\_fastlane/metadata/android/en-US*。

如果存储库没有这些文件，则向应用的开发团队发送合并请求，如果没有版本标记，则新开一个问题。有了这些元数据，开发人员就可以直接控制它，它们的更新和未来的翻译将被自动拉取。

## 理解构建元数据

作为示例，我们来看看 F-Droid 官方客户端的构建元数据。文件位于
<https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.fdroid.fdroid.yml>。
fdroiddata 存储库在 `metadata` 文件夹中包含 F-Droid 主存储库的所有应用的
[构建元数据](https://f-droid.org/zh_Hans/docs/Build_Metadata_Reference)。[fdroidserver](https://gitlab.com/fdroid/fdroidserver)
用于处理这些元数据并运行构建。元数据的文件名，即 `org.fdroid.fdroid` 对应的是应用的 Application ID。

元数据的第一个部分完全是描述性的，包括 `AntiFeatures`、`Categories`、 `AuthorName`、`AuthorEmail`、
`AuthorWebSite`、`License`、 `WebSite`、
`SourceCode`、`IssueTracker`、`Translation`、 `Changelog`、 `Donate`、
`Liberapay`、 `OpenCollective`、 `Bitcoin` 和 `Litecoin`。这些信息展示在客户端和网站上。

第二部分控制如何构建 apk。它包含：

* `RepoType` 和 `Repo`，源代码 VCS 的元数据
* `Builds`，构建块列表，其中每一项对应一个已被构建或将被构建的 apk
* `Binaries` 和 `AllowedAPKSigningKeys`，可重复构建参考 binary 的元数据

当 `Builds` 中有新构建块时，*fdroidserver* 根据它启动一个构建。比如，下列构建块定义这样一个过程：

```
  - versionName: 1.23.2 versionCode: 1023052 commit:
    0ea06b1c1d68765c4a434d0b676b3e0f23f04cb1 subdir: app gradle:
      - full
    scandelete:
      - app/src/androidTest/assets
      - app/src/test/resources
      - libs/sharedTest/src/main/assets
      - libs/index/src/commonTest/resources
    gradleprops:
      - strict.release
```

首先，从 `Repo` 克隆源代码。然后 `commit` 被 checkout。工作目录设置为
`app`。构建前，扫描器扫描源代码，`scandelete` 中定义的路径内的二进制文件被删除。然后 *fdroidserver* 运行 Gradle
命令来构建应用：

```
  gradle assembleFullRelease -Pstrict.release
```

apk 在 Gradle 项目的默认路径中被找到并检查确保其有正确的 `versionName` 和 `versionCode`。最后，该 apk
将被签名并和其他 apk 一道发布。

最后一部分是有关我们应该如何更新应用，包括 `AutoUpdateMode`、 `UpdateCheckMode`、
`UpdateCheckIgnore`、 `VercodeOperation`、 `UpdateCheckName`、
`UpdateCheckData`、`CurrentVersion` 和 `CurrentVersionCode`。checkupdates
运行器每天运行，检查存储库并寻找新版本。如果有新版本，元数据将被更新并且开一个合并请求。

在 [构建元数据参考](https://f-droid.org/zh_Hans/docs/Build_Metadata_Reference) 和
[存储库样式指南](https://f-droid.org/zh_Hans/docs/Repository_Style_Guide) 中可以找到构建元数据每个字段的更多详细信息。

## 为 Gradle 应用写构建元数据

在以下步骤中，我们假设应用的 Application ID 为 `com.example`。请使用应用的唯一 id，对应你的域名。Fork
`fdroiddata`，克隆仓库并从 master 创建新分支，如
`com.example`。<https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md>
中有更多详情。 当你写元数据时也建议 [安装
fdroidserver](https://f-droid.org/zh_Hans/docs/Installing_the_Server_and_Repo_Tools)。它能帮你调整元数据格式并 lint
元数据。 在 `metadata/com.example.yml`
创建元数据文件。在编辑器中打开它并开始写元数据。你也可以从模板入手。由于存在几千个构建元数据文件，你一般可以找到能被用作自己的元数据的基础的相似的构建元数据。比如，如果你的应用使用
Flutter，你可以搜索 Flutter 应用 `fdroiddata`
仓库存储库。[模板](https://gitlab.com/fdroid/fdroiddata/-/tree/master/templates)
文件夹中也有例子。你也可以使用 `fdroid import` 生成 *fdroidserver* 的模板。

现在你有了空的元数据文件或简单的模板。请提供尽可能多的描述性信息以便用户可以更好地了解该应用和其作者。

然后在 `Builds` 中添加 `Repo` 信息和一个构建块。对于 Gradle 应用，这不复杂。我们的模板也涵盖了流行的工具链，如
Flutter。如果你的应用使用其他工具链构建，你在 *fdroiddata* 中找不到示例，你可以手动用 `sudo`、`prebuild` 和
`build` 定义所有构建步骤。必须设置 `output` 以便 *fdroidserver* 可以找到 apk。

## 自动更新配置

除非你有手动控制更新的特别理由，你应当设置自动更新。这降低 F-Droid 维护者和你本人的维护成本。开启自动更新后，你只需 bump 版本并 tag
新版本。F-Droid 会定期检查你的仓库并在找到新版本时更新元数据。对于 `versionName` 和 `versionCode` 在正常位置（即
`android` block 或 `AndroidManifest.xml`）的 Gradle 应用，无需特殊设置。
但如果你把版本信息放在别处，你需要用 `UpdateCheckData` 提取它们。版本信息无法被动态编辑或计算，因为 F-Droid
只使用正则表达式来提取它们，不会运行 Gradle 代码。

## 设置 ABI split

这不是设置 ABI split 的硬性要求，但如果 apk 不小，而 ABI split 可以有效减少大小的话，那么非常鼓励这样做。

目前没有对 ABI split 的特别支持。
因此每个 apk 应被添加为一个构建块，版本码和构建步骤各不相同。
你可以使用不同的
Gradle 变体或属性来构建不同的原生库。你也可以 patch
`prebuild` 中的代码来控制 ABI。
这些有不同 ABI 的 apk 会被逐个构建。
请注意不同 ABI 的版本码必须特别设置。
F-Droid 客户端始终更新应用到可以安装在设备上的版本码最高的 apk，
因此一般说来版本码的顺序应该是这样：armeabi-v7a < arm64-v8a < x86 < x86\_64。 这是因为 *fdroidserver*
只在 `repo` 中保留版本码最高的 apk，其他的会被移动到 `archive`，
新版本的版本码必须高于旧版本的版本码。
换句话说，代表 ABI 的数字必须被放在版本码的最低位置。
需要设置 `VercodeOperation` 来计算版本码，比如

```
VercodeOperation:
  - 10 * %c + 1
  - 10 * %c + 2
  - 10 * %c + 3
  - 10 * %c + 4
```

## 设置可重复构建

要在 F-Droid 上发布应用，可重复构建不是必需条件。但我们的确认使用可重复构建是最佳实践。不幸的是，因为 Android
不允许签名密钥不同的更新，切换到可重复构建不是那么容易。这意味着切换后用户必须重新安装应用。因此，我们主要鼓励新应用使用可重复构建。

可重复构建的好处是开发者的签名（来自所发布的
APK）确保我们的构建和开发者的相同（因而不包含任何不应包含的东西），与此同时我们的构建服务器验证开发者的构建与所发布的源代码相匹配（因此同样不包含任何不应包含的东西）。

这么做可增加信任度并加大供应链攻击难度。此外，也确保不会有只存在于 F-Droid 版本的故障（反过来也一样）。
使用开发者密钥还意味着开发者在我们出于某些原因（暂时）无法向用户提供更新时可选择自行向用户提供更新。

可重复构建对于某些应用，尤其是那些没有原生代码，仅使用 Kotlin/Java
语言的应用，非常容易。其他应用可能需要更多努力才能做到。令人难过的是，某些应用根本无法实现可重复构建。

我们希望开发者认同我们的观点，即鉴于各种好处，至少值得试着让应用变得可重复，但要是开发者无法或不愿意在这上面花时间/精力，我们当然尊重开发者的决定。

更多信息，请参阅：

* [向着可重复的 F-Droid
  前进](https://f-droid.org/zh_Hans/2023/01/15/towards-a-reproducible-fdroid.html)
* [F-Droid 的可重复构建文档](https://f-droid.org/zh_Hans/docs/Reproducible_Builds)
* [可重复构建项目](https://reproducible-builds.org/)
* [HOWTO: diff & fix APKs for Reproducible
  Builds](https://gitlab.com/fdroid/wiki/-/wikis/HOWTO%3A-diff-%26-fix-APKs-for-Reproducible-Builds)

## 测试元数据

初始的构建元数据可能不会按预期工作。我们需要测试它。
如果安装了 *fdroidserver*，运行 `fdroid lint <appid>` 来获取一些关于常规问题和 `fdroid rewritemeta <appid>` 的提示
来调整元数据文件的格式。然后我们可以试着用元数据构建 apk。
如果你想要试试不那么简单的方法，
请阅读关于如何在本地运行构建的 [文档](https://f-droid.org/zh_Hans/docs/Build_Server_Setup)。
你也可以使用我们的 CI 来轻松测试你的元数据。提交并推送你的更改到你的 fork。
pipelines 将被自动触发。如果所有 pipeline 均没有问题，那么祝贺你。
你现在有了可以正常工作的构建元数据了。
如果一些 pipeline 失败，那么请阅读日志并相应地修复元数据。

### 构建环境

2022 年 2 月，在装有 Ubuntu 21.10 系统的笔记本上，需要 2GB 流量和 5GB 磁盘空间来建立一个 F-Droid 构建环境。

网络要求：

* 60 MB: 浅克隆 *fdroiddata* 和 *fdroidserver*
* 75 MB：安装 *docker.io*
* 1000 MB：加载容器
* 800 MB: 构建

存储要求：

* 1000 MB: 克隆代码库并安装 *docker.io*
* 4000 MB: 加载容器和构建

下载并启动最新版本的服务器工具容器：

```
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
sudo sh -c 'apt-get update &&apt-get install -y docker.io'
sudo docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
```

在容器中：

```
. /etc/profile
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 > /dev/null | grep 'java.home' | awk -F'=' '{print $2}' | tr -d ' ')
cd /build
fdroid readmeta
fdroid rewritemeta com.example
fdroid checkupdates --allow-dirty com.example
fdroid lint com.example
fdroid build com.example
```

如任何命令，比如 `fdroid readmeta`，返回一个错误，相应编辑
*~/fdroiddata/metadata/com.example.yml* 并尝试再次运行命令。在成功的构建后，退出容器，使用 `New App`
标签提交元数据文件，并将它推送到你的 fork：

```
exit
cd ~/fdroiddata
git add metadata/com.example.yml
git commit -m "New App: com.example"
git push origin com.example
```

在 *fdroiddata* 存储库创建一个
[合并请求](https://gitlab.com/fdroid/fdroiddata/-/merge_requests)，选中你的
*com.example* 源码分支。等待打包人员接收你的合并请求。如果他们有任何问题，请跟踪，并尽快回复。

### 故障排除

你可以通过 IRC、Matrix、XMPP、电子邮件和其他渠道 [获取 F-Droid 的帮助](https://f-droid.org/zh_Hans/about/#contact)。

## 应用审核流程

提交包含提案后，应用将进入审核流程，F-Droid 工作人员将查看应用的源代码并确定它是否适合收录（如果不是，则确定所有必要的步骤）。

由于 F-Droid 是一个向用户承诺自由软件的软件存储库，审查过程是为了确保从 F-Droid 主存储库分发的所有应用都是自由软件。

这是一个非详尽的列表，列出了审核者会做什么：

* 他们将转到你的源代码存储库，并在许可文件（包括 *README*）中查找版权声明，以检查提议的应用是基于 [公认的自由软件和/或 OIS
  许可证](https://spdx.org/licenses/) 发布的。
* 他们会查看你的构建脚本，以了解你使用的构建系统，以及 F-Droid 构建服务器是否可以处理它（Ant 和 Gradle 是最常见和最简单的）。
* 他们将尝试下载你的源代码的副本。
* 他们会查看所有的源代码文件，以验证其许可证是否与相应的许可证/README文件一致。
* 他们将检查应用是否使用任何预编译的库或二进制 blob。
* 它们会查看你的非源代码文件来识别用于你的应用中的[非自由资源](https://monitor.f-droid.org/anti-feature/NonFreeAssets).
* 他们将浏览源代码，以查看你的应用是否使用非自由依赖项、显示广告、跟踪用户、推广或依赖非自由或不可更改的服务/应用，或执行任何对用户有害或不受欢迎的操作。
* 他们将列出你的应用中所有 [*负面特征*](https://monitor.f-droid.org/anti-features) 的摘要。
* 他们将尝试修补你的应用，以移除对第三方私有软件（若有）的使用。
* 他们将尝试为你的应用确定一个合适的更新过程（例如，通过查看你的版本与 *AndroidManifest.xml*
  、build.gradle.kts、 pubspec.yaml 或其他地方中的 VCS 标签和/或版本信息的关系）。
* 他们将尝试为你的应用编写合适的元数据文件，并将其添加到本地 F-Droid 构建服务器实例。 （`fdroid rewritemeta`,
  `fdroid lint` 用于确保元数据格式良好）
* 他们将尝试在隔离的环境中构建你的应用，以查看该过程是否成功并产生功能正常的 APK。
* 通常使用 GitLab CI，但对于需要的资源比运行器可提供的资源 *（存储、内存、时间）* 来得多的应用，它们可以使用本地机器来准备并测试元数据。
* 如果一切顺利，他们将向本地 *fdroiddata* git 存储库添加一个新的元数据文件，并将更改同步到 GitLab。

如果申请没有通过审核的某些步骤，将在发布提案的原始提交队列主题中给出反馈。

一旦 *fdroiddata* 存储库在 GitLab 上更新，F-Droid 的官方构建服务器将在主 F-Droid 存储库上
fetch、构建和发布你的应用通常只是时间问题。

你可以通过查看 [GitLab *fdroiddata*
修订历史](https://gitlab.com/fdroid/fdroiddata/commits/master) 来确认包含你的应用。

## 构建过程

将应用的元数据添加到 *fdroiddata* GitLab 存储库后，下一步是让主 F-Droid
构建服务器获取应用源代码和相关组件，构建应用，并将其发布到主 F-Droid 存储库上。

这个构建过程不是定期运行，上个周期发布后会启动新周期，应用是分批处理的 *（可通过查看
[以往周期](https://gitlab.com/fdroid/f-droid.org-transparency-log/-/commits/master)
推测平均频率）*。由于构建步骤在幕后执行，且多数是自动的；提交者只需等待完成即可。

一则某个应用成功构建过程的记录可以在该特定应用的 F-Droid 网站页面上找到 (如 [查看 F-Droid
客户端的构建日志](https://f-droid.org/zh_Hans/packages/org.fdroid.fdroid/#latest)）。

你可在 [F-Droid Monitor - Running](https://monitor.f-droid.org/builds/running)
页面查看本周期构建失败的应用的日志, 上一周期构建失败的应用的日志可在
[Build](https://monitor.f-droid.org/builds/build) 页面上找到。这有助于在构建意外失败时帮助诊断问题。

## 预期的情况

当你的应用元数据被批准并接受到 GitLab 上的 *fdroiddata* git 存储库时，**它不会立即出现在**主 F-Droid 存储库。

如果你的应用没有任何构建问题，那么从 *fdroiddata* 合并到应用出现在主存储库中，大约需要 **24 到 48
小时**。[1](https://f-droid.org/forums/topic/how-fast-the-main-f-droid-repository-updates/)
这个时间限制是由于构建过程的 APK 签名部分，这需要人为干预密钥库访问步骤。 [2](https://f-droid.org/zh_Hans/forums/topic/encouraging-f-droid-participation-by-developers/#post-17868)

发布后，应用马上在存储库中可用，任何客户端现在可以更新或安装它。 f-droid.org
网站需要时间为所有语言生成已更新应用和新应用的页面，因此在更新前有轻微延迟。

## 外部链接

* [GitLab上的F-Droid应用提交队列](https://gitlab.com/fdroid/rfp/issues) (用于新的提交)
* [论坛上的 F-Droid 应用提交队列](https://f-droid.org/zh_Hans/forums/forum/submission-queue/)
  (用于跟进旧的提交)
* [*fdroiddata* GitLab 存储库](https://gitlab.com/fdroid/fdroiddata/)
* [*fdroiddata* 修订历史](https://gitlab.com/fdroid/fdroiddata/commits/master)
