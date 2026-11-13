package com.aurora.lsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The community page: share entry, identity line, and everyone else's curves.
 *
 * <p>It sits where the dock sits, not behind a menu - sharing is the point of the feature, and a
 * browse-only page nobody finds is a page nobody feeds.
 */
@Composable
internal fun CommunityPage(
    state: AuroraState,
    community: CommunityState,
    dark: Boolean,
    scope: CoroutineScope,
    active: Boolean,
    onOpenShare: () -> Unit,
    onOpenDetail: (Community.Item) -> Unit,
    onOpenIdentity: () -> Unit,
    onStatusClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val muted = colors.onSurfaceVariantSummary

    // All four pages are composed at startup, so fetching here on `Unit` would hit the network on
    // every launch even when nobody opens this tab. Load the first time the tab is actually shown.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (community.feedStatus == CommunityState.FeedStatus.Idle) community.refreshFeed()
        if (community.nickname.isBlank()) community.refreshProfile()
    }

    AuroraPage(
        title = "社区",
        underTitle = { StatusStrip(state, onStatusClick) }
    ) {
        AuroraGroup("分享") {
            AuroraRow(
                title = "分享我的曲线",
                summary = "发布一条你调的曲线，带上机型给同样设备的人参考",
                trailing = { ValueText("发布", accent = true) },
                onClick = onOpenShare
            )
            RowDivider()
            AuroraRow(
                title = community.nickname.ifBlank { "社区身份" },
                summary = "匿名发布 · 找回码可跨重装认领内容",
                leading = { AvatarBadge(community.avatar, 26.dp) },
                trailing = { ValueText("管理") },
                onClick = onOpenIdentity
            )
        }

        AuroraGroup("大家的声音") {
            when (community.feedStatus) {
                CommunityState.FeedStatus.Loading -> {
                    PaddedBlock {
                        Text("正在加载…", fontSize = 12.sp, color = muted)
                    }
                }
                CommunityState.FeedStatus.Offline -> {
                    PaddedBlock {
                        Column {
                            Text("连不上社区服务器。", fontSize = 13.sp, color = colors.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "检查一下网络再试。声音本身不受影响，社区只是看看。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = muted
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "重试",
                                fontSize = 13.sp,
                                color = colors.primary,
                                modifier = Modifier
                                    .clickable { scope.launch { community.refreshFeed() } }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                CommunityState.FeedStatus.Empty -> {
                    PaddedBlock {
                        Column {
                            Text("还没有人分享曲线。", fontSize = 13.sp, color = colors.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "第一条会出现在这里。你的曲线可能正好是别人在找的那条。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = muted
                            )
                        }
                    }
                }
                else -> {
                    community.feed.forEachIndexed { index, item ->
                        if (index > 0) RowDivider(startInset = Space.card)
                        CommunityCard(
                            item = item,
                            onOpen = { onOpenDetail(item) },
                            onLike = { scope.launch { community.toggleLike(item) } }
                        )
                    }
                    if (community.nextCursor != null) {
                        RowDivider()
                        AuroraRow(
                            title = if (community.loadingMore) "正在加载…" else "加载更多",
                            onClick = { scope.launch { community.loadMore() } }
                        )
                    }
                }
            }
        }

        community.error?.let { message ->
            AuroraGroup {
                PaddedBlock {
                    Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
                }
            }
        }
    }
}