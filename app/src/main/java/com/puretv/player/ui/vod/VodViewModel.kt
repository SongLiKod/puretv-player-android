package com.puretv.player.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.util.AdFilter
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.plugin.PluginManager
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
 * 榜单数据（资源插件「榜单订阅」，按首页所选资源线路动态聚合）：
 * [hot] 热播更新榜（该线路最近更新 Top50）、[top] 高分榜（评分 ≥ [CHART_RATING_FLOOR] Top50）。
 */
data class ChartLists(
    val hot: List<VodItemEntity> = emptyList(),
    val top: List<VodItemEntity> = emptyList(),
) {
    companion object {
        val EMPTY = ChartLists()
    }
}

/** 高分口碑榜入榜评分下限 */
private const val CHART_RATING_FLOOR = 6.0

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
    private val pluginManager: PluginManager,
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

    /** 广告过滤配置（插件「过滤广告」安装且启用 + 设置开启；[first]=是否生效，[second]=用户词库） */
    private val adFilterSpec: StateFlow<Pair<Boolean, List<String>>> =
        combine(pluginManager.observeCatalog(), settingsRepository.settings) { catalog, s ->
            val pluginOn = catalog.any { it.id == "playback_adfilter" && it.installed && it.enabled }
            (pluginOn && s.adFilterEnabled) to AdFilter.resolveKeywords(s.adFilterKeywords)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to AdFilter.DEFAULT_KEYWORDS)

    /** 是否已配置可用点播源（Xtream / MacCMS / TVBox） */
    val hasSources: StateFlow<Boolean> = vodRepository.vodSources
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 资源站 id → 名称（搜索结果显示条目来自哪个资源库） */
    val sourceNames: StateFlow<Map<String, String>> = vodRepository.vodSources
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 当前品类 + 所选线路下的分类（分类行与列表同源，切线路自动跟随） */
    val categories: StateFlow<List<String>> =
        combine(selectedKind, selectedSourceId) { kind, sourceId -> kind to sourceId }
            .flatMapLatest { (kind, sourceId) -> vodRepository.observeCategories(kind, sourceId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 海报墙数据（缓存 Flow，实时响应刷新/刮削回填；确定性播放失败过的影片自动隐藏）。
     * 跟随首页所选资源线路：切线路后仅展示该线路影片（未选中时兜底全部）。
     */
    val items: StateFlow<List<VodItemEntity>> =
        combine(selectedKind, selectedCategory, selectedSourceId) { kind, category, sourceId ->
            Triple(kind, category, sourceId)
        }
            .flatMapLatest { (kind, category, sourceId) ->
                vodRepository.observeItems(kind, category)
                    .map { list ->
                        if (sourceId == null) list else list.filter { it.sourceId == sourceId }
                    }
            }
            .combine(settingsRepository.brokenPlayUrls) { list, broken ->
                list.filter { item -> item.playUrl == null || item.playUrl !in broken }
            }
            .combine(adFilterSpec) { list, spec ->
                if (spec.first) list.filter { !AdFilter.isAd(it.title, it.overview, spec.second) } else list
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
            .combine(adFilterSpec) { list, spec ->
                if (spec.first) list.filter { !AdFilter.isAd(it.title, it.overview, spec.second) } else list
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /* ----------------------------- 榜单订阅（资源插件 source_chart） ----------------------------- */

    /** 榜单订阅插件安装启用后，影视页开放榜单入口（卸载/禁用即消失） */
    val chartEnabled: StateFlow<Boolean> = pluginManager.observeCatalog()
        .map { list -> list.any { it.id == "source_chart" && it.installed && it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 榜单数据（基于首页所选资源线路动态聚合，随线路切换/同步/刮削实时联动）：
     * 热播更新 = 该线路最近更新 Top50；高分口碑 = 该线路评分 ≥6.0 Top50。
     */
    val charts: StateFlow<ChartLists> =
        combine(chartEnabled, selectedSourceId, adFilterSpec) { enabled, sourceId, spec ->
            Triple(enabled, sourceId, spec)
        }
            .flatMapLatest { (enabled, sourceId, spec) ->
                if (!enabled) {
                    flowOf(ChartLists.EMPTY)
                } else {
                    vodRepository.observeItems(kind = null, category = null)
                        .combine(settingsRepository.brokenPlayUrls) { list, broken ->
                            list.filter { item -> item.playUrl == null || item.playUrl !in broken }
                        }
                        .map { raw ->
                            // 与首页资源站选择一致：只聚合当前选中线路的影片（未选中时兜底全部）
                            val filtered = if (spec.first) {
                                raw.filter { !AdFilter.isAd(it.title, it.overview, spec.second) }
                            } else {
                                raw
                            }
                            val base =
                                if (sourceId == null) filtered else filtered.filter { it.sourceId == sourceId }
                            ChartLists(
                                hot = base.sortedByDescending { it.updatedAt }.take(50),
                                top = base.filter { (it.rating ?: 0.0) >= CHART_RATING_FLOOR }
                                    .sortedByDescending { it.rating ?: 0.0 }
                                    .take(50),
                            )
                        }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChartLists.EMPTY)

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

    /** 刷新代次：切线路重启同步时，旧任务的收尾不覆盖新任务的进度/错误 */
    private var refreshSeq = 0

    init {
        viewModelScope.launch {
            // 一次性品类纠正：把误分进电影等品类的动漫内容归回动漫（打开影视页时静默执行）
            try {
                vodRepository.reclassifyKindsIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 纠正失败不影响列表功能
            }
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
            // 首页切换资源线路：影视页清空筛选/搜索，并只对新线路定向同步（清空重新刷新）
            var lastSourceId = selectedSourceStore.selectedSourceId.value
            selectedSourceStore.selectedSourceId.collect { sourceId ->
                if (sourceId == lastSourceId) return@collect
                lastSourceId = sourceId
                onSourceSwitched(sourceId)
            }
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

    /** 手动刷新（可取消，进度回调在 IO 协程发出；全源同步，供跨线路搜索与聚合使用） */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        startRefresh(onlySourceId = null)
    }

    /**
     * 定向同步：取消进行中的同步，只拉取目标线路（首页切线路的自动刷新）。
     * @param seq 代次守卫：旧任务被取消时的 finally/错误不覆盖新任务的进度与错误状态
     */
    private fun startRefresh(onlySourceId: String?) {
        val seq = ++refreshSeq
        refreshJob?.cancel()
        _error.value = null
        refreshJob = viewModelScope.launch {
            _refreshProgress.value = ImportProgress(
                stage = ImportProgress.Stage.PREPARING,
                message = if (onlySourceId == null) "正在同步点播资源…" else "正在同步所选线路…",
            )
            try {
                vodRepository.refresh(
                    kind = selectedKind.value,
                    onlySourceId = onlySourceId,
                ) { progress ->
                    if (seq == refreshSeq) _refreshProgress.value = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (seq == refreshSeq) _error.value = t.message ?: "同步失败，请稍后重试"
            } finally {
                if (seq == refreshSeq) _refreshProgress.value = null
            }
        }
    }

    /** 首页切换资源线路：影视页清空旧线路视图（筛选/搜索/错误），并定向同步新线路 */
    private fun onSourceSwitched(sourceId: String?) {
        selectedKind.value = null
        selectedCategory.value = null
        clearSearch()
        _error.value = null
        startRefresh(onlySourceId = sourceId)
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
