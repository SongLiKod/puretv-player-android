package com.puretv.player.data.source

import com.puretv.player.di.IoDispatcher
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/* ============================== Xtream Codes API 数据模型 ============================== */

@JsonClass(generateAdapter = true)
data class XtreamAuthResponse(
    @Json(name = "user_info") val userInfo: XtreamUserInfo? = null,
    @Json(name = "server_info") val serverInfo: XtreamServerInfo? = null,
)

@JsonClass(generateAdapter = true)
data class XtreamUserInfo(
    val auth: Int? = null,
    val status: String? = null,
    val username: String? = null,
    @Json(name = "exp_date") val expDate: String? = null,
    @Json(name = "max_connections") val maxConnections: String? = null,
)

@JsonClass(generateAdapter = true)
data class XtreamServerInfo(
    val url: String? = null,
    @Json(name = "server_port") val serverPort: String? = null,
    @Json(name = "https_port") val httpsPort: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
)

@JsonClass(generateAdapter = true)
data class XtreamCategory(
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "category_name") val categoryName: String? = null,
    @Json(name = "parent_id") val parentId: Int? = null,
)

@JsonClass(generateAdapter = true)
data class XtreamLiveStream(
    val num: String? = null,
    val name: String? = null,
    @Json(name = "stream_type") val streamType: String? = null,
    /** 兼容数字与字符串两种 JSON 形态（不同 Xtream 服务器实现不一致） */
    @Json(name = "stream_id") val streamId: Any? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,
    @Json(name = "epg_channel_id") val epgChannelId: String? = null,
    @Json(name = "category_id") val categoryId: Any? = null,
    @Json(name = "tv_archive") val tvArchive: Int? = null,
    @Json(name = "direct_source") val directSource: String? = null,
) {
    /** stream_id → 字符串 */
    fun streamIdText(): String? = scalarText(streamId)

    /** category_id → 字符串 */
    fun categoryIdText(): String? = scalarText(categoryId)

    companion object {
        internal fun scalarText(value: Any?): String? = when (value) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is Number -> value.toString()
            else -> value.toString()
        }
    }
}

/**
 * Xtream Codes API 客户端（需求 5.3.1：支持 Xtream 主流协议）。
 * player_api.php 校验账号 → get_live_categories / get_live_streams 拉取全量直播频道。
 */
@Singleton
class XtreamClient @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 归一化服务器地址：补 scheme、去尾部斜杠 */
    fun normalizeBaseUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        return url
    }

    /**
     * 账号校验（player_api.php 不带 action）
     * @throws IOException 地址不可达 / 解析失败
     * @throws XtreamAuthException 账号密码错误或账号失效
     */
    suspend fun auth(baseUrl: String, username: String, password: String): XtreamAuthResponse =
        withContext(io) {
            val response = executeForString(api(baseUrl, username, password))
            val parsed = moshi.adapter(XtreamAuthResponse::class.java).fromJson(response)
                ?: throw IOException("服务器响应无法解析")
            val info = parsed.userInfo
            if (info?.auth != 1) {
                throw XtreamAuthException(info?.status ?: "账号校验失败，请检查用户名与密码")
            }
            parsed
        }

    /** 直播分类列表 */
    suspend fun liveCategories(
        baseUrl: String,
        username: String,
        password: String,
    ): List<XtreamCategory> = withContext(io) {
        val response = executeForString(api(baseUrl, username, password, "get_live_categories"))
        if (!response.trim().startsWith("[")) return@withContext emptyList()
        val type = Types.newParameterizedType(List::class.java, XtreamCategory::class.java)
        moshi.adapter<List<XtreamCategory>>(type).fromJson(response) ?: emptyList()
    }

    /** 全量直播频道列表 */
    suspend fun liveStreams(
        baseUrl: String,
        username: String,
        password: String,
    ): List<XtreamLiveStream> = withContext(io) {
        val response = executeForString(api(baseUrl, username, password, "get_live_streams"))
        if (!response.trim().startsWith("[")) return@withContext emptyList()
        val type = Types.newParameterizedType(List::class.java, XtreamLiveStream::class.java)
        moshi.adapter<List<XtreamLiveStream>>(type).fromJson(response) ?: emptyList()
    }

    /**
     * 直播播放地址：{base}/live/{user}/{pass}/{streamId}.m3u8
     * 服务器返回 direct_source 时优先使用
     */
    fun streamUrl(
        baseUrl: String,
        username: String,
        password: String,
        streamId: String,
        directSource: String?,
    ): String {
        val direct = directSource?.trim()
        if (!direct.isNullOrEmpty()) return direct
        val base = normalizeBaseUrl(baseUrl)
        return "$base/live/$username/$password/$streamId.m3u8"
    }

    /** 构造 player_api.php 请求地址 */
    private fun api(baseUrl: String, username: String, password: String, action: String? = null): String {
        val base = normalizeBaseUrl(baseUrl)
        val http: HttpUrl = base.toHttpUrlOrNull()
            ?: throw IOException("服务器地址无效：$baseUrl")
        val builder = http.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build().toString()
    }

    private fun executeForString(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            return body.string()
        }
    }
}

/** Xtream 账号校验失败（账号密码错误/账号失效） */
class XtreamAuthException(message: String) : IOException(message)
