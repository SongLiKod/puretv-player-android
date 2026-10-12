package com.puretv.player.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.isTvDevice
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.ConfirmDialog
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvLinearProgress
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.entity.WatchHistoryEntity

/**
 * 播放记录（需求 5.2.4、5.4.3）：
 * 断点续播（OK 从上次进度继续）、未看完集数标记、按类型分组、删除/清空。
 */
@Composable
fun HistoryScreen(
    nav: NavHostController,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val tab by viewModel.currentTabFlow.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val openPlayback by viewModel.openPlayback.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf<WatchHistoryEntity?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(openPlayback) {
        if (openPlayback) {
            viewModel.consumeOpenPlayback()
            nav.navigate(Routes.PLAYBACK)
        }
    }

    TvScaffold(
        title = "播放记录",
        subtitle = "OK 从上次进度继续播放",
        onBack = { nav.popBackStack() },
        actions = {
            TvTextButton(
                text = "清空记录",
                color = colors.error,
                onClick = { confirmClearAll = true },
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HistoryTabs.ALL_TABS.forEach { (key, label) ->
                TvButton(
                    text = label,
                    onClick = { viewModel.switchTab(key) },
                    variant = if (tab == key) TvButtonVariant.PRIMARY else TvButtonVariant.SECONDARY,
                )
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                text = "暂无播放记录",
                subtext = "看过的直播和影视会自动出现在这里",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    HistoryRow(
                        item = item,
                        onResume = { viewModel.resume(item) },
                        onRemove = { confirmClear = item },
                    )
                }
            }
        }
    }

    confirmClear?.let { item ->
        ConfirmDialog(
            title = "删除记录",
            message = "确定删除「${item.title}」的播放记录吗？",
            danger = true,
            onConfirm = {
                viewModel.remove(item)
                confirmClear = null
            },
            onDismiss = { confirmClear = null },
        )
    }
    if (confirmClearAll) {
        ConfirmDialog(
            title = "清空播放记录",
            message = "将删除全部播放记录，确定继续吗？",
            danger = true,
            onConfirm = {
                viewModel.clear()
                confirmClearAll = false
            },
            onDismiss = { confirmClearAll = false },
        )
    }
}

/** 记录条目：缩略图 + 标题 + 集数标记 + 进度条 + 未看完徽标 */
@Composable
private fun HistoryRow(
    item: WatchHistoryEntity,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = PureTvTheme.colors
    // 手机触摸无焦点：删除按钮常显；TV 保留焦点进入时显示
    val isTv = isTvDevice()
    var focused by remember { mutableStateOf(false) }
    val percent = if (item.durationMs > 0) {
        ((item.positionMs * 100 / item.durationMs).toInt()).coerceIn(0, 100)
    } else 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
    ) {
        TvCard(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 120.dp, height = 68.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 标题过长时单行省略：不挤压右侧集数标记与「未看完」徽标（避免逐字换行）
                        Text(
                            text = item.title,
                            color = colors.textPrimary,
                            fontSize = 20.scaled(),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        item.episodeLabel?.let { label ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                color = colors.textSecondary,
                                fontSize = 14.scaled(),
                                maxLines = 1,
                            )
                        }
                        if (!item.isFinished && item.durationMs > 0) {
                            Spacer(Modifier.width(6.dp))
                            // 未看完集数标记（需求 5.4.3；紧凑样式：小圆角 + 小字）
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.warning.copy(alpha = 0.18f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "未看完",
                                    color = colors.warning,
                                    fontSize = 11.scaled(),
                                )
                            }
                        }
                    }
                    if (item.durationMs > 0) {
                        Spacer(Modifier.height(8.dp))
                        TvLinearProgress(percent = percent)
                        Text(
                            text = "${TimeUtils.formatDuration(item.positionMs)} / " +
                                TimeUtils.formatDuration(item.durationMs) + " · 已看 $percent%",
                            color = colors.textTertiary,
                            fontSize = 14.scaled(),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (focused || !isTv) {
                    RowIconAction(
                        icon = Icons.Filled.Delete,
                        tint = colors.error,
                        contentDescription = "删除",
                        onClick = onRemove,
                    )
                }
            }
        }
    }
}

/** 行内小图标操作按钮（删除等；小图标省空间，保留 40dp 触控目标） */
@Composable
private fun RowIconAction(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
