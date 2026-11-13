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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Publishing flow: pick a curve, confirm the name, publish.
 *
 * <p>The first publish also creates the anonymous identity, and the server hands back the recovery
 * key exactly once - the sheet shows it as the last step instead of a toast, because a key that
 * disappears in two seconds is a key nobody keeps.
 */
@Composable
internal fun ShareSheet(
    state: AuroraState,
    community: CommunityState,
    scope: CoroutineScope,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    val deviceLabel = ModelNames.friendly(state.device.model)
    var name by remember {
        mutableStateOf(if (state.preset == "custom") "我的曲线" else Presets.NAMES[state.presetIndex])
    }
    var curve by remember { mutableStateOf(state.curve.copyOf()) }
    var publishing by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    PanelScaffold(
        dark = dark,
        title = "分享到社区",
        subtitle = "曲线会带着机型（" + deviceLabel + "）发布，别人按机型判断参考价值。",
        onBack = onDismiss,
        actions = {
            if (published) {
                PanelButton("完成", dark, true, Modifier.weight(1f), onDismiss)
            } else {
                PanelButton("取消", dark, false, Modifier.weight(1f), onDismiss)
                PanelButton(
                    label = if (publishing) "发布中…" else "发布",
                    dark = dark,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    enabled = !publishing && name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            publishing = true
                            note = null
                            val bandHz = state.engineBandHz
                                ?.joinToString(",") { Math.round(it).toString() }
                                .orEmpty()
                            when (val result = community.publish(
                                name = name.trim(),
                                curve = curve,
                                model = state.device.model,
                                deviceLabel = deviceLabel,
                                bandHz = bandHz
                            )) {
                                is Community.Result.Ok -> published = true
                                is Community.Result.Denied -> note = community.error
                                is Community.Result.Failed -> note = community.error
                            }
                            publishing = false
                        }
                    }
                )
            }
        }
    ) {
        PanelCard(dark) {
            Text("曲线", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            CurvePreview(curve, accent, muted, showScale = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 20) name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("给这条曲线起个名字", fontSize = 13.sp, color = muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = ink),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
        }

        if (state.userPresets.isNotEmpty()) {
            PanelCard(dark) {
                Text("或者挑一条我保存过的", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(4.dp))
                state.userPresets.forEach { preset ->
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                curve = preset.curve.copyOf()
                                name = preset.name
                            }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EqGlyph(preset.curve, Modifier.width(42.dp), accent, height = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(preset.name, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
                        Text("选用", fontSize = 12.sp, color = accent)
                    }
                }
            }
        }

        community.freshRecoveryKey?.let { key ->
            RecoveryKeyCard(key, dark) { community.clearFreshKey() }
        }

        if (published) {
            PanelCard(dark) {
                Text("已发布", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    "它会出现在社区的列表里。想改名字或头像，去「社区身份」里调。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
        }

        note?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
            }
        }
    }
}

/**
 * "Save this key" card. Used wherever an identity comes into being: a first publish, a first
 * comment, or a first like. The card exists so no flow can create an account silently.
 */
@Composable
internal fun RecoveryKeyCard(key: String, dark: Boolean, onDone: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary
    PanelCard(dark) {
        Text("记住这个找回码", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "卸载重装后凭它认领你发布的内容、喜欢的曲线和昵称头像。它只显示这一次。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = muted
        )
        Spacer(Modifier.height(10.dp))
        RecoveryKeyBlock(key)
        Spacer(Modifier.height(10.dp))
        Text(
            "我记住了",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDone)
                .padding(vertical = 6.dp)
        )
    }
}

/** The recovery key itself: monospace, selectable-looking, with a copy action. */
@Composable
internal fun RecoveryKeyBlock(key: String) {
    val colors = MiuixTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            key,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (copied) "已复制" else "复制",
            fontSize = 13.sp,
            color = colors.primary,
            modifier = Modifier
                .clickable {
                    clipboard.setText(AnnotatedString(key))
                    copied = true
                }
                .padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/**
 * Identity management: nickname, avatar, and the way back in after a reinstall.
 *
 * <p>No password, no email - the recovery key is the whole account. Losing it is the same as
 * staying anonymous, which is exactly the state every install starts in.
 */
@Composable
internal fun IdentityPanel(
    community: CommunityState,
    scope: CoroutineScope,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    var nickname by remember(community.nickname) { mutableStateOf(community.nickname) }
    var chosen by remember(community.avatar) { mutableStateOf(community.avatar) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var recoverInput by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    PanelScaffold(
        dark = dark,
        title = "社区身份",
        subtitle = "匿名发布，不需要账号。找回码是唯一的身份凭证。",
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
                        note = if (community.updateProfile(nickname.trim(), chosen)) "已保存。"
                        else community.error
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
                    // Keep the last row the same width as the others.
                    repeat(6 - row.size) { Spacer(Modifier.size(44.dp)) }
                }
            }
        }

        PanelCard(dark) {
            Text("找回码", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "换手机或重装后，在这里输入找回码，就能把这个身份和它名下的内容认领回来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = muted
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (busy) "生成中…" else "重新生成找回码",
                fontSize = 13.sp,
                color = accent,
                modifier = Modifier
                    .clickable {
                        if (!busy) {
                            scope.launch {
                                busy = true
                                revealed = community.rotateKey()
                                busy = false
                            }
                        }
                    }
                    .padding(vertical = 4.dp)
            )
            revealed?.let { key ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "旧找回码已经失效，请记下新的：",
                    fontSize = 12.sp,
                    color = muted
                )
                Spacer(Modifier.height(8.dp))
                RecoveryKeyBlock(key)
            }
        }

        PanelCard(dark) {
            Text("找回已有身份", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = recoverInput,
                onValueChange = { if (it.length <= 16) recoverInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("XXXX-XXXX-XXXX", fontSize = 13.sp, color = muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = ink),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (busy) "认领中…" else "认领",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (recoverInput.isBlank() || busy) muted else accent,
                modifier = Modifier
                    .clickable {
                        if (recoverInput.isNotBlank() && !busy) {
                            scope.launch {
                                busy = true
                                note = if (community.recoverIdentity(recoverInput)) {
                                    nickname = community.nickname
                                    chosen = community.avatar
                                    "已认领，内容和喜欢都回来了。"
                                } else community.error
                                busy = false
                            }
                        }
                    }
                    .padding(vertical = 4.dp)
            )
        }

        note?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
            }
        }
    }
}

/** Everything the user liked, reachable from Settings. */
@Composable
internal fun MyLikesPanel(
    community: CommunityState,
    scope: CoroutineScope,
    dark: Boolean,
    onOpenDetail: (Community.Item) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val muted = colors.onSurfaceVariantSummary

    androidx.compose.runtime.LaunchedEffect(Unit) { community.refreshLikes() }

    PanelScaffold(
        dark = dark,
        title = "我的喜欢",
        subtitle = "你点过喜欢的曲线会一直留在这里，换设备后凭找回码也还在。",
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
        }
    ) {
        when (community.likesStatus) {
            CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在加载…", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上社区服务器，稍后再看。", fontSize = 12.sp, color = muted)
            }
            CommunityState.FeedStatus.Empty, CommunityState.FeedStatus.Idle -> PanelCard(dark) {
                Text(
                    "还没有喜欢的曲线。在社区里看到合适的，点一下「喜欢」就会出现在这里。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
            else -> community.myLikes.forEach { item ->
                PanelCard(dark) {
                    CommunityCard(
                        item = item,
                        onOpen = { onOpenDetail(item) },
                        onLike = { scope.launch { community.toggleLike(item) } }
                    )
                }
            }
        }
    }
}