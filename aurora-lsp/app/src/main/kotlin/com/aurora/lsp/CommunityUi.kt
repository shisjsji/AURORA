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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Round identity mark for one avatar key. Quiet by default: the accent stays for selection. */
@Composable
internal fun AvatarBadge(avatar: String, size: Dp = 28.dp, highlighted: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val tint = if (highlighted) colors.primary else colors.onSurfaceVariantSummary
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Avatars.icon(avatar),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.56f)
        )
    }
}

/**
 * One shared preset in a list.
 *
 * <p>Anatomy follows the page rhythm: the signature glyph says what the curve does, the name and
 * author row says whose it is, and the device model sits on its own line below the content,
 * right-aligned - a shared curve is only meaningful when you can see which machine it was tuned
 * on. Counts are icon plus number, the same way everywhere in the app.
 */
@Composable
internal fun CommunityCard(
    item: Community.Item,
    onOpen: () -> Unit,
    onLike: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariantSummary
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.card, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EqGlyph(
                curve = item.curve,
                modifier = Modifier.width(50.dp),
                color = colors.primary,
                height = 34.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarBadge(item.avatar, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(item.nickname, fontSize = 11.sp, color = muted, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    // A 14dp icon inside a 36dp hit box: the icon stays small, the finger gets
                    // something it can actually hit.
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onLike),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.liked) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                            contentDescription = "喜欢",
                            tint = if (item.liked) colors.primary else muted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        item.likes.toString(),
                        fontSize = 11.sp,
                        color = if (item.liked) colors.primary else muted
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        imageVector = MiuixIcons.Messages,
                        contentDescription = "评论",
                        tint = muted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(item.comments.toString(), fontSize = 11.sp, color = muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(item.deviceLabel, fontSize = 10.sp, color = muted)
        }
    }
}