package com.puretv.player.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.debrid.DebridRepository
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.model.VodKind
import com.puretv.player.data.repository.FavoritesRepository
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.data.vod.TmdbRepository
import com.puretv.player.data.vod.VodRepository
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlaybackEpisode
import com.puretv.player.player.PlayerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 断点续播目标（需求 5.4.3：智能定位未看完集数/进度） */
data class ResumeTarget(
    /** 描述文案：如「看到 第3集 · 已看 12:34」 */
    val label: String,
    /** 起播位置 */
    val positionMs: Long,
    /** 对应分集（剧集） */
    val episode: VodEpisodeEntity? = null,
)

/** Debrid 加速播放状态（需求 5.4.5） */
enum class DebridPlayState {
    IDLE,
    RESOLVING,
    /** 解析失败，已降级普通源播放 */
    FALLBACK,
}

/**
 * 影视详情 VM：
 * - 元数据补全（Xtream get_vod_info + TMDB 自动刮削，需求 5.4.2）
 * - 智能追更 / 断点续播（需求 5.4.3）
 * - 播放（电影直看 / 剧集选集，断点续播起播）、收藏、Debrid 加速（需求 5.4.5）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository,
    private val tmdbRepository: TmdbRepository,
    private val historyRepository: HistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val debridRepository: DebridRepository,
    private val playbackEngine: PlaybackEngine,
) : ViewModel() {

    val sourceId: String = savedStateHandle.get<String>("sourceId").orEmpty()
    val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    /** 影片实体（缓存读取 -> 源元数据补全 -> TMDB 刮削逐级增强） */
    private val _item = MutableStateFlow<VodItemEntity?>(null)
    val item: StateFlow<VodItemEntity?> = _item

    /** 详情加载失败（缓存被清空等场景），UI 提供重试 */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed

    /** 元数据补全/刮削进行中 */
    private val _enriching = MutableStateFlow(false)
    val enriching: StateFlow<Boolean> = _enriching

    /** 跨集预缓冲进度（播放内核实时状态；分集列表「已缓冲 / 缓冲中 x%」徽标） */
    val preloadProgress: StateFlow<Map<String, Int>> = playbackEngine.preloadProgress

    /** 剧集分集列表（剧集类实时观察缓存，含 TMDB 回填） */
    val episodes: StateFlow<List<VodEpisodeEntity>> = _item
        .flatMapLatest { current ->
            if (current != null && current.kind == VodKind.SERIES) {
                vodRepository.observeEpisodes(sourceId, itemId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选季号 */
    val seasons: StateFlow<List<Int>> = episodes
        .map { list -> list.map { it.season }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中季（null = 第一季） */
    val selectedSeason = MutableStateFlow<Int?>(null)

    /** 当前季展示的分集 */
    val visibleEpisodes: StateFlow<List<VodEpisodeEntity>> =
        combine(episodes, selectedSeason) { list, season ->
            val target = season ?: list.firstOrNull()?.season
            if (target == null) list else list.filter { it.season == target }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 本片播放记录（电影记录 + 各集记录） */
    private val historyEntries: StateFlow<List<WatchHistoryEntity>> =
        historyRepository.history
            .map { list ->
                list.filter { it.targetId == itemId || it.targetId.startsWith("$itemId|") }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已看集数（第N集号集合，智能追更比较用） */
    val watchedEpisodes: StateFlow<Set<Int>> = historyEntries
        .map { list ->
            list.filter { it.targetType == TargetType.EPISODE }
                .mapNotNull { it.episodeLabel?.let { label -> episodeNumberOf(label) } }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 各集播放记录（episodeLabel -> 记录，未看完标记用） */
    val episodeHistoryByLabel: StateFlow<Map<String, WatchHistoryEntity>> = historyEntries
        .map { list ->
            list.filter { it.targetType == TargetType.EPISODE }
                .mapNotNull { entry -> entry.episodeLabel?.let { it to entry } }
                .toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 断点续播目标：未看完的集/影片（需求 5.4.3） */
    val resumeTarget: StateFlow<ResumeTarget?> =
        combine(historyEntries, episodes) { history, eps ->
            resolveResume(history, eps)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 是否已收藏 */
    val isFavorite: StateFlow<Boolean> = favoritesRepository.favoriteKeys
        .map { keys -> keys.contains("${TargetType.VOD}|$itemId") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Debrid 加速状态 */
    private val _debridState = MutableStateFlow(DebridPlayState.IDLE)
    val debridState: StateFlow<DebridPlayState> = _debridState

    /** Debrid 提示文案（未配置/不可用降级提示） */
    private val _debridMessage = MutableStateFlow<String?>(null)
    val debridMessage: StateFlow<String?> = _debridMessage

    /** 跳转播放页事件 */
    private val _playEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playEvents: SharedFlow<Unit> = _playEvents

    init {
        viewModelScope.launch { load() }
    }

    /** 首次加载：读缓存 -> 源元数据补全 -> TMDB 刮削（不阻塞 UI） */
    private suspend fun load() {
        val cached = vodRepository.getItem(sourceId, itemId)
        if (cached == null) {
            _loadFailed.value = true
            return
        }
        _loadFailed.value = false
        _item.value = cached
        _enriching.value = true
        val enriched = try {
            vodRepository.enrich(cached)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            cached
        }
        _item.value = enriched
        _enriching.value = false
        // 剧集：分集加载完成后再补全分集元数据（TMDB）
        if (episodes.value.isNotEmpty() && enriched.tmdbId != null) {
            tmdbRepository.scrapeEpisodes(enriched)
        } else if (enriched.kind == VodKind.SERIES) {
            val loaded = try {
                vodRepository.loadSeries(sourceId, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
            if (loaded.isNotEmpty()) {
                _item.value = vodRepository.getItem(sourceId, itemId) ?: enriched
                _item.value?.takeIf { it.tmdbId != null }?.let { tmdbRepository.scrapeEpisodes(it) }
            }
        }
    }

    /** 失败重试：先同步点播缓存再读取 */
    fun retry() {
        viewModelScope.launch {
            try {
                vodRepository.refresh(_item.value?.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 失败时继续尝试读缓存
            }
            load()
        }
    }

    fun selectSeason(season: Int?) {
        selectedSeason.value = season
    }

    /** 电影播放（断点续播起播） */
    fun playMovie() {
        val current = _item.value ?: return
        val playUrl = current.playUrl ?: return
        viewModelScope.launch {
            playbackEngine.play(
                PlayerRequests.vod(
                    item = current,
                    playUrl = playUrl,
                    startPositionMs = resumePositionForLabel(null),
                    subtitle = current.category,
                )
            )
            _playEvents.tryEmit(Unit)
        }
    }

    /** 剧集播放（episodeLabel 第N集，历史 targetId 与 PlayerRequests 约定一致） */
    fun playEpisode(episode: VodEpisodeEntity) {
        val current = _item.value ?: return
        viewModelScope.launch {
            val label = "第${episode.episode}集"
            playbackEngine.play(
                PlayerRequests.vod(
                    item = current,
                    playUrl = episode.playUrl,
                    startPositionMs = resumePositionForLabel(label),
                    episodeLabel = label,
                    subtitle = listOfNotNull(label, episode.title).joinToString(" · "),
                    episodes = episodeOptions(),
                )
            )
            _playEvents.tryEmit(Unit)
        }
    }

    /** 继续观看：定位未看完的那一集/影片进度起播 */
    fun continueWatch() {
        val target = resumeTarget.value ?: return
        val episode = target.episode
        if (episode != null) {
            val current = _item.value ?: return
            viewModelScope.launch {
                val label = "第${episode.episode}集"
                playbackEngine.play(
                    PlayerRequests.vod(
                        item = current,
                        playUrl = episode.playUrl,
                        startPositionMs = target.positionMs,
                        episodeLabel = label,
                        subtitle = listOfNotNull(label, episode.title).joinToString(" · "),
                        episodes = episodeOptions(),
                    )
                )
                _playEvents.tryEmit(Unit)
            }
        } else {
            playMovie()
        }
    }

    /** Debrid 加速播放（需求 5.4.5）：解析直链失败降级普通源并提示 */
    fun playWithDebrid() {
        val current = _item.value ?: return
        val playUrl = current.playUrl ?: return
        viewModelScope.launch {
            if (!debridRepository.hasAnyToken()) {
                _debridMessage.value = "未配置 Debrid 服务，请在设置中心账号分区配置"
            }
            _debridState.value = DebridPlayState.RESOLVING
            val direct = try {
                debridRepository.resolve(playUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (direct != null) {
                _debridState.value = DebridPlayState.IDLE
                _debridMessage.value = null
                playbackEngine.play(
                    PlayerRequests.vod(
                        item = current,
                        playUrl = direct,
                        startPositionMs = resumePositionForLabel(null),
                        subtitle = current.category,
                    )
                )
                _playEvents.tryEmit(Unit)
            } else {
                _debridState.value = DebridPlayState.FALLBACK
                if (_debridMessage.value == null) {
                    _debridMessage.value = "拓展服务不可用，使用普通源"
                }
                playMovie()
            }
        }
    }

    /** Debrid 加速播放剧集（选集后调用） */
    fun playEpisodeWithDebrid(episode: VodEpisodeEntity) {
        val current = _item.value ?: return
        viewModelScope.launch {
            _debridState.value = DebridPlayState.RESOLVING
            val direct = try {
                debridRepository.resolve(episode.playUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (direct != null) {
                _debridState.value = DebridPlayState.IDLE
                _debridMessage.value = null
            } else {
                _debridState.value = DebridPlayState.FALLBACK
                _debridMessage.value = "拓展服务不可用，使用普通源"
            }
            val label = "第${episode.episode}集"
            playbackEngine.play(
                PlayerRequests.vod(
                    item = current,
                    playUrl = direct ?: episode.playUrl,
                    startPositionMs = resumePositionForLabel(label),
                    episodeLabel = label,
                    subtitle = listOfNotNull(label, episode.title).joinToString(" · "),
                    episodes = episodeOptions(),
                )
            )
            _playEvents.tryEmit(Unit)
        }
    }

    fun dismissDebridMessage() {
        _debridMessage.value = null
        _debridState.value = DebridPlayState.IDLE
    }

    /** 收藏/取消收藏（我的收藏页同步展示） */
    fun toggleFavorite() {
        val current = _item.value ?: return
        viewModelScope.launch {
            favoritesRepository.toggle(
                type = TargetType.VOD,
                targetId = itemId,
                title = current.title,
                sourceId = current.sourceId,
                imageUrl = current.posterUrl,
                url = current.playUrl,
            )
        }
    }

    /* ============================== 内部实现 ============================== */

    /**
     * 播放页「选集」抽屉数据：全剧集（跨季），逐集携带展示名、历史键与断点续播位置。
     * 无分集（电影）返回空，播放页不显示选集入口。
     */
    private suspend fun episodeOptions(): List<PlaybackEpisode> =
        PlayerRequests.episodeOptions(itemId, episodes.value) { label ->
            resumePositionForLabel(label)
        }

    /** 断点续播位置：未看完记录的 positionMs（看完/无记录时从头播放） */
    private suspend fun resumePositionForLabel(label: String?): Long {
        val entry = if (label == null) {
            historyEntries.value.firstOrNull {
                it.targetType == TargetType.VOD && it.targetId == itemId && !it.isFinished
            }
        } else {
            historyEntries.value.firstOrNull {
                it.targetType == TargetType.EPISODE && it.episodeLabel == label && !it.isFinished
            }
        }
        return entry?.positionMs?.takeIf { it > 0L } ?: 0L
    }

    /** 从播放记录推导「继续观看」目标 */
    private fun resolveResume(
        history: List<WatchHistoryEntity>,
        episodes: List<VodEpisodeEntity>,
    ): ResumeTarget? {
        val unfinished = history.filter { !it.isFinished && it.positionMs > 0L }
        if (unfinished.isEmpty()) return null
        // 剧集：优先最近未看完的那一集
        val episodeHistory = unfinished
            .filter { it.targetType == TargetType.EPISODE }
            .maxByOrNull { it.updatedAt }
        if (episodeHistory != null) {
            val number = episodeHistory.episodeLabel?.let { episodeNumberOf(it) }
            val episode = episodes.firstOrNull { it.episode == number }
            return ResumeTarget(
                label = "继续观看 ${episodeHistory.episodeLabel ?: ""}".trim(),
                positionMs = episodeHistory.positionMs,
                episode = episode,
            )
        }
        // 电影
        val movie = unfinished
            .filter { it.targetType == TargetType.VOD }
            .maxByOrNull { it.updatedAt }
            ?: return null
        return ResumeTarget(
            label = "继续观看",
            positionMs = movie.positionMs,
            episode = null,
        )
    }

    companion object {
        /** 「第12集」-> 12 */
        fun episodeNumberOf(label: String): Int? = label
            .removePrefix("第")
            .removeSuffix("集")
            .trim()
            .toIntOrNull()
    }
}
