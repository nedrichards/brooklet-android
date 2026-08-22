package com.nedrichards.brooklet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nedrichards.brooklet.designsystem.BrookletTheme

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = sharedHttpUrl(intent.action, intent.type, intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        enableEdgeToEdge()
        setContent { BrookletTheme { BrookletApp(sharedUrl, onSharedUrlHandled = { sharedUrl = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = sharedHttpUrl(intent.action, intent.type, intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
    }
}
