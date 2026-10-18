# How to Publish an Android App on F-Droid: Complete Step-by-Step Guide

> **作者**：Padmakar Garg（Android Developer | Kotlin | Jetpack Compose | Open Source）
> **来源**：[DEV Community](https://dev.to/gargpadmakar/how-to-publish-an-android-app-on-f-droid-complete-step-by-step-guide-3j2p) · 发布于 2026-09-30 · 标签：android / kotlin / opensource / fdroid

Publishing an **Android app on F-Droid** is an excellent way to distribute a free and open-source application to users who prefer an open-source Android ecosystem.

But publishing on F-Droid is different from simply uploading an APK. Your project needs publicly available source code, an appropriate FOSS license, compatible dependencies, build metadata, application metadata, and a build that F-Droid can reproduce from source.

In this **F-Droid submission guide**, I'll walk through the complete process from preparing an Android project to creating an F-Droid Merge Request.

## What You Will Learn

By the end of this guide, you will understand:

* How to prepare an Android app for F-Droid
* How to check open-source licenses and dependencies
* How to identify proprietary dependencies
* How to configure **F-Droid Fastlane metadata**
* How to prepare screenshots and app graphics
* How to create a GitHub release and tag
* How to fork the `fdroiddata` repository
* How to create `metadata/<applicationId>.yml`
* How F-Droid build metadata works
* How to create a GitLab Merge Request
* What to check when an F-Droid build fails

## F-Droid Publishing Workflow

The complete workflow can be summarized like this:

```text
Android App
    ↓
Public Source Code
    ↓
FOSS License
    ↓
Dependency & Policy Check
    ↓
Fastlane Metadata
    ↓
GitHub Release / Tag
    ↓
Fork fdroiddata
    ↓
Create YAML Metadata
    ↓
Validate Build
    ↓
GitLab Merge Request
    ↓
F-Droid Review
    ↓
Build & Publication
```

---

## 1. Prepare Your Android App

Start with a production-ready Android project.

Your source repository should contain the real, current source code rather than placeholder files.

A typical project might look like:

```text
MyAndroidApp/
├── app/
│   ├── src/
│   └── build.gradle.kts
├── fastlane/
│   └── metadata/
│       └── android/
│           └── en-US/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

Before starting the F-Droid submission process, verify:

```text
✓ Repository is public
✓ Source code is complete
✓ Application builds locally
✓ License is included
✓ Dependencies have been reviewed
✓ Application ID is stable
✓ Releases/tags are available
✓ No secrets are committed
```

F-Droid's Quick Start Guide recommends checking the Inclusion Policy before proposing an application. It also requires a public source repository and a FOSS license for apps submitted to the official repository.

---

## 2. Use a Stable Application ID

Your Android application should use a real, stable production `applicationId`.

Example:

```kotlin
android {
    defaultConfig {
        applicationId = "com.padmakargarg.myapp"
        minSdk = 26
        targetSdk = 36
    }
}
```

Avoid placeholder package names such as:

```text
com.example.app
com.example.myapplication
```

Use an application ID that you intend to keep stable.

### Important clarification

Do not assume that every `com.example.*` package is automatically rejected by F-Droid as a universal rule. The practical recommendation is to avoid placeholder IDs and use a unique, production-ready application ID.

---

## 3. Add an Open-Source License

F-Droid focuses on Free and Open Source Software.

Add a recognized open-source license to your repository.

Common licenses include:

```text
Apache-2.0
MIT
GPL-3.0
LGPL-3.0
```

The correct license depends on your project and its dependencies.

Also review the licenses of third-party libraries used by your Android application.

---

## 4. Check Your Android Dependencies

This is one of the most important parts of an **F-Droid submission**.

F-Droid's official documentation states that apps submitted to the main repository should use FOSS dependencies. It specifically mentions Firebase and Google Mobile Services as examples of non-FOSS libraries that are not accepted in the official repository.

For example, review your Gradle dependencies:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:...")
    implementation("androidx.compose.ui:ui:...")
    implementation("androidx.room:room-runtime:...")
}
```

Look carefully for:

```text
Google Mobile Services
Firebase
Closed-source SDKs
Proprietary analytics SDKs
Proprietary advertising SDKs
Closed-source build tools
```

If a proprietary dependency is optional, consider whether your application can provide an F-Droid-compatible flavor or implementation without it.

### Why this matters

F-Droid builds applications from source. Your project therefore needs a build path that works with the tools and dependencies acceptable under F-Droid's policies.

---

## 5. Set Up F-Droid Fastlane Metadata

F-Droid supports metadata from the upstream app repository, including the **Fastlane metadata structure**.

A common structure is:

```text
fastlane/
└── metadata/
    └── android/
        └── en-US/
            ├── title.txt
            ├── short_description.txt
            ├── full_description.txt
            ├── images/
            │   ├── icon.png
            │   └── phoneScreenshots/
            │       ├── 1.png
            │       ├── 2.png
            │       └── 3.png
            └── changelogs/
```

F-Droid's documentation supports the `fastlane/metadata/android/<locale>/` structure for localized descriptions, graphics, screenshots, and changelogs.

---

## 6. Create `title.txt`

Create `fastlane/metadata/android/en-US/title.txt`:

```text
My Open Source App
```

Keep the title short and consistent with your actual application name.

---

## 7. Create `short_description.txt`

Create `fastlane/metadata/android/en-US/short_description.txt`:

```text
A privacy-focused open-source Android application
```

Use this field to explain the app's primary purpose in one short sentence.

Avoid keyword stuffing.

Instead of:

```text
Android app, open source Android app, F-Droid Android app,
free Android app, privacy Android app...
```

write a natural description:

```text
A privacy-focused open-source Android application.
```

---

## 8. Create `full_description.txt`

Create `fastlane/metadata/android/en-US/full_description.txt`:

```text
My Open Source App is a free and open-source Android application.

Features:

* Simple and modern Android interface
* Privacy-focused design
* Open-source implementation
* Offline-friendly functionality
* Modern Android architecture

The complete source code is publicly available for review and contribution.
```

Keep the description useful to users rather than writing it only for search engines.

---

## 9. Add Screenshots and App Graphics

A good F-Droid listing should clearly show what your application does.

```text
fastlane/
└── metadata/
    └── android/
        └── en-US/
            ├── images/
            │   ├── icon.png
            │   └── phoneScreenshots/
            │       ├── 1.png
            │       ├── 2.png
            │       └── 3.png
            ├── title.txt
            ├── short_description.txt
            └── full_description.txt
```

Recommended screenshots:

1. Home screen
2. Main feature
3. Important workflow
4. Settings
5. A screen that demonstrates the app's primary value

Use real screenshots from your application.

F-Droid's graphics documentation describes supported screenshot directories such as `phoneScreenshots`, `sevenInchScreenshots`, `tenInchScreenshots`, `tvScreenshots`, and `wearScreenshots`.

---

## 10. Create a GitHub Repository

Push your Android project to a public Git repository.

```bash
git init
git add .
git commit -m "Initial open source release"
git branch -M main
git remote add origin https://github.com/USERNAME/REPOSITORY.git
git push -u origin main
```

Before continuing, open the repository in a browser and verify that the source code is publicly accessible.

---

## 11. Create a Release and Git Tag

F-Droid needs a specific source revision to build.

Create a release tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Your Android project may contain:

```kotlin
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.0.0"
    }
}
```

Make sure the release version and version code correspond to the source revision you submit to F-Droid.

---

## 12. Fork the F-Droid `fdroiddata` Repository

F-Droid maintains application build metadata in the [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata) repository.

Fork the repository to your GitLab account.

You will eventually add an application metadata file under `metadata/`, for example:

```text
metadata/com.padmakargarg.myapp.yml
```

---

## 13. Create the F-Droid YAML Metadata File

Create `metadata/com.padmakargarg.myapp.yml`.

A simplified example is:

```yaml
Categories:
  - Internet

License: Apache-2.0

AuthorName: Padmakar Garg

SourceCode: https://github.com/USERNAME/REPOSITORY
IssueTracker: https://github.com/USERNAME/REPOSITORY/issues

AutoName: My Open Source App

RepoType: git
Repo: https://github.com/USERNAME/REPOSITORY.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: FULL_COMMIT_HASH

AutoUpdateMode: Version
UpdateCheckMode: Tags

CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

### Important

This is an example, not a copy-paste universal template.

Your final metadata must follow the current F-Droid Build Metadata Reference and the requirements of your project.

Replace `USERNAME`, `REPOSITORY`, `FULL_COMMIT_HASH` with your real values.

---

## 14. Use the Full Commit Hash

The `commit` field identifies the source revision that F-Droid should build.

Find your commit hash with:

```bash
git rev-parse HEAD
```

Example:

```text
7c3f5a8c9d0e1234567890abcdef1234567890abcd
```

F-Droid's Build Metadata Reference recommends using the full commit hash for the build revision.

This is important because it gives the build process a precise source revision instead of depending on a moving branch.

---

## 15. Validate Your Android Build

Before creating your Merge Request, test the release build.

Linux/macOS:

```bash
./gradlew assembleRelease
```

Windows:

```bash
.\gradlew.bat assembleRelease
```

Also verify:

```text
✓ Build succeeds
✓ Version name is correct
✓ Version code is correct
✓ Dependencies are available
✓ No private files are required
✓ No API keys are required for the build
✓ No signing keys are committed
```

Never commit secrets such as:

```text
API keys
Passwords
Access tokens
Private certificates
Keystores
Signing keys
```

---

## 16. Create a GitLab Branch

Inside your fork of `fdroiddata`:

```bash
git checkout -b add-my-open-source-app
git add metadata/com.padmakargarg.myapp.yml
git commit -m "New App: My Open Source App"
git push origin add-my-open-source-app
```

---

## 17. Create a Merge Request

Open your fork on GitLab and create a **Merge Request** targeting the official F-Droid `fdroiddata` repository.

Your Merge Request should make it clear:

```text
Application Name
Application ID
Source Repository
Release Version
Build Information
Important Notes
```

F-Droid's official developer FAQ recommends making a Merge Request to `fdroiddata` as the quickest way to request inclusion in the official repository.

---

## 18. F-Droid Review and Build

After submitting your Merge Request, the metadata and build configuration can go through automated checks and maintainer review.

The process can include:

```text
Metadata validation
        ↓
Source verification
        ↓
Dependency checks
        ↓
Build checks
        ↓
Policy review
        ↓
Review feedback
        ↓
Merge
        ↓
F-Droid build
        ↓
Publication
```

If maintainers request changes, update your branch and push the changes:

```bash
git add .
git commit -m "Fix F-Droid metadata"
git push
```

Your existing Merge Request can then be updated with the new commit.

---

# Common F-Droid Submission Problems

## Problem 1: Proprietary Dependencies

Your application depends on a library or service that does not meet F-Droid's free-software requirements.

**What to do**: Review your dependency tree and determine whether the dependency can be removed or replaced. If the dependency is optional, consider an F-Droid-compatible build flavor where appropriate.

## Problem 2: Missing License

Your source repository does not clearly provide a FOSS license.

**What to do**: Add a recognized license and review the licensing of your dependencies.

## Problem 3: Version Code Mismatch

Your YAML metadata says `versionCode: 10` but the Android project produces `versionCode = 11`.

**What to do**: Make sure the F-Droid metadata matches the source revision being built.

## Problem 4: Wrong Commit

The YAML file points to a source revision that does not contain the expected release.

**What to do**: Check the exact commit with `git rev-parse HEAD`, then verify that the commit contains the intended release.

## Problem 5: Build Requires Private Configuration

Your project requires a local configuration file or private credential.

**What to do**: Make the build reproducible without exposing secrets. For example, avoid committing `google-services.json`, private API credentials, signing keys, passwords, access tokens when they contain sensitive production information.

---

# F-Droid Submission Checklist

Use this checklist before creating your Merge Request:

```text
☐ Public source repository
☐ FOSS license
☐ Stable application ID
☐ Dependencies reviewed
☐ Proprietary dependencies addressed
☐ Fastlane metadata added
☐ App icon added
☐ Screenshots added
☐ Changelog prepared if needed
☐ Git release/tag created
☐ versionName verified
☐ versionCode verified
☐ Full commit hash verified
☐ F-Droid YAML metadata created
☐ Release build tested
☐ No secrets committed
☐ GitLab branch created
☐ Merge Request created
```

---

# F-Droid Metadata Structure at a Glance

Your Android repository can contain:

```text
MyAndroidApp/
│
├── app/
│
├── fastlane/
│   └── metadata/
│       └── android/
│           └── en-US/
│               ├── title.txt
│               ├── short_description.txt
│               ├── full_description.txt
│               ├── images/
│               │   ├── icon.png
│               │   └── phoneScreenshots/
│               │       ├── 1.png
│               │       ├── 2.png
│               │       └── 3.png
│               └── changelogs/
│
├── LICENSE
├── README.md
├── build.gradle.kts
└── settings.gradle.kts
```

And in the F-Droid repository:

```text
fdroiddata/
│
└── metadata/
    └── com.padmakargarg.myapp.yml
```

---

# Frequently Asked Questions

**Can I publish any Android app on F-Droid?**

No. Apps submitted to the official F-Droid repository need to meet F-Droid's inclusion and free-software requirements. Check the current Inclusion Policy before submitting.

**Do I need GitHub?**

GitHub is commonly used for hosting the upstream source repository, but the important requirement is that F-Droid can access the source repository and build the application according to its metadata and policies.

**Do I need GitLab?**

For contributing metadata to the official `fdroiddata` repository, the submission workflow uses GitLab and Merge Requests.

**Does F-Droid build the APK from source?**

Yes. F-Droid's build system uses source code and build metadata to build applications rather than simply accepting an APK upload as the primary submission mechanism.

**Can I use Firebase?**

Firebase components can create compatibility issues with the official F-Droid repository because F-Droid requires FOSS dependencies. Check the current F-Droid policy and determine whether your application can work without the affected components.

**What is `fdroiddata`?**

`fdroiddata` is the repository containing F-Droid's application metadata and build configuration. Each application can have a corresponding YAML metadata file under `metadata/<ApplicationID>.yml`.

**What is the purpose of `Builds` in the YAML file?**

The `Builds` section tells F-Droid how a particular application version should be built from source. A basic build entry contains values such as:

```yaml
Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: FULL_COMMIT_HASH
```

---

# Official F-Droid Resources

For the latest requirements, always check the official F-Droid documentation because policies and tooling can change.

- [F-Droid Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [F-Droid Descriptions, Graphics & Screenshots](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [F-Droid Developer FAQ](https://f-droid.org/en/docs/FAQ_-_App_Developers/)
- [F-Droid Data Repository](https://gitlab.com/fdroid/fdroiddata)

---

# Conclusion

Publishing an Android application on F-Droid is a source-first process.

The most important things to prepare are:

1. A **public Android source repository**
2. A compatible **FOSS license**
3. A dependency tree that meets F-Droid requirements
4. Proper **Fastlane metadata**
5. App screenshots and graphics
6. A versioned source release
7. A correct `fdroiddata` YAML file
8. A reproducible build
9. A GitLab Merge Request

Once you understand these pieces, the **F-Droid submission process** becomes much easier to follow.

If you are an Android developer working with **Kotlin, Jetpack Compose, or modern Android architecture**, this workflow is also a useful introduction to source-based app distribution and reproducible builds.

---

## 评论区（Top comments · 2）

**Cyrille37**（2026-09-30）：

> Hi and thanks for this clear roadmap. What's about f-droid build for different tech like Flutter? Any reference links?

**Padmakar Garg**（作者，2026-10-07）：

> Yes, F-Droid can also be used for Flutter-based Android apps. The main process is similar, but the build configuration and dependencies need some Flutter-specific adjustments. For reference, you can check the official F-Droid documentation here: <https://f-droid.org/docs/>
