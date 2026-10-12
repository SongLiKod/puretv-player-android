package com.puretv.player.data.local.entity

import androidx.room.Entity
import com.squareup.moshi.JsonClass
import androidx.room.Index
import androidx.room.PrimaryKey

/* ============================== 直播频道 ============================== */

/**
 * 频道实体（Room 实体同时作为领域模型使用）
 * @param sourceId 所属源 ID，@param id 源内唯一 ID，联合主键保证多源共存
 */
@Entity(
    tableName = "channels",
    primaryKeys = ["sourceId", "id"],
    indices = [Index("groupName"), Index("epgId"), Index("name")]
)
@JsonClass(generateAdapter = true)
data class ChannelEntity(
    val sourceId: String,
    val id: String,
    val name: String,
    val logo: String? = null,
    val url: String,
    /** 频道分组（group-title） */
    val groupName: String = "未分组",
    /** EPG 关联 id（tvg-id） */
    val epgId: String? = null,
    /** 频道号 */
    val number: Int? = null,
    /** 清晰度标签：SD/HD/FHD/4K 等 */
    val quality: String? = null,
    /** 自定义请求头（EXTVLCOPT / #KODIPROP 等） */
    val headers: Map<String, String> = emptyMap(),
    /** 备用源地址（弱网/卡顿自动切换备用源） */
    val backupUrls: List<String> = emptyList(),
    /** 回看：default / shift / xtream，null 表示不支持 */
    val catchupType: String? = null,
    /** 回看地址模板，支持 {utc}/{lutc}/{utcend}/{timestamp}/{lutcend} 占位 */
    val catchupSource: String? = null,
    /** 回看可回溯天数 */
    val catchupDays: Int = 0,
    /** 自定义分类标记（用户自定义归类的分组名，null 表示未自定义） */
    val customGroup: String? = null,
    val updatedAt: Long = 0L,
)

/* ============================== EPG 节目单 ============================== */

@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["epgId", "startAt"]), Index("endAt")]
)
data class EpgProgramEntity(
    /** epgId|startAt|title 哈希，保证唯一 */
    @PrimaryKey val id: String,
    val epgId: String,
    val title: String,
    val description: String? = null,
    /** epoch millis */
    val startAt: Long,
    val endAt: Long,
)

/* ============================== 收藏 ============================== */

/** targetType: channel / vod / local */
@Entity(tableName = "favorites", primaryKeys = ["targetType", "targetId"])
@JsonClass(generateAdapter = true)
data class FavoriteEntity(
    val targetType: String,
    val targetId: String,
    val sourceId: String? = null,
    val title: String,
    val imageUrl: String? = null,
    /** 直播源地址（本地收藏直接播放用） */
    val url: String? = null,
    /** 收藏排序值：越小越靠前（收藏置顶） */
    val favoriteRank: Int = 0,
    val createdAt: Long = 0L,
)

/* ============================== 播放记录 ============================== */

/** targetType: channel / vod / episode / local */
@Entity(tableName = "watch_history", indices = [Index("updatedAt")])
@JsonClass(generateAdapter = true)
data class WatchHistoryEntity(
    /** targetType|targetId 联合唯一 */
    @PrimaryKey val key: String,
    val targetType: String,
    val targetId: String,
    val sourceId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /** 播放源地址（续播用） */
    val url: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 剧集标记：如 "第3集"，用于未看完集数标记与智能追更 */
    val episodeLabel: String? = null,
    val updatedAt: Long = 0L,
) {
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs * 95 / 100

    companion object {
        fun keyOf(targetType: String, targetId: String) = "$targetType|$targetId"
    }
}

/* ============================== 预约提醒 ============================== */

@Entity(tableName = "reminders", indices = [Index("startAt")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 节目唯一 id（epgProgram.id） */
    val programId: String,
    val epgId: String,
    val channelSourceId: String? = null,
    val channelId: String? = null,
    val channelName: String,
    val title: String,
    val startAt: Long,
    val createdAt: Long = 0L,
)

/* ============================== 直播源配置 ============================== */

/** type: m3u / xtream */
@Entity(tableName = "sources")
@JsonClass(generateAdapter = true)
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** M3U 地址 / Xtream 服务器地址 */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** EPG 地址（XMLTV），可为空 */
    val epgUrl: String? = null,
    val enabled: Boolean = true,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val lastSyncAt: Long = 0L,
    val createdAt: Long = 0L,
)

/* ============================== 频道播放偏好（清晰度记忆） ============================== */

@Entity(tableName = "channel_settings")
data class ChannelSettingEntity(
    /** sourceId|channelId */
    @PrimaryKey val key: String,
    /** 记住的清晰度（需求 5.3.3 清晰度记忆） */
    val quality: String? = null,
    val lastPlayedAt: Long = 0L,
)

/* ============================== 影视点播缓存 ============================== */

/** kind: movie / series / variety / anime（全品类聚合分类，需求 5.4.1） */
@Entity(
    tableName = "vod_items",
    primaryKeys = ["sourceId", "id"],
    indices = [Index("kind"), Index("category"), Index("updatedAt")]
)
@JsonClass(generateAdapter = true)
data class VodItemEntity(
    val sourceId: String,
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    /** 分类名（综艺/动漫等聚合分类） */
    val category: String? = null,
    val kind: String = "movie",
    /** 直接播放地址（电影） */
    val playUrl: String? = null,
    val totalEpisodes: Int? = null,
    /** 最新更新集数（智能追更） */
    val latestEpisode: Int? = null,
    /** TMDB 关联 id */
    val tmdbId: Long? = null,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "vod_episodes",
    primaryKeys = ["sourceId", "seriesId", "season", "episode"],
    indices = [Index("seriesId")]
)
data class VodEpisodeEntity(
    val sourceId: String,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val thumbnail: String? = null,
    val playUrl: String,
    val durationMs: Long? = null,
)

/* ============================== 插件 ============================== */

/** type: subtitle / source / skin（需求 5.7.1：字幕插件、资源插件、美化插件） */
@Entity(tableName = "plugins")
@JsonClass(generateAdapter = true)
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val type: String,
    val version: String = "1.0.0",
    /** 是否官方审核上架（需求 5.7.2） */
    val official: Boolean = true,
    /** 官方校验签名/摘要（杜绝恶意插件） */
    val checksum: String? = null,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val installedAt: Long = 0L,
)
