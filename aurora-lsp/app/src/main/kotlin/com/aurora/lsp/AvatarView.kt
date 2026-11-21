package com.aurora.lsp

import android.content.Context
import android.graphics.BitmapFactory
import com.aurora.audio.lsp.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.util.LruCache

/**
 * Uploaded avatars are a URL; everything else is the one built-in picture.
 *
 * <p>`img:<file>` is what the server stores after an upload. An empty value means "never chose
 * one", which draws the bundled default. There is no icon set to pick from: an account either
 * uploaded a picture or it did not.
 */
internal fun avatarImageUrl(avatar: String): String? =
    if (avatar.startsWith("img:")) Community.AVATARS + avatar.removePrefix("img:") else null

/** Small enough to hold in memory: these are 512×512 JPEGs, a few dozen KB each. */
private val avatarCache = LruCache<String, ImageBitmap>(48)
/** URLs that failed once and when they may be tried again; a dropped packet is not a dead image. */
private val avatarRetryAt = ConcurrentHashMap<String, Long>()
private const val RETRY_AFTER_MS = 30_000L

/** 交给系统调度用：内存吃紧时整层清掉，磁盘缓存还在，回来时重新解码。 */
internal fun trimAvatarMemory() {
    avatarCache.evictAll()
}

private fun cacheFile(context: Context, url: String): File =
    File(File(context.filesDir, "avatars"), Integer.toHexString(url.hashCode()) + ".img")

private fun decode(bytes: ByteArray): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.asImageBitmap()
}

/**
 * Memory first, then the copy on disk, then the network.
 *
 * <p>Without the disk copy every cold start re-downloaded every face in the feed; with it, a
 * second launch draws instantly and the network only happens once per file name.
 */
private suspend fun loadAvatar(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    avatarCache.get(url)?.let { return@withContext it }

    val file = cacheFile(context, url)
    if (file.isFile) {
        runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
            decode(bytes)?.let { image ->
                avatarCache.put(url, image)
                return@withContext image
            }
        }
        file.delete()
    }

    if (System.currentTimeMillis() < (avatarRetryAt[url] ?: 0L)) return@withContext null

    try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        try {
            val bytes = connection.inputStream.use { it.readBytes() }
            val image = decode(bytes)
            if (image != null) {
                avatarCache.put(url, image)
                avatarRetryAt.remove(url)
                runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
            } else {
                avatarRetryAt[url] = System.currentTimeMillis() + RETRY_AFTER_MS
            }
            image
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        avatarRetryAt[url] = System.currentTimeMillis() + RETRY_AFTER_MS
        null
    }
}

/**
 * One avatar, round, whatever kind it is.
 *
 * <p>The default is a bundled picture, so a fresh install shows a face before it ever reaches the
 * network. Only uploaded avatars are fetched, and a failed fetch keeps the default on screen and
 * retries later instead of showing a hole.
 */
@Composable
internal fun AvatarView(avatar: String, size: Dp, highlighted: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val url = avatarImageUrl(avatar)
    val loaded by produceState<ImageBitmap?>(initialValue = avatarCache.get(url ?: ""), key1 = url) {
        value = if (url == null) null else loadAvatar(context, url)
    }

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.onSurfaceVariantSummary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        val image = loaded
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.default_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
    }
}