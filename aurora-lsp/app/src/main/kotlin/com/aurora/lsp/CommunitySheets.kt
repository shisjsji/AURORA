package com.aurora.lsp

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/** A curve that never left the factory floor: every band within 0.5 dB of flat. */
internal fun isFlatCurve(curve: FloatArray): Boolean = curve.all { abs(it) <= 0.5f }

/**
 * Publishing flow: pick a curve, confirm the name, publish.
 *
 * <p>Two refusals happen before the request is even worth sending: a curve that is flat all the way
 * across says nothing to the next person, and the same curve published twice clutters the feed.
 * The server enforces both; this screen says so first so nobody waits on a round trip to find out.
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

    val flat = isFlatCurve(curve)
    val signedIn = community.auth.loggedIn

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
                    enabled = !publishing && name.isNotBlank() && !flat && signedIn,
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
                                bandHz = bandHz,
                                preset = state.preset,
                                strength = state.strength
                            )) {
                                is Community.Result.Ok -> published = true
                                is Community.Result.Denied -> note = result.message ?: community.error
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

        if (flat) {
            PanelCard(dark) {
                Text("这条曲线没有改过", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "六个频段都在 ±0.5 dB 以内，等于原厂平直。先调出你自己的声音再发，别人点开才不会看到一条直线。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
        }

        if (!signedIn) {
            PanelCard(dark) {
                Text(
                    "发布需要先登录：没有账号的曲线没法归属，也没法删。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
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

        if (published) {
            PanelCard(dark) {
                Text("已发布", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    "它会出现在社区的列表里。想改名或删掉，去社区页的「我的发布」。",
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

/** Everything the user liked, reachable from Settings and from the community page. */
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
        subtitle = "你点过喜欢的曲线会留在这里，换设备登录同一个邮箱也还在。",
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
        }
    ) {
        when {
            !community.auth.loggedIn -> PanelCard(dark) {
                Text(
                    "登录后才有「我的喜欢」——喜欢是跟着账号走的。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = muted
                )
            }
            community.likesStatus == CommunityState.FeedStatus.Loading -> PanelCard(dark) {
                Text("正在加载…", fontSize = 12.sp, color = muted)
            }
            community.likesStatus == CommunityState.FeedStatus.Offline -> PanelCard(dark) {
                Text("连不上社区服务器，稍后再看。", fontSize = 12.sp, color = muted)
            }
            community.myLikes.isEmpty() -> PanelCard(dark) {
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