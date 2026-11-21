package com.aurora.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Signing in: one mailbox, one code, no password.
 *
 * <p>Two steps in one sheet because there is nothing else to ask for. The mailbox is only ever used
 * to sign in and to recover the account - it is never shown next to a published curve, which is
 * what the note at the bottom is there to say.
 */
@Composable
internal fun LoginPanel(
    auth: AuthState,
    scope: CoroutineScope,
    dark: Boolean,
    subtitle: String,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    var email by remember { mutableStateOf(auth.email) }
    var code by remember { mutableStateOf("") }
    var stage by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { auth.clearError() }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    fun sendCode() {
        if (busy) return
        scope.launch {
            busy = true
            note = null
            if (auth.requestCode(email)) {
                stage = 1
                cooldown = 60
                note = "验证码已发到 " + email.trim() + "，10 分钟内有效。"
            }
            busy = false
        }
    }

    fun signIn() {
        if (busy) return
        scope.launch {
            busy = true
            note = null
            if (auth.verify(email, code)) onSignedIn()
            busy = false
        }
    }

    PanelScaffold(
        dark = dark,
        title = "登录社区",
        subtitle = subtitle,
        onBack = onDismiss,
        actions = {
            PanelButton("取消", dark, false, Modifier.weight(1f), onDismiss)
            if (stage == 0) {
                PanelButton(
                    label = if (busy) "发送中…" else "发送验证码",
                    dark = dark,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && email.isNotBlank(),
                    onClick = { sendCode() }
                )
            } else {
                PanelButton(
                    label = if (busy) "登录中…" else "登录",
                    dark = dark,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && code.length == 6,
                    onClick = { signIn() }
                )
            }
        }
    ) {
        PanelCard(dark) {
            Text("邮箱", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { if (it.length <= 120 && stage == 0) email = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = stage == 0 && !busy,
                placeholder = { Text("you@example.com", fontSize = 14.sp, color = muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = ink),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
            if (stage == 1) {
                Spacer(Modifier.height(16.dp))
                Text("验证码", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("6 位数字", fontSize = 14.sp, color = muted) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = ink),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                        cursorColor = accent
                    )
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (cooldown > 0) cooldown.toString() + " 秒后可以重发" else "没收到？重新发一次",
                    fontSize = 13.sp,
                    color = if (cooldown > 0) muted else accent,
                    modifier = Modifier
                        .clickable(enabled = cooldown == 0 && !busy) { sendCode() }
                        .padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "邮箱只用来登录和找回账号，不会出现在你发布的曲线旁边。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = muted
            )
        }

        val message = auth.error ?: note
        if (message != null) {
            PanelCard(dark) {
                Text(
                    message,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (auth.error != null) colors.error else muted
                )
            }
        }
    }
}

/**
 * The account itself: who you are in the community, and the two ways out.
 *
 * <p>A nick name and an avatar are all anyone else sees; the mailbox stays on this screen.
 */
@Composable
internal fun AccountPanel(
    auth: AuthState,
    community: CommunityState,
    scope: CoroutineScope,
    dark: Boolean,
    onSignedOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    var nickname by remember(auth.nickname) { mutableStateOf(auth.nickname) }
    var chosen by remember(auth.avatar) { mutableStateOf(auth.avatar) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { auth.clearError() }

    PanelScaffold(
        dark = dark,
        title = "账号",
        subtitle = auth.email.ifBlank { "已登录" },
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, false, Modifier.weight(1f), onDismiss)
            PanelButton(
                label = if (busy) "保存中…" else "保存",
                dark = dark,
                primary = true,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        note = if (auth.updateProfile(nickname.trim(), chosen)) {
                            community.refreshFeed()
                            "已保存。"
                        } else {
                            auth.error
                        }
                        busy = false
                    }
                }
            )
        }
    ) {
        PanelCard(dark) {
            Text("昵称", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 16) nickname = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("最多 16 个字", fontSize = 13.sp, color = muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = ink),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
            Spacer(Modifier.height(12.dp))
            Text("头像", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            Avatars.ALL.chunked(6).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { choice ->
                        val selected = choice.key == chosen
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) accent.copy(alpha = 0.18f)
                                    else colors.surfaceContainer
                                )
                                .clickable { chosen = choice.key },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                choice.icon,
                                null,
                                tint = if (selected) accent else muted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.size(44.dp)) }
                }
            }
        }

        PanelCard(dark) {
            Text("邮箱", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(auth.email.ifBlank { "—" }, fontSize = 15.sp, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "换设备或重装后，用这个邮箱收一次验证码就能找回全部内容。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = muted
            )
        }

        note?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
            }
        }

        PanelCard(dark) {
            Text(
                "退出登录",
                fontSize = 14.sp,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            auth.signOut()
                            community.resetForAuthChange()
                            community.refreshFeed()
                            busy = false
                            onSignedOut()
                        }
                    }
                    .padding(vertical = 6.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "注销账号",
                fontSize = 13.sp,
                color = colors.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { confirmingDelete = true }
                    .padding(vertical = 6.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "注销会删掉这个账号发布过的曲线、评论和喜欢，邮箱可以重新注册。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = muted
            )
        }
    }

    if (confirmingDelete) {
        ConfirmBox(
            title = "注销账号？",
            body = "你发布的曲线、收到的喜欢和评论会一起删除，这个操作无法撤销。",
            confirmLabel = "注销",
            onConfirm = {
                confirmingDelete = false
                scope.launch {
                    busy = true
                    if (auth.deleteAccount()) {
                        community.resetForAuthChange()
                        community.refreshFeed()
                        note = null
                        busy = false
                        onSignedOut()
                    } else {
                        note = auth.error
                        busy = false
                    }
                }
            },
            onDismiss = { confirmingDelete = false }
        )
    }
}

