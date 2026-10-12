package com.puretv.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity

/** 清晰度徽标（SD/HD/FHD/4K） */
@Composable
fun QualityBadge(quality: String?, modifier: Modifier = Modifier) {
    val colors = LocalPureTvColors.current
    if (quality.isNullOrBlank()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.primary.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = quality,
            color = colors.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 频道列表项（需求 5.2 遥控器适配 + 5.3.3 频道展示）：
 * 频道号 + 台标 + 名称 + 清晰度徽标 + 收藏星标 + 当前/下个节目（EPG）。
 * OK 播放；菜单/信息键弹节目详情；左/右可在条目与收藏星之间移动焦点。
 */
@Composable
fun ChannelRow(
    channel: ChannelEntity,
    isFavorite: Boolean,
    viewModel: LiveViewModel,
    now: Long,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    // 当前/下个节目（EPG 实时节目单），随分钟级时间轴刷新
    val programInfo by produceState<Pair<EpgProgramEntity?, EpgProgramEntity?>>(
        initialValue = null to null,
        key1 = channel.epgId,
        key2 = now / 60_000L,
    ) {
        value = if (channel.epgId.isNullOrBlank()) null to null
        else viewModel.nowNext(channel.epgId)
    }
    val current = programInfo.first
    val next = programInfo.second

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu, Key.Info -> {
                        onOpenGuide()
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvCard(
            onClick = onPlay,
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channel.number?.let { String.format("%03d", it) } ?: "--",
                    color = colors.textTertiary,
                    fontSize = 15.sp,
                    modifier = Modifier.width(48.dp),
                )
                ChannelLogo(logo = channel.logo, name = channel.name)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.name,
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!channel.quality.isNullOrBlank()) {
                            QualityBadge(
                                quality = channel.quality,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    EpgLine(current = current, next = next, now = now)
                }
            }
        }
        TvTextButton(
            text = if (isFavorite) "★" else "☆",
            color = if (isFavorite) colors.warning else colors.textHint,
            onClick = onToggleFavorite,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 台标（Coil 异步加载，失败显示主题渐变首字占位） */
@Composable
private fun ChannelLogo(logo: String?, name: String) {
    val colors = LocalPureTvColors.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.20f),
                        colors.primary.copy(alpha = 0.06f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Text(
                text = name.take(1),
                color = colors.highlight,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 当前节目 + 下个节目（EPG 时间轴预览） */
@Composable
private fun EpgLine(
    current: EpgProgramEntity?,
    next: EpgProgramEntity?,
    now: Long,
) {
    val colors = LocalPureTvColors.current
    when {
        current != null -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "正在播出",
                    color = colors.success,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = current.title,
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                Text(
                    text = "${TimeUtils.formatClock(current.startAt)}-${TimeUtils.formatClock(current.endAt)}",
                    color = colors.textTertiary,
                    fontSize = 14.sp,
                )
                if (next != null) {
                    Text(
                        text = "  下一档：${next.title} ${TimeUtils.formatClock(next.startAt)}",
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        next != null -> Text(
            text = "下档 ${TimeUtils.formatClock(next.startAt)} ${next.title}",
            color = colors.textTertiary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        else -> Text(
            text = if (current == null && next == null) "暂无节目单" else "",
            color = colors.textHint,
            fontSize = 14.sp,
        )
    }
}
