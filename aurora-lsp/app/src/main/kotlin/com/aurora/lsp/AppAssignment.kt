package com.aurora.lsp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** One installed app, as the assignment list needs it. */
internal data class AppEntry(val packageName: String, val label: String)

/**
 * Presets and per-app assignment, on one screen.
 *
 * <p>They belong together: the list of things an app can be pinned to is exactly the list of
 * presets, so splitting them into two screens would mean maintaining the same taxonomy twice.
 */
@Composable
fun PresetPanel(
    dark: Boolean,
    state: AuroraState,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val surface = MiuixTheme.colorScheme.surface
    val card = MiuixTheme.colorScheme.surfaceContainer
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<AuroraState.UserPreset?>(null) }
    var draftName by remember { mutableStateOf("") }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onDismiss()
        } finally {
            backProgress = 0f
        }
    }

    // Same entrance as every other second-level screen: fade up and settle. Without it this
    // panel appeared instantly while its siblings animated, which is exactly the inconsistency
    // people notice.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }

    // Built off the main thread: a few hundred apps, each with a label lookup, is enough to
    // freeze the panel on the frame it opens.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    val visible = remember(query, apps) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
    }

    Box(
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
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp)) {
                Text("预设与分配", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "自己保存的曲线可以随时改名；指定了应用之后，那个应用播放时永远用它，不受全局预设影响。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = muted
                )
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 22.dp, end = 22.dp, top = 4.dp, bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    PanelLabel("我的预设 " + state.userPresets.size + " 套", dark)
                }
                if (state.userPresets.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(card).padding(16.dp)
                        ) {
                            Text(
                                "还没有自建预设。去「均衡」页拖一条曲线，点保存并起个名字，它就会出现在这里。",
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = muted
                            )
                        }
                    }
                } else {
                    items(state.userPresets, key = { it.id }) { preset ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(card).padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (renaming?.id == preset.id) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = draftName,
                                        onValueChange = { if (it.length <= 24) draftName = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 15.sp, color = ink
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "保存",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accent,
                                        modifier = Modifier
                                            .clickable {
                                                val target = preset.id
                                                val name = draftName
                                                renaming = null
                                                scope.launch { state.renamePreset(target, name) }
                                            }
                                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(preset.name, fontSize = 15.sp, color = ink)
                                        Text(
                                            bandSummary(preset.curve),
                                            fontSize = 11.sp,
                                            color = muted
                                        )
                                    }
                                    Text(
                                        "重命名",
                                        fontSize = 12.sp,
                                        color = accent,
                                        modifier = Modifier
                                            .clickable {
                                                renaming = preset
                                                draftName = preset.name
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                    Text(
                                        "删除",
                                        fontSize = 12.sp,
                                        color = WarnColor,
                                        modifier = Modifier
                                            .clickable {
                                                scope.launch { state.deletePreset(preset.id) }
                                            }
                                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    PanelLabel("按应用分配 " + state.appMap.size + " 个", dark)
                }

                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(card).padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp, color = ink
                            ),
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text("搜索应用", fontSize = 14.sp, color = muted)
                                }
                                inner()
                            }
                        )
                    }
                }

                items(visible, key = { it.packageName }) { app ->
                    val assigned = state.appMap[app.packageName]
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(card).padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                expanded = if (expanded == app.packageName) null else app.packageName
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 14.sp, color = ink)
                                Text(
                                    presetLabel(assigned, state),
                                    fontSize = 11.sp,
                                    color = if (assigned == null) muted else accent
                                )
                            }
                            Text(
                                if (expanded == app.packageName) "收起" else "选择",
                                fontSize = 12.sp,
                                color = muted
                            )
                        }
                        if (expanded == app.packageName) {
                            Spacer(Modifier.height(8.dp))
                            PresetChoice(
                                state = state,
                                selected = assigned,
                                dark = dark,
                                onPick = { id ->
                                    expanded = null
                                    scope.launch { state.assignApp(app.packageName, id) }
                                }
                            )
                        }
                    }
                }
            }

            state.error?.let { message ->
                Text(
                    message,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = WarnColor,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
            }
        }
    }
}

/** The picker shown under an app row: built-in presets first, then the user's own. */
@Composable
private fun PresetChoice(
    state: AuroraState,
    selected: String?,
    dark: Boolean,
    onPick: (String?) -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent

    Column {
        ChoiceRow("跟随全局预设", selected == null, ink, muted, accent) { onPick(null) }
        Presets.KEYS.forEachIndexed { index, key ->
            ChoiceRow(Presets.NAMES[index], selected == key, ink, muted, accent) { onPick(key) }
        }
        state.userPresets.forEach { preset ->
            ChoiceRow(preset.name, selected == preset.id, ink, muted, accent) { onPick(preset.id) }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    ink: Color,
    muted: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (selected) accent else muted.copy(alpha = 0.3f))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = if (selected) accent else ink)
    }
}

internal fun presetLabel(id: String?, state: AuroraState): String {
    if (id == null) return "跟随全局预设"
    if (Presets.hasKey(id)) return Presets.NAMES[Presets.indexOf(id)]
    return state.userPresets.firstOrNull { it.id == id }?.name ?: "预设已删除"
}

