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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 观察某品类下的点播分类名（Xtream 分类横滑列表） */
    fun observeCategories(kind: String?): Flow<List<String>> = when {
        kind.isNullOrBlank() -> combine(ALL_KINDS.map { vodDao.observeCategories(it) }) { arrays ->
            arrays.flatMap { it }.distinct().sorted()
        }
        else -> vodDao.observeCategories(kind)
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
            updatedAt = System.currentTimeMillis(),
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
     */
    suspend fun refresh(
        kind: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ) = withContext(io) {
        val sources = sourceDao.getAll()
            .filter { it.enabled && (it.type == SourceType.XTREAM || it.type == SourceType.CMS) }
        if (sources.isEmpty()) {
            onProgress(
                ImportProgress(
                    stage = Stage.FAILED,
                    message = "未找到已启用的点播源，请先在设置中心添加 Xtream / MacCMS / TVBox 源",
                )
            )
            return@withContext
        }
        onProgress(ImportProgress(stage = Stage.PREPARING, message = "正在同步点播资源…"))
        var totalSaved = 0
        var anyFailure = false
        try {
            sources.forEach { source ->
                val saved = if (source.type == SourceType.CMS) {
                    refreshCmsSource(source, kind, onProgress) { anyFailure = true }
                } else {
                    refreshXtreamSource(source, kind, onProgress) { anyFailure = true }
                }
                totalSaved += saved
            }
            lastRefreshAt = System.currentTimeMillis()
            onProgress(
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
        streams.forEachIndexed { index, stream ->
            val streamId = stream.idString ?: return@forEachIndexed
            val title = stream.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return@forEachIndexed
            val categoryName = categoryNameById[stream.categoryId?.toString()] ?: "未分类"
            val kindOfItem = VodKindClassifier.of(stream.streamType, categoryName)
            val isSeries = kindOfItem == VodKind.SERIES
            val playUrl = stream.directSource?.takeIf { it.isNotBlank() }
                ?: xtreamClient.playUrl(source, streamId, stream.containerExtension, isSeries)
            if (playUrl != null) {
                items += VodItemEntity(
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
            }
            if (index % 400 == 0) {
                onProgress(
                    ImportProgress(
                        stage = Stage.PARSING,
                        processed = index,
                        total = total,
                        message = "正在解析「${source.name}」点播列表…",
                    )
                )
            }
        }
        val matched = if (kind.isNullOrBlank()) items else items.filter { it.kind == kind }
        onProgress(
            ImportProgress(
                stage = Stage.SAVING,
                processed = matched.size,
                total = total,
                message = "正在写入「${source.name}」缓存…",
            )
        )
        vodDao.deleteBySource(source.id)
        matched.chunked(DB_CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            vodDao.upsertAll(chunk)
        }
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
     * 逐页拉取 ac=detail（含播放地址），映射条目与分集后整源替换写库。
     * 电影取首条播放线直链；剧集解析全部分集入库（详情页可选线路）。
     */
    private suspend fun refreshCmsSource(
        source: SourceEntity,
        kind: String?,
        onProgress: (ImportProgress) -> Unit,
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
        val totalPages = firstPage.pageInt.coerceAtLeast(1) // 不限页数：资源有多少采集多少（用户明确要求）
        val items = ArrayList<VodItemEntity>(firstPage.totalInt.coerceAtLeast(0))
        val episodes = HashMap<String, List<VodEpisodeEntity>>()
        var page = 1
        var dto = firstPage
        var fetchFailed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            dto.list.orEmpty().forEach { item ->
                val mapped = mapCmsItem(source, item) ?: return@forEach
                val (entity, allEpisodes) = mapped
                items += entity
                if (allEpisodes.isNotEmpty()) episodes[entity.id] = allEpisodes
            }
            if (page >= totalPages || dto.list.isNullOrEmpty()) break
            page++
            val next = fetchDetailPageWithRetry(source, page)
            if (next == null) {
                // 重试后仍失败：中断但保留已取内容（写库阶段与现有库存比对，避免刷成残缺）
                fetchFailed = true
                break
            }
            dto = next
            onProgress(
                ImportProgress(
                    stage = Stage.PARSING,
                    processed = page,
                    total = totalPages,
                    message = "正在同步「${source.name}」第 $page/$totalPages 页…",
                )
            )
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
     * @return 第 [page] 页数据；重试耗尽返回 null
     */
    private suspend fun fetchDetailPageWithRetry(source: SourceEntity, page: Int): CmsVodListDto? {
        repeat(PAGE_FETCH_RETRIES) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return cmsClient.fetchDetailPage(source, page = page)
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
