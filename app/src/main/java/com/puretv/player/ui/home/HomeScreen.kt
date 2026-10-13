package com.puretv.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.theme.LocalTvUiConfig
import com.puretv.player.core.ui.LocalPaneBottomPadding
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.core.util.DeviceUtils
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.prefs.UiMode
import com.puretv.player.ui.vod.VodPosterCard
import androidx.compose.ui.platform.LocalContext

/**
 * 首页（需求 5.2.4：首页模块化——直播专区、影视点播、本地影音、我的收藏、播放记录、设置中心）
 */
@Composable
fun HomeScreen(
    nav: NavHostController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val colors = LocalPureTvColors.current
    val config = LocalTvUiConfig.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val channelCount by viewModel.channelCount.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val recent by viewModel.recentHistory.collectAsStateWithLifecycle()
    val vodSources by viewModel.vodSources.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val sourceItems by viewModel.visibleSourceItems.collectAsStateWithLifecycle()
    val selectedSourceTotal by viewModel.selectedSourceTotal.collectAsStateWithLifecycle()

    val modules = listOf(
        HomeModule(
            "直播专区",
            if (channelCount > 0) "$channelCount 个频道" else "IPTV / M3U / Xtream",
            Icons.Default.LiveTv,
            Routes.LIVE,
        ),
        HomeModule("影视点播", "电影 · 剧集 · 综艺 · 动漫", Icons.Default.Movie, Routes.VOD),
        HomeModule("本地影音", "本机视频一键播放", Icons.Default.Storage, Routes.LOCAL),
        HomeModule(
            "我的收藏",
            if (favoriteCount > 0) "$favoriteCount 项收藏" else "收藏置顶管理",
            Icons.Default.Favorite,
            Routes.FAVORITES,
        ),
        HomeModule(
            "播放记录",
            if (recent.isNotEmpty()) "断点续播" else "观看历史",
            Icons.Default.History,
            Routes.HISTORY,
        ),
        HomeModule("设置中心", "主题 · 源 · 播放参数", Icons.Default.Settings, Routes.SETTINGS),
    )

    val portrait = isPortrait()
    val hPad = if (portrait) 18.dp else 48.dp
    val vPad = if (portrait) 14.dp else 32.dp
    val context = LocalContext.current
    val isTv = remember(context) { DeviceUtils.isTvDevice(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = hPad, vertical = vPad)
            .padding(bottom = LocalPaneBottomPadding.current),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PureTV 极简影音",
                color = colors.textPrimary,
                fontSize = if (portrait) 26.sp else 36.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (isTv) {
                ModeBadge(mode = settings?.uiMode ?: UiMode.SIMPLE)
            } else {
                // 收藏 / 记录入口紧跟标题（追加需求：放在标题后面节约空间）
                IconButton(
                    onClick = { nav.navigate(Routes.FAVORITES) },
                    modifier = Modifier.padding(start = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "我的收藏",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { nav.navigate(Routes.HISTORY) }) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "播放记录",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            // 资源线路切换同样紧跟标题（追加需求）
            SourceSwitcher(
                sources = vodSources,
                selectedId = selectedSourceId,
                onSelect = viewModel::selectSource,
            )
        }

        // 模块化导航卡仅 TV 保留（手机端由底部浮动导航承担，顶部导航卡已按用户要求去掉）
        if (isTv) {
            HomeGrid(
                modules = modules,
                densityScale = config.densityScale,
                portrait = portrait,
                onModuleClick = { nav.navigate(it.route) },
                modifier = Modifier.padding(top = if (portrait) 20.dp else 32.dp),
            )
        }

        if (recent.isNotEmpty()) {
            Text(
                text = "继续观看",
                color = colors.textPrimary,
                fontSize = if (portrait) 17.sp else 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = if (portrait) 14.dp else 22.dp, bottom = 10.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 12.dp),
            ) {
                items(recent, key = { it.key }) { item ->
                    HistoryCard(
                        item = item,
                        onClick = {
                            if (!item.url.isNullOrBlank()) {
                                viewModel.resume(item)
                                nav.navigate(Routes.PLAYBACK)
                            } else {
                                nav.navigate(Routes.HISTORY)
                            }
                        },
                    )
                }
            }
        }

        // 资源站浏览（追加需求：首页可切换当前资源站，显示该资源站网址上的所有视频）
        if (vodSources.isNotEmpty()) {
            SourceBrowseSection(
                total = selectedSourceTotal,
                items = sourceItems,
                portrait = portrait,
                onLoadMore = viewModel::loadMoreSourceItems,
                onOpen = { nav.navigate(Routes.vodDetail(it.sourceId, it.id)) },
                modifier = Modifier.padding(top = if (portrait) 16.dp else 24.dp),
            )
        }
    }
}

/**
 * 资源站浏览区（追加需求）：
 * 直接网格展示当前资源站已缓存影片，滚动到底自动加载下一页（[HomeViewModel.loadMoreSourceItems]）。
 * 「资源站影片 共N部」标题与站点胶囊标签行按用户要求去掉；站点切换走顶部右上角 SourceSwitcher。
 */
