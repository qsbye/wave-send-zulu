package com.airscan.qr

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airscan.qr.ui.AirScanTheme
import com.airscan.qr.ui.ReceiverScreen
import com.airscan.qr.ui.SenderScreen
import com.airscan.qr.ui.SenderViewModel

private enum class Tab(val label: String) {
    Sender("发送"),
    Receiver("接收"),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirScanTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.Sender) }
    // Sender VM 提升到这里，便于根据发送状态保持屏幕常亮。
    val senderVm: SenderViewModel = viewModel()
    val senderState by senderVm.state.collectAsStateWithLifecycle()

    val activity = LocalContext.current as Activity
    DisposableEffect(tab, senderState.sending) {
        val window = activity.window
        if (tab == Tab.Sender && senderState.sending) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Sender,
                    onClick = { tab = Tab.Sender },
                    icon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                    label = { Text(Tab.Sender.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Receiver,
                    onClick = { tab = Tab.Receiver },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                    label = { Text(Tab.Receiver.label) },
                )
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when (tab) {
                Tab.Sender -> SenderScreen(senderVm)
                Tab.Receiver -> ReceiverScreen()
            }
        }
    }
}
