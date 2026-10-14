package com.puretv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.util.AdFilter
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.dao.VodDao
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.plugin.PluginManager
import com.puretv.player.data.repository.FavoritesRepository
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.data.source.SelectedSourceStore
import com.puretv.player.data.vod.VodRepository
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlaybackMode
import com.puretv.player.player.PlaybackRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    favoritesRepository: FavoritesRepository,
    channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val vodDao: VodDao,
    private val selectedSourceStore: SelectedSourceStore,
    private val vodRepository: VodRepository,
    private val playbackEngine: PlaybackEngine,
    private val pluginManager: PluginManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 频道总数（直播专区角标） */
    val channelCount: StateFlow<Int> = channelDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 最近播放（首页「播放记录」预览 + 断点续播） */
    val recentHistory: StateFlow<List<WatchHistoryEntity>> = historyRepository.recent(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 收藏数量 */
    val favoriteCount: StateFlow<Int> = favoritesRepository.favorites
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /* ============================== 资源站浏览（追加需求：首页切换资源站，显示该站全部视频） ============================== */

    /** 可切换的点播资源站（MacCMS / Xtream 聚合源，含内置 TVBox 线路） */
    val vodSources: StateFlow<List<SourceEntity>> = sourceDao.observeAll()
        .map { list ->
            list.filter {
                it.enabled && (it.type == SourceType.CMS || it.type == SourceType.XTREAM)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中的资源站 id（与影视页「本站搜索」共用同一选中态） */
    val selectedSourceId: StateFlow<String?> = selectedSourceStore.selectedSourceId

    /** 当前资源站已缓存影片（分页加载，滚动到底自动追加） */
    private val _sourceItems = MutableStateFlow<List<VodItemEntity>>(emptyList())
    val sourceItems: StateFlow<List<VodItemEntity>> = _sourceItems

    /** 广告过滤配置（插件「过滤广告」安装且启用 + 设置开启；[first]=是否生效，[second]=用户词库） */
    private val adFilterSpec: StateFlow<Pair<Boolean, List<String>>> =
        combine(pluginManager.observeCatalog(), settingsRepository.settings) { catalog, s ->
            val pluginOn = catalog.any { it.id == "playback_adfilter" && it.installed && it.enabled }
            (pluginOn && s.adFilterEnabled) to AdFilter.resolveKeywords(s.adFilterKeywords)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to AdFilter.DEFAULT_KEYWORDS)

    /** 对外展示列表：确定性播放失败过的影片自动隐藏（播放成功自动恢复显示）+ 广告过滤 */
    val visibleSourceItems: StateFlow<List<VodItemEntity>> =
        combine(_sourceItems, settingsRepository.brokenPlayUrls, adFilterSpec) { items, broken, spec ->
            items.filter { item ->
                (item.playUrl == null || item.playUrl !in broken) &&
                    (!spec.first || !AdFilter.isAd(item.title, item.overview, spec.second))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前资源站影片总数（源同步时回写的 channelCount） */
    val selectedSourceTotal: StateFlow<Int> =
        combine(vodSources, selectedSourceId) { list, id ->
            list.firstOrNull { it.id == id }?.channelCount ?: 0
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var sourceLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // 等 DataStore 恢复落地（awaitSelection）再决定选中线路：直接读 .value 会被
            // 「默认第一个源」抢在恢复前执行并写回持久化，用户保存的线路每次启动都被覆盖。
            val available = sourceDao.observeAll()
                .first()
                .filter {
                    it.enabled && (it.type == SourceType.CMS || it.type == SourceType.XTREAM)
                }
            var id = selectedSourceStore.awaitSelection()
            when {
                // 历史线路仍可用（或源列表尚未就绪，先不误判）：保持用户选择——退出重进不回默认
                id != null && (available.isEmpty() || available.any { it.id == id }) -> Unit
                // 无历史选择 / 历史线路已失效（被删除或停用）：回落第一个可用源并记住
                else -> {
                    id = available.firstOrNull()?.id
                    if (id != null) selectedSourceStore.select(id)
                }
            }
            if (id != null) {
                _sourceItems.value = vodDao.getItemsPaged(id, HOME_MAX_ITEMS, 0)
            }
        }
        // 源数据刷新后重查首页第一页：否则刷新前的旧条目残留列表，点入报「影片信息不存在」
        viewModelScope.launch {
            vodRepository.refreshTick.collect { tick ->
                if (tick <= 0L) return@collect
                val id = selectedSourceStore.selectedSourceId.value ?: return@collect
                sourceLoadJob?.cancel()
                _sourceItems.value = vodDao.getItemsPaged(id, HOME_MAX_ITEMS, 0)
            }
        }
        // 同步进行中每约 2 秒重查第一页：切线路清缓存后主页随服务器数据到达实时增长
        // （仅列表仍在第一页范围内时刷新，用户深滚后不打断其位置；收尾 refreshTick 仍回第一页）
        viewModelScope.launch {
            var lastReloadAt = 0L
            vodRepository.syncProgress.collect { progress ->
                if (progress == null) return@collect
                if (_sourceItems.value.size > HOME_MAX_ITEMS) return@collect
                val id = selectedSourceStore.selectedSourceId.value ?: return@collect
                val now = System.currentTimeMillis()
                if (now - lastReloadAt < 2_000L) return@collect
                lastReloadAt = now
                _sourceItems.value = vodDao.getItemsPaged(id, HOME_MAX_ITEMS, 0)
            }
        }
    }

    /** 切换资源站：重置列表并加载该站第一页 */
    fun selectSource(sourceId: String) {
        if (selectedSourceStore.selectedSourceId.value == sourceId && _sourceItems.value.isNotEmpty()) return
        selectedSourceStore.select(sourceId)
        _sourceItems.value = emptyList()
        sourceLoadJob?.cancel()
        sourceLoadJob = viewModelScope.launch {
            _sourceItems.value = vodDao.getItemsPaged(sourceId, HOME_MAX_ITEMS, 0)
        }
        // 切线路立即定向同步新线路（用户要求：首页与影视页数据都重新加载）。
        // 即使影视页从未打开过也会触发；与影视页发起的同目标同步在仓库侧自动去重，
        // 同步完成后 refreshTick 回调首页重查第一页拿到最新数据，影视页进度转圈同步显示。
        vodRepository.requestSync(kind = null, onlySourceId = sourceId)
    }

    companion object {
        /** 首页资源站浏览最多展示条数（固定展示最新 100 部，更多内容引导去影视点播界面） */
        private const val HOME_MAX_ITEMS = 100
    }

    /* ======================================================================================== */

    /** 断点续播：按记录的位置继续播放 */
    fun resume(item: WatchHistoryEntity) {
        val url = item.url ?: return
        playbackEngine.play(
            PlaybackRequest(
                url = url,
                title = item.title,
                subtitle = item.episodeLabel ?: item.subtitle,
                artworkUrl = item.imageUrl,
                mode = if (item.targetType == "channel") PlaybackMode.LIVE else PlaybackMode.VOD,
                startPositionMs = item.positionMs,
                historyTargetId = item.targetId,
                historyType = item.targetType,
                episodeLabel = item.episodeLabel,
            )
        )
    }
}
