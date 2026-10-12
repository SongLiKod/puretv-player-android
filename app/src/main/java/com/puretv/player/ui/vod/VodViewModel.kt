package com.puretv.player.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.source.SelectedSourceStore
import com.puretv.player.data.vod.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 影视点播列表 VM（需求 5.4.1 全品类聚合 / 5.4.3 智能追更后台同步 / 6 流畅度）。
 * 列表数据全部来自 Room 缓存 Flow，网络刷新只更新缓存，UI 不卡死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    private val selectedSourceStore: SelectedSourceStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 当前品类 Tab（null = 全部） */
    val selectedKind = MutableStateFlow<String?>(null)

    /** 当前 Xtream 分类（null = 全部分类） */
    val selectedCategory = MutableStateFlow<String?>(null)

    /** 搜索词（null = 未搜索） */
    val searchQuery = MutableStateFlow<String?>(null)

    /** 搜索范围：true = 全网（跨全部线路），false = 本站（首页选中的资源站） */
    val searchScopeAll = MutableStateFlow(true)

    /** 常用搜索词（内置热门关键词，追加需求：搜索框下常用搜索提示，点按即搜） */
    val hotKeywords: List<String> = HOT_KEYWORDS

    /** 与首页「资源站」共用的当前选中资源站 */
    val selectedSourceId: StateFlow<String?> = selectedSourceStore.selectedSourceId

    /** 是否已配置可用点播源（Xtream / MacCMS / TVBox） */
    val hasSources: StateFlow<Boolean> = vodRepository.vodSources
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前品类下的 Xtream 分类 */
    val categories: StateFlow<List<String>> = selectedKind
        .flatMapLatest { kind -> vodRepository.observeCategories(kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 海报墙数据（缓存 Flow，实时响应刷新/刮削回填；确定性播放失败过的影片自动隐藏） */
    val items: StateFlow<List<VodItemEntity>> =
        combine(selectedKind, selectedCategory) { kind, category -> kind to category }
            .flatMapLatest { (kind, category) -> vodRepository.observeItems(kind, category) }
            .combine(settingsRepository.brokenPlayUrls) { list, broken ->
                list.filter { item -> item.playUrl == null || item.playUrl !in broken }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 搜索结果（本地缓存；在线命中的条目已入库，Room Flow 自动带出；按「本站/全网」范围过滤） */
    val searchResults: StateFlow<List<VodItemEntity>> =
        combine(searchQuery, searchScopeAll, selectedSourceId) { query, all, sourceId ->
            Triple(query, all, sourceId)
        }
            .flatMapLatest { (query, all, sourceId) ->
                if (query.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    vodRepository.search(query, sourceId = if (all) null else sourceId)
                }
            }
            .combine(settingsRepository.brokenPlayUrls) { list, broken ->
                list.filter { item -> item.playUrl == null || item.playUrl !in broken }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 在线搜索进行中（UI 显示「正在全网搜索…」） */
    private val _onlineSearching = MutableStateFlow(false)
    val onlineSearching: StateFlow<Boolean> = _onlineSearching

    /** 同步进度（null = 空闲；界面显示进度条且保持可操作） */
    private val _refreshProgress = MutableStateFlow<ImportProgress?>(null)
    val refreshProgress: StateFlow<ImportProgress?> = _refreshProgress

    /** 同步错误（异常抛出场景） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var refreshJob: Job? = null
    private var onlineSearchJob: Job? = null

    init {
        viewModelScope.launch {
            // 「本站搜索」默认资源站：未选中时兜底为第一个可用点播源（CMS/Xtream）
            if (selectedSourceStore.selectedSourceId.value == null) {
                val first = vodRepository.vodSources.first()
                    .filter {
                        it.type == com.puretv.player.data.model.SourceType.CMS ||
                            it.type == com.puretv.player.data.model.SourceType.XTREAM
                    }
                    .firstOrNull()
                if (first != null) selectedSourceStore.select(first.id)
            }
            // 打开页面自动同步（智能追更），缓存新鲜时跳过
            val hasSourcesNow = try {
                vodRepository.hasVodSources()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                false
            }
            if (!hasSourcesNow) return@launch
            val stale = System.currentTimeMillis() - vodRepository.lastRefreshAt > AUTO_SYNC_INTERVAL_MS
            if (stale) refresh()
        }
    }

    fun selectKind(kind: String?) {
        selectedKind.value = kind
        selectedCategory.value = null
    }

    fun selectCategory(category: String?) {
        selectedCategory.value = category
    }

    /** 切换搜索范围（本站/全网），切换后对当前关键词立即重搜 */
    fun setSearchScope(all: Boolean) {
        if (searchScopeAll.value == all) return
        searchScopeAll.value = all
        searchQuery.value?.let { submitSearch(it) }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        searchQuery.value = trimmed
        // 本地缓存只含每源部分页，冷门/新片搜不到 → 同时向资源站在线检索，
        // 命中结果写入缓存后由 Room Flow 自动出现在搜索结果里。
        // 「本站」只查首页选中的资源站；「全网」并发检索全部启用线路。
        onlineSearchJob?.cancel()
        if (trimmed.isEmpty()) {
            _onlineSearching.value = false
            return
        }
        _onlineSearching.value = true
        val scopeSourceId = if (searchScopeAll.value) null else selectedSourceStore.selectedSourceId.value
        onlineSearchJob = viewModelScope.launch {
            try {
                vodRepository.searchOnline(trimmed, sourceId = scopeSourceId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 在线搜索失败静默降级为本地搜索结果
            } finally {
                _onlineSearching.value = false
            }
        }
    }

    fun clearSearch() {
        searchQuery.value = null
        onlineSearchJob?.cancel()
        _onlineSearching.value = false
    }

    /** 手动刷新（可取消，进度回调在 IO 协程发出） */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        _error.value = null
        refreshJob = viewModelScope.launch {
            _refreshProgress.value = ImportProgress(
                stage = ImportProgress.Stage.PREPARING,
                message = "正在同步点播资源…",
            )
            try {
                vodRepository.refresh(selectedKind.value) { progress ->
                    _refreshProgress.value = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _error.value = t.message ?: "同步失败，请稍后重试"
            } finally {
                _refreshProgress.value = null
            }
        }
    }

    /** 取消同步 */
    fun cancelRefresh() {
        refreshJob?.cancel()
    }

    companion object {
        /** 自动追更同步间隔：30 分钟 */
        private const val AUTO_SYNC_INTERVAL_MS = 30 * 60 * 1000L

        /**
         * 常用搜索词（内置热门关键词，均为各资源站高命中题材，点按即搜）。
         * 追加需求：搜索框下提供常用搜索提示，选一个即可直接搜到本系统可用资源。
         */
        private val HOT_KEYWORDS = listOf(
            "仙逆",
            "凡人修仙传",
            "斗破苍穹",
            "斗罗大陆",
            "完美世界",
            "遮天",
            "庆余年",
            "狐妖小红娘",
            "海贼王",
            "名侦探柯南",
            "流浪地球",
            "长安三万里",
            "熊出没",
            "喜羊羊",
            "西游记",
        )
    }
}
