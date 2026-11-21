package com.aurora.lsp

import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL
import android.util.LruCache

/**
 * Uploaded avatars are a URL, built-in ones are a key.
 *
 * <p>`img:<file>` is what the server stores after an upload; anything else names one of the twelve
 * built-in icons. Keeping both in one string means every existing call site can keep passing an
 * avatar around without knowing which kind it has.
 */
internal fun avatarImageUrl(avatar: String): String? =
    if (avatar.startsWith("img:")) Community.AVATARS + avatar.removePrefix("img:") else null

/** Small enough to hold in memory: these are 512×512 JPEGs, a few dozen KB each. */
private val avatarCache = LruCache<String, ImageBitmap>(48)

private suspend fun loadAvatar(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    avatarCache.get(url)?.let { return@withContext it }
    try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        try {
            connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext null
                val image = decoded.asImageBitmap()
                avatarCache.put(url, image)
                image
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        null
    }
}

/**
 * One avatar, round, whatever kind it is.
 *
 * <p>The picture arrives over the network, so there is a fallback path: while it loads - or if it
 * never arrives - the icon for "eq" stands in, which is what every account starts with anyway.
 */
@Composable
internal fun AvatarView(avatar: String, size: Dp, highlighted: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val tint = if (highlighted) colors.primary else colors.onSurfaceVariantSummary
    val url = avatarImageUrl(avatar)
    val loaded by produceState<ImageBitmap?>(initialValue = avatarCache.get(url ?: ""), key1 = url) {
        value = if (url == null) null else loadAvatar(url)
    }

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f)),
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
            Icon(
                imageVector = if (url == null) Avatars.icon(avatar) else Avatars.icon("eq"),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.56f)
            )
        }
    }
}