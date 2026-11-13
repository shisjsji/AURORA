package com.aurora.lsp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.VolumeUp

private data class NavEntry(val label: String, val icon: ImageVector)

@Composable
fun AuroraBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null
) {
    val entries = listOf(
        NavEntry("音效", MiuixIcons.VolumeUp),
        NavEntry("均衡", MiuixIcons.Tune),
        NavEntry("社区", MiuixIcons.Community),
        NavEntry("设置", MiuixIcons.Settings)
    )
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }

    FloatingBottomBar(
        modifier = modifier.padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
        selectedIndex = selected,
        onSelected = onSelect,
        backdrop = backdrop,
        tabsCount = entries.size,
        isBlurEnabled = true,
        progressOverride = progress
    ) { activateTab ->
        entries.forEachIndexed { index, entry ->
            FloatingBottomBarItem(
                selected = selected == index,
                onClick = { activateTab(index) },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label
                )
                Text(
                    text = entry.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}