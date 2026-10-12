package com.puretv.player.data.repository

import com.puretv.player.data.local.dao.HistoryDao
import com.puretv.player.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放记录仓库（需求 5.4.3：智能追更、未看完集数标记、断点续播）
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao,
) {
    val history: Flow<List<WatchHistoryEntity>> = historyDao.observeAll()

    fun recent(limit: Int): Flow<List<WatchHistoryEntity>> = historyDao.observeRecent(limit)

    fun byType(type: String): Flow<List<WatchHistoryEntity>> = historyDao.observeByType(type)

    /** 剧集类记录（智能追更入口） */
    val seriesHistory: Flow<List<WatchHistoryEntity>> = historyDao.observeSeries()

    suspend fun get(key: String): WatchHistoryEntity? = historyDao.get(key)

    suspend fun get(targetType: String, targetId: String): WatchHistoryEntity? =
        historyDao.get(WatchHistoryEntity.keyOf(targetType, targetId))

    /** 记录播放进度（断点续播）；position 为 0 时不覆盖已有进度 */
    suspend fun record(
        targetType: String,
        targetId: String,
        title: String,
        sourceId: String? = null,
        subtitle: String? = null,
        imageUrl: String? = null,
        url: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        episodeLabel: String? = null,
    ) {
        historyDao.upsert(
            WatchHistoryEntity(
                key = WatchHistoryEntity.keyOf(targetType, targetId),
                targetType = targetType,
                targetId = targetId,
                sourceId = sourceId,
                title = title,
                subtitle = subtitle,
                imageUrl = imageUrl,
                url = url,
                positionMs = positionMs,
                durationMs = durationMs,
                episodeLabel = episodeLabel,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(key: String) = historyDao.delete(key)

    suspend fun clear() = historyDao.clear()
}
