package com.puretv.player.data.vod

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * TMDB（The Movie Database）刮削 DTO（需求 5.4.2：自动刮削 TMDB 信息，
 * 匹配海报、简介、评分、演员表、分集信息；中文优先 language=zh-CN）。
 */

/** search/movie、search/tv 通用响应外壳 */
@JsonClass(generateAdapter = true)
data class TmdbPagedDto(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "results") val results: List<TmdbSearchItemDto>? = null,
)

/** 搜索结果条目（movie 与 tv 共用字段子集） */
@JsonClass(generateAdapter = true)
data class TmdbSearchItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
) {
    val displayName: String? get() = title ?: name
    val displayDate: String? get() = releaseDate ?: firstAirDate
}

/** movie/{id} / tv/{id}（append_to_response=credits） */
@JsonClass(generateAdapter = true)
data class TmdbDetailDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "genres") val genres: List<TmdbGenreDto>? = null,
    @Json(name = "credits") val credits: TmdbCreditsDto? = null,
) {
    val displayName: String? get() = title ?: name
    val displayDate: String? get() = releaseDate ?: firstAirDate
}

@JsonClass(generateAdapter = true)
data class TmdbGenreDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbCreditsDto(
    @Json(name = "cast") val cast: List<TmdbCastDto>? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbCastDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "order") val order: Int? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
)

/** tv/{id}/season/{season} */
@JsonClass(generateAdapter = true)
data class TmdbSeasonDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episodes") val episodes: List<TmdbEpisodeDto>? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbEpisodeDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
)
