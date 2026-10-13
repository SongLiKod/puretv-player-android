package com.puretv.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.ChannelSettingEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.local.entity.PluginEntity
import com.puretv.player.data.local.entity.ReminderEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

/* ================================ 频道 ================================ */

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY number ASC, name ASC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY number ASC, name ASC")
    fun observeBySource(sourceId: String): Flow<List<ChannelEntity>>

    @Query(
        "SELECT * FROM channels WHERE COALESCE(customGroup, groupName) = :groupName " +
            "ORDER BY number ASC, name ASC"
    )
    fun observeByGroup(groupName: String): Flow<List<ChannelEntity>>

    @Query(
        "SELECT DISTINCT COALESCE(customGroup, groupName) AS groupName FROM channels ORDER BY groupName ASC"
    )
    fun observeGroups(): Flow<List<String>>

    @Query(
        "SELECT * FROM channels WHERE name LIKE '%' || :query || '%' " +
            "OR groupName LIKE '%' || :query || '%' ORDER BY number ASC, name ASC"
    )
    fun search(query: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND id = :id LIMIT 1")
    suspend fun get(sourceId: String, id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE epgId = :epgId LIMIT 1")
    suspend fun findByEpgId(epgId: String): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query(
        "UPDATE channels SET customGroup = :customGroup WHERE sourceId = :sourceId AND id = :id"
    )
    suspend fun setCustomGroup(sourceId: String, id: String, customGroup: String?)

    /** 大容量频道列表整体替换（事务保证解析过程界面可正常读取旧数据） */
    @Transaction
    suspend fun replaceSourceChannels(sourceId: String, channels: List<ChannelEntity>) {
        deleteBySource(sourceId)
        upsertAll(channels)
    }
}

/* ================================ EPG 节目单 ================================ */

@Dao
interface EpgDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrograms(programs: List<EpgProgramEntity>)

    @Query(
        "SELECT * FROM epg_programs WHERE epgId = :epgId AND endAt >= :from AND startAt <= :to " +
            "ORDER BY startAt ASC"
    )
    fun observePrograms(epgId: String, from: Long, to: Long): Flow<List<EpgProgramEntity>>

    @Query(
        "SELECT * FROM epg_programs WHERE epgId = :epgId AND startAt <= :now AND endAt > :now " +
            "LIMIT 1"
    )
    suspend fun currentProgram(epgId: String, now: Long): EpgProgramEntity?

    @Query(
        "SELECT * FROM epg_programs WHERE epgId = :epgId AND startAt > :now " +
            "ORDER BY startAt ASC LIMIT :limit"
    )
    suspend fun upcoming(epgId: String, now: Long, limit: Int): List<EpgProgramEntity>

    @Query("DELETE FROM epg_programs WHERE endAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM epg_programs WHERE epgId = :epgId")
    suspend fun deleteByEpgId(epgId: String)
}

/* ================================ 收藏 ================================ */

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY favoriteRank ASC, createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE targetType = :type ORDER BY favoriteRank ASC, createdAt DESC")
    fun observeByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE targetType = :type AND targetId = :targetId LIMIT 1")
    suspend fun get(type: String, targetId: String): FavoriteEntity?

    @Query("SELECT targetType || '|' || targetId FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE targetType = :type AND targetId = :targetId")
    suspend fun delete(type: String, targetId: String)

    @Query("UPDATE favorites SET favoriteRank = :rank WHERE targetType = :type AND targetId = :targetId")
    suspend fun setRank(type: String, targetId: String, rank: Int)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}

/* ================================ 播放记录 ================================ */

@Dao
interface HistoryDao {

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE targetType = :type ORDER BY updatedAt DESC")
    fun observeByType(type: String): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE key = :key LIMIT 1")
    suspend fun get(key: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE targetType = 'series' OR targetType = 'episode' ORDER BY updatedAt DESC")
    fun observeSeries(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}

/* ================================ 预约提醒 ================================ */

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY startAt ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE programId = :programId LIMIT 1")
    suspend fun getByProgramId(programId: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE startAt >= :from AND startAt <= :to")
    suspend fun between(from: Long, to: Long): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE programId = :programId")
    suspend fun deleteByProgramId(programId: String)

    @Query("DELETE FROM reminders WHERE startAt < :before")
    suspend fun prune(before: Long)
}

/* ================================ 直播源 ================================ */

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt ASC")
    fun observeEnabled(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SourceEntity?

    @Query("SELECT * FROM sources")
    suspend fun getAll(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<SourceEntity>)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        "UPDATE sources SET channelCount = :channelCount, groupCount = :groupCount, " +
            "lastSyncAt = :syncedAt WHERE id = :id"
    )
    suspend fun updateStats(id: String, channelCount: Int, groupCount: Int, syncedAt: Long)
}

/* ================================ 清晰度记忆 ================================ */

@Dao
interface ChannelSettingDao {

