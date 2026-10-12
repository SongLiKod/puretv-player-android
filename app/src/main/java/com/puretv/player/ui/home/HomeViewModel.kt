package com.puretv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.dao.VodDao
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.SettingsRepository
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
    settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    favoritesRepository: FavoritesRepository,
    channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val vodDao: VodDao,
    private val selectedSourceStore: SelectedSourceStore,
    private val vodRepository: VodRepository,
    private val playbackEngine: PlaybackEngine,
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

    /** 当前资源站影片总数（源同步时回写的 channelCount） */
    val selectedSourceTotal: StateFlow<Int> =
        combine(vodSources, selectedSourceId) { list, id ->
            list.firstOrNull { it.id == id }?.channelCount ?: 0
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var sourceLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // 默认选中第一个资源站并加载首页数据
            // （直接读 DAO，避免订阅型 StateFlow 的初始空值竞态）
            var id = selectedSourceStore.selectedSourceId.value
            if (id == null) {
                id = sourceDao.observeAll()
                    .first()
                    .filter {
                        it.enabled && (it.type == SourceType.CMS || it.type == SourceType.XTREAM)
                    }
                    .firstOrNull()
                    ?.id
                if (id != null) selectedSourceStore.select(id)
            }
            if (id != null) {
                _sourceItems.value = vodDao.getItemsPaged(id, SOURCE_PAGE_SIZE, 0)
            }
        }
        // 源数据刷新后重查首页第一页：否则刷新前的旧条目残留列表，点入报「影片信息不存在」
        viewModelScope.launch {
            vodRepository.refreshTick.collect { tick ->
                if (tick <= 0L) return@collect
                val id = selectedSourceStore.selectedSourceId.value ?: return@collect
                sourceLoadJob?.cancel()
                _sourceItems.value = vodDao.getItemsPaged(id, SOURCE_PAGE_SIZE, 0)
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
            _sourceItems.value = vodDao.getItemsPaged(sourceId, SOURCE_PAGE_SIZE, 0)
        }
    }

    /** 滚动到底部：加载当前资源站下一页影片 */
    fun loadMoreSourceItems() {
        val id = selectedSourceStore.selectedSourceId.value ?: return
        val current = _sourceItems.value
        if (sourceLoadJob?.isActive == true || current.isEmpty()) return
        val total = vodSources.value.firstOrNull { it.id == id }?.channelCount ?: 0
        if (total > 0 && current.size >= total) return
        sourceLoadJob = viewModelScope.launch {
            val more = vodDao.getItemsPaged(id, SOURCE_PAGE_SIZE, current.size)
            if (more.isNotEmpty()) _sourceItems.value = current + more
        }
    }

    companion object {
        /** 资源站浏览每页加载条数 */
        private const val SOURCE_PAGE_SIZE = 60
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
