package com.puretv.player.data.debrid

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import javax.inject.Inject

/**
 * TorBox 客户端（需求 5.4.5：支持 TorBox 高级拓展，加载高清稳定资源）。
 * 最小可用链路：checktoken 校验 -> createtorrent 转存 -> torrentinfo 轮询
 * -> requestdl 取已缓存资源直链。失败由调用方降级为普通源。
 */

internal interface TorBoxApi {

    @GET("user/checktoken")
    suspend fun checkToken(@Query("token") token: String): TorBoxCheckDto

    @FormUrlEncoded
    @POST("torrents/createtorrent")
    suspend fun createTorrent(
        @Query("token") token: String,
        @Field("link") link: String,
        @Field("seed") seed: Boolean = false,
        @Field("download") download: Boolean = true,
    ): TorBoxCreateDto

    @GET("torrents/torrentinfo")
    suspend fun torrentInfo(
        @Query("token") token: String,
        @Query("id") id: Long,
    ): TorBoxTorrentInfoDto

    @FormUrlEncoded
    @POST("torrents/requestdl")
    suspend fun requestDirectLink(
        @Query("token") token: String,
        @Query("torrent_id") torrentId: Long,
        @Field("file_id") fileId: Long? = null,
    ): TorBoxDirectLinkDto
}

@JsonClass(generateAdapter = true)
data class TorBoxCheckDto(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: TorBoxCheckDataDto? = null,
)

@JsonClass(generateAdapter = true)
data class TorBoxCheckDataDto(
    @Json(name = "authenticated") val authenticated: Boolean? = null,
    @Json(name = "username") val username: String? = null,
)

@JsonClass(generateAdapter = true)
data class TorBoxCreateDto(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: Any? = null,
) {
    val torrentId: Long?
        get() = data?.toString()?.trim()?.toLongOrNull()
}

@JsonClass(generateAdapter = true)
data class TorBoxTorrentInfoDto(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: TorBoxTorrentDto? = null,
)

@JsonClass(generateAdapter = true)
data class TorBoxTorrentDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "download_state") val downloadState: String? = null,
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "files") val files: List<TorBoxFileDto>? = null,
)

@JsonClass(generateAdapter = true)
data class TorBoxFileDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
)

@JsonClass(generateAdapter = true)
data class TorBoxDirectLinkDto(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: String? = null,
)

class TorBoxClient @Inject constructor(
    okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val api: TorBoxApi = Retrofit.Builder()
        .baseUrl("https://api.torbox.app/v1/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TorBoxApi::class.java)

    /** 校验 Token */
    suspend fun checkToken(token: String): Boolean = try {
        val dto = api.checkToken(token.trim())
        dto.success == true && dto.data?.authenticated != false
    } catch (t: Throwable) {
        false
    }

    /** 转存资源（返回 TorBox 任务 id） */
    suspend fun createTorrent(token: String, link: String): Long? = try {
        api.createTorrent(token.trim(), link).torrentId?.takeIf { it > 0L }
    } catch (t: Throwable) {
        null
    }

    /** 查询转存状态（download_finished / uploading 视为可取直链） */
    suspend fun torrentInfo(token: String, torrentId: Long): TorBoxTorrentDto? = try {
        api.torrentInfo(token.trim(), torrentId).data
    } catch (t: Throwable) {
        null
    }

    /** 已缓存资源取直链 */
    suspend fun requestDirectLink(token: String, torrentId: Long, fileId: Long? = null): String? =
        try {
            api.requestDirectLink(token.trim(), torrentId, fileId)
                .takeIf { it.success == true }
                ?.data
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }

    companion object {
        /** 转存完成态 */
        val FINISHED_STATES = setOf("download_finished", "finished", "uploading", "completed")
    }
}