/** My published curves, with the one action that closes the loop: delete. */
@Composable
internal fun MyPostsPanel(
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

    var pending by remember { mutableStateOf<Community.Item?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { community.refreshMine() }

    PanelScaffold(
        dark = dark,
        title = "我的发布",
        subtitle = "发出去的曲线自己可以删。删掉之后别人也看不到了。",
        onBack = onDismiss,
        actions = { PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss) }
    ) {
        when (community.myPostsStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在加载…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上社区服务器，稍后再看。", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Empty, CommunityState.FeedStatus.Idle -> PanelCard(dark) {
                Text(
                    "还没有发布过曲线。在社区页点「分享我的曲线」就能发一条。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
            else -> community.myPosts.forEach { item ->
                PanelCard(dark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EqGlyph(item.curve, Modifier.width(46.dp), accent, height = 30.dp)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "喜欢 " + item.likes + " · 评论 " + item.comments,
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
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                        Text(
                            "删除",
                            fontSize = 13.sp,
                            color = colors.error,
                            modifier = Modifier
                                .clickable { pending = item }
                                .padding(vertical = 6.dp, horizontal = 8.dp)
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

    pending?.let { item ->
        ConfirmBox(
            title = "删除「" + item.name + "」？",
            body = "这条曲线和它下面的评论会一起删除，别人也看不到了。",
            confirmLabel = "删除",
            onConfirm = {
                pending = null
                scope.launch {
                    note = if (community.deletePost(item)) "已删除。" else community.error
                }
            },
            onDismiss = { pending = null }
        )
    }
}

/**
 * The first-launch notice: the community needs an account, the sound does not.
 *
 * <p>It has to be dismissible in one tap and it must not block anything - this app is an equaliser
 * first, and a login wall in front of a local audio setting is a wall with nothing behind it.
 */
@Composable
internal fun CommunityLoginHintDialog(onLogin: () -> Unit, onLater: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainer)
                .padding(20.dp)
        ) {
            Text("社区现在要登录了", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "浏览别人的曲线不用登录；发布、喜欢和评论需要一个邮箱账号，这样你发出去的东西才真正属于你，换手机也能拿回来。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = colors.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "调音本身完全不受影响，不登录也能用。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHigh)
                        .clickable(onClick = onLater)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("先看看", fontSize = 14.sp, color = colors.onSurface)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primary.copy(alpha = 0.16f))
                        .clickable(onClick = onLogin)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("去登录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.primary)
                }
            }
        }
    }
}

/** Same shell as the update prompt: cancel left, the destructive choice right. */
@Composable
private fun ConfirmBox(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainer)
                .padding(20.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHigh)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 14.sp, color = colors.onSurface)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.error.copy(alpha = 0.16f))
                        .clickable(onClick = onConfirm)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.error)
                }
            }
        }
    }
}