package com.aurora.lsp

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The two screens that explain state instead of setting it.
 *
 * Both answer questions a user actually has and cannot answer from the main pages: "why is nothing
 * happening on my phone" and "what did the module find here". They are full screens rather than
 * dialogs because both can carry a screenful of facts, and a dialog with a scrolling body is a
 * dialog nobody scrolls.
 */

@Composable
internal fun PanelScaffold(
    dark: Boolean,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // Same predictive-back behaviour as the main pages: the sheet shrinks and rounds while the
    // gesture is in flight, so backing out of a second-level screen feels like leaving a place
    // rather than watching it vanish.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onBack()
        } finally {
            backProgress = 0f
        }
    }
    val ink = Ink
    val muted = Muted
    // Same surface the main pages paint, so a second-level screen never looks like a
    // different app. The old hard-coded 0xFF0E1013 read blue against it.
    val surface = MiuixTheme.colorScheme.surface

    // Entrance: rises a little and fades in, the same settle the dock pages use. Without this
    // the sheet appeared instantly, which read as a different kind of screen.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = backProgress
                val scale = 1f - 0.07f * p
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape((26 * p).dp)
                clip = p > 0.01f
                val e = enter.value
                alpha = e
                translationY = (1f - e) * 48.dp.toPx()
            }
            .background(surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 10.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = muted)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = actions
        )
    }
}

@Composable
internal fun PanelCard(dark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = Radius.card,
        insideMargin = PaddingValues(Space.card)
    ) {
        Column { content() }
    }
}

@Composable
internal fun PanelButton(
    label: String,
    dark: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val ink = Ink
    val accent = Accent
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    primary -> accent
                    else -> MiuixTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = when {
                primary && enabled -> Color.White
                primary -> Color.White.copy(alpha = 0.55f)
                enabled -> ink
                else -> ink.copy(alpha = 0.45f)
            }
        )
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String, dark: Boolean) {
    val ink = Ink
    val muted = Muted
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, fontSize = 12.sp, color = muted, modifier = Modifier.width(74.dp))
        Text(value.ifBlank { "—" }, fontSize = 12.sp, lineHeight = 17.sp, color = ink)
    }
}

@Composable
internal fun PanelLabel(text: String, dark: Boolean) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Muted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
internal fun StepRow(index: String, text: String, dark: Boolean) {
    val ink = Ink
    val accent = Accent
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (dark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(index, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = accent)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = ink)
    }
}

/**
 * First run, and the standing explanation for "I turned it on and nothing happened".
 *
 * The three states look identical to a user but need completely different actions, so the screen
 * says which one it is before saying anything else.
 */
@Composable
fun OnboardingPanel(
    dark: Boolean,
    profile: DeviceProfile,
    onRecheck: () -> Unit,
    onOpenDevice: () -> Unit,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val ready = profile.supported
    val started = profile.probed

    val title = when {
        ready -> "已经就绪"
        started -> "这台机器还没适配"
        else -> "先让模块跑起来"
    }
    val subtitle = when {
        ready -> "检测全部通过，可以开始调音了。"
        started -> "模块在系统里运行，但音频链路和已验证的机型不同。"
        else -> "App 只是控制面板，真正处理声音的部分在系统进程里。"
    }

    PanelScaffold(
        dark = dark,
        title = title,
        subtitle = subtitle,
        onBack = onDismiss,
        actions = {
            when {
                ready -> PanelButton("开始使用", dark, true, Modifier.weight(1f), onDismiss)
                started -> {
                    PanelButton("知道了", dark, false, Modifier.weight(1f), onDismiss)
                    PanelButton("查看适配详情", dark, true, Modifier.weight(1f), onOpenDevice)
                }
                else -> {
                    PanelButton("稍后再说", dark, false, Modifier.weight(1f), onDismiss)
                    PanelButton("重新检测", dark, true, Modifier.weight(1f), onRecheck)
                }
            }
        }
    ) {
        when {
            ready -> {
                PanelCard(dark) {
                    Text("接下来", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "去「音效」页挑一个预设。播放任何音频时曲线会自动挂到那条会话上，" +
                            "暂停、切歌都不会残留，恢复播放会自己接上。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = muted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "想确认真的生效了，去「诊断」页放一段测试音：听到一强一弱就是通了。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = muted
                    )
                }
            }

            started -> {
                PanelCard(dark) {
                    Text(
                        "自动检测停在「" + profile.stageLabel() + "」",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ink
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "开关可以打开，界面也会正常响应，但这台机器的音频链路上不会有声音变化——" +
                            "所以不会假装它生效了。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = muted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "机型和完整链路档案已经自动发回。作者拿到之后才能做针对性适配。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = muted
                    )
                }
            }

            else -> {
                PanelCard(dark) {
                    Text("三步，缺一不可", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                    Spacer(Modifier.height(10.dp))
                    StepRow("1", "在 LSPosed 里启用 AURORA", dark)
                    StepRow("2", "作用域勾选「系统」", dark)
                    StepRow("3", "重启手机", dark)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "只做前两步不重启，模块不会进入系统进程。重启后回到这里点「重新检测」。",
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = muted
                    )
                }
                PanelCard(dark) {
                    Text(
                        "如果三步都做了还是这样，多半是系统把模块拦住了。点下面的按钮看检测停在哪一步。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = muted
                    )
                }
            }
        }
    }
}

