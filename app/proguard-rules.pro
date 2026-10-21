# 项目自定义 R8 规则。
# 采用 proguard-android-optimize.txt 作为基线，AndroidX / Compose / CameraX
# 均自带 consumer 规则；当前代码无反射、无 JNI，无需额外 keep。
# 如需排查线上崩溃，可保留行号信息：
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
