package com.airscan.zulu

import kotlin.JsFun

/**
 * 浏览器桥接：文件选择/下载/摄像头二维码检测。
 * JS 侧实现由构建注入 index.html（window.AFZ = {...}），
 * 全部走同步轮询模式（避免 Promise 互操作），由 Kotlin 侧协程定时拉取。
 *
 * 注意：wasm 的 @JsFun 只能修饰顶层 external 函数，
 * 因此这里先声明顶层 external，再经 [Js] 门面供 UI 调用。
 */
@JsFun("() => window.AFZ.startPick()")
external fun afzStartPick()

@JsFun("() => window.AFZ.takePicked()")
external fun afzTakePicked(): String?

@JsFun("() => window.AFZ.pickedName()")
external fun afzPickedName(): String

@JsFun("(b64, name) => window.AFZ.download(b64, name)")
external fun afzDownload(b64: String, name: String)

@JsFun("() => window.AFZ.startCam()")
external fun afzStartCam()

@JsFun("() => window.AFZ.stopCam()")
external fun afzStopCam()

@JsFun("() => window.AFZ.takeFrame()")
external fun afzTakeFrame(): String?

@JsFun("() => window.AFZ.camErr()")
external fun afzCamErr(): String?

object Js {
    /** 开始选择文件（异步，结果经 [takePickedFile] 轮询）。 */
    fun startPickFile() = afzStartPick()

    /** 取已选文件内容（base64）：null=仍在等待，""=用户取消，非空=数据。 */
    fun takePickedFile(): String? = afzTakePicked()

    /** 取已选文件名（一次性）。 */
    fun pickedFileName(): String = afzPickedName()

    /** 触发浏览器下载（base64 数据 + 文件名）。 */
    fun download(b64: String, name: String) = afzDownload(b64, name)

    /** 打开摄像头并启动 BarcodeDetector 检测循环。 */
    fun startCam() = afzStartCam()

    /** 关闭摄像头。 */
    fun stopCam() = afzStopCam()

    /** 取最近一次检出的二维码载荷（base64），取后清空；null=无新帧。 */
    fun takeFrame(): String? = afzTakeFrame()

    /** 取摄像头错误信息（一次性）；null=无。 */
    fun camError(): String? = afzCamErr()
}
