package com.puretv.player.data.vod

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.puretv.player.data.local.dao.VodDao
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.di.IoDispatcher
import com.puretv.player.data.model.VodKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 刮削仓库（需求 5.4.2 / 6.4 稳定性）：
 * - 中文优先（language=zh-CN）匹配海报、背板、评分、简介、类型、演员、年份
 * - 剧集拉取分集标题/缩略图/时长回填分集表
 * - 每次请求 5 秒超时；Key 未配置或网络不可达时静默降级，返回原条目，不阻塞播放
 */
@Singleton
class TmdbRepository @Inject constructor(
    private val tmdbClient: TmdbClient,
    private val vodDao: VodDao,
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 设置中心配置的 TMDB API Key（与设置中心共用同一存储键） */
    suspend fun apiKey(): String? = dataStore.data.first()[TMDB_API_KEY]
        ?.trim()?.takeIf { it.isNotEmpty() }

    /** 刮削是否可用 */
    suspend fun isConfigured(): Boolean = apiKey() != null

    /** 写回 API Key（设置中心调用） */
    suspend fun saveApiKey(key: String) {
        dataStore.edit { preferences ->
            val trimmed = key.trim()
            if (trimmed.isEmpty()) preferences.remove(TMDB_API_KEY)
            else preferences[TMDB_API_KEY] = trimmed
        }
    }

    /**
     * 刮削单个影片/剧集并回填实体，返回合并后的实体。
     * 任何失败（Key 缺失、超时、网络异常、匹配失败）都静默返回原实体。
     * @param includeEpisodes 剧集是否同时补全分集元数据
     */
    suspend fun scrapeFor(
        item: VodItemEntity,
        includeEpisodes: Boolean = true,
    ): VodItemEntity {
        val key = apiKey() ?: return item
        return try {
            val scraped = withTimeoutOrNull(SCRAP_TIMEOUT_MS) {
                withContext(io) { doScrape(key, item, includeEpisodes) }
            } ?: item
            if (scraped != item) {
                vodDao.upsertAll(listOf(scraped))
            }
            scraped
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 刮削失败静默降级（需求 6.4：稳定性优先，不影响正常播放）
            item
        }
    }

    /** 剧集分集补全：按缓存中的季号拉取 TMDB 分集标题/缩略图/时长 */
    suspend fun scrapeEpisodes(item: VodItemEntity) {
        val key = apiKey() ?: return
        if (item.kind != VodKind.SERIES || item.tmdbId == null) return
        try {
            withTimeoutOrNull(SCRAP_TIMEOUT_MS * 2) {
                withContext(io) { enrichEpisodes(key, item.id, item.tmdbId) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 静默降级
        }
    }

    /** 拉取 TMDB 各季分集信息并回填本地分集表 */
    private suspend fun enrichEpisodes(apiKey: String, seriesItemId: String, tmdbId: Long) {
        val cached = vodDao.episodes(seriesItemId)
        if (cached.isEmpty()) return
        cached.map { it.season }.distinct().forEach { season ->
            val seasonInfo = runCatching {
                tmdbClient.seasonDetail(tmdbId, season, apiKey)
            }.getOrNull() ?: return@forEach
            val byEpisode = seasonInfo.episodes.orEmpty()
                .filter { it.episodeNumber != null }
                .associateBy { it.episodeNumber }
            val updated = cached.filter { it.season == season }.mapNotNull { ep ->
                val match = byEpisode[ep.episode] ?: return@mapNotNull null
                ep.copy(
                    title = match.name?.takeIf { it.isNotBlank() } ?: ep.title,
                    thumbnail = TmdbClient.stillUrl(match.stillPath) ?: ep.thumbnail,
                    durationMs = match.runtime?.takeIf { it > 0 }
                        ?.times(60_000L) ?: ep.durationMs,
                )
            }
            if (updated.isNotEmpty()) vodDao.upsertEpisodes(updated)
        }
    }

    private suspend fun doScrape(
        apiKey: String,
        item: VodItemEntity,
        includeEpisodes: Boolean,
    ): VodItemEntity {
        val query = cleanQuery(item.title)
        if (query.isEmpty()) return item
        val isSeries = item.kind == VodKind.SERIES
        val year = item.year?.takeIf { it.length == 4 }?.toIntOrNull()

        // 1) 先按已有关联 id 直取，否则按片名搜索（年份接近优先）
        val matchedId: Long = item.tmdbId ?: runCatching {
            val results = if (isSeries) {
                tmdbClient.searchTv(apiKey, query)
            } else {
                tmdbClient.searchMovie(apiKey, query)
            }
            pickBestMatch(results, year)?.id
        }.getOrNull() ?: return item

        // 2) 详情（含演员表）
        val detail = runCatching {
            if (isSeries) tmdbClient.tvDetail(matchedId, apiKey)
            else tmdbClient.movieDetail(matchedId, apiKey)
        }.getOrNull() ?: return item

        val merged = item.copy(
            title = detail.displayName?.takeIf { it.isNotBlank() } ?: item.title,
            posterUrl = TmdbClient.posterUrl(detail.posterPath) ?: item.posterUrl,
            backdropUrl = TmdbClient.backdropUrl(detail.backdropPath) ?: item.backdropUrl,
            rating = detail.voteAverage?.takeIf { it > 0.0 } ?: item.rating,
            overview = detail.overview?.takeIf { it.isNotBlank() } ?: item.overview,
            genres = detail.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .ifEmpty { item.genres },
            cast = detail.credits?.cast.orEmpty()
                .sortedBy { it.order ?: Int.MAX_VALUE }
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .take(MAX_CAST)
                .ifEmpty { item.cast },
            year = detail.displayDate?.takeIf { it.length >= 4 }?.substring(0, 4) ?: item.year,
            tmdbId = detail.id ?: matchedId,
        )

        // 3) 剧集补全分集元数据（标题/缩略图/时长）
        if (isSeries && includeEpisodes) {
            merged.tmdbId?.let { enrichEpisodes(apiKey, item.id, it) }
        }
        return merged
    }

    /** 选取最接近的匹配项：年份一致优先，其次首项 */
    private fun pickBestMatch(results: List<TmdbSearchItemDto>, year: Int?): TmdbSearchItemDto? {
        if (results.isEmpty()) return null
        if (year != null) {
            results.firstOrNull {
                it.displayDate?.takeIf { d -> d.length >= 4 }?.substring(0, 4)?.toIntOrNull() == year
            }?.let { return it }
        }
        return results.firstOrNull { it.id != null }
    }

    /** 清洗搜索词：去除括号年份、季数后缀等噪声 */
    private fun cleanQuery(title: String): String = title
        .replace(Regex("""\s*[(\[(【]\s*\d{4}\s*[)\]）】]\s*"""), " ")
        .replace(Regex("""\s*第\s*\d+\s*季\s*$"""), " ")
        .trim()

    companion object {
        /** 单次刮削链路超时（搜索/详情各一次请求） */
        const val SCRAP_TIMEOUT_MS = 5_000L

        /** 演员表最多保留人数 */
        const val MAX_CAST = 8

        /** 与设置中心共用的 Key 存储名（勿改） */
        val TMDB_API_KEY: Preferences.Key<String> = stringPreferencesKey("tmdb_api_key")
    }
}
