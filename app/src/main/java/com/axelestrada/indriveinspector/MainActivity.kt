package com.axelestrada.indriveinspector

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.axelestrada.indriveinspector.core.Notifications
import com.axelestrada.indriveinspector.ui.MainScreen
import com.axelestrada.indriveinspector.ui.theme.InDriveInspectorTheme

class MainActivity : ComponentActivity() {
    var pendingNotificationStart by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationStart =
            intent?.action == Notifications.ACTION_START_FROM_NOTIFICATION

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            InDriveInspectorTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationStart =
            intent.action == Notifications.ACTION_START_FROM_NOTIFICATION
    }

    fun consumeNotificationStart() {
        pendingNotificationStart = false
        intent?.action = null
    }
}
