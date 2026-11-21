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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The community page: share entry, account line, and everyone else's curves.
 *
 * <p>Browsing is open to anyone; the moment a tap would create something - publishing, liking,
 * commenting - the page asks for an account first. Everything that was published stays reachable
 * under "我的发布", so sharing is a loop rather than a one-way dump.
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
    onOpenLogin: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMyPosts: () -> Unit,
    onStatusClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val muted = colors.onSurfaceVariantSummary
    val signedIn = community.auth.loggedIn

    // All four pages are composed at startup, so fetching here on `Unit` would hit the network on
    // every launch even when nobody opens this tab. Load the first time the tab is actually shown.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (community.feedStatus == CommunityState.FeedStatus.Idle) community.refreshFeed()
        if (signedIn) community.auth.refresh()
    }

    AuroraPage(
        title = "社区",
        underTitle = { StatusStrip(state, onStatusClick) }
    ) {
        AuroraGroup("分享") {
            AuroraRow(
                title = if (signedIn) "分享我的曲线" else "登录后发布曲线",
                leading = {
                    Icon(
                        MiuixIcons.UploadCloud,
                        null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                },
                trailing = { ValueText(if (signedIn) "发布" else "登录", accent = true) },
                onClick = if (signedIn) onOpenShare else onOpenLogin
            )
            RowDivider()
            AuroraRow(
                title = if (signedIn) community.auth.nickname.ifBlank { "账号" } else "邮箱登录",
                leading = {
                    if (signedIn) {
                        AvatarBadge(community.auth.avatar, 26.dp)
                    } else {
                        AvatarBadge("eq", 26.dp)
                    }
                },
                trailing = { ValueText(if (signedIn) "账号" else "") },
                mutedTitle = true,
                onClick = if (signedIn) onOpenAccount else onOpenLogin
            )
            if (signedIn) {
                RowDivider()
                AuroraRow(
                    title = "我的发布",
                    trailing = { ValueText("管理") },
                    mutedTitle = true,
                    onClick = onOpenMyPosts
                )
            }
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
                            Spacer(Modifier.height(8.dp))
                            Text("还没有人分享曲线。", fontSize = 13.sp, color = colors.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "你的曲线可能正好是别人在找的那条。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = muted
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (signedIn) "发布第一条" else "登录后发布第一条",
                                fontSize = 15.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = colors.primary,
                                modifier = Modifier
                                    .clickable { if (signedIn) onOpenShare() else onOpenLogin() }
                                    .padding(vertical = 8.dp)
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
                            onLike = {
                                if (signedIn) {
                                    scope.launch { community.toggleLike(item) }
                                } else {
                                    onOpenLogin()
                                }
                            }
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