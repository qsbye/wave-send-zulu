plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            // 直接复用 Android app 模块中的纯 Kotlin 喷泉码核心，保持收发两端逐位一致。
            kotlin.srcDir("../app/src/main/java/com/airscan/qr/core")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

                // ZXing：桌面端生成 + 识别二维码
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.google.zxing:javase:3.5.3")

                // 摄像头采集（接收端）
                implementation("com.github.sarxos:webcam-capture:0.3.12")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.airscan.zulu.MainKt"

        nativeDistributions {
            packageName = "AirFountainZulu"
            packageVersion = "26.10.8"

            windows {
                menuGroup = "AirFountainZulu"
                // 固定 GUID，升级安装时识别同一应用
                upgradeUuid = "7f3a9c52-1b8e-4d6a-9c21-5e8f0a4b3d77"
            }
        }
    }
}
