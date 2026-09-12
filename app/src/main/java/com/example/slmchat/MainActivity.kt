package com.example.slmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.slmchat.ui.navigation.SlmNavGraph
import com.example.slmchat.ui.theme.SlmChatTheme

/**
 * Single-activity host. All UI is Jetpack Compose, navigated via
 * [SlmNavGraph] (chat <-> settings).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SlmChatTheme {
                SlmNavGraph()
            }
        }
    }
}
