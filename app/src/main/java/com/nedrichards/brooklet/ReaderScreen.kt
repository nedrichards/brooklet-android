package com.nedrichards.brooklet

import android.content.Intent
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val articleImages by lazy { ArticleImageClient() }
private val articleImageCache by lazy {
    val maxMemoryKiB = (Runtime.getRuntime().maxMemory() / 1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val cacheSizeKiB = (maxMemoryKiB / 16).coerceIn(8 * 1024, 32 * 1024)
    object : LruCache<String, ImageBitmap>(cacheSizeKiB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height * 4) / 1024).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    accountId: Long,
    entryId: Long,
    repository: EntryRepository,
    savePosition: (block: Int, offset: Int) -> Unit,
    onBack: () -> Unit,
    onKeptUnread: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val entry by repository.entry(accountId, entryId).collectAsStateWithLifecycle(initialValue = null)
    val position by repository.position(accountId, entryId).collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val online = rememberOnlineState()
    MarkEntryReadOnOpen(entryId, entry?.id, entry?.read) { repository.markRead(accountId, entryId, true) }
    LaunchedEffect(entryId, position?.entryId, position?.updatedAt) {
        val saved = position?.takeIf { it.entryId == entryId }
        listState.scrollToItem(saved?.firstVisibleBlock ?: 0, saved?.offsetPx ?: 0)
    }
    DisposableEffect(entryId) {
        onDispose {
            savePosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    val current = entry ?: return FullScreenProgress()
    Scaffold(
        topBar = {
            ReaderTopAppBar(
                entry = current,
                onBack = onBack,
                onKeepUnread = {
                    scope.launch {
                        repository.markRead(accountId, entryId, false)
                        onKeptUnread()
                    }
                },
                onOpenInBrowser = { context.startActivity(Intent(Intent.ACTION_VIEW, current.url.toUri())) },
                onSave = { scope.launch { repository.setStarred(accountId, entryId, !current.starred) } },
                onSendToKarakeep = { scope.launch { repository.sendToKarakeep(current, KarakeepRoute.MINIFLUX) } },
                onShareUrl = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current.url), null)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.TopCenter).testTag("reader-content"),
            state = listState,
        ) {
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

/**
 * Mark an unread entry once when it first becomes available to the reader.
 *
 * The live read value must not be a key: "Keep unread" changes it back to
 * false, and keying the effect to that value would immediately mark it read
 * again before the reader leaves composition.
 */
@Composable
internal fun MarkEntryReadOnOpen(
    entryId: Long,
    loadedEntryId: Long?,
    read: Boolean?,
    markRead: suspend () -> Unit,
) {
    LaunchedEffect(entryId, loadedEntryId) {
        if (loadedEntryId == entryId && read == false) markRead()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopAppBar(
    entry: com.nedrichards.brooklet.model.Entry,
    onBack: () -> Unit,
    onKeepUnread: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onSave: () -> Unit,
    onSendToKarakeep: () -> Unit,
    onShareUrl: () -> Unit,
) {
    var moreActionsOpen by remember(entry.id) { mutableStateOf(false) }
    TopAppBar(
        title = { Text(entry.feedTitle, maxLines = 1) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = {
            IconButton(onClick = onKeepUnread) { Icon(Icons.Rounded.MarkEmailUnread, "Keep unread") }
            IconButton(onClick = onOpenInBrowser) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") }
            Box {
                IconButton(onClick = { moreActionsOpen = true }) { Icon(Icons.Rounded.MoreVert, "More article actions") }
                DropdownMenu(expanded = moreActionsOpen, onDismissRequest = { moreActionsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (entry.starred) "Remove from saved" else "Save") },
                        leadingIcon = { Icon(if (entry.starred) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null) },
                        onClick = { onSave(); moreActionsOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Send to Karakeep") },
                        leadingIcon = { Icon(Icons.Rounded.CloudUpload, null) },
                        onClick = { onSendToKarakeep(); moreActionsOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Share URL") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { onShareUrl(); moreActionsOpen = false },
                    )
                }
            }
        },
    )
}

@Composable
private fun DocumentBlockView(block: DocumentBlock, articleUrl: String, online: Boolean) {
    when (block) {
        is DocumentBlock.Heading -> RichArticleText(block.html, block.text, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp), if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        is DocumentBlock.Paragraph -> RichArticleText(block.html, block.text, Modifier.padding(horizontal = 20.dp, vertical = 7.dp), MaterialTheme.typography.bodyLarge)
        is DocumentBlock.Quote -> RichArticleText(block.html, block.text, Modifier.padding(horizontal = 32.dp, vertical = 12.dp), MaterialTheme.typography.bodyLarge)
        is DocumentBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp))
        is DocumentBlock.ListItem -> Row(Modifier.padding(horizontal = 24.dp, vertical = 3.dp)) { Text(if (block.ordered) "1. " else "• ", fontWeight = FontWeight.Bold); RichArticleText(block.html, block.text, Modifier.weight(1f), MaterialTheme.typography.bodyLarge) }
        is DocumentBlock.Caption -> RichArticleText(block.html, block.text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), MaterialTheme.typography.labelMedium)
        is DocumentBlock.Table -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { block.rows.forEach { row -> Text(row.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) } }
        is DocumentBlock.Image -> OnlineArticleImage(block, articleUrl, online)
    }
}

@Composable
internal fun RichArticleText(html: String?, fallback: String, modifier: Modifier, style: androidx.compose.ui.text.TextStyle) {
    if (html == null) {
        Text(fallback, style = style, modifier = modifier)
    } else {
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val linkColor = MaterialTheme.colorScheme.primary.toArgb()
        val rendered = remember(html) { themeSafeArticleText(html) }
        AndroidView(
            modifier = modifier,
            factory = { context -> TextView(context).apply { movementMethod = LinkMovementMethod.getInstance() } },
            update = { view ->
                configureRichArticleText(view, html, rendered, style.fontSize.value, textColor, linkColor)
            },
        )
    }
}

internal fun configureRichArticleText(
    view: TextView,
    html: String,
    textSizeSp: Float,
    textColor: Int,
    linkColor: Int,
) {
    configureRichArticleText(view, html, themeSafeArticleText(html), textSizeSp, textColor, linkColor)
}

private fun configureRichArticleText(
    view: TextView,
    html: String,
    rendered: SpannableStringBuilder,
    textSizeSp: Float,
    textColor: Int,
    linkColor: Int,
) {
    if (view.tag != html) {
        view.text = rendered
        view.tag = html
    }
    view.textSize = textSizeSp
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    view.setBackgroundColor(Color.TRANSPARENT)
}

/**
 * Retains only the inline structure Brooklet intentionally supports. Feed HTML
 * is not allowed to choose foreground/background colours because those values
 * commonly assume a white page and become unreadable in the app's dark theme.
 */
internal fun themeSafeArticleText(html: String): SpannableStringBuilder {
    val rendered = SpannableStringBuilder(
        HtmlCompat.fromHtml(sanitiseInlineArticleHtml(html), HtmlCompat.FROM_HTML_MODE_LEGACY),
    )
    rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java).forEach(rendered::removeSpan)
    rendered.getSpans(0, rendered.length, BackgroundColorSpan::class.java).forEach(rendered::removeSpan)
    return rendered
}

internal fun sanitiseInlineArticleHtml(html: String): String {
    val withoutActiveContent = html
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), "")
        .replace(Regex("(?is)<!--.*?-->"), "")
    return Regex("(?is)<(/?)([a-z][a-z0-9]*)(?:\\s[^>]*)?/?>").replace(withoutActiveContent) { match ->
        val closing = match.groupValues[1] == "/"
        when (val name = match.groupValues[2].lowercase()) {
            "strong", "b", "em", "i" -> if (closing) "</$name>" else "<$name>"
            "code" -> if (closing) "</tt>" else "<tt>"
            "br" -> if (closing) "" else "<br>"
            "a" -> when {
                closing -> "</a>"
                else -> absoluteWebHref(match.value)?.let { "<a href=\"$it\">" } ?: "<a>"
            }
            else -> ""
        }
    }
}

