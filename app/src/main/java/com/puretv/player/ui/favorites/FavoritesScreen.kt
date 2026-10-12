package com.puretv.player.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.ConfirmDialog
import com.puretv.player.core.ui.isTvDevice
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.SectionHeader
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.data.local.entity.FavoriteEntity

/**
 * 我的收藏（需求 5.2.4）：直播/影视/本地三类收藏，
 * 支持置顶排序（上移/下移）、取消收藏、清空、OK 直接播放。
 */
@Composable
fun FavoritesScreen(
    nav: NavHostController,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val tab by viewModel.currentTab.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf<FavoriteEntity?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    TvScaffold(
        title = "我的收藏",
        subtitle = "长列表按置顶排序 · OK 播放",
        onBack = { nav.popBackStack() },
        actions = {
            TvTextButton(
                text = "清空收藏",
                color = colors.error,
                onClick = { confirmClearAll = true },
            )
        },
    ) {
        // 类型 Tab（左右键切换）
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FavoriteTabs.ALL.forEach { (type, label) ->
                TabChip(
                    text = label,
                    selected = tab == type,
                    onClick = { viewModel.switchTab(type) },
                )
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                text = "暂无收藏",
                subtext = "在直播频道或影视详情页按「收藏」即可加入这里",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { "${it.targetType}|${it.targetId}" }) { item ->
                    FavoriteRow(
                        item = item,
                        onPlay = { viewModel.play(item) },
                        onUp = { viewModel.move(item, -1) },
                        onDown = { viewModel.move(item, 1) },
                        onRemove = { confirmClear = item },
                    )
                }
            }
        }
    }

    confirmClear?.let { item ->
        ConfirmDialog(
            title = "取消收藏",
            message = "确定将「${item.title}」移出收藏吗？",
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
            title = "清空收藏",
            message = "将删除全部收藏记录，确定继续吗？",
            danger = true,
            onConfirm = {
                viewModel.clearAll()
                confirmClearAll = false
            },
            onDismiss = { confirmClearAll = false },
        )
    }
}

/** 类型 Tab 芯片（统一焦点） */
@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    TvButton(
        text = text,
        onClick = onClick,
        variant = if (selected) TvButtonVariant.PRIMARY else TvButtonVariant.SECONDARY,
    )
}

/** 收藏条目：缩略图 + 标题 + 排序/删除操作（焦点进入时显示操作区） */
@Composable
private fun FavoriteRow(
    item: FavoriteEntity,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = PureTvTheme.colors
    // 手机触摸无焦点：操作按钮常显；TV 保留焦点进入时显示
    val isTv = isTvDevice()
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
    ) {
        TvCard(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                Text(
                    text = item.title,
                    color = colors.textPrimary,
                    fontSize = 20.scaled(),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when (item.targetType) {
                        "channel" -> "直播频道"
                        "local" -> "本地视频"
                        else -> "影视"
                    },
                    color = colors.textTertiary,
                    fontSize = 14.scaled(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (focused || !isTv) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    RowIconAction(
                        icon = Icons.Filled.ArrowUpward,
                        tint = colors.textSecondary,
                        contentDescription = "上移",
                        onClick = onUp,
                    )
                    RowIconAction(
                        icon = Icons.Filled.ArrowDownward,
                        tint = colors.textSecondary,
                        contentDescription = "下移",
                        onClick = onDown,
                    )
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
}

/** 行内小图标操作按钮（排序/删除；小图标省空间，保留 40dp 触控目标） */
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
