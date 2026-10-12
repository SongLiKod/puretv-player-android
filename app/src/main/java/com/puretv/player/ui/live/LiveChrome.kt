package com.puretv.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.ui.ConfirmDialog
import com.puretv.player.core.ui.LocalPaneBottomPadding
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.ui.tvFocusHighlight
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.SourceEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 直播页通用构件（需求 5.2 UI交互 + 5.3 直播IPTV）：
 * 页头（源选择/搜索/四分屏）、分组栏（左右键切换）、频道列表、源管理弹窗、搜索弹窗。
 */

/* ------------------------------- 页头 ------------------------------- */

@Composable
fun LiveHeader(
    sourceName: String,
    hasSource: Boolean,
    onSourceClick: () -> Unit,
    onSearchClick: () -> Unit,
    onMosaicClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "直播",
            color = colors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(modifier = Modifier.width(16.dp))
        TvButton(
            text = "源 · $sourceName",
            onClick = onSourceClick,
            variant = TvButtonVariant.SECONDARY,
        )
        Box(modifier = Modifier.width(16.dp))
        TvButton(
            text = "搜索",
            onClick = onSearchClick,
            variant = TvButtonVariant.SECONDARY,
            enabled = hasSource,
        )
        Box(modifier = Modifier.width(16.dp))
        TvButton(
            text = "四分屏",
            onClick = onMosaicClick,
            variant = TvButtonVariant.SECONDARY,
            enabled = hasSource,
        )
    }
}

/* ------------------------------- 分组栏 ------------------------------- */