private fun absoluteWebHref(tag: String): String? {
    val href = Regex("(?is)\\bhref\\s*=\\s*(['\"])(.*?)\\1").find(tag)?.groupValues?.get(2) ?: return null
    return href.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        ?.replace("\"", "&quot;")
}

@Composable
private fun OnlineArticleImage(block: DocumentBlock.Image, articleUrl: String, online: Boolean) {
    val resolvedUrl = remember(block.url, articleUrl) { resolveImageUrl(articleUrl, block.url) }
    var bitmap by remember(resolvedUrl) { mutableStateOf(resolvedUrl?.let(articleImageCache::get)) }
    var loading by remember(resolvedUrl) { mutableStateOf(false) }
    var failed by remember(resolvedUrl) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("article-image")) {
    val density = LocalDensity.current
    val targetSizePx = remember(maxWidth, density) {
        with(density) { maxWidth.roundToPx() }.coerceIn(720, 1600)
    }

    LaunchedEffect(resolvedUrl, online, targetSizePx) {
        if (resolvedUrl == null || !online || bitmap != null) return@LaunchedEffect
        loading = true
        failed = false
        bitmap = try {
            val bytes = articleImages.load(resolvedUrl)
            withContext(Dispatchers.Default) { decodeSampledImage(bytes, targetSizePx) }
                ?.also { articleImageCache.put(resolvedUrl, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
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

private fun decodeSampledImage(bytes: ByteArray, targetSizePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > targetSizePx || bounds.outHeight / sample > targetSizePx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
