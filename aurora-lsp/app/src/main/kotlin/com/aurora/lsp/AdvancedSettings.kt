package com.aurora.lsp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The engine controls that are not part of the curve: stereo width, perceived loudness, loudness
 * maximisation, dialog focus and the loudness guard.
 *
 * <p>Same anatomy as every other adjustable row in the app - label, one line of explanation, the
 * current value on the right, the slider underneath - so the equaliser page and this one read as
 * the same instrument rather than two different apps.
 */
@Composable
internal fun AdvancedSettingsPanel(
    state: AuroraState,
    scope: CoroutineScope
) {
    val colors = MiuixTheme.colorScheme
    val spread = rememberDraft(state.spread)
    val loudness = rememberDraft(state.loudnessDb)
    val lmh = rememberDraft(state.lmh.toFloat())

    Column(Modifier.fillMaxWidth()) {
        SliderRow(
            title = "声场宽度",
            value = spread.value,
            valueRange = 0f..8f,
            steps = 7,
            label = dbLabel(spread.value),
            onChange = { spread.value = it },
            onCommit = { scope.launch { state.updateAdvanced(spread = spread.value) } }
        )
        RowDivider()
        SliderRow(
            title = "感知响度",
            value = loudness.value,
            valueRange = 0f..10f,
            steps = 9,
            label = dbLabel(loudness.value),
            onChange = { loudness.value = it },
            onCommit = { scope.launch { state.updateAdvanced(loudnessDb = loudness.value) } }
        )
        RowDivider()
        SliderRow(
            title = "响度最大化",
            value = lmh.value,
            valueRange = 0f..100f,
            steps = 9,
            label = if (lmh.value < 1f) "关" else "${lmh.value.roundToInt()}%",
            onChange = { lmh.value = it },
            onCommit = { scope.launch { state.updateExpand(lmh = lmh.value.roundToInt()) } }
        )
        RowDivider()
        AuroraRow(
            title = "对话聚焦",
            leading = { Icon(MiuixIcons.Messages, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
            trailing = {
                Switch(
                    checked = state.dialog,
                    onCheckedChange = { value ->
                        scope.launch { state.updateExpand(dialog = value) }
                    }
                )
            }
        )
        RowDivider()
        AuroraRow(
            title = "响度守卫",
            leading = { Icon(MiuixIcons.Favorites, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
            trailing = {
                Switch(
                    checked = state.guard,
                    onCheckedChange = { value ->
                        scope.launch { state.updateExpand(guard = value) }
                    }
                )
            }
        )
    }
}

/** A value that edits locally and only reaches the engine when the finger lifts. */
@Composable
private fun rememberDraft(initial: Float) = androidx.compose.runtime.remember(initial) {
    androidx.compose.runtime.mutableFloatStateOf(initial)
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = Space.card, vertical = Space.rowV)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, color = colors.onSurface)
            Spacer(Modifier.width(12.dp))
            ResetAffordance(visible = abs(value) > 0.05f, description = "恢复默认 关") {
                onChange(0f)
                onCommit()
            }
            Spacer(Modifier.weight(1f))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = if (abs(value) < 0.05f) colors.onSurfaceVariantSummary else colors.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onCommit,
            height = 24.dp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun dbLabel(value: Float): String =
    if (abs(value) < 0.05f) "关" else String.format(Locale.US, "%.1f dB", value)