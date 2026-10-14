package com.puretv.player.data.vod

import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.dao.VodDao
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.ImportProgress.Stage
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.model.VodKind
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 点播资源仓库（需求 5.4.1：电影、电视剧、综艺、动漫全品类聚合分类）。
 * 内容来源为已启用的点播源：
 * - Xtream Codes（player_api.php get_vod_categories / get_vod_streams / get_vod_info）
 * - MacCMS 采集协议（api.php/provide/vod ac=detail，TVBox 资源站通用后端）
 * 结果写入 Room 缓存，界面统一从缓存读取（列表滑动丝滑、离线可看）。
 */
@Singleton
class VodRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val vodDao: VodDao,
    private val xtreamClient: XtreamVodClient,
    private val cmsClient: CmsVodClient,
    private val tmdbRepository: TmdbRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 已启用的点播源（Xtream + MacCMS 采集，需求 5.4.1） */
    val vodSources: Flow<List<SourceEntity>> = sourceDao.observeEnabled()
        .map { sources -> sources.filter { it.type == SourceType.XTREAM || it.type == SourceType.CMS } }

    /** 上次全量刷新时间（VM 用于判断是否需要自动追更同步） */
    @Volatile
    var lastRefreshAt: Long = 0L
        private set

    /** 刷新代次（每次点播刷新结束 +1；首页等快照列表据此重查，避免旧条目残留） */
    private val _refreshTick = MutableStateFlow(0L)
    val refreshTick: StateFlow<Long> = _refreshTick.asStateFlow()

    /**
     * 同步进度（全局唯一）：任一入口（影视页刷新 / 首页切线路）发起的同步都更新这里，
     * 影视页右上角转圈与圈内「已加载部数」小数字统一读取该状态。
     */
    private val _syncProgress = MutableStateFlow<ImportProgress?>(null)
    val syncProgress: StateFlow<ImportProgress?> = _syncProgress.asStateFlow()

    /** 仓库级同步作用域：同步是应用级任务，不随发起它的界面销毁而中断 */
    private val syncScope = CoroutineScope(SupervisorJob() + io)
    private var syncJob: Job? = null

    /** 进行中任务的目标（onlySourceId to kind），用于同目标幂等去重 */
    @Volatile
    private var syncTarget: Pair<String?, String?>? = null

    /** 同步代次：重启同步时旧任务的收尾/迟到回调不覆盖新任务的进度 */
    @Volatile
    private var syncSeq = 0

    /** 请求中止「完成总数停留展示」（停留期点按转圈取消时立即收起） */
    @Volatile
    private var holdAbort = false

    /**
     * CMS 线路增量分页断点（点播页动态加载）：
     * - [CmsPaging.Exhausted] 已拉到资源站末页，滚动加载不再发请求；
     * - [CmsPaging.Continue] 从 [CmsPaging.Continue.nextPage] 继续（totalPages=0 表示服务端未给页数）。
     * 仅存内存态；冷启动丢失时按缓存条数推算已加载页码。
     */
    private sealed interface CmsPaging {
        object Exhausted : CmsPaging
        data class Continue(val nextPage: Int, val totalPages: Int) : CmsPaging
    }

    /** sourceId → 增量分页断点 */
    private val cmsPaging = ConcurrentHashMap<String, CmsPaging>()

    /**
     * sourceId → (typeId → 该分类归入的品类)：从拉取到的条目累积学习
     * （MacCMS 分类 id 由站点自定，无官方枚举接口），供标签加载走服务端 t= 过滤。
     */
    private val cmsTypeKinds = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** sourceId → (kind → 分类过滤流的下一页页码)；[Int.MAX_VALUE] = 该标签已到末页 */
    private val cmsKindPaging = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** 增量加载互斥（滚动事件密集：同时只允许一个任务，后来者直接跳过） */
    private val loadMoreLock = Mutex()

    /**
     * 从条目学习「分类 id → 品类」归属（按分类名**模糊归类**：日漫/国产动漫/少儿/国创…
     * 等关键词命中即归入对应标签，增量加载/整源同步时持续累积）。
     *
     * type_id 在 DTO 里声明为 Any，Moshi 把 JSON 数字解析成 Double（6 → 6.0）——直接
     * toString 会向服务器发 t=6.0，部分站点不认（分类 id 学错 → 该标签过滤拉不到内容，
     * 用户反馈的动漫/综艺不加载），所以先归一化成整数串。
     */
    private fun rememberCmsType(sourceId: String, rawTypeId: Any?, categoryName: String?) {
        val typeId = typeIdToString(rawTypeId) ?: return
        val kind = VodKindClassifier.ofCategory(categoryName.orEmpty())
        cmsTypeKinds.getOrPut(sourceId) { ConcurrentHashMap() }[typeId] = kind
    }

    /** JSON 数字 id 归一化：6.0 → "6"、"6.0" → "6"；非数字 id 原样保留；空值丢弃 */
    private fun typeIdToString(raw: Any?): String? = when (raw) {
        null -> null
        is Number -> raw.toInt().toString()
        else -> raw.toString().trim().let { text ->
            val head = text.substringBefore('.')
            if (head.isNotEmpty() && head.all { it in '0'..'9' }) head else text.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 发起同步（非挂起，任意界面可调）：
     * - 相同目标（线路 + 品类）进行中 → 幂等跳过（首页与影视页双入口不重复拉取）；
     * - 目标不同 → 取消进行中的任务并立即按新目标重启；
     * - 完成后圈内停留展示累计总数（[SYNC_DONE_HOLD_MS]），停留期内可被新请求立刻抢占。
     */
    @Synchronized
    fun requestSync(kind: String?, onlySourceId: String?) {
        val target = onlySourceId to kind
        val holdingDone = _syncProgress.value?.stage == Stage.DONE
        if (syncJob?.isActive == true && syncTarget == target && !holdingDone) return
        val seq = ++syncSeq
        syncJob?.cancel()
        syncTarget = target
        holdAbort = false
        syncJob = syncScope.launch {
            _syncProgress.value = ImportProgress(
                stage = Stage.PREPARING,
                message = if (onlySourceId == null) "正在同步点播资源…" else "正在同步所选线路…",
            )
            try {
                val report: (ImportProgress) -> Unit = { progress ->
                    if (seq == syncSeq) _syncProgress.value = progress
                }
                if (onlySourceId != null) {
                    // 点播页语义：进页面/切线路/手动刷新只加载该线路首批 INITIAL_LOAD_ITEMS 条，
                    // 剩余影片由 loadMoreItems() 随用户滚动增量拉取（不再整源一次拉完）
                    refreshInitial(onlySourceId, report)
                } else {
                    refresh(kind = kind, onlySourceId = null, onProgress = report)
                }
            } finally {
                // 加载完成：圈内停留展示累计总数几秒再收起（用户要求「加载完显示总数」）。
                // NonCancellable 保证停留期间任务状态异常也能走完；holdAbort 让转圈点按立即收起；
                // 代次变化（被新同步抢占）则提前退出。
                if (seq == syncSeq && _syncProgress.value?.stage == Stage.DONE && !holdAbort) {
                    val deadline = System.currentTimeMillis() + SYNC_DONE_HOLD_MS
                    withContext(NonCancellable) {
                        while (
                            System.currentTimeMillis() < deadline &&
                            !holdAbort &&
                            seq == syncSeq
                        ) {
                            delay(150)
                        }
                    }
                }
                // 停留期间可能已启动新同步（代次变化）：二次校验避免误清新任务的进度
                if (seq == syncSeq) {
                    _syncProgress.value = null
                    syncTarget = null
                }
            }
        }
    }

    /** 取消进行中的同步（影视页转圈点按取消；完成停留展示期点按则立即收起） */
    @Synchronized
    fun cancelSync() {
        holdAbort = true
        syncJob?.cancel()
    }

    private val episodeMutex = Mutex()

    suspend fun hasVodSources(): Boolean = vodSources.first().isNotEmpty()

    /* ============================== 查询 ============================== */

    /**
     * 观察影片列表。
     * @param kind null/空白 = 全部品类聚合（需求 5.4.1）
     * @param category null/空白 = 该品类全部分类
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeItems(kind: String?, category: String?): Flow<List<VodItemEntity>> = when {
        // 「全部」品类 + 选定分类：跨品类按分类过滤（此前被忽略导致切分类内容不变）
        kind.isNullOrBlank() && !category.isNullOrBlank() ->
            vodDao.observeByCategoryAll(category)
        kind.isNullOrBlank() -> combine(ALL_KINDS.map { vodDao.observeByKind(it) }) { arrays ->
            arrays.flatMap { it }.sortedByDescending { it.updatedAt }
        }
        category.isNullOrBlank() -> vodDao.observeByKind(kind)
        else -> vodDao.observeByCategory(kind, category)
    }

    /**
     * 观察某品类下的点播分类名（Xtream 分类横滑列表）。
     * @param sourceId 非空 = 仅该资源站的分类（分类行与影视列表的线路过滤同源）
     */
    fun observeCategories(kind: String?, sourceId: String? = null): Flow<List<String>> = when {
        sourceId.isNullOrBlank() -> when {
            kind.isNullOrBlank() ->
                combine(ALL_KINDS.map { vodDao.observeCategories(it) }) { arrays ->
                    arrays.flatMap { it }.distinct().sorted()
                }
            else -> vodDao.observeCategories(kind)
        }
        kind.isNullOrBlank() -> vodDao.observeCategoriesInSourceAll(sourceId)
        else -> vodDao.observeCategoriesInSource(kind, sourceId)
    }

    /** 观察单个影片（一次性读取，刮削回填后由详情页直接更新状态） */
    fun observeItem(sourceId: String, id: String): Flow<VodItemEntity?> = flow {
        emit(vodDao.get(sourceId, id))
    }

    suspend fun getItem(sourceId: String, id: String): VodItemEntity? = vodDao.get(sourceId, id)

    /** 品类纠正是否已执行（进程内一次即可，纠正的是历史同步写入的旧归类） */
    private var kindsReclassified = false

    /**
     * 一次性品类纠正（用户反馈：「全部」里有大量动漫内容，切到「动漫」标签却看不到）。
     * 旧归类规则把「少儿/国漫/卡通/XX动漫电影」等动画面向分类误分进电影，且分类名已随
     * 同步存库——仅改规则只对新同步生效，这里按存量条目的分类名把动漫内容纠正回动漫，
     * 单向移动（只改判为动漫的），不影响剧集等其他既有归类。
     */
    suspend fun reclassifyKindsIfNeeded() {
        if (kindsReclassified) return
        kindsReclassified = true
        withContext(io) {
            val rows = vodDao.getAllWithCategory()
            val updates = rows.mapNotNull { item ->
                val category = item.category.orEmpty()
                if (item.kind != VodKind.ANIME && VodKindClassifier.isAnimeCategory(category)) {
                    item.copy(kind = VodKind.ANIME)
                } else {
                    null
                }
            }
            updates.chunked(300).forEach { vodDao.upsertAll(it) }
        }
    }

    /**
     * 观察剧集分集列表（缓存为空时自动从 Xtream 拉取 get_vod_info 解析分集）
     */
    /**
     * 一次性读取剧集分集（历史/收藏快速播放时构建播放页「选集」列表用）。
     *
     * 兼容历史记录 sourceId 缺失的旧数据：
     * - [sourceId] 为空或匹配不到时回退到全部同 id 剧集；
     * - 多源 id 撞车时用 [hintUrl]（正在播放的地址）按域名挑出正确源的分集，兜底取第一组。
     */
    suspend fun episodesOnce(
        sourceId: String?,
        seriesId: String,
        hintUrl: String? = null,
    ): List<VodEpisodeEntity> {
        val rows = vodDao.observeEpisodes(seriesId).first()
        if (rows.isEmpty()) return emptyList()
        val bySource = if (sourceId.isNullOrBlank()) {
            rows
        } else {
            rows.filter { it.sourceId == sourceId }.ifEmpty { rows }
        }
        val groups = bySource.groupBy { it.sourceId }
        val picked = if (groups.size <= 1) {
            bySource
        } else {
            val host = hintUrl?.let { hostOf(it) }
            groups.values.firstOrNull { group ->
                host != null && group.any { hostOf(it.playUrl) == host }
            } ?: groups.values.first()
        }
        return picked.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun hostOf(url: String): String? = try {
        android.net.Uri.parse(url).host
    } catch (t: Throwable) {
        null
    }

    fun observeEpisodes(sourceId: String, seriesId: String): Flow<List<VodEpisodeEntity>> =
        vodDao.observeEpisodes(seriesId)
            .map { list -> list.filter { it.sourceId == sourceId } }
            .onEach { cached ->
                if (cached.isEmpty()) {
                    try {
                        loadSeries(sourceId, seriesId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // 加载失败保持空列表，UI 展示空态可重试
                    }
                }
            }

    /** 读取（必要时联网加载）剧集分集 */
    suspend fun loadSeries(sourceId: String, seriesId: String): List<VodEpisodeEntity> =
        withContext(io) {
            vodDao.episodes(seriesId).filter { it.sourceId == sourceId }.takeIf { it.isNotEmpty() }
                ?: episodeMutex.withLock {
                    vodDao.episodes(seriesId).filter { it.sourceId == sourceId }
                        .takeIf { it.isNotEmpty() }
                        ?: fetchAndSaveEpisodes(sourceId, seriesId)
                }
        }

    /** 搜索（本地缓存模糊匹配） */
    /**
     * 本地缓存搜索。
     * @param sourceId 非空时限定在单个资源站内搜索（「本站搜索」），null 搜索全部缓存
     */
    fun search(query: String, sourceId: String? = null): Flow<List<VodItemEntity>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return flow { emit(emptyList()) }
        return if (sourceId.isNullOrBlank()) {
            vodDao.search(trimmed)
        } else {
            vodDao.searchInSource(trimmed, sourceId)
        }
    }

    /**
     * 全网在线搜索（MacCMS 协议 wd 搜索，跨全部启用的 CMS/TVBox 线路并发查询）。
     * 本地缓存只同步了每源前几页，冷门/新片搜不到；本方法直接向资源站实时检索，
     * 命中结果写入缓存（详情页、续看、收藏立即可用）。
     * 单源失败静默跳过，整体不可用时返回空列表（UI 保持本地搜索结果）。
     */
    suspend fun searchOnline(query: String, sourceId: String? = null): List<VodItemEntity> = withContext(io) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val sources = sourceDao.getAll()
            .filter {
                it.enabled && it.type == SourceType.CMS &&
                    (sourceId.isNullOrBlank() || it.id == sourceId)
            }
            .take(ONLINE_SEARCH_MAX_SOURCES)
        if (sources.isEmpty()) return@withContext emptyList()
        val mapped: List<Pair<VodItemEntity, List<VodEpisodeEntity>>> = coroutineScope {
            sources.map { source ->
                async {
                    val dto = try {
                        cmsClient.fetchDetailPage(source, page = 1, search = trimmed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        null
                    }
                    dto?.list.orEmpty()
                        .take(ONLINE_SEARCH_MAX_PER_SOURCE)
                        .mapNotNull { item -> mapCmsItem(source, item) }
                }
            }.awaitAll().flatten()
        }
        // 写入缓存（upsert 增量，不整源替换，避免清掉已有浏览缓存）
        mapped.map { it.first }.chunked(DB_CHUNK).forEach { vodDao.upsertAll(it) }
        mapped.flatMap { it.second }.chunked(DB_CHUNK).forEach { vodDao.upsertEpisodes(it) }
        mapped.map { it.first }
    }

    /**
     * 单条 MacCMS DTO 映射为本地条目 + 分集：
     * 电影取首条播放线直链；剧集解析全部播放线为分集（season = 线路序号，可切线路）。
     */
    private fun mapCmsItem(
        source: SourceEntity,
        item: CmsVodItemDto,
    ): Pair<VodItemEntity, List<VodEpisodeEntity>>? {
        val id = item.vodId?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        val title = item.vodName?.trim().takeIf { it.isNullOrEmpty() == false } ?: return null
        val categoryName = item.typeName?.trim().takeIf { it.isNullOrEmpty() == false }
            ?: item.vodClass?.trim().takeIf { it.isNullOrEmpty() == false } ?: "未分类"
        val kindOfItem = VodKindClassifier.ofCategory(categoryName)
        val playSources = CmsVodClient.parsePlaySources(item.vodPlayFrom, item.vodPlayUrl)
        val primary = playSources.firstOrNull() ?: return null
        val isSeries = kindOfItem == VodKind.SERIES || primary.episodes.size > 1
        val year = item.vodYear?.trim()?.takeIf { it.length == 4 }
        val entity = VodItemEntity(
            sourceId = source.id,
            id = id,
            title = title,
            posterUrl = item.vodPic?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.vodPicThumb?.trim()?.takeIf { it.isNotEmpty() },
            year = year,
            rating = item.vodScore?.toString()?.toDoubleOrNull(),
            overview = item.vodBlurb?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.vodContent?.trim()?.takeIf { it.isNotEmpty() },
            cast = splitNames(item.vodActor).orEmpty(),
            category = categoryName,
            kind = if (isSeries) VodKind.SERIES else kindOfItem,
            playUrl = if (isSeries) null else primary.episodes.firstOrNull()?.url,
            totalEpisodes = if (isSeries) primary.episodes.size else null,
            latestEpisode = if (isSeries) primary.episodes.size else null,
            // 站点更新时间（vod_time）：列表查询统一 ORDER BY updatedAt DESC，必须存站点时间
            // 而非入库时间，否则「最新在前」排序失效（曾导致海报墙顺序错乱）
            updatedAt = parseCmsTime(item.vodTime),
        )
        val episodes = if (isSeries) {
            val all = ArrayList<VodEpisodeEntity>()
            playSources.forEachIndexed { lineIndex, line ->
                line.episodes.forEachIndexed { epIndex, episode ->
                    all += VodEpisodeEntity(
                        sourceId = source.id,
                        seriesId = id,
                        season = lineIndex + 1,
                        episode = epIndex + 1,
                        title = episode.title,
                        playUrl = episode.url,
                    )
                }
            }
            all
        } else if (playSources.size > 1) {
            // 电影多线路：每条线路存一行（season=线路序号、episode=1、title=线路名），
            // 播放时作为备用线路（主线路 403/失效自动切换），详情页按 kind=电影不显示选集
            playSources.mapIndexed { lineIndex, line ->
                val first = line.episodes.first()
                VodEpisodeEntity(
                    sourceId = source.id,
                    seriesId = id,
                    season = lineIndex + 1,
                    episode = 1,
                    title = line.name,
                    playUrl = first.url,
                )
            }
        } else {
            emptyList()
        }
        return entity to episodes
    }

    /* ============================== 刷新（智能追更同步） ============================== */

    /**
     * 刷新点播缓存（IO 协程、可取消、带进度回调，需求 5.3.2/5.4.3）。
     * @param kind 仅刷新指定品类（null = 全部）
     * @param onlySourceId 仅刷新指定资源站（首页切线路自动同步用；null = 全部线路）
     */
    suspend fun refresh(
        kind: String? = null,
        onlySourceId: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ) = withContext(io) {
        var sources = sourceDao.getAll()
            .filter { it.enabled && (it.type == SourceType.XTREAM || it.type == SourceType.CMS) }
        if (onlySourceId != null) {
            sources = sources.filter { it.id == onlySourceId }
        }
        if (sources.isEmpty()) {
            onProgress(
                ImportProgress(
                    stage = Stage.FAILED,
                    message = if (onlySourceId != null) {
                        "所选线路无需同步（仅直播源或未启用点播）"
                    } else {
                        "未找到已启用的点播源，请先在设置中心添加 Xtream / MacCMS / TVBox 源"
                    },
                )
            )
            return@withContext
        }
        onProgress(ImportProgress(stage = Stage.PREPARING, message = "正在同步点播资源…"))
        var totalSaved = 0
        var anyFailure = false
        // 圈内显示值（只增不减）：跨线路累加（第 1 线 20 部 + 第 2 线 20 部 → 圈里显示 40），
        // TMDB 刮削等以索引上报进度的阶段不回落，同步结束停在累计总数
        var displayedCount = 0
        fun report(p: ImportProgress, offset: Int = 0) {
            displayedCount = maxOf(displayedCount, offset + p.processed)
            onProgress(p.copy(processed = displayedCount))
        }
        try {
            sources.forEach { source ->
                // 偏移 = 已完成线路的写入片数：新线路的进度在此基础上继续累加
                val offset = totalSaved
                val sourceReport: (ImportProgress) -> Unit = { p -> report(p, offset) }
                val saved = if (source.type == SourceType.CMS) {
                    refreshCmsSource(source, kind, sourceReport) { anyFailure = true }
                } else {
                    refreshXtreamSource(source, kind, sourceReport) { anyFailure = true }
                }
                totalSaved += saved
            }
            lastRefreshAt = System.currentTimeMillis()
            report(
                ImportProgress(
                    stage = Stage.DONE,
                    processed = totalSaved,
                    total = totalSaved,
                    message = if (anyFailure) "部分源同步失败，已缓存可用内容" else "同步完成，共 ${totalSaved} 部影片",
                )
            )
        } catch (e: CancellationException) {
            onProgress(ImportProgress(stage = Stage.FAILED, message = "已取消同步"))
            throw e
        } catch (t: Throwable) {
            onProgress(
                ImportProgress(
                    stage = Stage.FAILED,
                    message = "同步失败：${t.message ?: "网络异常"}",
                )
            )
        } finally {
            // 数据库已重建（含失败/取消时的部分写入）：通知首页等快照列表重查，避免旧条目残留
            _refreshTick.value = _refreshTick.value + 1
        }
    }

    /**
     * 线路初始加载（打开点播页 / 首页切线路 / 手动刷新按钮）：
     * 硬清该线路缓存后只拉前 [INITIAL_LOAD_ITEMS] 条（约前 5 页），加载快、不阻塞；
     * 剩余影片由 [loadMoreItems] 在用户滚动到底时增量拉取，直到该线路全部加载完。
     * Xtream 协议无服务端分页，仍为单次全量请求（展示层再分页）。
     */
    private suspend fun refreshInitial(
        sourceId: String,
        onProgress: (ImportProgress) -> Unit,
    ) = withContext(io) {
        val source = sourceDao.get(sourceId)
        if (source == null || !source.enabled ||
            (source.type != SourceType.CMS && source.type != SourceType.XTREAM)
        ) {
            onProgress(
                ImportProgress(
                    stage = Stage.FAILED,
                    message = "所选线路无需同步（仅直播源或未启用点播）",
                ),
            )
            return@withContext
        }
        // 硬刷新：清空该线路本地缓存（影片/分集/统计），本次只展示从服务器重新拉回的数据
        cmsPaging.remove(sourceId)
        cmsTypeKinds.remove(sourceId)
        cmsKindPaging.remove(sourceId)
        vodDao.deleteBySource(sourceId)
        vodDao.deleteEpisodesBySource(sourceId)
        sourceDao.updateStats(id = sourceId, channelCount = 0, groupCount = 0, syncedAt = 0)
        onProgress(ImportProgress(stage = Stage.PREPARING, message = "正在加载「${source.name}」…"))
        val saved = try {
            if (source.type == SourceType.CMS) {
                refreshCmsSource(
                    source = source,
                    kind = null,
                    onProgress = onProgress,
                    budgetItems = INITIAL_LOAD_ITEMS,
                    markFailure = { },
                )
            } else {
                refreshXtreamSource(source, kind = null, onProgress) { }
            }
        } catch (e: CancellationException) {
            onProgress(ImportProgress(stage = Stage.FAILED, message = "已取消同步"))
            throw e
        } catch (t: Throwable) {
            onProgress(
                ImportProgress(stage = Stage.FAILED, message = "同步失败：${t.message ?: "网络异常"}"),
            )
            0
        }
        lastRefreshAt = System.currentTimeMillis()
        onProgress(
            ImportProgress(
                stage = Stage.DONE,
                processed = saved,
                total = saved,
                message = "已加载前 $saved 部，滚动到底继续加载",
            ),
        )
        _refreshTick.value = _refreshTick.value + 1
    }

    /**
     * 滚动到底增量加载（MacCMS 服务端分页）：从断点页继续拉取入库，
     * 直到新增 ≥ [needed] 条或到达资源站末页；增量写库，海报墙随滚动实时增长。
     * [kind] = 当前选中的品类标签（电影/电视剧/综艺/动漫，null = 全部）：
     * MacCMS 协议无品类参数，翻页途中非该品类的条目也入缓存、但**不计入条数**，
     * 保证「选电影滚动一次 = 实打实追加 50 部电影」，而不是混杂 50 条。
     * 同线路同时只允许一个任务（滚动触发密集，tryLock 去抖）；Xtream 一次请求即全量，无需增量。
     * @return 实际计入条数（kind 口径的新增入库条数）
     */
    suspend fun loadMoreItems(
        sourceId: String,
        needed: Int = LOAD_MORE_ITEMS,
        kind: String? = null,
    ): Int = withContext(io) {
        val source = sourceDao.get(sourceId)
        if (source == null || source.type != SourceType.CMS) return@withContext 0
        if (!loadMoreLock.tryLock()) return@withContext 0
        try {
            // 选中标签：优先走服务端分类过滤（MacCMS t=分类id）——返回的整页都是该品类，
            // 约 3 页就凑满一批，不再「翻好几页才碰到几条」。分类 id 未学到时直接走通用翻页；
            // 已学到但服务器不认（首翻空页/整页都不是该标签）返回 -1 → 作废分类 id 后，
            // **同一次调用内**回退通用翻页按标签凑数，不让用户对着空列表干等一次滚动。
            if (!kind.isNullOrBlank()) {
                val typeIds = cmsTypeKinds[sourceId]
                    ?.filterValues { it == kind }?.keys?.toList().orEmpty()
                if (typeIds.isNotEmpty()) {
                    val byType = loadMoreByType(source, kind, typeIds, needed)
                    if (byType >= 0) return@withContext byType
                }
            }
            var paging = cmsPaging[sourceId]
            if (paging == CmsPaging.Exhausted) return@withContext 0
            if (paging !is CmsPaging.Continue) {
                // 冷启动断点丢失（仅存内存）：按缓存条数推算已加载页码（每页 [CMS_PAGE_SIZE] 条）
                val cached = vodDao.countBySource(sourceId)
                paging = CmsPaging.Continue(nextPage = cached / CMS_PAGE_SIZE + 1, totalPages = 0)
            }
            var nextPage = paging.nextPage
            var totalPages = paging.totalPages.takeIf { it > 1 } ?: 0 // 0 = 服务端未提供页数，靠空页停止
            var added = 0
            var exhausted = false
            var fetchFailed = false
            var lastFirstId: String? = null
            while (added < needed && !exhausted && !fetchFailed) {
                currentCoroutineContext().ensureActive()
                // 并行波次：一次并发抓 [WAVE_PAGES] 页，再按页序逐页处理入库——
                // 3 个串行网络往返并成 1 个，一批 50 条的滚动等待约为原来的 1/3
                //（市面主流的预取式加载：抓下一批与浏览当前批并行）。
                val wavePages = if (totalPages > 0) {
                    (nextPage..minOf(nextPage + WAVE_PAGES - 1, totalPages)).toList()
                } else {
                    (nextPage until nextPage + WAVE_PAGES).toList()
                }
                // 断点页已越过服务端总页数（如预算恰在末页截断）：视作到头，防空波次死循环
                if (wavePages.isEmpty()) {
                    exhausted = true
                    break
                }
                val wave = coroutineScope {
                    wavePages.map { p -> async { p to fetchDetailPageWithRetry(source, p) } }.awaitAll()
                }
                var stopWave = false
                for ((pageIndex, dto) in wave) {
                    if (dto == null) {
                        // 重试后仍失败：断点停在失败页（不跳页），下次滚动从这里续拉
                        nextPage = pageIndex
                        fetchFailed = true
                        stopWave = true
                        break
                    }
                    val list = dto.list.orEmpty()
                    // 空页 = 到末页；与上一页首条相同 = 资源站忽略 pg 翻页参数（防死循环）
                    val firstId = list.firstOrNull()?.vodId?.toString()
                    if (list.isEmpty() || (lastFirstId != null && lastFirstId == firstId)) {
                        exhausted = true
                        stopWave = true
                        break
                    }
                    lastFirstId = firstId
                    val entities = ArrayList<VodItemEntity>(list.size)
                    val episodes = ArrayList<VodEpisodeEntity>()
                    var matchedCount = 0
                    list.forEach { item ->
                        val mapped = mapCmsItem(source, item) ?: return@forEach
                        entities += mapped.first
                        episodes += mapped.second
                        // 持续学习「分类 id → 品类」，下次该标签直接走服务端过滤
                        rememberCmsType(sourceId, item.typeId, mapped.first.category)
                        // 计数口径 = 当前标签（选了标签就只认该品类，凑满 needed 条为止）
                        if (kind.isNullOrBlank() || mapped.first.kind == kind) matchedCount++
                    }
                    if (entities.isNotEmpty()) {
                        vodDao.upsertAll(entities)
                        if (episodes.isNotEmpty()) vodDao.upsertEpisodes(episodes)
                        added += matchedCount
                    }
                    // 只信任 >1 的页数（无 pagecount 的站点按 1 返回，靠空页兜底停止）
                    val reported = dto.pageInt
                    if (reported > 1) totalPages = reported
                    nextPage = pageIndex + 1
                    if (totalPages > 0 && nextPage > totalPages) {
                        exhausted = true
                        stopWave = true
                        break
                    }
                }
                if (stopWave) break
            }
            cmsPaging[sourceId] =
                if (exhausted) CmsPaging.Exhausted else CmsPaging.Continue(nextPage, totalPages)
            if (added > 0) {
                // 只回写片数（保留原分类数/同步时间），设置页与首页分页上限随之更新
                val cached = vodDao.countBySource(sourceId)
                val stats = sourceDao.get(sourceId)
                sourceDao.updateStats(
                    id = sourceId,
                    channelCount = cached,
                    groupCount = stats?.groupCount ?: 0,
                    syncedAt = stats?.lastSyncAt ?: System.currentTimeMillis(),
                )
                _refreshTick.value = _refreshTick.value + 1
            }
            added
        } finally {
            loadMoreLock.unlock()
        }
    }

    /**
     * 标签增量加载（服务端分类过滤）：`t=分类id` 直接向资源站要该品类的下一批，
     * 返回的整页（默认 20 条）全部是所选标签内容——约 3 页凑满 50 条，一批到达、不再零星冒条。
     *
     * 起始页估算：分类过滤流按更新时间倒序、与无过滤流同序，已缓存的该品类条数
     * （[VodDao.countBySourceKind]）即该流的头部，从下一页开始避免重复拉已缓存内容。
     * 分类 id 由分类名**模糊学习**（[VodKindClassifier]：日漫/国产动漫/少儿…均归动漫），
     * 每翻一页继续累积新分类 id。
     *
     * **过滤有效性校验**：首翻空页（本地明明有该标签内容）或整页条目都不是该标签
     * = 服务器没认 t= 参数（或分类 id 学错）→ 作废该标签已学 id、不记末页断点，
     * 返回 -1 让调用方同一次调用内回退通用翻页凑数（用户反馈：动漫/综艺标签不加载）。
     *
     * @return 本标签新增条数；-1 = t= 过滤不可用已回退（id 已作废）
     */
    private suspend fun loadMoreByType(
        source: SourceEntity,
        kind: String,
        typeIds: List<String>,
        needed: Int,
    ): Int {
        val kindPaging = cmsKindPaging.getOrPut(source.id) { ConcurrentHashMap() }
        val storedPage = kindPaging[kind]
        if (storedPage == Int.MAX_VALUE) return 0 // 该标签此前已到末页
        // 首次为该标签加载：从「已缓存条数 / 每页」推算起始页，跳过已有的头部内容
        var page = storedPage ?: (vodDao.countBySourceKind(source.id, kind) / CMS_PAGE_SIZE + 1)
        val startPage = page
        val freshRun = storedPage == null
        var added = 0
        var exhausted = false
        var filterBroken = false
        var fetchFailed = false
        var lastFirstId: String? = null
        var totalPages = 0
        while (added < needed && !exhausted && !filterBroken && !fetchFailed) {
            currentCoroutineContext().ensureActive()
            // 并行波次：一次并发抓 [WAVE_PAGES] 页再按页序处理——凑满一批约 3 页，
            // 从 3 个串行网络往返降为 1 个（预取式加载，滚动等待约为原来的 1/3）
            val wavePages = if (totalPages > 0) {
                (page..minOf(page + WAVE_PAGES - 1, totalPages)).toList()
            } else {
                (page until page + WAVE_PAGES).toList()
            }
            // 断点页已越过服务端总页数：视作到头，防空波次死循环
            if (wavePages.isEmpty()) {
                exhausted = true
                break
            }
            val wave = coroutineScope {
                wavePages.map { p ->
                    async { p to fetchDetailPageWithRetry(source, p, categoryId = typeIds.joinToString(",")) }
                }.awaitAll()
            }
            var stopWave = false
            for ((pageIndex, dto) in wave) {
                if (dto == null) {
                    // 网络失败：断点停在失败页（不跳页），保留分类 id，下次滚动重试
                    page = pageIndex
                    fetchFailed = true
                    stopWave = true
                    break
                }
                val list = dto.list.orEmpty()
                val firstId = list.firstOrNull()?.vodId?.toString()
                // 空页 = 分类流到末页；与上一页首条相同 = 站点忽略翻页参数（防死循环）
                if (list.isEmpty() || (lastFirstId != null && lastFirstId == firstId)) {
                    // 首翻就空：本地有该标签内容而 t= 流头部为空 → 过滤没生效（id 学错/不支持）
                    if (list.isEmpty() && freshRun && pageIndex == startPage && added == 0) {
                        filterBroken = true
                    } else {
                        exhausted = true
                    }
                    stopWave = true
                    break
                }
                lastFirstId = firstId
                val entities = ArrayList<VodItemEntity>(list.size)
                val episodes = ArrayList<VodEpisodeEntity>()
                var matched = 0
                list.forEach { item ->
                    val mapped = mapCmsItem(source, item) ?: return@forEach
                    entities += mapped.first
                    episodes += mapped.second
                    // 服务端 t= 过滤期间也继续学习分类 id（细分小类逐步补齐：日漫/国产动漫…）
                    rememberCmsType(source.id, item.typeId, mapped.first.category)
                    if (mapped.first.kind == kind) matched++
                }
                if (entities.isNotEmpty()) {
                    vodDao.upsertAll(entities)
                    if (episodes.isNotEmpty()) vodDao.upsertEpisodes(episodes)
                    added += matched
                }
                // 整页都不是该标签：服务器忽略了 t= 参数（或分类 id 学错）→ 本流不可用
                if (list.isNotEmpty() && matched == 0) {
                    filterBroken = true
                    stopWave = true
                    break
                }
                val reported = dto.pageInt
                if (reported > 1) totalPages = reported
                page = pageIndex + 1
                if (totalPages > 0 && page > totalPages) {
                    exhausted = true
                    stopWave = true
                    break
                }
            }
            if (stopWave) break
        }
        if (filterBroken) {
            // 作废该标签已学的分类 id（大概率是 6.0 双精度串/父分类 id/站点不支持 t=），
            // 不记末页断点；本标签此后回退通用翻页，重新学习到的 id 会自动再试 t=
            val learned = cmsTypeKinds[source.id]
            learned?.filterValues { it == kind }?.keys?.toList()
                ?.forEach { learned.remove(it) }
            return -1
        }
        // 断点续存：末页置 MAX_VALUE 停止后续请求；失败保留页码下次滚动重试
        kindPaging[kind] = when {
            exhausted -> Int.MAX_VALUE
            else -> page
        }
        return added
    }

    /**
     * Xtream 源单源刷新：分类 + 全量流列表 + 分块写库 + TMDB 刮削。
     * @return 本源写入条目数；失败时置 markFailure 并返回 0
     */
    private suspend fun refreshXtreamSource(
        source: SourceEntity,
        kind: String?,
        onProgress: (ImportProgress) -> Unit,
        markFailure: () -> Unit,
    ): Int {
        val categoryNameById = try {
            xtreamClient.fetchCategories(source).associate { it.idString to (it.categoryName ?: "未分类") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            markFailure()
            onProgress(ImportProgress(stage = Stage.FAILED, message = "「${source.name}」连接失败"))
            return 0
        }
        // 点播列表是单次大请求（可能数十秒）：先报「下载中」，避免长等待期间无任何动静
        onProgress(ImportProgress(stage = Stage.DOWNLOADING, message = "正在下载「${source.name}」点播列表…"))
        val streams = try {
            xtreamClient.fetchStreams(source)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            markFailure()
            onProgress(ImportProgress(stage = Stage.FAILED, message = "「${source.name}」点播列表获取失败"))
            return 0
        }
        val total = streams.size
        val items = ArrayList<VodItemEntity>(total)
        // 解析即入库：影视页海报墙随同步实时增长（与 CMS 逐页入库同口径），不再等整源解析完才写
        var written = 0
        val pending = ArrayList<VodItemEntity>()
        suspend fun flushPending() {
            if (pending.isEmpty()) return
            // 与收尾 matched 同口径过滤（kind 参数），入库数 == 最终写入数
            val chunk =
                if (kind.isNullOrBlank()) pending.toList() else pending.filter { it.kind == kind }
            pending.clear()
            if (chunk.isEmpty()) return
            vodDao.upsertAll(chunk)
            written += chunk.size
        }
        streams.forEachIndexed { index, stream ->
            val streamId = stream.idString ?: return@forEachIndexed
            val title = stream.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return@forEachIndexed
            val categoryName = categoryNameById[stream.categoryId?.toString()] ?: "未分类"
            val kindOfItem = VodKindClassifier.of(stream.streamType, categoryName)
            val isSeries = kindOfItem == VodKind.SERIES
            val playUrl = stream.directSource?.takeIf { it.isNotBlank() }
                ?: xtreamClient.playUrl(source, streamId, stream.containerExtension, isSeries)
            if (playUrl != null) {
                val entity = VodItemEntity(
                    sourceId = source.id,
                    id = streamId,
                    title = title,
                    posterUrl = stream.streamIcon?.trim()?.takeIf { it.isNotEmpty() },
                    rating = stream.ratingDouble,
                    category = categoryName,
                    kind = kindOfItem,
                    playUrl = if (isSeries) null else playUrl,
                    updatedAt = stream.addedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
                items += entity
                pending += entity
            }
            if (pending.size >= 200) flushPending()
            // 每 200 条上报一次：数字 = 已入库条数（服务器返回且实际可用的影视条目数）
            if (index % 200 == 0) {
                onProgress(
                    ImportProgress(
                        stage = Stage.PARSING,
                        processed = written,
                        total = total,
                        message = "正在解析「${source.name}」点播列表…",
                    )
                )
            }
        }
        flushPending()
        val matched = if (kind.isNullOrBlank()) items else items.filter { it.kind == kind }
        onProgress(
            ImportProgress(
                stage = Stage.SAVING,
                processed = matched.size,
                total = total,
                message = "正在写入「${source.name}」缓存…",
            )
        )
        // 事务内全量收口（与 CMS 同口径）：解析期间已增量入库，这里原子替换掉服务器已下架的旧条目；
        // 事务保证替换期间海报墙不出现「清空 → 重填」的空窗闪烁
        vodDao.replaceSource(source.id, matched)
        // 回写源统计（设置页「X 部影片」与同步时间）
        sourceDao.updateStats(
            id = source.id,
            channelCount = matched.size,
            groupCount = matched.mapNotNull { it.category }.distinct().size,
            syncedAt = System.currentTimeMillis(),
        )
        // TMDB 自动刮削（需求 5.4.2）：补全缺失元数据的条目，Key 未配置时静默跳过
        scrapeMissingMetadata(matched, onProgress)
        return matched.size
    }

    /**
     * MacCMS 采集源单源刷新（TVBox 资源站通用协议）：
     * 逐页拉取 ac=detail（含播放地址），映射条目与分集后写库。
     * 电影取首条播放线直链；剧集解析全部分集入库（详情页可选线路）。
     * @param budgetItems 预算截断（点播页初始加载）：累计条数达到该值即停，
     *   剩余影片由 [loadMoreItems] 滚动增量拉取；null = 整源全量刷新
     */
    private suspend fun refreshCmsSource(
        source: SourceEntity,
        kind: String?,
        onProgress: (ImportProgress) -> Unit,
        budgetItems: Int? = null,
        markFailure: () -> Unit,
    ): Int {
        val firstPage = try {
            cmsClient.fetchDetailPage(source, page = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            markFailure()
            onProgress(ImportProgress(stage = Stage.FAILED, message = "「${source.name}」连接失败"))
            return 0
        }
        var totalPages = firstPage.pageInt.coerceAtLeast(1) // 有效页数（>1 才可信；无 pagecount 的站点按 1 返回）
        val items = ArrayList<VodItemEntity>(firstPage.totalInt.coerceAtLeast(0))
        val episodes = HashMap<String, List<VodEpisodeEntity>>()
        var page = 1
        var dto = firstPage
        var fetchFailed = false
        var budgetHit = false
        var prevFirstId: String? = null
        // 圈内小数字要显示「已加载部数」：总长按资源站返回的总条数估算，接口没给就按页数×每页条数
        val estimatedTotal = firstPage.totalInt.takeIf { it > 0 }
            ?: totalPages * (firstPage.list?.size?.takeIf { it > 0 } ?: 20)
        // 已入库条数（实际返回并写进缓存的具体数字，不是请求数）
        var written = 0
        // 并行预取缓冲：翻页决策通过后一次并发抓 [WAVE_PAGES] 页入队，按页序逐页消费——
        // N 个串行网络往返并成 ⌈N/WAVE_PAGES⌉ 次（首批 100 条从 5 次降为 2 次，
        // 整源同步同理提速约 3 倍；预取到的页丢弃发生在预算/末页边界，量很小）
        var pending = emptyList<Pair<Int, CmsVodListDto>>()
        var pendingIdx = 0
        var failedAt: Int? = null
        suspend fun prefetchWave(startPage: Int): List<Pair<Int, CmsVodListDto>> {
            if (failedAt != null) return emptyList()
            val wavePages = if (totalPages > 1) {
                (startPage..minOf(startPage + WAVE_PAGES - 1, totalPages)).toList()
            } else {
                (startPage until startPage + WAVE_PAGES).toList()
            }
            val results = coroutineScope {
                wavePages.map { p -> async { p to fetchDetailPageWithRetry(source, p) } }.awaitAll()
            }
            val ok = ArrayList<Pair<Int, CmsVodListDto>>(results.size)
            for ((p, d) in results) {
                if (d == null) {
                    // 首个失败页：前面成功页照常消费，消费完再判定中断（断点停在失败页，不跳页）
                    failedAt = p
                    break
                }
                ok.add(p to d)
            }
            return ok
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val pageEntities = ArrayList<VodItemEntity>()
            dto.list.orEmpty().forEach { item ->
                val mapped = mapCmsItem(source, item) ?: return@forEach
                val (entity, allEpisodes) = mapped
                items += entity
                // 持续学习「分类 id → 品类」，供标签滚动加载走服务端 t= 过滤
                rememberCmsType(source.id, item.typeId, entity.category)
                if (allEpisodes.isNotEmpty()) episodes[entity.id] = allEpisodes
                // 与收尾 matched 同口径过滤，保证入库数 == 最终写入数
                if (kind.isNullOrBlank() || entity.kind == kind) pageEntities += entity
            }
            // 逐页立即入库：影视页海报墙随同步实时增长（Room Flow 自动刷新），不再等整源拉完才变
            if (pageEntities.isNotEmpty()) {
                vodDao.upsertAll(pageEntities)
                written += pageEntities.size
                // 分集随页写入：同步期间点开新剧集详情也能直接看到分集
                val pageEpisodes = pageEntities.flatMap { e -> episodes[e.id].orEmpty() }
                if (pageEpisodes.isNotEmpty()) vodDao.upsertEpisodes(pageEpisodes)
            }
            // 圈内数字 = 已入库条数（返回来的真实数字），实时上报（页数未知显示 ?）
            onProgress(
                ImportProgress(
                    stage = Stage.PARSING,
                    processed = written,
                    total = estimatedTotal,
                    message = "正在同步「${source.name}」第 $page/${totalPages.takeIf { it > 1 } ?: "?"} 页…",
                )
            )
            if (dto.list.isNullOrEmpty()) break
            // 资源站忽略 pg 翻页参数（连续两页首条相同）：视为到末页，防死循环
            val pageFirstId = dto.list?.firstOrNull()?.vodId?.toString()
            if (pageFirstId != null && pageFirstId == prevFirstId) break
            prevFirstId = pageFirstId
            // 预算截断：够首批即停，断点记入增量分页状态，剩余随滚动加载
            if (budgetItems != null && items.size >= budgetItems) {
                budgetHit = true
                break
            }
            if (totalPages > 1) {
                if (page >= totalPages) break
            } else if (budgetItems == null) {
                // 整源刷新且服务端没给有效页数：维持旧口径停在第 1 页（预算加载则继续翻页，靠空页/重复页兜底）
                break
            }
            // 决策通过：优先出预取队列，空了并发预取下一批（并行化核心：串行往返 → 1 次/批）
            if (pendingIdx >= pending.size) {
                pending = prefetchWave(page + 1)
                pendingIdx = 0
                if (pending.isEmpty()) {
                    // 后续页重试后仍失败：中断但保留已取内容（写库阶段与现有库存比对，避免刷成残缺）
                    fetchFailed = true
                    break
                }
            }
            val (nextPage, nextDto) = pending[pendingIdx]
            pendingIdx++
            page = nextPage
            dto = nextDto
            // 服务端可能在后续页更新总页数（部分站点首响应给 0/1）
            val reportedPages = nextDto.pageInt
            if (reportedPages > 1) totalPages = reportedPages
            // 每 50 页回写一次统计，长任务进行中也能看到片数增长
            if (page % STATS_EVERY_PAGES == 0) {
                sourceDao.updateStats(
                    id = source.id,
                    channelCount = items.size,
                    groupCount = items.mapNotNull { it.category }.distinct().size,
                    syncedAt = System.currentTimeMillis(),
                )
            }
        }
        // 增量分页断点：预算截断/翻页中断 → 记录续传页（滚动到底时继续拉）；正常拉完 → 已到末页
        // totalPages 归一：≤1 视为「服务端未提供页数」存 0（靠空页兜底停止）
        cmsPaging[source.id] = when {
            budgetHit || fetchFailed -> CmsPaging.Continue(
                nextPage = page + 1,
                totalPages = totalPages.takeIf { it > 1 } ?: 0,
            )
            else -> CmsPaging.Exhausted
        }
        val matched = if (kind.isNullOrBlank()) items else items.filter { it.kind == kind }
        onProgress(
            ImportProgress(
                stage = Stage.SAVING,
                processed = matched.size,
                total = items.size,
                message = "正在写入「${source.name}」缓存…",
            )
        )
        val existingCount = vodDao.countBySource(source.id)
        if (matched.isEmpty() && existingCount > 0) {
            // 采集结果为空但库存非空：视为接口异常，不覆盖现有资源
        } else if (!fetchFailed || matched.size >= existingCount) {
            // 完整同步（或新数据更多）→ 事务内全量快照替换
            vodDao.replaceSource(source.id, matched)
        } else {
            // 分页中断且少于现有库存 → 只合并写入，绝不把已有资源刷成残缺
            matched.chunked(DB_CHUNK).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                vodDao.upsertAll(chunk)
            }
        }
        // 回写源统计（设置页「X 部影片」与同步时间；分类数作为分组数展示）
        sourceDao.updateStats(
            id = source.id,
            channelCount = matched.size.coerceAtLeast(existingCount),
            groupCount = matched.mapNotNull { it.category }.distinct().size,
            syncedAt = System.currentTimeMillis(),
        )
        // 分集整批写库（剧集平台约定 5.4.1）
        episodes.entries
            .filter { (seriesId, _) -> matched.any { it.id == seriesId } }
            .flatMap { it.value }
            .chunked(DB_CHUNK).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                vodDao.upsertEpisodes(chunk)
            }
        scrapeMissingMetadata(matched, onProgress)
        return matched.size
    }

    /**
     * 分页拉取（带重试）：单页瞬时失败自动重试 [PAGE_FETCH_RETRIES] 次，
     * 避免一次抖动导致整源同步被截断成「只有第 1 页」（用户反馈的 20 部问题）。
     * @param categoryId 分类过滤（MacCMS t= 参数，逗号分隔多 id；null = 全部分类）
     * @return 第 [page] 页数据；重试耗尽返回 null
     */
    private suspend fun fetchDetailPageWithRetry(
        source: SourceEntity,
        page: Int,
        categoryId: String? = null,
    ): CmsVodListDto? {
        repeat(PAGE_FETCH_RETRIES) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return cmsClient.fetchDetailPage(source, page = page, categoryId = categoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (attempt < PAGE_FETCH_RETRIES - 1) delay(PAGE_RETRY_DELAY_MS)
            }
        }
        return null
    }

    /**
     * 详情打开时的元数据补全：先合并源站自带元数据（Xtream get_vod_info / MacCMS ac=detail&ids），
     * 再走 TMDB 刮削。失败静默降级返回已缓存实体（需求 6.4 稳定性）。
     */
    suspend fun enrich(item: VodItemEntity): VodItemEntity = withContext(io) {
        var merged = item
        val source = sourceDao.get(item.sourceId)
        if (source != null && source.type == SourceType.CMS) {
            // MacCMS：ac=detail&ids 取详情，合并演员/简介/年份/评分，并同步全部分集
            val dto = try {
                cmsClient.fetchDetailByIds(source, item.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (dto != null) {
                val playSources = CmsVodClient.parsePlaySources(dto.vodPlayFrom, dto.vodPlayUrl)
                val isSeries = merged.kind == VodKind.SERIES ||
                    playSources.any { it.episodes.size > 1 }
                merged = merged.copy(
                    posterUrl = dto.vodPic?.trim()?.takeIf { it.isNotEmpty() } ?: merged.posterUrl,
                    rating = dto.vodScore?.toString()?.toDoubleOrNull() ?: merged.rating,
                    overview = dto.vodBlurb?.trim()?.takeIf { it.isNotEmpty() }
                        ?: dto.vodContent?.trim()?.takeIf { it.isNotEmpty() } ?: merged.overview,
                    genres = splitNames(dto.vodClass) ?: merged.genres,
                    cast = splitNames(dto.vodActor) ?: merged.cast,
                    year = dto.vodYear?.trim()?.takeIf { it.length == 4 } ?: merged.year,
                    kind = if (isSeries) VodKind.SERIES else merged.kind,
                    playUrl = if (isSeries) merged.playUrl
                    else playSources.firstOrNull()?.episodes?.firstOrNull()?.url ?: merged.playUrl,
                    totalEpisodes = if (isSeries) {
                        playSources.maxOfOrNull { it.episodes.size } ?: merged.totalEpisodes
                    } else merged.totalEpisodes,
                )
                if (isSeries && playSources.isNotEmpty()) {
                    val all = ArrayList<VodEpisodeEntity>()
                    playSources.forEachIndexed { lineIndex, line ->
                        line.episodes.forEachIndexed { epIndex, episode ->
                            all += VodEpisodeEntity(
                                sourceId = item.sourceId,
                                seriesId = item.id,
                                season = lineIndex + 1,
                                episode = epIndex + 1,
                                title = episode.title,
                                playUrl = episode.url,
                            )
                        }
                    }
                    vodDao.upsertEpisodes(all)
                }
                if (merged != item) vodDao.upsertAll(listOf(merged))
            }
        } else if (source != null) {
            val info = try {
                xtreamClient.fetchVodInfo(source, item.id)?.info
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (info != null) {
                merged = merged.copy(
                    posterUrl = info.movieImage?.ifBlank { null } ?: info.cover?.ifBlank { null } ?: merged.posterUrl,
                    backdropUrl = merged.backdropUrl ?: info.backdropUrl,
                    rating = info.ratingDouble ?: merged.rating,
                    overview = info.plot?.ifBlank { null } ?: merged.overview,
                    genres = splitNames(info.genre) ?: merged.genres,
                    cast = splitNames(info.cast) ?: merged.cast,
                    year = yearOf(info.releaseDate ?: info.releaseDateAlt) ?: merged.year,
                    tmdbId = info.tmdbIdLong ?: merged.tmdbId,
                )
                if (merged != item) vodDao.upsertAll(listOf(merged))
            }
        }
        tmdbRepository.scrapeFor(merged)
    }

    /* ============================== 内部实现 ============================== */

    /** 刷新后对缺失海报/简介的条目做 TMDB 轻量刮削（单源限量，保证刷新时长可控） */
    private suspend fun scrapeMissingMetadata(
        items: List<VodItemEntity>,
        onProgress: (ImportProgress) -> Unit,
    ) {
        if (!tmdbRepository.isConfigured()) return
        val missing = items.filter { it.posterUrl.isNullOrBlank() || it.overview.isNullOrBlank() }
        if (missing.isEmpty()) return
        missing.take(MAX_SCRAPES_PER_REFRESH).forEach { item ->
            currentCoroutineContext().ensureActive()
            onProgress(
                ImportProgress(
                    stage = Stage.PARSING,
                    processed = items.indexOf(item),
                    total = items.size,
                    message = "TMDB 刮削补全「${item.title}」…",
                )
            )
            tmdbRepository.scrapeFor(item, includeEpisodes = false)
        }
    }

    /** get_vod_info 解析剧集分集并写库（需求 5.4.1 剧集平台约定） */
    private suspend fun fetchAndSaveEpisodes(
        sourceId: String,
        seriesId: String,
    ): List<VodEpisodeEntity> {
        val source = sourceDao.get(sourceId) ?: return emptyList()

        // MacCMS 源：ac=detail&ids 取详情并解析全部播放线为分集
        if (source.type == SourceType.CMS) {
            val dto = try {
                cmsClient.fetchDetailByIds(source, seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return emptyList()
            } ?: return emptyList()
            val playSources = CmsVodClient.parsePlaySources(dto.vodPlayFrom, dto.vodPlayUrl)
            val entities = ArrayList<VodEpisodeEntity>()
            playSources.forEachIndexed { lineIndex, line ->
                line.episodes.forEachIndexed { epIndex, episode ->
                    entities += VodEpisodeEntity(
                        sourceId = sourceId,
                        seriesId = seriesId,
                        season = lineIndex + 1,
                        episode = epIndex + 1,
                        title = episode.title,
                        playUrl = episode.url,
                    )
                }
            }
            if (entities.isEmpty()) return emptyList()
            vodDao.upsertEpisodes(entities)
            updateSeriesStats(sourceId, seriesId)
            return entities
        }

        val info = try {
            xtreamClient.fetchVodInfo(source, seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return emptyList()
        }
        val episodesMap = info?.episodes.orEmpty()
        if (episodesMap.isEmpty()) return emptyList()
        val entities = ArrayList<VodEpisodeEntity>()
        episodesMap.forEach { (seasonKey, list) ->
            val season = seasonKey.trim().toIntOrNull()
                ?: list.firstOrNull()?.season?.toString()?.toIntOrNull()
                ?: 1
            list.forEachIndexed { index, episode ->
                val episodeNum = episode.episodeNum?.toString()?.toIntOrNull() ?: (index + 1)
                val episodeId = episode.idString ?: return@forEachIndexed
                val playUrl = episode.directSource?.takeIf { it.isNotBlank() }
                    ?: xtreamClient.playUrl(source, episodeId, episode.containerExtension, isSeries = true)
                    ?: return@forEachIndexed
                entities += VodEpisodeEntity(
                    sourceId = sourceId,
                    seriesId = seriesId,
                    season = season,
                    episode = episodeNum,
                    title = episode.title?.takeIf { it.isNotBlank() },
                    thumbnail = episode.info?.movieImage?.takeIf { it.isNotBlank() },
                    playUrl = playUrl,
                    durationMs = episode.info?.durationMs,
                )
            }
        }
        if (entities.isEmpty()) return emptyList()
        vodDao.upsertEpisodes(entities)
        updateSeriesStats(sourceId, seriesId)
        return entities
    }

    /** 更新条目集数统计（智能追更：totalEpisodes / latestEpisode） */
    private suspend fun updateSeriesStats(sourceId: String, seriesId: String) {
        val all = vodDao.episodes(seriesId).filter { it.sourceId == sourceId }
        val item = vodDao.get(sourceId, seriesId)
        if (item != null && all.isNotEmpty()) {
            val latestSeason = all.maxOf { it.season }
            vodDao.upsertAll(
                listOf(
                    item.copy(
                        totalEpisodes = all.size,
                        latestEpisode = all.filter { it.season == latestSeason }.maxOf { it.episode },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            )
        }
    }

    private fun splitNames(raw: String?): List<String>? {
        val source = raw?.trim().orEmpty()
        if (source.isEmpty() || source == "null") return null
        val names = source.split(Regex("""[,，、/|]"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
        return names.ifEmpty { null }
    }

    private fun yearOf(date: String?): String? =
        date?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()?.toString()

    /**
     * MacCMS vod_time → 毫秒时间戳（海报墙「最新在前」排序依据）。
     * 兼容三种形态："2024-05-01 12:30:00" / "2024-05-01" / Unix 时间戳（10 位秒、13 位毫秒）；
     * 解析失败回退当前时间（保底不打乱排序）。
     */
    private fun parseCmsTime(value: String?): Long {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return System.currentTimeMillis()
        trimmed.toLongOrNull()?.let { raw ->
            return when {
                raw >= 1_000_000_000_000L -> raw // 毫秒时间戳
                raw >= 1_000_000_000L -> raw * 1000 // 秒时间戳
                else -> System.currentTimeMillis() // 无效数值
            }
        }
        return try {
            val pattern = if (trimmed.length >= 19) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd"
            SimpleDateFormat(pattern, Locale.US).parse(trimmed)?.time
                ?: System.currentTimeMillis()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }

    companion object {
        private val ALL_KINDS = listOf(VodKind.MOVIE, VodKind.SERIES, VodKind.VARIETY, VodKind.ANIME)

        /** 单次写库分批大小（低配盒子内存友好） */
        private const val DB_CHUNK = 300

        /** 分页拉取单页失败重试次数（防抖动截断同步） */
        private const val PAGE_FETCH_RETRIES = 3

        /** 重试间隔（毫秒） */
        private const val PAGE_RETRY_DELAY_MS = 600L

        /** 同步途中回写统计的页间隔（长任务进行中也能看到片数增长） */
        private const val STATS_EVERY_PAGES = 50

        /** 点播页初始加载条数（进页面/切线路只拉这么多，加载快；剩余随滚动增量拉取） */
        private const val INITIAL_LOAD_ITEMS = 100

        /** 滚动到底每次增量加载条数 */
        private const val LOAD_MORE_ITEMS = 50

        /** MacCMS 服务端每页条数（冷启动断点丢失时按缓存条数推算已加载页码用） */
        private const val CMS_PAGE_SIZE = 20

        /**
         * 并行预取波次：一次并发抓取的页数。3 个串行网络往返并成 1 个——
         * 一批 50 条（3 页）= 1 次往返，首批 100 条（5 页）= 2 次，滚动/刷新等待约降为 1/3。
         * 取 3 是对吞吐与站点友好度的折中（更高可能触发部分站点限流，失败会走页级重试）。
         */
        private const val WAVE_PAGES = 3

        /** 同步完成后圈内停留展示「累计总数」的时长（毫秒），随后恢复刷新图标 */
        private const val SYNC_DONE_HOLD_MS = 4_000L

        /** 单次刷新 TMDB 刮削条目上限（保证刷新时长可控） */
        private const val MAX_SCRAPES_PER_REFRESH = 200

        /** 在线搜索最多并发的源数（覆盖全部内置 20 条线路，控制请求风暴） */
        private const val ONLINE_SEARCH_MAX_SOURCES = 20

        /** 在线搜索单源最多取回条数（搜索场景够用，避免大响应卡解析） */
        private const val ONLINE_SEARCH_MAX_PER_SOURCE = 30
    }
}

/**
 * 品类归类启发式（需求 5.4.1 全品类聚合）：
 * 含"动漫/动画/卡通/漫画/少儿/国漫/国创"→动漫、含"电影/剧场"→电影、含"剧/连续剧"→电视剧、
 * 含"综艺"→综艺、默认电影；服务端标注 stream_type=series 时按电视剧处理。
 * 动漫判定优先于电影/剧（用户反馈：「全部」里大量动漫内容，切到「动漫」标签却看不到——
 * 旧规则「电影」在前且缺关键词，把「少儿/国漫/卡通/XX动漫电影」等都归进了电影）。
 */
object VodKindClassifier {
    fun of(streamType: String?, categoryName: String): String = when {
        streamType?.trim().equals("series", ignoreCase = true) -> VodKind.SERIES
        else -> ofCategory(categoryName)
    }

    /** 动漫类分类关键词（含动画面向的少儿/国创等细分，命中即归动漫） */
    private val ANIME_KEYWORDS = listOf(
        "动漫", "动画", "卡通", "漫画", "少儿", "国漫", "日漫", "国创", "奥特曼", "熊出没",
    )

    fun isAnimeCategory(categoryName: String): Boolean =
        ANIME_KEYWORDS.any { categoryName.contains(it) }

    fun ofCategory(categoryName: String): String = when {
        isAnimeCategory(categoryName) -> VodKind.ANIME
        categoryName.contains("电影") || categoryName.contains("剧场") -> VodKind.MOVIE
        categoryName.contains("剧") || categoryName.contains("连续剧") -> VodKind.SERIES
        categoryName.contains("综艺") -> VodKind.VARIETY
        else -> VodKind.MOVIE
    }
}
