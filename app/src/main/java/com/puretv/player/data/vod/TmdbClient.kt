package com.puretv.player.data.vod

import com.puretv.player.di.TmdbOkHttp
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB 客户端（需求 5.4.2：自动刮削 TMDB 海报、简介、评分、演员表、分集信息）。
 * API Key 由设置中心统一配置（DataStore "tmdb_api_key"），未配置时刮削静默跳过。
 * 独立使用 5 秒超时的 OkHttp 客户端（需求 6.4：网络不可达时优雅降级，不阻塞播放）。
 */
internal interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): TmdbPagedDto

    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): TmdbPagedDto

    @GET("movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "credits",
    ): TmdbDetailDto

    @GET("tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "credits",
    ): TmdbDetailDto

    @GET("tv/{id}/season/{season}")
    suspend fun seasonDetail(
        @Path("id") id: Long,
        @Path("season") season: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbSeasonDto
}

class TmdbClient(
    @TmdbOkHttp okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val api: TmdbApi = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApi::class.java)

    suspend fun searchMovie(apiKey: String, query: String, language: String = LANGUAGE): List<TmdbSearchItemDto> =
        api.searchMovie(apiKey, language, query).results.orEmpty()

    suspend fun searchTv(apiKey: String, query: String, language: String = LANGUAGE): List<TmdbSearchItemDto> =
        api.searchTv(apiKey, language, query).results.orEmpty()

    suspend fun movieDetail(id: Long, apiKey: String, language: String = LANGUAGE): TmdbDetailDto =
        api.movieDetail(id, apiKey, language)

    suspend fun tvDetail(id: Long, apiKey: String, language: String = LANGUAGE): TmdbDetailDto =
        api.tvDetail(id, apiKey, language)

    suspend fun seasonDetail(id: Long, season: Int, apiKey: String, language: String = LANGUAGE): TmdbSeasonDto =
        api.seasonDetail(id, season, apiKey, language)

    companion object {
        /** 中文优先刮削（需求 5.4.2） */
        const val LANGUAGE = "zh-CN"

        /** TMDB 图片服务（海报 w500 / 背板 w780） */
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

        fun posterUrl(path: String?): String? =
            path?.trim()?.takeIf { it.startsWith("/") }?.let { "${IMAGE_BASE}w500$it" }

        fun backdropUrl(path: String?): String? =
            path?.trim()?.takeIf { it.startsWith("/") }?.let { "${IMAGE_BASE}w780$it" }

        fun stillUrl(path: String?): String? =
            path?.trim()?.takeIf { it.startsWith("/") }?.let { "${IMAGE_BASE}w300$it" }
    }
}
