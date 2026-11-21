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
 * Moderation, in one place: who is here, what they published, and the two levers that matter.
 *
 * <p>Everything on this screen is an admin action, so both destructive ones (ban, delete) ask once
 * and then say what happened. Content decisions stay where the content is - this is for the cases
 * the feed cannot reach: a user who keeps posting, or a curve that has to go regardless of author.
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
            Text("找用户", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { if (it.length <= 64) query = it },
                    modifier = Modifier.weight(1f),
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
                Spacer(Modifier.width(10.dp))
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
        }

        when (community.adminUsersStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在读取…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上社区服务器。", fontSize = 12.sp, color = muted)
            }
            else -> community.adminUsers.forEach { user ->
                PanelCard(dark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBadge(user.avatar, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    user.nickname.ifBlank { "未命名" },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ink
                                )
                                if (user.role == "admin") {
                                    Spacer(Modifier.width(6.dp))
                                    Text("管理员", fontSize = 10.sp, color = accent)
                                }
                                if (user.banned) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("已封禁", fontSize = 10.sp, color = colors.error)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(user.email.ifBlank { "—" }, fontSize = 11.sp, color = muted)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "曲线 " + user.presets + " · 评论 " + user.comments,
                                fontSize = 11.sp,
                                color = muted
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 自己这一行没有动作可做：封禁自己会把自己锁在外面，角色也不该自己改自己。
                    val isSelf = user.email.isNotBlank() && user.email == auth.email
                    if (isSelf) {
                        Text("这是你自己的账号", fontSize = 11.sp, color = muted)
                    } else {
                        Row {
                        Text(
                            if (user.banned) "解封" else "封禁",
                            fontSize = 13.sp,
                            color = if (user.banned) accent else colors.error,
                            modifier = Modifier
                                .clickable(enabled = !busy && user.id != 0) {
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
                                .padding(vertical = 6.dp, horizontal = 2.dp)
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(
                            if (user.role == "admin") "取消管理员" else "设为管理员",
                            fontSize = 13.sp,
                            color = muted,
                            modifier = Modifier
                                .clickable(enabled = !busy && user.id != 0) {
                                    scope.launch {
                                        busy = true
                                        val role = if (user.role == "admin") "user" else "admin"
                                        note = if (community.setRole(user.id, role)) {
                                            if (role == "admin") "已设为管理员" else "已取消管理员"
                                        } else community.error
                                        busy = false
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 2.dp)
                        )
                    }
                    }
                    if (user.banned && user.banReason.isNotBlank()) {
                        Text("原因：" + user.banReason, fontSize = 11.sp, color = muted)
                    }
                }
            }
        }

        PanelCard(dark) {
            Text("最近的曲线", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "管理删的是内容，不是账号。删掉之后评论和喜欢一起清空。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = muted
            )
        }

        when (community.adminPresetsStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在读取…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上社区服务器。", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Empty -> PanelCard(dark) {
                Text("社区里还没有曲线。", fontSize = 12.sp, color = muted)
            }
            else -> community.adminPresets.forEach { item ->
                PanelCard(dark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EqGlyph(item.curve, Modifier.width(44.dp), accent, height = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 14.sp, color = ink)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.nickname + " · 喜欢 " + item.likes + " · 评论 " + item.comments,
                                fontSize = 11.sp,
                                color = muted
                            )
                        }
                        Text(
                            "查看",
                            fontSize = 13.sp,
                            color = accent,
                            modifier = Modifier
                                .clickable { onOpenDetail(item) }
                                .padding(vertical = 6.dp, horizontal = 6.dp)
                        )
                        Text(
                            "删除",
                            fontSize = 13.sp,
                            color = colors.error,
                            modifier = Modifier
                                .clickable(enabled = !busy) { pendingDelete = item }
                                .padding(vertical = 6.dp, horizontal = 6.dp)
                        )
                    }
                }
            }
        }

        note?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
            }
        }
    }

    pendingBan?.let { target ->
        ConfirmBox(
            title = "封禁「" + target.nickname + "」？",
            body = "封禁后这个人不能登录、不能发布和评论，已发布的曲线会从社区列表里下架。" +
                "曲线和评论本身不会删除，解封后恢复。",
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
            title = "删除「" + item.name + "」？",
            body = "这条曲线和它下面的评论会一起删除，作者也拿不回来。",
            confirmLabel = "删除",
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    busy = true
                    note = if (community.deletePost(item)) "已删除。" else community.error
                    busy = false
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}