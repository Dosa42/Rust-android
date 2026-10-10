package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.proxy.ProxyForegroundService
import com.example.proxy.ProxyManager
import com.example.ui.ProxyDashboardScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val proxyManager = ProxyManager.getInstance(applicationContext)

        // Try starting the foreground service to ensure background daemon persistence
        try {
            ProxyForegroundService.start(this)
        } catch (_: Exception) {
            // Ignore if notification permission pending
        }

        setContent {
            MyApplicationTheme {
                ProxyDashboardScreen(manager = proxyManager)
            }
        }
    }
}
