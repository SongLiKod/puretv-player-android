package com.puretv.player.data.repository

import com.puretv.player.data.local.dao.FavoriteDao
import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.model.TargetType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 我的收藏仓库（需求 5.2.4 / 5.3.3：收藏置顶；支持直播、点播、本地）
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
) {
    val favorites: Flow<List<FavoriteEntity>> = favoriteDao.observeAll()

    fun byType(type: String): Flow<List<FavoriteEntity>> = favoriteDao.observeByType(type)

    val channels: Flow<List<FavoriteEntity>> = favoriteDao.observeByType(TargetType.CHANNEL)
    val vodItems: Flow<List<FavoriteEntity>> = favoriteDao.observeByType(TargetType.VOD)
    val localVideos: Flow<List<FavoriteEntity>> = favoriteDao.observeByType(TargetType.LOCAL)

    /** 已收藏的 key 集合（"type|id"），用于列表即时显示收藏态 */
    val favoriteKeys: Flow<Set<String>> = favoriteDao.observeKeys().map { it.toSet() }

    suspend fun isFavorite(type: String, targetId: String): Boolean =
        favoriteDao.get(type, targetId) != null

    suspend fun toggle(
        type: String,
        targetId: String,
        title: String,
        sourceId: String? = null,
        imageUrl: String? = null,
        url: String? = null,
    ): Boolean {
        val existing = favoriteDao.get(type, targetId)
        return if (existing != null) {
            favoriteDao.delete(type, targetId)
            false
        } else {
            favoriteDao.upsert(
                FavoriteEntity(
                    targetType = type,
                    targetId = targetId,
                    sourceId = sourceId,
                    title = title,
                    imageUrl = imageUrl,
                    url = url,
                    favoriteRank = 0,
                    createdAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    /** 收藏置顶：调整排序值（越小越靠前） */
    suspend fun move(type: String, targetId: String, rank: Int) =
        favoriteDao.setRank(type, targetId, rank)

    suspend fun remove(type: String, targetId: String) = favoriteDao.delete(type, targetId)

    suspend fun clearAll() = favoriteDao.clear()
}
