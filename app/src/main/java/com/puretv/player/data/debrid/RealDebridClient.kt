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
import retrofit2.http.Header
import retrofit2.http.POST
import javax.inject.Inject

/**
 * Real-Debrid 客户端（需求 5.4.5：支持 Real-Debrid 高级拓展，加载高清稳定资源）。
 * 核心能力：unrestrict/link 已缓存资源取直链；/user 校验 Token 有效性。
 * 任何网络/接口异常由调用方降级处理（失败返回 null，提示使用普通源）。
 */
internal interface RealDebridApi {

    /** 解析受保护链接为直链 */
    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unrestrictLink(
        @Header("Authorization") authorization: String,
        @Field("link") link: String,
    ): RealDebridLinkDto

    /** Token 有效性校验 */
    @GET("user")
    suspend fun user(
        @Header("Authorization") authorization: String,
    ): RealDebridUserDto
}

@JsonClass(generateAdapter = true)
data class RealDebridLinkDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "filesize") val filesize: Long? = null,
    @Json(name = "link") val link: String? = null,
    /** 直链地址 */
    @Json(name = "download") val download: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
)

@JsonClass(generateAdapter = true)
data class RealDebridUserDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "type") val type: String? = null,
)

class RealDebridClient @Inject constructor(
    okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val api: RealDebridApi = Retrofit.Builder()
        .baseUrl("https://api.real-debrid.com/rest/1.0/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(RealDebridApi::class.java)

    /** 解析直链；失败返回 null（调用方降级普通源） */
    suspend fun unrestrict(token: String, link: String): String? = try {
        api.unrestrictLink("Bearer ${token.trim()}", link).download?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /** 测试 Token 是否可用 */
    suspend fun testToken(token: String): Boolean = try {
        !api.user("Bearer ${token.trim()}").username.isNullOrBlank()
    } catch (t: Throwable) {
        false
    }
}
