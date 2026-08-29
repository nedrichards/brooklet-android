package com.nedrichards.brooklet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.net.URI
import com.nedrichards.brooklet.designsystem.BrookletInlineError
import com.nedrichards.brooklet.designsystem.BrookletSpacing
import com.nedrichards.brooklet.designsystem.BrookletContextIcon
import com.nedrichards.brooklet.designsystem.BrookletWidths

private val sharedUrlPattern = Regex("""https?://[^\s<>()]+""", RegexOption.IGNORE_CASE)

internal fun sharedHttpUrl(action: String?, mimeType: String?, text: CharSequence?): String? {
    if (action != "android.intent.action.SEND" || mimeType != "text/plain") return null
    val candidate = sharedUrlPattern.find(text?.toString().orEmpty())?.value
        ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        ?: return null
    return candidate.takeIf {
        runCatching {
            val parsed = URI(it)
            (parsed.scheme.equals("https", ignoreCase = true) || parsed.scheme.equals("http", ignoreCase = true)) &&
                !parsed.host.isNullOrBlank() && parsed.userInfo == null
        }.getOrDefault(false)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SubscribeScreen(application: BrookletApplication, accountId: Long, url: String, onDismiss: () -> Unit) {
    var working by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDismiss)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscribe") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.widthIn(max = BrookletWidths.form).fillMaxWidth().align(Alignment.Center)
                    .padding(horizontal = BrookletSpacing.screenComfortable),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrookletContextIcon(
                    icon = Icons.Rounded.RssFeed,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                Text("Subscribe to this feed?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Brooklet will ask Miniflux to find a feed for this URL, then update your inbox.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 20.dp),
                )
                error?.let { BrookletInlineError(it, Modifier.padding(top = 16.dp)) }
                Button(
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            error = null
                            runCatching { application.repository.subscribe(accountId, url) }
                                .onSuccess { onDismiss() }
                                .onFailure { error = it.message ?: "Could not subscribe to this URL" }
                            working = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(if (working) "Subscribing…" else "Subscribe")
                }
                OutlinedButton(
                    enabled = !working,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Not now") }
            }
        }
    }
}
