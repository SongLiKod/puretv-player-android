package com.puretv.player.data.vod

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Xtream Codes 点播（VOD）接口 DTO。
 * 接口：player_api.php?username=..&password=..&action=get_vod_categories /
 * get_vod_streams / get_vod_info&vod_id=..
 *
 * 说明：不同 Xtream 服务端实现对 id / rating / added 等字段可能返回字符串或数字，
 * 统一用 [Any] 接收后在转换层归一化，避免解析崩溃（需求 6 稳定性）。
 */

/** 点播分类：get_vod_categories */
@JsonClass(generateAdapter = true)
data class XtreamCategoryDto(
    @Json(name = "category_id") val categoryId: Any? = null,
    @Json(name = "category_name") val categoryName: String? = null,
    @Json(name = "parent_id") val parentId: Any? = null,
) {
    val idString: String?
        get() = categoryId?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** 点播影片/剧集条目：get_vod_streams */
@JsonClass(generateAdapter = true)
data class XtreamVodStreamDto(
    @Json(name = "num") val num: Any? = null,
    @Json(name = "name") val name: String? = null,
    /** movie / series（部分服务端提供，可辅助品类判定） */
    @Json(name = "stream_type") val streamType: String? = null,
    @Json(name = "stream_id") val streamId: Any? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,
    @Json(name = "rating") val rating: Any? = null,
    @Json(name = "added") val added: Any? = null,
    @Json(name = "category_id") val categoryId: Any? = null,
    @Json(name = "container_extension") val containerExtension: String? = null,
    /** 服务端已解析好的直链（存在时优先使用） */
    @Json(name = "direct_source") val directSource: String? = null,
) {
    val idString: String?
        get() = streamId?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    val ratingDouble: Double?
        get() = rating?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }

    val addedAtMs: Long
        get() = added?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?.let { if (it < 100_000_000_000L) it * 1000 else it }
            ?: 0L
}

/** get_vod_info 响应 */
@JsonClass(generateAdapter = true)
data class XtreamVodInfoDto(
    @Json(name = "info") val info: XtreamMovieInfoDto? = null,
    /** 季号字符串 -> 该季分集列表 */
    @Json(name = "episodes") val episodes: Map<String, List<XtreamEpisodeDto>>? = null,
    @Json(name = "movie_data") val movieData: XtreamVodStreamDto? = null,
)

/** get_vod_info.info 影片元数据 */
@JsonClass(generateAdapter = true)
data class XtreamMovieInfoDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "movie_image") val movieImage: String? = null,
    @Json(name = "cover") val cover: String? = null,
    @Json(name = "plot") val plot: String? = null,
    @Json(name = "cast") val cast: String? = null,
    @Json(name = "director") val director: String? = null,
    @Json(name = "genre") val genre: String? = null,
    @Json(name = "rating") val rating: Any? = null,
    @Json(name = "releasedate") val releaseDate: String? = null,
    @Json(name = "release_date") val releaseDateAlt: String? = null,
    @Json(name = "duration") val duration: Any? = null,
    @Json(name = "tmdb") val tmdb: Any? = null,
    /** 可能是字符串或字符串数组 */
    @Json(name = "backdrop_path") val backdropPath: Any? = null,
) {
    val ratingDouble: Double?
        get() = rating?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }

    val tmdbIdLong: Long?
        get() = tmdb?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    /** 背板地址归一化（字符串或数组取第一项） */
    val backdropUrl: String?
        get() = when (val value = backdropPath) {
            null -> null
            is List<*> -> value.firstOrNull()?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            else -> value.toString().trim().takeIf { it.isNotEmpty() }
        }

    /** 时长归一化："01:30:00" / "90" 均可 */
    val durationMs: Long?
        get() {
            val raw = duration?.toString()?.trim().orEmpty()
            if (raw.isEmpty() || raw == "null") return null
            val parts = raw.split(":")
            val seconds = when (parts.size) {
                3 -> parts[0].toLongOrNull()?.times(3600)
                    ?.plus((parts[1].toLongOrNull() ?: 0) * 60)
                    ?.plus(parts[2].toLongOrNull() ?: 0)
                2 -> (parts[0].toLongOrNull() ?: 0) * 60 + (parts[1].toLongOrNull() ?: 0)
                else -> raw.toLongOrNull()
            }
            return seconds?.takeIf { it > 0 }?.times(1000)
        }
}

/** get_vod_info.episodes 分集条目 */
@JsonClass(generateAdapter = true)
data class XtreamEpisodeDto(
    @Json(name = "id") val id: Any? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "container_extension") val containerExtension: String? = null,
    @Json(name = "episode_num") val episodeNum: Any? = null,
    @Json(name = "season") val season: Any? = null,
    @Json(name = "direct_source") val directSource: String? = null,
    @Json(name = "info") val info: XtreamEpisodeInfoDto? = null,
) {
    val idString: String?
        get() = id?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** 分集元数据 */
@JsonClass(generateAdapter = true)
data class XtreamEpisodeInfoDto(
    @Json(name = "movie_image") val movieImage: String? = null,
    @Json(name = "duration") val duration: Any? = null,
    @Json(name = "plot") val plot: String? = null,
    @Json(name = "rating") val rating: Any? = null,
) {
    val durationMs: Long?
        get() {
            val raw = duration?.toString()?.trim().orEmpty()
            if (raw.isEmpty() || raw == "null") return null
            val parts = raw.split(":")
            val seconds = when (parts.size) {
                3 -> parts[0].toLongOrNull()?.times(3600)
                    ?.plus((parts[1].toLongOrNull() ?: 0) * 60)
                    ?.plus(parts[2].toLongOrNull() ?: 0)
                2 -> (parts[0].toLongOrNull() ?: 0) * 60 + (parts[1].toLongOrNull() ?: 0)
                else -> raw.toLongOrNull()
            }
            return seconds?.takeIf { it > 0 }?.times(1000)
        }
}
