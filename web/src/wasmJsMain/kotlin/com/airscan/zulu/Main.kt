package com.airscan.zulu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.CanvasBasedWindow

/**
 * Web（Kotlin/Wasm）入口。复用与 Android/桌面端完全相同的喷泉码核心，
 * 二维码编码使用纯 Kotlin 的 QrMatrix（字节模式 + ISO-8859-1 + 纠错级 L）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow("AirFountainZulu", canvasElementId = "ComposeTarget") {
        MaterialTheme {
            App()
        }
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("发送") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("接收") })
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (tab) {
                0 -> SenderTab()
                else -> ReceiverTab()
            }
        }
    }
}
