package com.aurora.lsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Moderation: who is here, and what is published.
 *
 * <p>Two lists, nothing else. Each row carries one line of identity and one action, so a scan down
 * the page answers "who is a problem" without reading sentences. Destructive actions ask once.
 */
@Composable
internal fun AdminPanel(
    auth: AuthState,
    community: CommunityState,
    scope: CoroutineScope,
    dark: Boolean,
    onOpenDetail: (Community.Item) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    var query by remember { mutableStateOf("") }
    var pendingBan by remember { mutableStateOf<Community.AdminUser?>(null) }
    var pendingDelete by remember { mutableStateOf<Community.Item?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        community.refreshAdminUsers()
        community.refreshAdminPresets()
    }

    PanelScaffold(
        dark = dark,
        title = "社区管理",
        subtitle = auth.email.ifBlank { "管理员" },
        onBack = onDismiss,
        actions = { PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss) }
    ) {
        PanelCard(dark) {
            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 64) query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("邮箱或昵称", fontSize = 13.sp, color = muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = ink),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "搜索",
                fontSize = 13.sp,
                color = accent,
                modifier = Modifier
                    .clickable(enabled = !busy) {
                        scope.launch { community.refreshAdminUsers(query.trim()) }
                    }
                    .padding(vertical = 6.dp)
            )
        }

        SectionLabel("用户", muted)
        when (community.adminUsersStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在读取…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上服务器", fontSize = 12.sp, color = muted)
            }
            else -> community.adminUsers.forEach { user ->
                val isSelf = user.email.isNotBlank() && user.email == auth.email
                PanelCard(dark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBadge(user.avatar, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    user.nickname.ifBlank { "未命名" },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ink
                                )
                                val badge = when {
                                    isSelf -> "你"
                                    user.role == "admin" -> "管理员"
                                    user.banned -> "已封禁"
                                    else -> ""
                                }
                                if (badge.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        badge,
                                        fontSize = 10.sp,
                                        color = when {
                                            user.banned && !isSelf -> colors.error
                                            else -> muted
                                        }
                                    )
                                }
                            }
                            Text(
                                user.email.ifBlank { "无账号" },
                                fontSize = 11.sp,
                                color = muted
                            )
                        }
                        if (!isSelf) {
                            Text(
                                if (user.banned) "解封" else "封禁",
                                fontSize = 13.sp,
                                color = if (user.banned) accent else colors.error,
                                modifier = Modifier
                                    .clickable(enabled = !busy) {
                                        if (user.banned) {
                                            scope.launch {
                                                busy = true
                                                note = if (community.unbanUser(user.id)) {
                                                    "已解封 " + user.nickname
                                                } else community.error
                                                busy = false
                                            }
                                        } else {
                                            pendingBan = user
                                        }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (user.role == "admin") "取消管理" else "设为管理",
                                fontSize = 13.sp,
                                color = muted,
                                modifier = Modifier
                                    .clickable(enabled = !busy) {
                                        scope.launch {
                                            busy = true
                                            val role = if (user.role == "admin") "user" else "admin"
                                            note = if (community.setRole(user.id, role)) {
                                                if (role == "admin") "已设为管理员" else "已取消管理员"
                                            } else community.error
                                            busy = false
                                        }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        SectionLabel("内容", muted)
        when (community.adminPresetsStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在读取…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上服务器", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Empty -> PanelCard(dark) {
                Text("还没有曲线", fontSize = 12.sp, color = muted)
            }
            else -> community.adminPresets.forEach { item ->
                PanelCard(dark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EqGlyph(item.curve, Modifier.width(40.dp), accent, height = 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 14.sp, color = ink)
                            Text(item.nickname, fontSize = 11.sp, color = muted)
                        }
                        Text(
                            "查看",
                            fontSize = 13.sp,
                            color = muted,
                            modifier = Modifier
                                .clickable { onOpenDetail(item) }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "删除",
                            fontSize = 13.sp,
                            color = colors.error,
                            modifier = Modifier
                                .clickable(enabled = !busy) { pendingDelete = item }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        note?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, color = muted)
            }
        }
    }

    pendingBan?.let { target ->
        ConfirmBox(
            title = "封禁「" + target.nickname + "」",
            body = "不能登录、不能发布和评论，已发布的曲线从社区下架。解封后恢复。",
            confirmLabel = "封禁",
            onConfirm = {
                pendingBan = null
                scope.launch {
                    busy = true
                    note = if (community.banUser(target.id, "")) {
                        "已封禁 " + target.nickname
                    } else community.error
                    busy = false
                }
            },
            onDismiss = { pendingBan = null }
        )
    }

    pendingDelete?.let { item ->
        ConfirmBox(
            title = "删除「" + item.name + "」",
            body = "曲线和它下面的评论一起删除，作者也拿不回来。",
            confirmLabel = "删除",
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    busy = true
                    note = if (community.deletePost(item)) "已删除" else community.error
                    busy = false
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** A short heading between blocks inside a panel. */
@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}