/** 左侧竖排分组栏：全部 / 收藏（置顶）/ 各分组 */
@Composable
fun GroupRail(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    LazyColumn(modifier = modifier) {
        items(groups, key = { it }) { group ->
            val isSelected = group == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .tvFocusHighlight(shape = RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.primary.copy(alpha = 0.18f) else colors.card)
                    .clickable { onSelect(group) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = group,
                    color = if (isSelected) colors.primary else colors.textSecondary,
                    fontSize = 17.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/* ------------------------------- 频道列表 ------------------------------- */

/** 右侧频道列表：OK 播放，菜单/信息键弹节目单，星标切换收藏 */
@Composable
fun ChannelList(
    channels: List<ChannelEntity>,
    viewModel: LiveViewModel,
    favoriteKeys: Set<String>,
    onPlay: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onOpenGuide: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分钟级时间轴：驱动 EPG 当前/下个节目刷新
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = LocalPaneBottomPadding.current),
        modifier = modifier.fillMaxSize(),
    ) {
        items(channels, key = { "${it.sourceId}|${it.id}" }) { channel ->
            ChannelRow(
                channel = channel,
                isFavorite = "${channel.sourceId}|${channel.id}" in favoriteKeys,
                viewModel = viewModel,
                now = now,
                onPlay = { onPlay(channel) },
                onToggleFavorite = { onToggleFavorite(channel) },
                onOpenGuide = { onOpenGuide(channel) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/* ------------------------------- 源管理弹窗 ------------------------------- */

/** 源选择 + 导入（M3U 网址 / Xtream）+ 刷新 / EPG 同步 / 删除 */
@Composable
fun SourceManageDialog(
    viewModel: LiveViewModel,
    sources: List<SourceEntity>,
    selectedSourceId: String?,
    onDismiss: () -> Unit,
) {
    val colors = LocalPureTvColors.current
    var showM3uImport by remember { mutableStateOf(false) }
    var showXtreamImport by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<SourceEntity?>(null) }

    TvDialog(onDismiss = onDismiss, title = "直播源管理", maxWidth = 760.dp) {
        if (sources.isEmpty()) {
            Text(
                text = "尚未导入直播源，选择导入方式：",
                color = colors.textSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        sources.forEach { source ->
            val selected = source.id == selectedSourceId
            TvCard(
                onClick = { viewModel.selectSource(source.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name + if (selected) "（当前）" else "",
                            color = if (selected) colors.primary else colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${if (source.type == "xtream") "Xtream" else "M3U"} · ${source.channelCount} 频道",
                            color = colors.textTertiary,
                            fontSize = 14.sp,
                        )
                    }
                    TvTextButton(text = "刷新", onClick = { viewModel.refreshSource(source.id) })
                    if (!source.epgUrl.isNullOrBlank()) {
                        TvTextButton(text = "EPG", onClick = { viewModel.syncEpgNow(source) })
                    }
                    TvTextButton(text = "删除", color = colors.error, onClick = { confirmRemove = source })
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(text = "M3U 网址导入", onClick = { showM3uImport = true }, variant = TvButtonVariant.SECONDARY)
            TvButton(text = "Xtream 导入", onClick = { showXtreamImport = true }, variant = TvButtonVariant.SECONDARY)
            TvButton(text = "完成", onClick = onDismiss)
        }

        if (showM3uImport) {
            M3uImportDialog(
                onDone = { name, url, epg ->
                    showM3uImport = false
                    viewModel.importM3u(name, url, epg)
                },
                onDismiss = { showM3uImport = false },
            )
        }
        if (showXtreamImport) {
            XtreamImportDialog(
                onDone = { name, base, user, pass ->
                    showXtreamImport = false
                    viewModel.importXtream(name, base, user, pass)
                },
                onDismiss = { showXtreamImport = false },
            )
        }
        confirmRemove?.let { source ->
            ConfirmDialog(
                title = "删除源",
                message = "确定删除「${source.name}」及其全部频道吗？",
                danger = true,
                onConfirm = {
                    viewModel.removeSource(source.id)
                    confirmRemove = null
                },
                onDismiss = { confirmRemove = null },
            )
        }
    }
}

/** M3U 网址导入（名称 + 地址 + 可选 EPG） */
@Composable
private fun M3uImportDialog(onDone: (String, String, String?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    TvDialog(onDismiss = onDismiss, title = "导入 M3U 源") {
        InputRow("名称", name) { name = it }
        InputRow("M3U 地址", url) { url = it }
        InputRow("EPG 地址（可选）", epg) { epg = it }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(
                text = "开始导入",
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onDone(name.trim(), url.trim(), epg.trim().ifBlank { null }) },
            )
            TvButton(text = "取消", onClick = onDismiss, variant = TvButtonVariant.SECONDARY)
        }
    }
}

/** Xtream 导入（名称 + 服务器 + 账号密码） */
@Composable
private fun XtreamImportDialog(
    onDone: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    TvDialog(onDismiss = onDismiss, title = "导入 Xtream 源") {
        InputRow("名称", name) { name = it }
        InputRow("服务器地址（含端口）", base) { base = it }
        InputRow("用户名", user) { user = it }
        InputRow("密码", pass) { pass = it }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(
                text = "开始导入",
                enabled = name.isNotBlank() && base.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                onClick = { onDone(name.trim(), base.trim(), user.trim(), pass.trim()) },
            )
            TvButton(text = "取消", onClick = onDismiss, variant = TvButtonVariant.SECONDARY)
        }
    }
}

/** TvDialog 内嵌输入行（带标题 + 底部分隔线，主题色光标） */
@Composable
private fun InputRow(label: String, value: String, onChange: (String) -> Unit) {
    val colors = LocalPureTvColors.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, color = colors.textTertiary, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .width(1.dp)
                .background(colors.divider),
        )
    }
}

/* ------------------------------- 搜索弹窗 ------------------------------- */

/** 频道搜索：输入即搜（Room Flow），OK 直接播放 */
@Composable
fun LiveSearchDialog(
    viewModel: LiveViewModel,
    onPlay: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPureTvColors.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 输入变化即触发搜索（大列表毫秒级响应）
        snapshotFlow { query }.collectLatest { q ->
            viewModel.search(q).collect { results = it }
        }
    }

    TvDialog(onDismiss = onDismiss, title = "搜索频道", maxWidth = 760.dp) {
        InputRow(label = "频道名称", value = query, onChange = { query = it })
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
            items(results.take(50), key = { "${it.sourceId}|${it.id}" }) { channel ->
                TvCard(
                    onClick = { onPlay(channel) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = channel.name,
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        QualityBadge(quality = channel.quality)
                    }
                }
            }
        }
        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                text = "未找到「$query」相关频道",
                color = colors.textTertiary,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
