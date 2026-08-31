package com.nedrichards.brooklet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.database.AccountEntity
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.designsystem.BrookletInlineError
import com.nedrichards.brooklet.designsystem.BrookletSpacing
import com.nedrichards.brooklet.designsystem.BrookletContextIcon
import com.nedrichards.brooklet.designsystem.BrookletWidths
import com.nedrichards.brooklet.network.MinifluxClient
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(application: BrookletApplication) {
    // Debug-only build fields come from the ignored local.properties file.
    // Release builds receive no fields, so credentials are never packaged there.
    var server by remember { mutableStateOf(if (BuildConfig.DEBUG) BuildConfig.DEV_MINIFLUX_URL else "") }
    var token by remember { mutableStateOf(if (BuildConfig.DEBUG) BuildConfig.DEV_MINIFLUX_TOKEN else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tokenFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val canSubmit = !working && server.isNotBlank() && token.isNotBlank()
    val submit = {
        if (canSubmit) {
            focusManager.clearFocus()
            scope.launch {
                working = true
                error = null
                runCatching {
                    val client = MinifluxClient(server, token)
                    val identity = client.validate()
                    val cipher = TokenCipher()
                    val encrypted = cipher.encrypt(token)
                    application.database.dao().upsertAccount(AccountEntity(
                        serverUrl = server.trim().trimEnd('/'), username = identity.user.username,
                        tokenCiphertext = encrypted.ciphertext, tokenIv = encrypted.iv,
                        serverVersion = identity.version.version, createdAt = System.currentTimeMillis(),
                    ))
                    application.scheduler.ensurePeriodic()
                    application.scheduler.enqueueForegroundSync()
                }.onFailure { error = it.message ?: "Could not connect to Miniflux" }
                working = false
            }
        }
        Unit
    }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.widthIn(max = BrookletWidths.form).fillMaxWidth().align(Alignment.Center)
            .verticalScroll(rememberScrollState()).padding(horizontal = BrookletSpacing.screenComfortable, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BrookletContextIcon(
            icon = Icons.Rounded.WaterDrop,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(20.dp))
        Text("Brooklet", style = MaterialTheme.typography.headlineLarge)
        Text("Your Miniflux inbox, ready even when the network isn’t.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            server,
            { server = it },
            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                    tokenFocus.requestFocus()
                    true
                } else false
            },
            label = { Text("Miniflux HTTPS URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { tokenFocus.requestFocus() }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            token,
            { token = it },
            Modifier.fillMaxWidth().focusRequester(tokenFocus).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Enter && canSubmit) {
                    submit()
                    true
                } else false
            },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        error?.let { BrookletInlineError(it, Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
            onClick = submit,
        ) { Text(if (working) "Connecting…" else "Connect and sync") }
        if (working) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(
                "Checking your account…",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Requires Miniflux 2.3.2 or later. Your token is encrypted, stored locally and is never logged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}
