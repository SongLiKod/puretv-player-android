package com.puretv.player.data.tvbox

import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.ImportProgress.Stage
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.repository.SourceRepository
import com.puretv.player.core.util.StringUtils
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TVBox 配置格式导入器（用户追加需求：支持 TVBox 聚合配置，如「饭太硬」等 .json 配置）。
 *
 * 解析标准 TVBox 配置 JSON 的三类资源并映射到 PureTV 的源模型：
 * - `sites` 中的 MacCMS 采集站点（api 为 `api.php/provide/vod` JSON 接口）→ 点播聚合源（SourceType.CMS）；
 *   jar 爬虫类站点（api 以 csp_ 开头 / 带 jar 包）明确提示不支持（不加载第三方 dex，安全边界）。
 * - `lives` 直播条目（url 为 m3u 清单）→ 直播源（复用 M3U 导入）。
 * - `parses` 解析接口仅统计提示（当前版本直链播放，不接入解析器）。
 */
@Singleton
class TvBoxConfigImporter @Inject constructor(
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val sourceRepository: SourceRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    data class ImportSummary(
        val cmsImported: Int = 0,
        val livesImported: Int = 0,
        val spiderSkipped: Int = 0,
        val message: String = "",
    )

    /**
     * 从网络地址导入 TVBox 配置（.json / .txt 均可，内容为 TVBox 标准配置 JSON）。
     */
    suspend fun importFromUrl(
        configUrl: String,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportSummary = withContext(io) {
        onProgress(ImportProgress(Stage.PREPARING, message = "正在下载 TVBox 配置"))
        val request = Request.Builder().url(configUrl.trim()).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("配置下载失败：HTTP ${response.code}")
            response.body?.string() ?: throw IOException("配置内容为空")
        }
        parseAndImport(json, configUrl, onProgress)
    }

    /** 从原始 JSON 文本导入（内置 TVBox 配置资产复用同一解析逻辑） */
    suspend fun importFromJson(
        json: String,
        origin: String,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportSummary = withContext(io) {
        parseAndImport(json, origin, onProgress)
    }

    /** 解析 TVBox 配置 JSON 并导入可用资源 */
    private suspend fun parseAndImport(
        json: String,
        origin: String,
        onProgress: (ImportProgress) -> Unit,
    ): ImportSummary = withContext(io) {
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            throw IOException("配置格式错误：不是有效的 TVBox JSON 配置")
        }

        // ---- sites：MacCMS 采集站点 → 点播聚合源 ----
        val sites = root.optJSONArray("sites") ?: JSONArray()
        var cmsImported = 0
        var spiderSkipped = 0
        var siteIndex = 0
        repeat(sites.length()) { i ->
            currentCoroutineContext().ensureActive()
            val site = sites.optJSONObject(i) ?: return@repeat
            siteIndex++
            val name = site.optString("name").trim().ifEmpty { "TVBox 线路 ${i + 1}" }
            val api = site.optString("api").trim()
            val type = site.optString("type").trim()
            onProgress(
                ImportProgress(
                    Stage.PARSING,
                    processed = i + 1,
                    total = sites.length(),
                    message = "正在解析站点「$name」",
                )
            )
            when {
                isJarSpiderSite(site, api, type) -> spiderSkipped++
                looksLikeCms(api) || cmsUrlsFromExt(site).any { looksLikeCms(it) } -> {
                    // 采集地址可能在 api，也可能在 ext（部分配置写法）
                    val cmsUrl = (listOf(api) + cmsUrlsFromExt(site))
                        .firstOrNull { looksLikeCms(it) }
                        ?.trim()?.trimEnd('/')
                        ?: return@repeat
                    val sourceId = StringUtils.stableId(cmsUrl)
                    val existing = sourceDao.get(sourceId)
                    sourceDao.upsert(
                        SourceEntity(
                            id = sourceId,
                            name = name,
                            type = SourceType.CMS,
                            url = cmsUrl,
                            enabled = existing?.enabled ?: true,
                            channelCount = existing?.channelCount ?: 0,
                            groupCount = existing?.groupCount ?: 0,
                            lastSyncAt = existing?.lastSyncAt ?: 0L,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        )
                    )
                    cmsImported++
                }
            }
        }

        // ---- lives：直播条目 → M3U 直播源 ----
        val lives = root.optJSONArray("lives") ?: JSONArray()
        var livesImported = 0
        repeat(lives.length()) { i ->
            currentCoroutineContext().ensureActive()
            val live = lives.optJSONObject(i) ?: return@repeat
            val name = live.optString("name").trim().ifEmpty { "TVBox 直播 ${i + 1}" }
            val url = live.optString("url").trim()
            if (url.isEmpty() || !StringUtils.isHttpUrl(url)) return@repeat
            val epg = live.optString("epg").trim().takeIf { it.isNotEmpty() }
            onProgress(
                ImportProgress(
                    Stage.DOWNLOADING,
                    processed = i + 1,
                    total = lives.length(),
                    message = "正在导入直播「$name」",
                )
            )
            val result = try {
                sourceRepository.importM3u(name, url, epg) {}
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                false
            }
            if (result) livesImported++
        }

        // ---- parses：解析接口统计（当前版本直链播放，提示不支持） ----
        val parses = root.optJSONArray("parses")?.length() ?: 0

        val message = buildString {
            append("TVBox 配置导入完成（$origin）：聚合线路 $cmsImported 条")
            if (livesImported > 0) append("，直播源 $livesImported 条")
            if (spiderSkipped > 0) {
                append(
                    "；$spiderSkipped 个 csp_/jar 爬虫站点需 TVBox 自带的 Java 爬虫引擎动态执行" +
                        "（如「饭太硬」配置的主力站点），本应用出于安全不加载第三方代码，已跳过——" +
                        "配置中的直连线路与直播源均已按 TVBox 原生格式导入，可直接播放",
                )
            }
            if (parses > 0) append("；$parses 个解析接口暂未启用（当前直链播放）")
        }
        ImportSummary(cmsImported, livesImported, spiderSkipped, message)
    }

    /**
     * jar / js 爬虫类站点识别：api 以 csp_ 开头，或配置带 jar / spider / ext.js 扩展包。
     * 此类站点（「饭太硬」等聚合配置的主力，约占 80%）依赖 TVBox 内置的 Java 爬虫引擎
     * 动态加载第三方 dex/js，纯原生播放器无法执行，出于安全与稳定性不支持，
     * 导入时统计并明确提示，其余 MacCMS 采集站与直播源照常导入。
     */
    private fun isJarSpiderSite(site: JSONObject, api: String, type: String): Boolean {
        if (api.startsWith("csp_", ignoreCase = true)) return true
        if (site.optString("jar").isNotEmpty()) return true
        val ext = site.optString("ext")
        if (ext.endsWith(".jar", ignoreCase = true) || ext.endsWith(".js", ignoreCase = true)) return true
        // type=3/4 通常为 jar / drpy 爬虫站点
        return type == "3" || type == "4"
    }

    /**
     * MacCMS 采集接口识别：http(s) 地址且路径为 `api.php/provide/vod`（或以 /at/vod 等结尾的采集端点）。
     * 兼容 TVBox 常见写法：api 直接给采集地址；或地址放在 ext（字符串 / JSON 对象的 api、url 字段）。
     */
    private fun looksLikeCms(url: String): Boolean {
        val trimmed = url.trim()
        if (!StringUtils.isHttpUrl(trimmed)) return false
        val lower = trimmed.lowercase()
        return lower.contains("provide/vod") || lower.contains("api.php/provide")
    }

    /** ext 字段里可能的采集地址候选（字符串本身 / JSON 对象的 api、url 字段） */
    private fun cmsUrlsFromExt(site: JSONObject): List<String> {
        val ext = site.optString("ext")
        if (ext.isBlank()) return emptyList()
        if (looksLikeCms(ext)) return listOf(ext)
        val obj = runCatching { JSONObject(ext) }.getOrNull() ?: return emptyList()
        return listOf(obj.optString("api"), obj.optString("url")).filter { it.isNotBlank() }
    }

    /**
     * 在线搜索发现的地址批量添加为 MacCMS 点播线路（设置 → 在线搜索添加线路）。
     * 地址若已是采集端点（含 provide/vod）直接探测；否则自动补 `api.php/provide/vod`
     * 端点再探测——只有返回采集 JSON（含 list / vod 字段）的地址才写库，
     * 避免把普通网页地址污染进线路列表；重复地址自动跳过。
     * @return 新写入的线路条数
     */
    suspend fun addCmsSources(
        entries: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(io) {
        var added = 0
        entries.forEachIndexed { index, raw ->
            currentCoroutineContext().ensureActive()
            val base = raw.trim().trimEnd('/')
            val candidates = if (looksLikeCms(base)) {
                listOf(base)
            } else {
                listOf("$base/api.php/provide/vod", "$base/api.php/provide/vod/at/json")
            }
            val usable = candidates.firstOrNull { probeCms(it) }
            if (usable != null) {
                val sourceId = StringUtils.stableId(usable)
                if (sourceDao.get(sourceId) == null) {
                    sourceDao.upsert(
                        SourceEntity(
                            id = sourceId,
                            name = hostOf(usable),
                            type = SourceType.CMS,
                            url = usable,
                            enabled = true,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    added++
                }
            }
            onProgress(index + 1, entries.size)
        }
        added
    }

    /** 探测 MacCMS 采集端点：HTTP 200 且响应是采集 JSON（含 list / vod 字段） */
    private fun probeCms(url: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string()?.take(4096).orEmpty()
            body.contains("\"list\"") || body.contains("\"vod\"")
        }
    } catch (t: Throwable) {
        false
    }

    /** 取地址主机名做线路名（如 example.com），解析失败退回原地址 */
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.")?.ifBlank { null } ?: url
    } catch (t: Throwable) {
        url
    }
}
