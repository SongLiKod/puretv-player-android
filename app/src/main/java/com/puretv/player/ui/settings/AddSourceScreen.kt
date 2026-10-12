package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.isPortrait

/**
 * 在线搜索添加线路（设置 → 源管理 → 在线搜索线路）。
 *
 * 交互（按用户要求）：关键词搜索 → 默认收满 10 条自动停止、搜索中可手动停止 →
 * 推荐关键词（MacCMS 采集协议特征词）一点即搜 → 结果多选 → 批量添加，
 * 添加前自动探测 `api.php/provide/vod` 采集端点，仅可用线路入库。
 */
@Composable
fun AddSourceScreen(
    nav: NavHostController,
    viewModel: AddSourceViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val addProgress by viewModel.addProgress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val portrait = isPortrait()

    TvScaffold(
        title = "在线搜索线路",
        subtitle = "搜索全网 MacCMS 采集站，自动探测可用性，多选批量添加",
        onBack = { nav.popBackStack() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (portrait) 16.dp else 36.dp,
                    vertical = if (portrait) 14.dp else 20.dp,
                ),
        ) {
            // ---------------- 搜索框 ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    enabled = !searching && !adding,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "如：苹果CMS采集接口",
                                    color = colors.textHint,
                                    fontSize = 15.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                TvButton(
                    text = "搜索",
                    onClick = { viewModel.search(query) },
                    enabled = query.isNotBlank() && !searching && !adding,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            // ---------------- 推荐关键词 ----------------
            Text(
                text = "推荐关键词（MacCMS 采集协议特征词）",
                style = MaterialTheme.typography.labelMedium.scaled(),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.recommendedKeywords) { word ->
                    val active = word == query
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (active) colors.primary.copy(alpha = 0.18f) else colors.card)
                            .border(1.dp, if (active) colors.primary else colors.divider, RoundedCornerShape(15.dp))
                            .clickable {
                                query = word
                                viewModel.search(word)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = word,
                            color = if (active) colors.highlight else colors.textSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            // ---------------- 状态行 / 操作提示 ----------------
            val statusText = addProgress ?: status
            if (statusText != null || searching || adding) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (adding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = colors.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = statusText ?: "正在搜索…",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = if (searching || adding) colors.highlight else colors.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (adding) 8.dp else 0.dp),
                    )
                    if (searching) {
                        TvButton(
                            text = "停止",
                            onClick = { viewModel.stopSearch() },
                            variant = TvButtonVariant.GHOST,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.success,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            // ---------------- 结果多选列表 ----------------
            if (results.isEmpty()) {
                if (!searching) {
                    Text(
                        text = "输入关键词或点选上方推荐词开始搜索；结果经可用性探测后批量添加为点播线路。",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = colors.textHint,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "搜索结果 ${results.size} 条 · 已选 ${selected.size} 条",
                        style = MaterialTheme.typography.bodyMedium.scaled(),
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected.size == results.size) {
                        TvButton(
                            text = "取消全选",
                            onClick = { viewModel.clearSelection() },
                            variant = TvButtonVariant.GHOST,
                        )
                    } else {
                        TvButton(
                            text = "全选",
                            onClick = { viewModel.selectAll() },
                            variant = TvButtonVariant.GHOST,
                        )
                    }
                    TvButton(
                        text = "清空",
                        onClick = { viewModel.clearResults() },
                        variant = TvButtonVariant.GHOST,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                results.forEach { url ->
                    SearchResultRow(
                        url = url,
                        checked = url in selected,
                        onClick = { viewModel.toggle(url) },
                    )
                }

                // ---------------- 批量添加 ----------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvButton(
                        text = "批量添加已选（${selected.size}）",
                        onClick = { viewModel.addSelected() },
                        enabled = selected.isNotEmpty() && !adding && !searching,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 搜索结果行：圆形勾选框 + 地址 + 主机名 */
@Composable
private fun SearchResultRow(
    url: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val host = remember(url) {
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
    }
    TvCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) colors.primary else Color.Transparent)
                    .border(2.dp, if (checked) colors.primary else colors.divider, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium.scaled(),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