/** Everything the boot probe found, in one place. Read-only by design. */
@Composable
fun DevicePanel(
    dark: Boolean,
    profile: DeviceProfile,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted

    val effects = remember(profile.effects) { splitItems(profile.effects) }
    val outputs = remember(profile.outputs) { splitItems(profile.outputs) }
    val probedAt = remember(profile.probedAt) { formatTime(profile.probedAt) }

    PanelScaffold(
        dark = dark,
        title = "设备适配",
        subtitle = if (profile.probed) {
            "模块启动时在系统进程里做的自检，整理自上次开机。"
        } else {
            "还没有检测记录——模块没有在系统里运行过。"
        },
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
        }
    ) {
        PanelCard(dark) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (profile.supported) OkColor else WarnColor)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    if (profile.supported) "链路可访问，可以正常使用" else profile.stageLabel(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink
                )
            }
            if (!profile.supported && profile.reason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(profile.reason, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
            }
        }

        PanelLabel("设备", dark)
        PanelCard(dark) {
            KeyValueRow("机型", profile.model, dark)
            KeyValueRow("SoC", profile.soc, dark)
            KeyValueRow("SDK", profile.sdk, dark)
            KeyValueRow("系统", profile.miui, dark)
        }

        if (effects.isNotEmpty()) {
            PanelLabel("全局效果器 " + effects.size + " 个", dark)
            PanelCard(dark) {
                effects.forEach { line ->
                    val parts = line.split('|')
                    val name = parts.getOrNull(0).orEmpty()
                    val implementor = parts.getOrNull(3).orEmpty()
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            name.ifBlank { "未命名" },
                            fontSize = 12.sp,
                            color = ink,
                            modifier = Modifier.weight(1f)
                        )
                        if (implementor.isNotBlank()) {
                            Text(implementor, fontSize = 11.sp, color = muted)
                        }
                    }
                }
            }
        }

        if (outputs.isNotEmpty()) {
            PanelLabel("输出设备 " + outputs.size + " 个", dark)
            PanelCard(dark) {
                outputs.forEach { line ->
                    val parts = line.split('|')
                    Text(
                        parts.getOrNull(1).orEmpty().ifBlank { parts.getOrNull(0).orEmpty() },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = ink,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        if (profile.route.isNotBlank()) {
            PanelLabel("输出参数", dark)
            PanelCard(dark) {
                Text(profile.route, fontSize = 12.sp, lineHeight = 18.sp, color = ink)
            }
        }

        if (profile.stages.isNotBlank() || profile.backendState.isNotBlank()) {
            PanelLabel("处理链", dark)
            PanelCard(dark) {
                KeyValueRow("后端状态", profile.backendState.ifBlank { "—" }, dark)
                // "bands=6/6 limiter=ok mbc=skip loudness=fail:..." - this is where a device whose
                // limiter write silently failed stops hiding.
                KeyValueRow("生效阶段", formatStages(profile.stages).ifBlank { "未上报" }, dark)
                if (profile.backendMessage.isNotBlank()) {
                    Text(
                        profile.backendMessage,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = muted,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        PanelLabel("记录", dark)
        PanelCard(dark) {
            KeyValueRow("检测时间", probedAt, dark)
            KeyValueRow(
                "引擎版本",
                if (profile.engineVersion > 0) profile.engineVersion.toString() else "未记录",
                dark
            )
        }
    }
}

/**
 * Turns the engine's stage string into one readable line.
 *
 * <p>The key names are what the engine and the feedback payload use, so they stay as they are in
 * the raw string; this only renames them for the screen. An unknown key is passed through rather
 * than dropped - a stage added in a later build should still show up on an older UI.
 */
internal fun formatStages(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.trim().split(' ').mapNotNull { part ->
        val cut = part.indexOf('=')
        if (cut <= 0) return@mapNotNull part.takeIf { it.isNotBlank() }
        val name = when (part.substring(0, cut)) {
            "bands" -> "频段"
            "limiter" -> "限幅器"
            "mbc" -> "低频压缩"
            "loudness" -> "响度"
            "verify" -> "回读"
            "short" -> "段数不足"
            "arch" -> "架构"
            "config" -> "高级配置"
            else -> part.substring(0, cut)
        }
        val value = part.substring(cut + 1)
        val shown = when {
            value == "ok" -> "正常"
            value == "skip" -> "跳过"
            value == "stock" -> "平台默认"
            value == "no_builder" -> "平台不支持"
            value.startsWith("kept:") -> "设备只给 " + value.removePrefix("kept:") + " 段"
            value.startsWith("fallback:") -> "退回默认（" + value.removePrefix("fallback:") + "）"
            value.matches(Regex("\\d+x\\d+ms")) -> {
                val (bands, ms) = value.split('x')
                bands + " 段 / " + ms.removeSuffix("ms") + " ms"
            }
            value.startsWith("fail:") -> "失败（" + value.removePrefix("fail:") + "）"
            value.startsWith("mismatch:") -> "不一致（" + value.removePrefix("mismatch:") + "）"
            else -> value
        }
        if (part.startsWith("bands=") || part.startsWith("short=") || part.startsWith("arch=")) {
            "$name $shown"
        } else {
            "$name：$shown"
        }
    }.joinToString("　")
}

private fun splitItems(raw: String): List<String> =
    raw.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("error") }

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}