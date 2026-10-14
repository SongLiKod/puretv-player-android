package com.puretv.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.navigation.SettingsSections
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.ErrorState
import com.puretv.player.core.ui.InlineLoading
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.core.ui.LocalPaneBottomPadding
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.ui.TvLinearProgress
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.model.ImportProgress

/**
 * 影视点播（海报墙）：
 * - 需求 5.4.1 电影/电视剧/综艺/动漫全品类聚合 + Xtream 分类横滑
 * - 需求 5.2.5 海报墙沉浸式展示：海报、评分、年份，焦点 1.02 缩放 + 边框
 * - 搜索（本地缓存模糊匹配）、加载/空态/错误三态、同步进度可取消不卡死
 */
@Composable
fun VodScreen(nav: NavHostController) {
    val viewModel: VodViewModel = hiltViewModel()
    val colors = PureTvTheme.colors

    val items by viewModel.items.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedKind by viewModel.selectedKind.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchScopeAll by viewModel.searchScopeAll.collectAsStateWithLifecycle()
    val onlineSearching by viewModel.onlineSearching.collectAsStateWithLifecycle()
    val sourceNames by viewModel.sourceNames.collectAsStateWithLifecycle()
    val refreshProgress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val hasSources by viewModel.hasSources.collectAsStateWithLifecycle()
    val chartEnabled by viewModel.chartEnabled.collectAsStateWithLifecycle()
    val charts by viewModel.charts.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()

    var showSearchDialog by remember { mutableStateOf(false) }
    // 榜单订阅（资源插件 source_chart）：安装启用后标题栏开放榜单入口
    var showCharts by remember { mutableStateOf(false) }

    val isRefreshing = refreshProgress != null
    // 同步失败信息随进度透传（仓库在失败节点发出 FAILED 进度，完成即清空）
    val syncMessage = refreshProgress?.takeIf { it.stage == ImportProgress.Stage.FAILED }?.message
    val searchActive = !searchQuery.isNullOrBlank()
    val chartActive = showCharts && !searchActive

    val portrait = isPortrait()

    // 海报网格滚动状态上提：同步前后共享同一状态，加载完成回到页面顶部（用户反馈）
    val gridState = rememberLazyGridState()
    var wasRefreshing by remember { mutableStateOf(isRefreshing) }
    LaunchedEffect(isRefreshing) {
        if (wasRefreshing && !isRefreshing) {
            // 同步完成：回到页面顶部，免去手动上滑
            runCatching { gridState.scrollToItem(0) }
        }
        wasRefreshing = isRefreshing
    }
    // 首页切换资源线路：列表换源清空重载，回到页面顶部衔接视觉
    LaunchedEffect(selectedSourceId) {
        runCatching { gridState.scrollToItem(0) }
    }

    TvScaffold(
        title = "影视点播",
        showBack = false,
        actions = {
            // 标题后的小图标操作：榜单（插件）/ 搜索 / 刷新（刷新中显示小圈，点按取消同步）
            if (chartEnabled) {
                IconButton(
                    onClick = {
                        showCharts = !showCharts
                        if (showCharts) viewModel.clearSearch()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Leaderboard,
                        contentDescription = "榜单",
                        tint = if (showCharts) colors.primary else colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            IconButton(onClick = { showSearchDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (isRefreshing) {
                IconButton(onClick = viewModel::cancelRefresh) {
                    // 圈内小数字 = 已加载部数（同步实时累加）：转圈不再"干转"，一眼看出进度
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            color = colors.highlight,
                            strokeWidth = 2.dp,
                        )
                        val loaded = refreshProgress?.processed ?: 0
                        if (loaded > 0) {
                            val label = syncCountLabel(loaded)
                            Text(
                                text = label,
                                color = colors.highlight,
                                fontSize = if (label.length >= 4) 6.sp else 7.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (portrait) 16.dp else 40.dp),
        ) {
            // 榜单面板打开时隐藏分类行（分类对榜单无意义，用户反馈）
            if (!chartActive) {
                VodFilterRow(
                    selectedKind = selectedKind,
                    onKindSelect = viewModel::selectKind,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelect = viewModel::selectCategory,
                    modifier = Modifier.padding(top = if (portrait) 10.dp else 14.dp),
                )
            }
            // 同步状态行（追加需求：转圈之外要「眼见为实」）：
            // 实时显示 第X/Y页 · 已入库N部，完成后显示总数；海报墙随入库逐页实时增长
            if (isRefreshing) {
                refreshProgress?.let { p ->
                    val detail = if (p.processed > 0 && p.stage != ImportProgress.Stage.DONE) {
                        " · 已入库 ${p.processed} 部"
                    } else {
                        ""
                    }
                    Text(
                        text = (p.message.orEmpty() + detail).trim(),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // 同步不再占用页面空间（追加需求：不显示加载站点的大块提示）；
            // 同步状态由标题旁的小圈表示，失败时仅一行小字提示。
            if (!isRefreshing && syncMessage != null && items.isNotEmpty()) {
                Text(
                    text = syncMessage,
                    color = colors.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 16.dp),
            ) {
                when {
                    searchActive -> SearchResults(
                        query = searchQuery.orEmpty(),
                        results = searchResults,
                        onlineSearching = onlineSearching,
                        scopeAll = searchScopeAll,
                        onScopeChange = viewModel::setSearchScope,
                        onClear = viewModel::clearSearch,
                        onOpen = { openDetail(nav, it) },
                        sourceNames = sourceNames,
                    )

                    // 榜单订阅面板（资源插件 source_chart 安装启用后可见）
                    chartActive -> ChartList(
                        charts = charts,
                        sourceName = selectedSourceId?.let { sourceNames[it] },
                        onOpen = {
                            showCharts = false
                            openDetail(nav, it)
                        },
                    )

                    !hasSources && items.isEmpty() && !isRefreshing -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        EmptyState(
                            text = "暂无点播资源",
                            subtext = "请先在设置中心添加 Xtream / TVBox 聚合影视源，返回本页将自动同步",
                            modifier = Modifier.padding(top = 60.dp),
                        )
                        TvButton(
                            text = "前往设置中心添加源",
                            onClick = { nav.navigate(Routes.settings(SettingsSections.SOURCES)) },
                            variant = TvButtonVariant.SECONDARY,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    items.isEmpty() && isRefreshing -> androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 加载中：仅一个小圆圈旋转，不占用页面空间（追加需求）
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = colors.primary,
                            strokeWidth = 2.5.dp,
                        )
                    }

                    items.isEmpty() && syncMessage != null -> ErrorState(
                        message = syncMessage,
                        onRetry = viewModel::refresh,
                    )

                    items.isEmpty() -> EmptyState(
                        text = "暂无影视资源",
                        subtext = "按「刷新」同步点播列表",
                    )

                    else -> VodPosterGrid(
                        items = items,
                        onOpen = { openDetail(nav, it) },
                        state = gridState,
                    )
                }
            }
        }
    }

    if (showSearchDialog) {
        SearchDialog(
            hotKeywords = viewModel.hotKeywords,
            onSearch = { query ->
                showSearchDialog = false
                if (query.isNotBlank()) viewModel.submitSearch(query)
            },
            onDismiss = { showSearchDialog = false },
        )
    }
}

/** 同步转圈内的小数字标签：<1 万显示原数，更大折算 12k / 123k 形式保证 18dp 圈内放得下 */
private fun syncCountLabel(count: Int): String =
    if (count < 10_000) count.toString() else "${count / 1000}k"

private fun openDetail(nav: NavHostController, item: VodItemEntity) {
    nav.navigate(Routes.vodDetail(item.sourceId, item.id))
}

/**
 * 榜单面板（资源插件「榜单订阅」）：热播更新 / 高分口碑两榜，
 * 聚合本地片库缓存（随同步与刮削回填实时联动），点按进详情页。
 */
@Composable
private fun ChartList(
    charts: ChartLists,
    sourceName: String?,
    onOpen: (VodItemEntity) -> Unit,
) {
    val colors = PureTvTheme.colors
    var topMode by remember { mutableStateOf(false) }
    val list = if (topMode) charts.top else charts.hot
    Column(modifier = Modifier.fillMaxSize()) {
        // 榜单跟随首页所选资源线路动态聚合（切线路后本面板数据随之变化）
        Text(
            text = "基于资源站：${sourceName ?: "全部线路"} · 热播 ${charts.hot.size} 部 · 高分 ${charts.top.size} 部",
            color = colors.textTertiary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChartTab(text = "热播更新", selected = !topMode, onClick = { topMode = false })
            ChartTab(text = "高分口碑", selected = topMode, onClick = { topMode = true })
        }
        if (list.isEmpty()) {
            EmptyState(
                text = if (topMode) "该线路暂无高分资源" else "该线路暂无榜单数据",
                subtext = if (topMode) "刮削回填评分（≥6.0）后自动上榜" else "同步该资源线路后自动聚合榜单",
                modifier = Modifier.padding(top = 48.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(list) { index, item ->
                    ChartRow(rank = index + 1, item = item, onClick = { onOpen(item) })
                }
            }
        }
    }
}

/** 榜单切换胶囊（热播更新 / 高分口碑） */
@Composable
private fun ChartTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    Text(
        text = text,
        color = if (selected) Color.White else colors.textSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.primary else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.primary else colors.divider,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** 榜单条目行：名次 + 标题 + 年份·分类 + 评分 */
@Composable
private fun ChartRow(rank: Int, item: VodItemEntity, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    val rankColor = when (rank) {
        1 -> Color(0xFFF59E0B)
        2 -> Color(0xFF94A3B8)
        3 -> Color(0xFFD97706)
        else -> colors.textTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            color = rankColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = item.title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(item.year, item.category).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item.rating?.let { rating ->
            Text(
                text = String.format(java.util.Locale.US, "%.1f", rating),
                color = colors.highlight,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 搜索结果区（含「本站 / 全网」搜索范围切换，追加需求） */
@Composable
private fun SearchResults(
    query: String,
    results: List<VodItemEntity>,
    onlineSearching: Boolean,
    scopeAll: Boolean,
    onScopeChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onOpen: (VodItemEntity) -> Unit,
    sourceNames: Map<String, String> = emptyMap(),
) {
    val colors = PureTvTheme.colors
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "搜索：$query",
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                )
                // 加载圈只显示一个（用户反馈：此前标题行与结果区各有一个）：
                // 有结果时标题行小圈提示在线检索继续，结果区不再重复渲染
                if (onlineSearching && results.isNotEmpty()) {
                    InlineLoading(
                        message = if (scopeAll) {
                            "正在全网搜索 ${results.size} 条结果…"
                        } else {
                            "正在本站搜索 ${results.size} 条结果…"
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp),
                )
                TvTextButton(text = "清除搜索", onClick = onClear)
            }
            // 搜索范围：本站（首页选中的资源站）/ 全网（跨全部线路在线检索）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScopeChip(text = "本站", selected = !scopeAll) { onScopeChange(false) }
                ScopeChip(text = "全网", selected = scopeAll) { onScopeChange(true) }
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp),
                )
                Text(
                    text = if (scopeAll) "跨全部线路在线检索" else "仅搜索当前选中的资源站",
                    color = colors.textHint,
                    fontSize = 12.sp,
                )
            }
            if (results.isEmpty()) {
                if (onlineSearching) {
                    InlineLoading(
                        message = if (scopeAll) "本地未命中，正在全网搜索…" else "本地未命中，正在本站搜索…",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    EmptyState(
                        text = "未找到相关影片",
                        subtext = "换个关键词试试，或在设置中心增加 TVBox 线路后重试",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            } else {
                VodPosterGrid(
                    items = results,
                    onOpen = onOpen,
                    sourceNames = sourceNames,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** 搜索范围切换小胶囊（本站 / 全网） */
@Composable
private fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    Text(
        text = text,
        color = if (selected) colors.highlight else colors.textTertiary,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.card)
            .border(
                1.dp,
                if (selected) colors.highlight.copy(alpha = 0.5f) else colors.divider,
                RoundedCornerShape(15.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 搜索弹窗：关键词输入 + 常用搜索词（内置热门关键词，点按即搜，追加需求） */
@Composable
private fun SearchDialog(
    hotKeywords: List<String>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PureTvTheme.colors
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    TvDialog(onDismiss = onDismiss, title = "搜索影片") {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                .copy(color = colors.textPrimary, fontSize = 17.sp),
            cursorBrush = SolidColor(colors.highlight),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "输入影片名称关键词，如：仙逆",
                            color = colors.textHint,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            text = "常用搜索",
            color = colors.textTertiary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
        )
        hotKeywords.chunked(3).forEach { rowWords ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                rowWords.forEach { word ->
                    HotKeywordChip(word = word) { onSearch(word) }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TvTextButton(text = "取消", onClick = onDismiss)
            TvTextButton(text = "搜索", onClick = { onSearch(value.trim()) })
        }
    }
}

/** 常用搜索词胶囊（点按即搜） */
@Composable
private fun HotKeywordChip(word: String, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    Text(
        text = word,
        color = colors.textSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 海报网格（key = sourceId|id 保证多源共存稳定；竖屏缩小最小列宽，保证手机 2 列） */
@Composable
private fun VodPosterGrid(
    items: List<VodItemEntity>,
    onOpen: (VodItemEntity) -> Unit,
    state: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    sourceNames: Map<String, String> = emptyMap(),
) {
    val portrait = isPortrait()
    val spacing = if (portrait) 12.dp else 20.dp
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minSize = if (portrait) 140.dp else 168.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = 32.dp + LocalPaneBottomPadding.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(
            count = items.size,
            key = { index -> "${items[index].sourceId}|${items[index].id}" },
        ) { index ->
            val item = items[index]
            VodPosterCard(
                item = item,
                onClick = { onOpen(item) },
                sourceName = sourceNames[item.sourceId],
            )
        }
    }
}
