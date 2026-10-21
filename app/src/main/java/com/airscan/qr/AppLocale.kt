package com.airscan.qr

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import java.util.TimeZone

/**
 * 界面语言由手机时区决定：UTC+7、+8、+9、+10 使用中文，其余时区使用英文。
 *
 * 以原始偏移（rawOffset，忽略夏令时）判定，避免同一时区在夏季被切换到英文。
 * 资源默认语言为英文（values/），中文放在 values-zh/。
 */
object AppLocale {

    fun isChineseTimeZone(): Boolean {
        val offsetHours = TimeZone.getDefault().rawOffset / 3_600_000
        return offsetHours in 7..10
    }

    fun languageTag(): String = if (isChineseTimeZone()) "zh" else "en"

    /**
     * 返回应用目标语言配置后的 Context。
     * Compose 通过 attachBaseContext 使用；ViewModel 使用它取字符串，
     * 避免 Application 资源仍按系统语言解析。
     */
    fun localized(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag()))
        return base.createConfigurationContext(config)
    }
}
