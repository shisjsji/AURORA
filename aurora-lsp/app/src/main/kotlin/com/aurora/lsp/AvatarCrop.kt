package com.aurora.lsp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** The avatar the server stores: square, 512 px, JPEG. */
private const val AVATAR_OUTPUT = 512

/**
 * Decodes a picked picture at a sane size and with the rotation already applied.
 *
 * <p>[ImageDecoder] is used instead of BitmapFactory precisely because it honours the EXIF
 * orientation: a photo taken in portrait would otherwise arrive sideways and stay sideways.
 */
internal suspend fun decodeForCrop(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val shortest = minOf(info.size.width, info.size.height)
            // 1600 px on the short side is plenty: the output is 512.
            if (shortest > 1600) decoder.setTargetSampleSize(shortest / 1600)
        }
    } catch (t: Throwable) {
        null
    }
}

/**
 * Circular crop: drag to move, pinch to zoom, the circle is what you get.
 *
 * <p>No library does this here, so the maths is explicit: the preview applies a graphics layer,
 * and [renderAvatar] replays the same transform onto a 512×512 canvas. That is why the preview and
 * the result agree - they are the same three numbers (fit, scale, offset).
 */
@Composable
internal fun AvatarCropPanel(
    source: Bitmap,
    dark: Boolean,
    onCancel: () -> Unit,
    onUse: (ByteArray) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val muted = colors.onSurfaceVariantSummary
    val ink = colors.onSurface

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxPx by remember { mutableFloatStateOf(0f) }
    val boxDp = 300.dp

    fun clamp(candidate: Offset, currentScale: Float): Offset {
        if (boxPx <= 0f) return candidate
        val fit = max(boxPx / source.width, boxPx / source.height)
        val slackX = (source.width * fit * currentScale - boxPx) / 2f
        val slackY = (source.height * fit * currentScale - boxPx) / 2f
        return Offset(
            candidate.x.coerceIn(-slackX, slackX),
            candidate.y.coerceIn(-slackY, slackY)
        )
    }

    PanelScaffold(
        dark = dark,
        title = "裁头像",
        subtitle = "拖动调整位置，双指缩放。圆里的部分就是别人看到的头像。",
        onBack = onCancel,
        actions = {
            PanelButton("取消", dark, false, Modifier.weight(1f), onCancel)
            PanelButton(
                label = "使用",
                dark = dark,
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    val bytes = renderAvatar(source, boxPx, scale, offset.x, offset.y)
                    if (bytes != null) onUse(bytes)
                }
            )
        }
    ) {
        PanelCard(dark) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(boxDp)
                        .onSizeChanged { boxPx = it.width.toFloat() }
                        .clip(CircleShape)
                        .background(colors.surfaceContainer)
                        .border(1.dp, colors.dividerLine, CircleShape)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = clamp(offset + pan, scale)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "原图 " + source.width + "×" + source.height + "，输出 " + AVATAR_OUTPUT + "×" + AVATAR_OUTPUT,
                    fontSize = 11.sp,
                    color = muted
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "上传到社区后，任何登录的人都能看到这张图。",
                    fontSize = 11.sp,
                    color = ink.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Replays the preview transform onto a square canvas and encodes it.
 *
 * <p>Returns null when the preview never got a size - there is nothing honest to upload then.
 */
private fun renderAvatar(source: Bitmap, boxPx: Float, scale: Float, tx: Float, ty: Float): ByteArray? {
    if (boxPx <= 0f) return null
    val fit = max(boxPx / source.width, boxPx / source.height)
    val total = fit * scale
    val drawnWidth = source.width * total
    val drawnHeight = source.height * total
    val left = (boxPx - drawnWidth) / 2f + tx
    val top = (boxPx - drawnHeight) / 2f + ty
    val k = AVATAR_OUTPUT / boxPx

    val output = Bitmap.createBitmap(AVATAR_OUTPUT, AVATAR_OUTPUT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(android.graphics.Color.BLACK)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val destination = RectF(
        left * k,
        top * k,
        (left + drawnWidth) * k,
        (top + drawnHeight) * k
    )
    canvas.drawBitmap(source, null, destination, paint)
    val stream = ByteArrayOutputStream()
    output.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    output.recycle()
    return stream.toByteArray()
}