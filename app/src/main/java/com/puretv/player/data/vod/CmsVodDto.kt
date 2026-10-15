package com.puretv.player.data.vod

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlin.math.floor

/**
 * MacCMS 采集协议 DTO（苹果 CMS「api.php/provide/vod」JSON 接口）。
 * 响应示例：{"code":1,"msg":"数据列表","page":1,"pagecount":7919,"total":158373,"list":[...]}
 * 数值字段在不同站点可能是数字或字符串，统一用 Any? 接收后经扩展转义。
 */
@JsonClass(generateAdapter = true)
data class CmsVodListDto(
    @Json(name = "code") val code: Any? = null,
    @Json(name = "msg") val msg: String? = null,
    @Json(name = "page") val page: Any? = null,
    @Json(name = "pagecount") val pageCount: Any? = null,
    @Json(name = "limit") val limit: Any? = null,
    @Json(name = "total") val total: Any? = null,
    @Json(name = "list") val list: List<CmsVodItemDto>? = null,
) {
    val pageInt: Int get() = pageCount.toIntOrDefault(1)
    val totalInt: Int get() = total.toIntOrDefault(0)
}

/**
 * Any? 数值 → Int（MacCMS 分页字段专用）。
 *
 * Moshi 把 JSON 数字统一解析为 Double（`pagecount:7919` → `7919.0`），直接 `toString()`
 * 会得到 "7919.0" 令 `toIntOrNull()` 失败 → 总页数被误判为 1 → 整源同步只拉第 1 页
 * （每条线路只加载 20 部）。这里兼容三种形态：
 * Double/Float 整值（7919.0 → 7919）、其他 Number、以及 "7919" / "7919.0" 字符串。
 */
internal fun Any?.toIntOrDefault(default: Int): Int = when (this) {
    null -> default
    is Double -> if (isFinite() && this == floor(this)) toInt() else default
    is Float -> if (isFinite() && this == floor(this)) toInt() else default
    is Number -> toInt()
    else -> toString().trim().substringBefore('.').toIntOrNull() ?: default
}

/**
 * JSON 数字 id 归一化（type_id / 分类 id 学习专用）：6.0 → "6"、"6.0" → "6"；
 * 非数字 id（如 "abc"）原样保留；null/空白 → null（丢弃）。
 *
 * Moshi 把 JSON 数字统一解析成 Double（`6` → `6.0`），直接 toString 会向服务器发
 * `t=6.0`，部分站点不认（分类 id 学错 → 该标签过滤拉不到内容，用户反馈的动漫/综艺不加载）。
 */
internal fun typeIdToString(raw: Any?): String? = when (raw) {
    null -> null
    is Number -> raw.toInt().toString()
    else -> raw.toString().trim().let { text ->
        val head = text.substringBefore('.')
        if (head.isNotEmpty() && head.all { it in '0'..'9' }) head else text.takeIf { it.isNotEmpty() }
    }
}

@JsonClass(generateAdapter = true)
data class CmsVodItemDto(
    @Json(name = "vod_id") val vodId: Any? = null,
    @Json(name = "type_id") val typeId: Any? = null,
    @Json(name = "type_name") val typeName: String? = null,
    @Json(name = "vod_name") val vodName: String? = null,
    @Json(name = "vod_sub") val vodSub: String? = null,
    @Json(name = "vod_pic") val vodPic: String? = null,
    @Json(name = "vod_pic_thumb") val vodPicThumb: String? = null,
    @Json(name = "vod_actor") val vodActor: String? = null,
    @Json(name = "vod_director") val vodDirector: String? = null,
    @Json(name = "vod_blurb") val vodBlurb: String? = null,
    @Json(name = "vod_content") val vodContent: String? = null,
    @Json(name = "vod_class") val vodClass: String? = null,
    @Json(name = "vod_year") val vodYear: String? = null,
    @Json(name = "vod_area") val vodArea: String? = null,
    @Json(name = "vod_lang") val vodLang: String? = null,
    @Json(name = "vod_remarks") val vodRemarks: String? = null,
    @Json(name = "vod_score") val vodScore: Any? = null,
    @Json(name = "vod_tag") val vodTag: String? = null,
    @Json(name = "vod_time") val vodTime: String? = null,
    /** 播放源名，$$$ 分隔（如 "飞飞$$$量子"） */
    @Json(name = "vod_play_from") val vodPlayFrom: String? = null,
    /** 播放地址组，与 vod_play_from 一一对应；组内 # 分集，集内 标题$URL */
    @Json(name = "vod_play_url") val vodPlayUrl: String? = null,
)

/** 解析后的一条播放线（同名线路多集） */
data class CmsPlaySource(
    val name: String,
    val episodes: List<CmsPlayEpisode>,
)

/** 解析后的单集（标题 + 直链） */
data class CmsPlayEpisode(
    val title: String,
    val url: String,
)