@Composable
private fun SourceBrowseSection(
    total: Int,
    items: List<VodItemEntity>,
    portrait: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (VodItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            Text(
                text = "正在载入该资源站影片…",
                color = colors.textHint,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        } else {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState, items.size, total) {
                val info = gridState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= items.size - 8 && items.size < total) onLoadMore()
            }
            // 网格高度按实际行数收敛（此前固定 430dp，影片少时底部空出一大块，用户反馈）；
            // 超过 430dp 才封顶，由网格内部继续滚动。列数按 GridCells.Adaptive 同款公式推算。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cellWidth = if (portrait) 112.dp else 132.dp
                val space = 12.dp
                val columns = ((maxWidth + space) / (cellWidth + space)).toInt().coerceAtLeast(1)
                val rows = (items.size + columns - 1) / columns
                // Adaptive 单元格会均分拉伸到 ≥ 最小宽：按拉伸后的实际格宽算行高（2:3 竖版），
                // 否则行高被低估、网格底边界把末行海报切掉一半（用户反馈：底部导航处有一块被遮挡）
                val stretchedCell = (maxWidth - space * (columns - 1)) / columns
                val natural = 14.dp + stretchedCell * 1.5f * rows + space * (rows - 1) + 4.dp
                // 封顶按屏幕高度动态取（此前固定 430dp 会把常见的两行海报拦腰切断）：
                // 常规两/三行完整展示，超长时才由网格内部滚动、边界半行为可滚动提示
                val screenHeight =
                    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                val heightCap = (screenHeight - 240).coerceIn(430, 900).dp
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(cellWidth),
                    horizontalArrangement = Arrangement.spacedBy(space),
                    verticalArrangement = Arrangement.spacedBy(space),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp),
                    modifier = Modifier
                        .height(minOf(natural, heightCap))
                        .fillMaxWidth(),
                ) {
                items(items, key = { "${it.sourceId}|${it.id}" }) { item ->
                    VodPosterCard(item = item, onClick = { onOpen(item) })
                }
                if (items.size < total) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "加载中… 已缓存 ${items.size} / $total 部",
                            color = colors.textHint,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * 顶部右上角「资源网站切换」胶囊（追加需求）：
 * 点击弹出站点下拉列表，选择后首页资源站区块与影视页「本站搜索」同步切换。
 */
@Composable
private fun SourceSwitcher(
    sources: List<SourceEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalPureTvColors.current
    if (sources.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val current = sources.firstOrNull { it.id == selectedId } ?: sources.firstOrNull()

    Box {
        TvCard(
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(start = 8.dp)
                .height(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current?.name ?: "资源站",
                    color = colors.highlight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "切换资源站",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.dialog),
        ) {
            sources.forEach { source ->
                val selected = source.id == selectedId
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (source.channelCount > 0) {
                                "${source.name} · ${source.channelCount} 部"
                            } else {
                                source.name
                            },
                            color = if (selected) colors.highlight else colors.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = colors.highlight,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(source.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class HomeModule(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
)

/** 两行三列模块化布局；竖屏手机改为两列自适应宽度 */
@Composable
private fun HomeGrid(
    modules: List<HomeModule>,
    densityScale: Float,
    portrait: Boolean,
    onModuleClick: (HomeModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = if (portrait) 2 else 3
    val cardWidth = (310 * densityScale).dp
    val cardHeight = if (portrait) 120.dp else (150 * densityScale).dp
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (portrait) 12.dp else 20.dp)) {
        modules.chunked(columns).forEachIndexed { rowIndex, rowModules ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (portrait) 12.dp else 20.dp)) {
                rowModules.forEachIndexed { index, module ->
                    HomeModuleCard(
                        module = module,
                        autoFocus = rowIndex == 0 && index == 0,
                        portrait = portrait,
                        onClick = { onModuleClick(module) },
                        modifier = if (portrait) {
                            Modifier
                                .weight(1f)
                                .height(cardHeight)
                        } else {
                            Modifier
                                .width(cardWidth)
                                .height(cardHeight)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeModuleCard(
    module: HomeModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    portrait: Boolean = false,
) {
    val colors = LocalPureTvColors.current
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    if (autoFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    TvCard(
        onClick = onClick,
        modifier = modifier.then(
            if (autoFocus) Modifier.focusRequester(focusRequester) else Modifier
        ),
        shape = RoundedCornerShape(if (portrait) 16.dp else 18.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        // 顶部主题色渐变微光，让卡片更有层次（低透明度，不干扰文字）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            colors.primary.copy(alpha = 0.10f),
                            colors.card.copy(alpha = 0f),
                        ),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (portrait) 16.dp else 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 图标圆角底板
            Box(
                modifier = Modifier
                    .size(if (portrait) 44.dp else 56.dp)
                    .clip(RoundedCornerShape(if (portrait) 12.dp else 14.dp))
                    .background(colors.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = module.icon,
                    contentDescription = module.title,
                    tint = colors.highlight,
                    modifier = Modifier.size(if (portrait) 24.dp else 30.dp),
                )
            }
            Column {
                Text(
                    text = module.title,
                    color = colors.textPrimary,
                    fontSize = if (portrait) 18.sp else 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = module.subtitle,
                    color = colors.textTertiary,
                    fontSize = if (portrait) 13.sp else 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: UiMode) {
    val colors = LocalPureTvColors.current
    val (label, bg) = when (mode) {
        UiMode.SIMPLE -> "极简模式" to colors.primary
        UiMode.PRO -> "专业模式" to colors.highlight
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text = label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun HistoryCard(
    item: WatchHistoryEntity,
    onClick: () -> Unit,
) {
    val colors = LocalPureTvColors.current
    // 卡片紧凑化（追加需求：继续观看占用空间小一点）
    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(196.dp)
            .height(92.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = item.title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            item.episodeLabel?.let {
                Text(
                    text = it,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = if (item.isFinished) "已看完"
                else "看到 ${TimeUtils.formatDuration(item.positionMs)}",
                color = if (item.isFinished) colors.success else colors.textTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (item.durationMs > 0) {
                val progress = (item.positionMs * 100 / item.durationMs).toInt().coerceIn(0, 100)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(3.dp)
                            .background(if (item.isFinished) colors.success else colors.primary),
                    )
                }
            }
        }
    }
}
