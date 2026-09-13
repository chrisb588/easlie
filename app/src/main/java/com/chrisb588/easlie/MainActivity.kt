package com.chrisb588.easlie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.chrisb588.easlie.canvas.FullScreenCanvas
import com.chrisb588.easlie.ui.theme.EaslieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EaslieTheme {
                FullScreenCanvas(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
