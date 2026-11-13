package com.aurora.lsp

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Recording
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * The avatar set. Built-in marks only - no uploads, so there is nothing to host and nothing to
 * moderate. The key is what the server stores; the icon is resolved here.
 */
object Avatars {

    data class Choice(val key: String, val icon: ImageVector)

    val ALL = listOf(
        Choice("eq", MiuixIcons.Tune),
        Choice("music", MiuixIcons.Music),
        Choice("mic", MiuixIcons.Mic),
        Choice("album", MiuixIcons.Album),
        Choice("play", MiuixIcons.Play),
        Choice("playlist", MiuixIcons.Playlist),
        Choice("recording", MiuixIcons.Recording),
        Choice("notes", MiuixIcons.Notes),
        Choice("favorites", MiuixIcons.Favorites),
        Choice("community", MiuixIcons.Community),
        Choice("layers", MiuixIcons.Layers),
        Choice("theme", MiuixIcons.Theme)
    )

    fun icon(key: String): ImageVector =
        ALL.firstOrNull { it.key == key }?.icon ?: MiuixIcons.Tune
}