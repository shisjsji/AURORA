package com.aurora.lsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
 * One shared preset, opened: the curve itself, whether you like it, and what people said.
 *
 * <p>Saving is the primary action - the point of the community is to end up owning the curve,
 * not to admire it through a list.
 */
@Composable
internal fun CommunityDetailPanel(
    item: Community.Item,
    community: CommunityState,
    state: AuroraState,
    dark: Boolean,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    val accent = colors.primary

    var current by remember { mutableStateOf(item) }
    var comments by remember { mutableStateOf<List<Community.Comment>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveNote by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        when (val result = Community.detail(item.id, community.anonId)) {
            is Community.Result.Ok -> {
                current = result.value.first
                comments = result.value.second
            }
            else -> Unit
        }
    }

    PanelScaffold(
        dark = dark,
        title = current.name,
        subtitle = current.nickname + " · " + current.deviceLabel,
        onBack = onDismiss,
        actions = {
            PanelButton(
                label = (if (current.liked) "已喜欢 · " else "喜欢 · ") + current.likes,
                dark = dark,
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        if (community.toggleLike(current)) {
                            current = current.copy(
                                liked = !current.liked,
                                likes = (current.likes + if (!current.liked) 1 else -1).coerceAtLeast(0)
                            )
                        }
                    }
                }
            )
            PanelButton(
                label = if (saving) "保存中…" else "保存到我的预设",
                dark = dark,
                primary = true,
                modifier = Modifier.weight(1f),
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        state.savePreset(current.name, current.curve)
                        saving = false
                        saveNote = "已保存。去「均衡」页的“我的预设”里就能找到它。"
                    }
                }
            )
        }
    ) {
        // Liking or commenting can be the first thing this install ever does. If that created
        // the identity, the key is shown right here - the panel may be the only place the user is.
        community.freshRecoveryKey?.let { key ->
            RecoveryKeyCard(key, dark) { community.clearFreshKey() }
        }

        PanelCard(dark) {
            CurvePreview(current.curve, accent, muted, showScale = false)
            Spacer(Modifier.height(10.dp))
            // Band readings with the publisher's real band table.
            val bandHz = parseBandHz(current.bandHz)
            val labels = bandHz ?: AudioEq.BAND_HZ
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, hz ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(bandLabel(hz), fontSize = 10.sp, color = muted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            (if (current.curve.getOrNull(index) ?: 0f >= 0f) "+" else "") +
                                String.format(java.util.Locale.US, "%.1f", current.curve.getOrNull(index) ?: 0f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ink
                        )
                    }
                }
            }
            if (bandHz != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "这条曲线来自 " + current.deviceLabel + " 的兼容频点表（250 Hz–6.3 kHz），在你的设备上会有细微差别。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = muted
                )
            }
            saveNote?.let { note ->
                Spacer(Modifier.height(8.dp))
                Text(note, fontSize = 12.sp, lineHeight = 18.sp, color = accent)
            }
        }

        PanelCard(dark) {
            Text("评论 " + comments.size, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.height(8.dp))
            if (comments.isEmpty()) {
                Text("还没有评论。说说这条曲线在你设备上听起来怎么样。", fontSize = 12.sp, lineHeight = 18.sp, color = muted)
            } else {
                comments.forEachIndexed { index, comment ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.HorizontalDivider(
                            thickness = 0.75.dp,
                            color = colors.dividerLine
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        AvatarBadge(comment.avatar, 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(comment.nickname, fontSize = 11.sp, color = muted)
                            Spacer(Modifier.height(2.dp))
                            Text(comment.body, fontSize = 13.sp, lineHeight = 19.sp, color = ink)
                        }
                    }
                }
            }
        }

        PanelCard(dark) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 140) input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp),
                placeholder = {
                    Text("说点什么（最多 140 字）", fontSize = 13.sp, color = muted)
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = ink,
                    lineHeight = 20.sp
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val sendable = input.isNotBlank() && !sending
                Text(
                    if (sending) "发送中…" else "发送评论",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sendable) accent else muted,
                    modifier = Modifier
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .then(
                            if (sendable) {
                                Modifier.clickable {
                                    scope.launch {
                                        sending = true
                                        if (community.addComment(current.id, input.trim())) {
                                            val refreshed = Community.detail(current.id, community.anonId)
                                            if (refreshed is Community.Result.Ok) {
                                                current = refreshed.value.first
                                                comments = refreshed.value.second
                                            }
                                            input = ""
                                        }
                                        sending = false
                                    }
                                }
                            } else Modifier
                        )
                )
            }
        }
    }
}

/** "250,500,1000,2000,4000,6300" -> floats; anything else -> null (use the default table). */
private fun parseBandHz(raw: String): FloatArray? {
    if (raw.isBlank()) return null
    val parts = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (parts.size != AudioEq.BANDS) return null
    val table = FloatArray(AudioEq.BANDS) { parts[it] }
    val isDefault = table.indices.all { Math.abs(table[it] - AudioEq.BAND_HZ[it]) < 0.5f }
    return if (isDefault) null else table
}