    @Query("SELECT * FROM channel_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ChannelSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: ChannelSettingEntity)

    @Query("DELETE FROM channel_settings WHERE lastPlayedAt < :before")
    suspend fun prune(before: Long)
}

/* ================================ 影视点播 ================================ */

@Dao
interface VodDao {

    @Query("SELECT * FROM vod_items WHERE kind = :kind ORDER BY updatedAt DESC")
    fun observeByKind(kind: String): Flow<List<VodItemEntity>>

    @Query("SELECT * FROM vod_items WHERE kind = :kind AND category = :category ORDER BY updatedAt DESC")
    fun observeByCategory(kind: String, category: String): Flow<List<VodItemEntity>>

    /** 「全部」品类下按分类过滤（用户反馈：选分类内容不变——此前 kind=null 时分类被忽略） */
    @Query("SELECT * FROM vod_items WHERE category = :category ORDER BY updatedAt DESC")
    fun observeByCategoryAll(category: String): Flow<List<VodItemEntity>>

    @Query(
        "SELECT * FROM vod_items WHERE title LIKE '%' || :query || '%' ORDER BY updatedAt DESC"
    )
    fun search(query: String): Flow<List<VodItemEntity>>

    /** 本站搜索：限定单个资源站的标题模糊匹配 */
    @Query(
        "SELECT * FROM vod_items WHERE title LIKE '%' || :query || '%' AND sourceId = :sourceId " +
            "ORDER BY updatedAt DESC"
    )
    fun searchInSource(query: String, sourceId: String): Flow<List<VodItemEntity>>

    /** 按资源站分页取影片（首页资源站浏览，追加需求：显示所选资源站网址上的所有视频） */
    @Query(
        "SELECT * FROM vod_items WHERE sourceId = :sourceId ORDER BY updatedAt DESC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun getItemsPaged(sourceId: String, limit: Int, offset: Int): List<VodItemEntity>

    @Query("SELECT * FROM vod_items WHERE sourceId = :sourceId AND id = :id LIMIT 1")
    suspend fun get(sourceId: String, id: String): VodItemEntity?

    @Query("SELECT DISTINCT category FROM vod_items WHERE kind = :kind AND category IS NOT NULL ORDER BY category ASC")
    fun observeCategories(kind: String): Flow<List<String>>

    /** 一次性品类纠正用读取：有分类名的全部条目 */
    @Query("SELECT * FROM vod_items WHERE category IS NOT NULL AND category != ''")
    suspend fun getAllWithCategory(): List<VodItemEntity>

    @Query("SELECT kind, COUNT(*) AS cnt FROM vod_items GROUP BY kind")
    fun observeKindCounts(): Flow<List<KindCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VodItemEntity>)

    @Query("DELETE FROM vod_items WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    /** 该源现有库存条数（同步写库前比对，避免残缺数据覆盖完整库存） */
    @Query("SELECT COUNT(*) FROM vod_items WHERE sourceId = :sourceId")
    suspend fun countBySource(sourceId: String): Int

    /**
     * 全量快照替换：事务内「删除 + 分块写入」，同步期间不出现空窗，
     * 避免列表中途清空导致 UI 分支切换、滚动位置重置（用户反馈的页面跳动问题）。
     */
    @Transaction
    suspend fun replaceSource(sourceId: String, items: List<VodItemEntity>) {
        deleteBySource(sourceId)
        items.chunked(300).forEach { upsertAll(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<VodEpisodeEntity>)

    @Query("SELECT * FROM vod_episodes WHERE seriesId = :seriesId ORDER BY season ASC, episode ASC")
    fun observeEpisodes(seriesId: String): Flow<List<VodEpisodeEntity>>

    @Query("SELECT * FROM vod_episodes WHERE seriesId = :seriesId ORDER BY season ASC, episode ASC")
    suspend fun episodes(seriesId: String): List<VodEpisodeEntity>
}

/** 按品类统计（首页/分类角标用） */
data class KindCount(val kind: String, val cnt: Int)

/* ================================ 插件 ================================ */

@Dao
interface PluginDao {

    @Query("SELECT * FROM plugins ORDER BY type ASC, name ASC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE installed = 1 ORDER BY name ASC")
    fun observeInstalled(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plugins: List<PluginEntity>)

    @Query("UPDATE plugins SET installed = :installed, enabled = :enabled, installedAt = :at WHERE id = :id")
    suspend fun setInstalled(id: String, installed: Boolean, enabled: Boolean, at: Long)

    @Query("UPDATE plugins SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun delete(id: String)
}
