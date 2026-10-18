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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AirFountainZulu",
        resizable = false,
        // 4:3 窗口
        state = rememberWindowState(size = DpSize(1024.dp, 768.dp)),
    ) {
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
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> SenderTab()
                else -> ReceiverTab()
            }
        }
    }
}
