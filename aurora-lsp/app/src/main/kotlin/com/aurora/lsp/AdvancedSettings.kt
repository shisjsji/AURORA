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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.abs

/**
 * The two engine controls that are not part of the curve: stereo width and perceived loudness.
 *
 * <p>Perceived loudness carries one line of explanation because it is the one control here whose
 * trade-off is not obvious from its name: the enhancer adds loudness by compressing dynamics.
 */
@Composable
internal fun AdvancedSettingsPanel(
    state: AuroraState,
    scope: CoroutineScope
) {
    val spread = rememberDraft(state.spread)
    val loudness = rememberDraft(state.loudnessDb)

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
            summary = "增强听感响度，会压缩动态",
            value = loudness.value,
            valueRange = 0f..10f,
            steps = 9,
            label = dbLabel(loudness.value),
            onChange = { loudness.value = it },
            onCommit = { scope.launch { state.updateAdvanced(loudnessDb = loudness.value) } }
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
    summary: String? = null,
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
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 15.sp, color = colors.onSurface)
                    Spacer(Modifier.width(12.dp))
                    ResetAffordance(visible = abs(value) > 0.05f, description = "恢复默认 关") {
                        onChange(0f)
                        onCommit()
                    }
                }
                if (summary != null) {
                    Text(
                        summary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.onSurfaceVariantSummary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
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