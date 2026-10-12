package com.puretv.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.ErrorState
import com.puretv.player.core.ui.InlineLoading
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.model.VodKind

/**
 * 影视详情页（需求 5.2.5 海报墙信息展示 / 5.4.2 刮削信息 / 5.4.3 智能追更与断点续播）：
 * 背景大图 + 海报 + 片名/年份/评分/类型标签/简介（展开收起）/演员；
 * 电影「播放」；剧集季选择 + 分集列表（未看完/有更新徽标，OK 播放）；
 * 「收藏」与「Debrid 加速」入口（需求 5.4.5）。
 */
@Composable
fun VodDetailScreen(nav: NavHostController, sourceId: String, itemId: String) {
    val viewModel: VodDetailViewModel = hiltViewModel()
    val colors = PureTvTheme.colors

    val item by viewModel.item.collectAsStateWithLifecycle()
    val episodes by viewModel.visibleEpisodes.collectAsStateWithLifecycle()
    val preloadProgress by viewModel.preloadProgress.collectAsStateWithLifecycle()
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val resumeTarget by viewModel.resumeTarget.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val watchedEpisodes by viewModel.watchedEpisodes.collectAsStateWithLifecycle()
    val episodeHistory by viewModel.episodeHistoryByLabel.collectAsStateWithLifecycle()
    val enriching by viewModel.enriching.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
    val debridState by viewModel.debridState.collectAsStateWithLifecycle()
    val debridMessage by viewModel.debridMessage.collectAsStateWithLifecycle()

    // 播放事件 -> 跳转播放页
    LaunchedEffect(Unit) {
        viewModel.playEvents.collect { nav.navigate(Routes.PLAYBACK) }
    }

    if (item == null) {
        TvScaffold(
            title = "影视详情",
            onBack = { nav.popBackStack() },
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadFailed) {
                    ErrorState(
                        message = "影片信息不存在或缓存已清空",
                        onRetry = viewModel::retry,
                    )
                } else {
                    InlineLoading(message = "正在加载详情…")
                }
            }
        }
        return
    }

    val current = item ?: return
    val isSeries = current.kind == VodKind.SERIES
    val debridResolving = debridState == DebridPlayState.RESOLVING
    val portrait = isPortrait()
    val sidePad = if (portrait) 16.dp else 40.dp

    TvScaffold(
        title = current.title,
        subtitle = listOfNotNull(
            current.year,
            current.category,
        ).joinToString(" · ").ifBlank { null },
        onBack = { nav.popBackStack() },
        actions = {
            TvButton(
                text = if (isFavorite) "已收藏" else "收藏",
                onClick = viewModel::toggleFavorite,
                variant = if (isFavorite) TvButtonVariant.PRIMARY else TvButtonVariant.SECONDARY,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "backdrop") {
                VodBackdrop(url = current.backdropUrl, title = current.title)
            }
            item(key = "info") {
                DetailInfoSection(
                    item = current,
                    enriching = enriching,
                    resumeLabel = resumeTarget?.label,
                    canContinue = resumeTarget != null,
                    hasUpdate = hasUpdate(current.kind == VodKind.SERIES, current.latestEpisode, watchedEpisodes),
                    onPlay = viewModel::playMovie,
                    onContinue = viewModel::continueWatch,
                    onDebridPlay = viewModel::playWithDebrid,
                    debridResolving = debridResolving,
                    isSeries = isSeries,
                )
            }
            if (debridMessage != null) {
                item(key = "debridMessage") {
                    DebridMessageBar(
                        message = debridMessage.orEmpty(),
                        onDismiss = viewModel::dismissDebridMessage,
                    )
                }
            }
            if (isSeries) {
                if (seasons.size > 1) {
                    item(key = "seasons") {
                        SeasonSelector(
                            seasons = seasons,
                            selected = viewModel.selectedSeason.collectAsStateWithLifecycle().value,
                            onSelect = viewModel::selectSeason,
                        )
                    }
                }
                item(key = "episodeHeader") {
                    Text(
                        text = "分集列表",
                        color = colors.textPrimary,
                        fontSize = if (portrait) 18.sp else 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = sidePad, end = sidePad, top = 24.dp, bottom = 8.dp),
                    )
                }
                if (episodes.isEmpty()) {
                    item(key = "episodeEmpty") {
                        EmptyState(
                            text = "暂无分集信息",
                            subtext = "可按返回后点「刷新」重新同步",
                            modifier = Modifier.padding(bottom = 32.dp),
                        )
                    }
                }
                items(
                    count = episodes.size,
                    key = { index -> "ep-${episodes[index].season}-${episodes[index].episode}" },
                ) { index ->
                    val episode = episodes[index]
                    EpisodeRow(
                        episode = episode,
                        history = episodeHistory[episodeLabel(episode.episode)],
                        preloadPct = preloadProgress[episode.playUrl],
                        isLatest = hasUpdate(
                            series = true,
                            latestEpisode = current.latestEpisode,
                            watched = watchedEpisodes,
                        ) && episode.episode == current.latestEpisode,
                        onPlay = { viewModel.playEpisode(episode) },
                        onDebridPlay = { viewModel.playEpisodeWithDebrid(episode) },
                        debridResolving = debridResolving,
                        modifier = Modifier.padding(horizontal = sidePad, vertical = 6.dp),
                    )
                }
            }
            item(key = "footerSpacer") {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

/** 是否有新集数未看（智能追更：latestEpisode 与已看集数比较） */
private fun hasUpdate(series: Boolean, latestEpisode: Int?, watched: Set<Int>): Boolean {
    if (!series || latestEpisode == null || latestEpisode <= 0) return false
    val maxWatched = watched.maxOrNull() ?: return true
    return latestEpisode > maxWatched
}

/** 「第N集」标签（与播放历史 episodeLabel 约定一致） */
private fun episodeLabel(episode: Int): String = "第${episode}集"

/** 顶部信息区：海报 + 片名/年份/评分/标签/简介/演员 + 操作按钮 */
@Composable
private fun DetailInfoSection(
    item: com.puretv.player.data.local.entity.VodItemEntity,
    enriching: Boolean,
    resumeLabel: String?,
    canContinue: Boolean,
    hasUpdate: Boolean,
    onPlay: () -> Unit,
    onContinue: () -> Unit,
    onDebridPlay: () -> Unit,
    debridResolving: Boolean,
    isSeries: Boolean,
) {
    val colors = PureTvTheme.colors
    val portrait = isPortrait()
    val sidePad = if (portrait) 16.dp else 40.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = if (portrait) 16.dp else 24.dp),
    ) {
        // 左侧海报（无图用主题色占位首字）
        Box(
            modifier = Modifier
                .width(if (portrait) 130.dp else 190.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            if (item.posterUrl.isNullOrBlank()) {
                Text(text = item.title.take(1), color = colors.textHint, fontSize = 48.sp)
            } else {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (portrait) 16.dp else 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    color = colors.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (enriching) {
                    Text(
                        text = " · 刮削中",
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!item.year.isNullOrBlank()) {
                    Text(text = item.year, color = colors.textTertiary, fontSize = 15.sp)
                }
                VodRating(rating = item.rating)
                if (hasUpdate) {
                    StatusBadge(text = "有更新", color = colors.success)
                }
            }
            if (item.genres.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.genres.take(6).forEach { genre -> GenreChip(text = genre) }
                }
            }
            OverviewText(overview = item.overview, modifier = Modifier.padding(top = 12.dp))
            if (item.cast.isNotEmpty()) {
                Text(
                    text = "演员：${item.cast.take(6).joinToString(" / ")}",
                    color = colors.textTertiary,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isSeries) {
                    TvButton(text = "播放", onClick = onPlay, enabled = !debridResolving)
                }
                if (canContinue) {
                    TvButton(
                        text = resumeLabel ?: "继续观看",
                        onClick = onContinue,
                        variant = TvButtonVariant.SECONDARY,
                        enabled = !debridResolving,
                    )
                }
                TvButton(
                    text = if (debridResolving) "解析中…" else "Debrid 加速",
                    onClick = onDebridPlay,
                    variant = TvButtonVariant.SECONDARY,
                    enabled = !debridResolving,
                )
            }
        }
    }
}

/** 简介（超长自动折叠 + 展开/收起） */
@Composable
private fun OverviewText(overview: String?, modifier: Modifier = Modifier) {
    val colors = PureTvTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val text = overview?.trim().orEmpty()
    if (text.isEmpty()) {
        Text(
            text = "暂无简介",
            color = colors.textHint,
            fontSize = 15.sp,
            modifier = modifier,
        )
        return
    }
    val collapsible = text.length > OVERVIEW_COLLAPSE_LENGTH
    Column(modifier = modifier) {
        Text(
            text = text,
            color = colors.textSecondary,
            fontSize = 16.sp,
            maxLines = if (expanded || !collapsible) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (collapsible) {
            TvTextButton(
                text = if (expanded) "收起" else "展开",
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Debrid 提示条（未配置/不可用降级提示） */
@Composable
private fun DebridMessageBar(message: String, onDismiss: () -> Unit) {
    val colors = PureTvTheme.colors
    val portrait = isPortrait()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (portrait) 16.dp else 40.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = colors.warning,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        TvTextButton(text = "知道了", onClick = onDismiss)
    }
}

/** 季选择器 */
@Composable
private fun SeasonSelector(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val portrait = isPortrait()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (portrait) 16.dp else 40.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val current = selected ?: seasons.firstOrNull()
        seasons.forEach { season ->
            VodFilterChip(
                text = "第${season}季",
                selected = season == current,
                onClick = { onSelect(season) },
            )
        }
    }
}

/** 分集行：第N集 + 标题 + 时长 + 未看完/已看完/有更新徽标，OK 播放 */
@Composable
private fun EpisodeRow(
    episode: VodEpisodeEntity,
    history: WatchHistoryEntity?,
    preloadPct: Int? = null,
    isLatest: Boolean,
    onPlay: () -> Unit,
    onDebridPlay: () -> Unit,
    debridResolving: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    var focused by remember { mutableStateOf(false) }
    TvCard(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(10.dp),
        contentAlignment = Alignment.CenterStart,
        enabled = !debridResolving,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .background(
                    if (focused) colors.primary.copy(alpha = 0.08f) else colors.card
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episodeLabel(episode.episode),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!episode.title.isNullOrBlank()) {
                Text(
                    text = episode.title,
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp).weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (!episode.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = episode.title,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .width(96.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.background),
                )
            }
            if (episode.durationMs != null && episode.durationMs!! > 0) {
                Text(
                    text = TimeUtils.formatDuration(episode.durationMs!!),
                    color = colors.textTertiary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 14.dp),
                )
            }
            // 未看完集数标记（需求 5.4.3，warning 色徽标）
            if (history != null && !history.isFinished && history.positionMs > 0L) {
                StatusBadge(text = "未看完", color = colors.warning)
            } else if (history != null && history.isFinished) {
                Text(
                    text = "已看完",
                    color = colors.textHint,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            // 跨集预缓冲徽标（设置 → 预缓冲时长；100% 显示「已缓冲」）
            if (preloadPct != null) {
                StatusBadge(
                    text = if (preloadPct >= 100) "已缓冲" else "缓冲 $preloadPct%",
                    color = if (preloadPct >= 100) colors.success else colors.warning,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (isLatest) {
                StatusBadge(
                    text = "更新",
                    color = colors.success,
                    modifier = Modifier.padding(start = if (history != null && !history.isFinished && history.positionMs > 0L) 8.dp else 0.dp),
                )
            }
            TvTextButton(
                text = "Debrid",
                onClick = onDebridPlay,
                enabled = !debridResolving,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private const val OVERVIEW_COLLAPSE_LENGTH = 80
