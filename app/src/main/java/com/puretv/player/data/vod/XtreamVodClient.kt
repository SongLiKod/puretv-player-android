package com.puretv.player.data.vod

import com.puretv.player.data.local.entity.SourceEntity
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Xtream Codes 点播接口客户端。
 * 每个源独立拼接 player_api.php 地址（Retrofit @Url 绝对地址调用），
 * 网络异常由调用方（VodRepository）统一降级处理。
 */
internal interface XtreamVodApi {

    @GET
    suspend fun categories(@Url url: String): List<XtreamCategoryDto>

    @GET
    suspend fun streams(@Url url: String): List<XtreamVodStreamDto>

    @GET
    suspend fun vodInfo(@Url url: String): XtreamVodInfoDto
}

class XtreamVodClient @Inject constructor(
    okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val api: XtreamVodApi = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(XtreamVodApi::class.java)

    /** 点播分类列表（get_vod_categories） */
    suspend fun fetchCategories(source: SourceEntity): List<XtreamCategoryDto> {
        val base = apiBase(source) ?: return emptyList()
        return api.categories("$base&action=get_vod_categories")
    }

    /** 点播列表（get_vod_streams，可按分类过滤） */
    suspend fun fetchStreams(
        source: SourceEntity,
        categoryId: String? = null,
    ): List<XtreamVodStreamDto> {
        val base = apiBase(source) ?: return emptyList()
        val categoryPart = categoryId?.takeIf { it.isNotBlank() }
            ?.let { "&category_id=${encode(it)}" }.orEmpty()
        return api.streams("$base&action=get_vod_streams$categoryPart")
    }

    /** 影片/剧集详情（get_vod_info，剧集分集列表来源） */
    suspend fun fetchVodInfo(source: SourceEntity, vodId: String): XtreamVodInfoDto? {
        val base = apiBase(source) ?: return null
        return api.vodInfo("$base&action=get_vod_info&vod_id=${encode(vodId)}")
    }

    /**
     * 播放地址拼接（Xtream 平台约定）：
     * 电影 {base}/movie/{user}/{pass}/{streamId}.{ext}，剧集 {base}/series/..
     */
    fun playUrl(
        source: SourceEntity,
        streamId: String,
        extension: String?,
        isSeries: Boolean,
    ): String? {
        val base = source.url.trim().trimEnd('/')
        val user = source.username?.trim().orEmpty()
        val pass = source.password?.trim().orEmpty()
        if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) return null
        val type = if (isSeries) "series" else "movie"
        val ext = extension?.trim()?.takeIf { it.isNotEmpty() } ?: "mp4"
        return "$base/$type/$user/$pass/$streamId.$ext"
    }

    private fun apiBase(source: SourceEntity): String? {
        val base = source.url.trim().trimEnd('/')
        val user = source.username?.trim().orEmpty()
        val pass = source.password?.trim().orEmpty()
        if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) return null
        return "$base/player_api.php?username=${encode(user)}&password=${encode(pass)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
