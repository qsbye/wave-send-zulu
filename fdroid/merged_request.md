# MR 53108 — New App: AirFountainZulu

本文件存放 F-Droid 收录 MR（[!53108](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53108)）的英文描述与评论文本，按 fdroiddata 官方 App inclusion 模板填写（模板结构参考成功先例 [!52314](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/52314)）。

---

## MR Description

```markdown
## Abstract

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

AirFountainZulu transfers files across an air gap: the sender's screen shows a stream of fountain-coded QR codes (10–30 fps), the receiver's camera picks them up, and a Luby Transform fountain code reassembles the file. A ggwave-style audio channel (speaker → microphone, multi-tone FSK with RS(64,24) per segment) runs as a complementary fallback — frames lost on one channel are filled in by the other. No Wi-Fi, no Bluetooth, no USB, no network. The only permissions are CAMERA and RECORD_AUDIO; the app has no INTERNET permission at all.

On completion the file is SHA-256-verified and saved automatically to `Download/AirFountainZulu/` (no storage permission needed). The receiver can disable the camera and receive over sound alone — transfers even work through a wall. A lock-on overlay tracks the QR code with a four-corner affine-following dashed frame, and a three-stage decode chain (fast path → half-res locate & crop → Sauvola adaptive threshold) keeps screen capture robust against glare, moiré and uneven lighting. The sender side also runs on Windows and as a single-file Web page, bit-compatible with the Android receiver.

<img src="https://raw.githubusercontent.com/qsbye/wave-send-zulu/ggwave-fountain-android/screenshots/android-main.png" width="200" alt="Receiver"> <img src="https://raw.githubusercontent.com/qsbye/wave-send-zulu/ggwave-fountain-android/screenshots/android-qr-lock.png" width="200" alt="QR lock-on"> <img src="https://raw.githubusercontent.com/qsbye/wave-send-zulu/ggwave-fountain-android/screenshots/android-demo.gif" width="200" alt="Sending demo"> <img src="https://raw.githubusercontent.com/qsbye/wave-send-zulu/ggwave-fountain-android/screenshots/android-receiver-live.gif" width="200" alt="Receiving live">

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

There is already [CimBar Scanner](https://f-droid.org/packages/com.nfcarchiver.cimbar/) in F-Droid, an excellent screen-to-camera file transfer app using its own colored-tile matrix format. AirFountainZulu takes a different approach and complements it rather than replacing it:

- **Standard QR codes as the optical carrier**, decoded with ZXing. No proprietary color-tile format — any camera and any QR decoder can reason about the frames, and the fountain code layer tolerates dropped frames just like CimBar's repair frames.
- **A second, independent acoustic channel** (speaker → microphone, pure-Kotlin ggwave-style multi-tone FSK) runs in parallel with the QR stream. Frames lost on one channel are filled in by the other, so transfer continues when the camera is briefly obstructed, and the receiver can disable the camera and receive over sound alone — including [through a wall](https://github.com/qsbye/wave-send-zulu/blob/ggwave-fountain-android/README.md). CimBar is camera-only.
- **Three send-side implementations sharing the codec core**: the Android receiver, a .NET 8 WinForms desktop tool, and a single-file Web page (double-click, no server). All speak the same decimen-v3-compatible wire format, so a Windows machine can send to an Android phone, or a browser tab to a phone, with no installs on the sending side.

As far as I know no app in F-Droid combines a standard-QR optical channel with an acoustic fallback and a shared cross-platform codec; the existing optical-transfer apps are either single-channel camera apps or use a proprietary barcode format.

## Checklist

### Policy

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy).
* [x] The original app author has been notified (and does not oppose the inclusion). I am the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) folder structure (`fastlane/metadata/android/en-US`: title, short_description, full_description, icon, phone screenshots, per-version changelogs).

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request (there are none).
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file (`fdroid lint` passes locally with fdroidserver 2.4.5, and `fdroid rewritemeta` produces no changes).
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo.
* [x] Releases are tagged and auto update is enabled. Tags are plain dotted numbers identical to `versionName` (e.g. `26.10.18.1704`); `UpdateCheckMode: Tags ^[0-9.]+$` excludes one legacy v-prefixed tag from the repo's early history.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [x] External repos are added as git submodules instead of srclibs (there are none).
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds) — not enabled for now: the release signing config activates only when a local keystore exists, so the F-Droid build produces an unsigned APK to be signed by F-Droid. Happy to switch to RB (Binaries + AllowedAPKSigningKeys) if preferred.
* [x] Setup abi split if the APK is large and the splitted ones can be much smaller (not needed: the APK is ~12 MB and contains no native code — the ZXing decoder is pure Java).
* [x] Only the latest versions should be kept in the metadata before it's merged.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash.

### Pipeline

* [ ] All pipelines should pass. GitLab asks this new account for identity verification before it runs CI, so the fork's pipeline failed without starting any job. Could a maintainer please trigger the CI?
* [x] All warnings and errors in the Reports tab should be fixed or explained (to be done in a comment once CI runs; none expected beyond the usual info notes — the manifest declares only CAMERA and RECORD_AUDIO).

Checked locally before submitting: a fresh clone builds `:app:assembleRelease` from a clean Gradle home, and `fdroid lint` passes (the "Categories not valid" notes seen locally are an artifact of running lint outside a full fdroiddata checkout — both `Connectivity` and `System` are valid F-Droid categories).
```

---

## Pipeline 触发后的评论（CI 运行后按实际情况改写发出）

```markdown
Thanks for triggering the pipeline!

Notes on the Reports tab:

- **Permissions**: the app declares exactly CAMERA (scanning the sender's QR stream) and RECORD_AUDIO (the acoustic fallback channel). There is no INTERNET permission; the app is fully offline by design.
- **Signing**: `app/build.gradle` enables the release signing config only when a local keystore file exists, so the CI build produces an unsigned APK as intended for F-Droid signing.
- **Prebuild step**: the repository also contains Compose Multiplatform `:desktop`/`:web` modules in its root `settings.gradle`; the `prebuild` sed removes them so only `:app` is configured, avoiding the Wasm/desktop toolchain in the build container.
- **Version bump**: this MR now builds the latest release `26.10.18.1704` (versionCode 27, commit `83a23e9…`), superseding the version the MR was first opened with.

Let me know if anything else needs changing.
```

---

## 使用方式

1. 更新 MR 描述：用上面 "MR Description" 代码块内容（不含外层 ```markdown 围栏）通过 GitLab API 或网页编辑替换现有描述
2. 推送更新后的 `fdroid/com.airscan.qr.yml` 到 fork 分支 `com.airscan.qr`（commit message: `New App: AirFountainZulu`，内容已含 vc27/完整哈希/AuthorName/Tags 正则）
3. CI 由维护者触发后，按实际 Reports 内容改写第二条评论发出

## 事实来源（撰写依据，勿改动事实）

- 权限：`app/src/main/AndroidManifest.xml` 仅 CAMERA + RECORD_AUDIO
- 版本：tag `26.10.18.1704` = versionCode 27 = commit `83a23e9110092b4d4cf591c91e5c2ecb5356f91d`
- 截图：仓库 `screenshots/` 下 4 张已入库图片（分支 `ggwave-fountain-android`）
- 单测：72 项（`:app:testDebugUnitTest`）
- 合规：MIT；ZXing（Apache-2.0）替代 ML Kit；无 GMS/Firebase/INTERNET
