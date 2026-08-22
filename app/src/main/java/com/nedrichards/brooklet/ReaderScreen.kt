package com.nedrichards.brooklet

import android.content.Intent
import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.KarakeepRoute
import com.nedrichards.brooklet.network.ArticleImageClient
import com.nedrichards.brooklet.sync.EntryRepository
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val articleImages = ArticleImageClient()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(accountId: Long, entryId: Long, repository: EntryRepository, onBack: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    val entry by repository.entry(accountId, entryId).collectAsStateWithLifecycle(initialValue = null)
    val position by repository.position(accountId, entryId).collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val online = rememberOnlineState()
    var moreActionsOpen by remember(entryId) { mutableStateOf(false) }
    LaunchedEffect(entryId, entry?.read) { if (entry?.read == false) repository.markRead(accountId, entryId, true) }
    LaunchedEffect(entryId, position) { position?.let { listState.scrollToItem(it.firstVisibleBlock, it.offsetPx) } }
    DisposableEffect(entryId) { onDispose { scope.launch { repository.savePosition(accountId, entryId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) } } }

    val current = entry ?: return FullScreenProgress()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.feedTitle, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch { repository.markRead(accountId, entryId, false); onBack() } }) {
                        Icon(Icons.Rounded.MarkEmailUnread, "Keep unread")
                    }
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, current.url.toUri())) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser")
                    }
                    Box {
                        IconButton(onClick = { moreActionsOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "More article actions")
                        }
                        DropdownMenu(expanded = moreActionsOpen, onDismissRequest = { moreActionsOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (current.starred) "Remove from saved" else "Save") },
                                leadingIcon = { Icon(if (current.starred) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null) },
                                onClick = {
                                    scope.launch { repository.setStarred(accountId, entryId, !current.starred) }
                                    moreActionsOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Send to Karakeep") },
                                leadingIcon = { Icon(Icons.Rounded.CloudUpload, null) },
                                onClick = {
                                    scope.launch { repository.sendToKarakeep(current, KarakeepRoute.MINIFLUX) }
                                    moreActionsOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share URL") },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current.url), null))
                                    moreActionsOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.TopCenter), state = listState) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(current.title, style = MaterialTheme.typography.headlineSmall)
                    Text(listOfNotNull(current.author, current.feedTitle).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            current.blocks.forEach { block -> item { DocumentBlockView(block, current.url, online) } }
            item {
                HorizontalDivider(Modifier.padding(top = 20.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text(" Previous") }
                    FilledTonalButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("Next "); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
                }
            }
        }
        }
    }
}

@Composable
private fun DocumentBlockView(block: DocumentBlock, articleUrl: String, online: Boolean) {
    when (block) {
        is DocumentBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp))
        is DocumentBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp))
        is DocumentBlock.Quote -> Text(block.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
        is DocumentBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp))
        is DocumentBlock.ListItem -> Row(Modifier.padding(horizontal = 24.dp, vertical = 3.dp)) { Text(if (block.ordered) "1. " else "• ", fontWeight = FontWeight.Bold); Text(block.text, style = MaterialTheme.typography.bodyLarge) }
        is DocumentBlock.Image -> OnlineArticleImage(block, articleUrl, online)
    }
}

@Composable
private fun OnlineArticleImage(block: DocumentBlock.Image, articleUrl: String, online: Boolean) {
    val resolvedUrl = remember(block.url, articleUrl) { resolveImageUrl(articleUrl, block.url) }
    var bitmap by remember(resolvedUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(resolvedUrl) { mutableStateOf(false) }
    var failed by remember(resolvedUrl) { mutableStateOf(false) }

    LaunchedEffect(resolvedUrl, online) {
        if (resolvedUrl == null || !online || bitmap != null) return@LaunchedEffect
        loading = true
        failed = false
        bitmap = runCatching {
            val bytes = articleImages.load(resolvedUrl)
            withContext(Dispatchers.Default) { decodeSampledImage(bytes) }
        }.getOrNull()
        failed = bitmap == null
        loading = false
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = block.description,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                .aspectRatio((loaded.width.toFloat() / loaded.height.coerceAtLeast(1)).coerceAtLeast(0.7f))
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Image, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !online -> block.description ?: "Image available online"
                    failed -> block.description ?: "Image unavailable"
                    else -> block.description ?: "Loading image…"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun rememberOnlineState(): Boolean {
    val context = LocalContext.current
    return produceState(initialValue = context.isOnline(), context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = context.isOnline() }
            override fun onLost(network: Network) { value = context.isOnline() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { value = context.isOnline() }
        }
        manager.registerDefaultNetworkCallback(callback)
        try {
            awaitCancellation()
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }.value
}

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun resolveImageUrl(articleUrl: String, imageUrl: String): String? = runCatching {
    URI(articleUrl).resolve(imageUrl).toString().takeIf { it.startsWith("https://", ignoreCase = true) }
}.getOrNull()

private fun decodeSampledImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
