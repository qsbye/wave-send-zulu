plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compose Multiplatform Web（Kotlin/Wasm）目标。
// 直接复用 Android app 模块中的纯 Kotlin 喷泉码核心与 QrMatrix 编码器，
// 与 Android/桌面端逐位一致。
kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            kotlin.srcDir("../app/src/main/java/com/airscan/qr/core")
            // JVM 平台实现不参与 wasm 编译（wasm 侧使用 PlatformWasm）
            kotlin.exclude("**/PlatformJvm.kt")
            // 声波段协议依赖 JVM @Volatile 语义且 web 端无声波功能，不参与 wasm 编译
            kotlin.exclude("**/audio/**")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
    }
}
