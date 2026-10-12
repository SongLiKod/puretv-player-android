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
 * MacCMS 采集协议客户端（苹果 CMS「api.php/provide/vod」JSON 接口，
 * TVBox 资源站的通用后端协议）：
 * - 列表 ac=list&pg=N（轻量，无播放地址）
 * - 详情 ac=detail&pg=N / ac=detail&ids=X（含 vod_play_from / vod_play_url）
 * - 搜索 ac=detail&wd=关键词
 */
internal interface CmsVodApi {

    @GET
    suspend fun get(@Url url: String): CmsVodListDto
}

class CmsVodClient @Inject constructor(
    okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val api: CmsVodApi = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(CmsVodApi::class.java)

    /**
     * 详情列表（含播放地址）。page 从 1 开始。
     * @param categoryId 限定分类 id（type_id，来自 [fetchCategories]）
     * @param search 关键词搜索（MacCMS 约定 wd 参数）
     */
    suspend fun fetchDetailPage(
        source: SourceEntity,
        page: Int = 1,
        categoryId: String? = null,
        search: String? = null,
    ): CmsVodListDto {
        val base = apiBase(source) ?: return CmsVodListDto()
        val sb = StringBuilder("$base?ac=detail&pg=$page")
        categoryId?.takeIf { it.isNotBlank() }?.let { sb.append("&t=").append(encode(it)) }
        search?.takeIf { it.isNotBlank() }?.let { sb.append("&wd=").append(encode(it)) }
        return api.get(sb.toString())
    }

    /** 轻量列表（无播放地址，用于快速探测分类与总数） */
    suspend fun fetchListPage(
        source: SourceEntity,
        page: Int = 1,
        categoryId: String? = null,
    ): CmsVodListDto {
        val base = apiBase(source) ?: return CmsVodListDto()
        val sb = StringBuilder("$base?ac=list&pg=$page")
        categoryId?.takeIf { it.isNotBlank() }?.let { sb.append("&t=").append(encode(it)) }
        return api.get(sb.toString())
    }

    /**
     * 按 id 单独取详情（剧集分集加载用）。
     * MacCMS 约定 ids 参数可逗号分隔多值。
     */
    suspend fun fetchDetailByIds(source: SourceEntity, ids: String): CmsVodItemDto? {
        val base = apiBase(source) ?: return null
        return api.get("$base?ac=detail&ids=${encode(ids)}").list?.firstOrNull()
    }

    /** 基址归一：去首尾空白与末尾斜杠，兼容已带 /at/vod 等路径的地址 */
    private fun apiBase(source: SourceEntity): String? {
        val base = source.url.trim().trimEnd('/')
        return base.takeIf { it.startsWith("http") }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {

        /**
         * 解析 MacCMS 播放地址串为播放线列表。
         * 格式约定（TVBox / 苹果 CMS 通用）：
         * - vod_play_from = "飞飞$$$量子"（线路名，$$$ 分隔）
         * - vod_play_url  = "第01集$url1#第02集$url2$$$正片$url3"（与线路一一对应）
         */
        fun parsePlaySources(from: String?, url: String?): List<CmsPlaySource> {
            val names = from?.split("$$$").orEmpty().map { it.trim() }
            val groups = url?.split("$$$").orEmpty()
            if (groups.isEmpty()) return emptyList()
            return groups.mapIndexedNotNull { index, group ->
                val episodes = group.split("#").mapNotNull { part ->
                    val separator = part.lastIndexOf('$')
                    if (separator <= 0 || separator >= part.length - 1) return@mapNotNull null
                    val title = part.substring(0, separator).trim()
                    val playUrl = part.substring(separator + 1).trim()
                    if (playUrl.isEmpty()) return@mapNotNull null
                    CmsPlayEpisode(
                        title = title.ifEmpty { "正片" },
                        url = playUrl,
                    )
                }.ifEmpty { null } ?: return@mapIndexedNotNull null
                CmsPlaySource(
                    name = names.getOrNull(index)?.ifEmpty { "线路${index + 1}" } ?: "线路${index + 1}",
                    episodes = episodes,
                )
            }
        }

        /** 首条可用播放线（优先线路顺序，TVBox 约定第一条为主线路） */
        fun primarySource(from: String?, url: String?): CmsPlaySource? =
            parsePlaySources(from, url).firstOrNull()
    }
}
