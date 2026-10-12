package com.puretv.player.data.source

import com.puretv.player.di.IoDispatcher
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.SourceCheckReport
import com.puretv.player.data.model.SourceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 源检测工具（需求 5.6.4）：
 * 可达性 + 响应延迟 + 频道数 + 分组数 + EPG 探测 + 抽样播放地址健康度 + 总评。
 */
@Singleton
class SourceChecker @Inject constructor(
    private val client: OkHttpClient,
    private val xtreamClient: XtreamClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 检测专用客户端：短超时，避免长时间等待拖垮检测体验 */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** 检测已保存的源 */
    suspend fun check(source: SourceEntity): SourceCheckReport = when (source.type) {
        SourceType.XTREAM -> checkXtream(
            baseUrl = source.url,
            username = source.username.orEmpty(),
            password = source.password.orEmpty(),
            knownEpgUrl = source.epgUrl,
        )
        SourceType.CMS -> checkCms(source.url)
        else -> checkM3u(
            url = source.url,
            username = source.username,
            password = source.password,
            knownEpgUrl = source.epgUrl,
        )
    }

    /** 检测任意地址（自动识别 M3U / Xtream） */
    suspend fun checkUrl(url: String, username: String?, password: String?): SourceCheckReport =
        if (!username.isNullOrBlank() && !password.isNullOrBlank() && looksLikeXtream(url)) {
            checkXtream(url, username, password, null)
        } else {
            checkM3u(url, username, password, null)
        }

    /** 地址是否像 Xtream 服务器（带账号密码且不是 .m3u 清单） */
    private fun looksLikeXtream(url: String): Boolean {
        val lower = url.trim().lowercase()
        return !lower.endsWith(".m3u") && !lower.endsWith(".m3u8") &&
            !lower.contains("player_api.php")
    }

    /* ------------------------------ M3U 检测 ------------------------------ */

    private suspend fun checkM3u(
        url: String,
        username: String?,
        password: String?,
        knownEpgUrl: String?,
    ): SourceCheckReport = withContext(io) {
        val builder = Request.Builder().url(url.trim())
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        val start = System.currentTimeMillis()
        try {
            probeClient.newCall(builder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    return@withContext SourceCheckReport(
                        target = url,
                        reachable = false,
                        latencyMs = latency,
                        message = "地址不可达：HTTP ${response.code}",
                    )
                }
                val body = response.body
                    ?: return@withContext SourceCheckReport(
                        target = url, reachable = true, latencyMs = latency, message = "响应为空",
                    )
                // 流式解析，仅保留抽样频道，避免大清单占用内存
                val samples = mutableListOf<ChannelEntity>()
                val groups = HashSet<String>()
                var total = 0
                val parseResult = M3uParser.parse(
                    reader = java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8),
                    sourceId = "probe",
                ) { channel ->
                    total++
                    groups.add(channel.groupName)
                    if (samples.size < SAMPLE_SIZE && channel.url.startsWith("http")) {
                        samples.add(channel)
                    }
                }
                val epgUrl = knownEpgUrl ?: parseResult.epgUrl
                val epgFound = !epgUrl.isNullOrBlank()
                val (broken, healthy) = probeStreams(samples)
                SourceCheckReport(
                    target = url,
                    reachable = true,
                    latencyMs = latency,
                    channelCount = total,
                    groupCount = groups.size,
                    epgFound = epgFound,
                    streamHealthy = healthy > 0,
                    brokenChannels = broken,
                    message = buildM3uMessage(total, groups.size, healthy, broken, epgFound),
                    checkedAt = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            SourceCheckReport(
                target = url,
                reachable = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "地址不可达：${e.message ?: "网络错误"}",
            )
        }
    }

    private fun buildM3uMessage(
        total: Int,
        groups: Int,
        healthy: Int,
        broken: Int,
        epgFound: Boolean,
    ): String = when {
        total == 0 -> "地址可达，但未解析到任何频道，请确认是 M3U 播放列表"
        healthy == 0 -> "发现 $total 个频道，但抽样播放地址全部失效"
        else -> "可用：$total 个频道 / $groups 个分组，抽样 $healthy 个播放地址正常" +
            (if (broken > 0) "，$broken 个失效" else "") +
            (if (epgFound) "，已发现 EPG" else "")
    }

    /* ------------------------------ Xtream 检测 ------------------------------ */

    private suspend fun checkXtream(
        baseUrl: String,
        username: String,
        password: String,
        knownEpgUrl: String?,
    ): SourceCheckReport {
        val target = baseUrl.trim()
        if (username.isBlank() || password.isBlank()) {
            return SourceCheckReport(
                target = target,
                reachable = false,
                message = "Xtream 源需要提供用户名与密码",
            )
        }
        val start = System.currentTimeMillis()
        return try {
            // 账号校验（失败抛出 XtreamAuthException / IOException）
            xtreamClient.auth(target, username, password)
            val categories = xtreamClient.liveCategories(target, username, password)
            val streams = xtreamClient.liveStreams(target, username, password)
            val latency = System.currentTimeMillis() - start
            val groupCount = categories.size.takeIf { it > 0 }
                ?: streams.mapNotNull { it.categoryIdText() }.distinct().size
            val samples = streams.take(SAMPLE_SIZE).mapNotNull { stream ->
                val id = stream.streamIdText() ?: return@mapNotNull null
                ChannelEntity(
                    sourceId = "probe",
                    id = id,
                    name = stream.name ?: "频道",
                    url = xtreamClient.streamUrl(target, username, password, id, stream.directSource),
                )
            }
            val epgUrl = knownEpgUrl ?: "${xtreamClient.normalizeBaseUrl(target)}/xmltv.php"
            val epgFound = probeEpgUrl(epgUrl)
            val (broken, healthy) = probeStreams(samples)
            SourceCheckReport(
                target = target,
                reachable = true,
                latencyMs = latency,
                channelCount = streams.size,
                groupCount = groupCount,
                epgFound = epgFound,
                streamHealthy = healthy > 0,
                brokenChannels = broken,
                message = if (streams.isEmpty()) {
                    "账号校验通过，但未返回直播频道"
                } else {
                    "账号有效：${streams.size} 个频道 / $groupCount 个分组，抽样播放地址" +
                        (if (healthy > 0) "$healthy 个正常" else "全部失效") +
                        (if (epgFound) "，已发现 EPG" else "")
                },
                checkedAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            SourceCheckReport(
                target = target,
                reachable = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "检测失败：${e.message ?: "网络错误"}",
            )
        }
    }

    /* ------------------------------ MacCMS 采集源检测 ------------------------------ */

    /** 检测 MacCMS 采集接口：`?ac=list&pg=1` 可达性 + 影片总数 + 抽样播放地址健康度 */
    private suspend fun checkCms(baseUrl: String): SourceCheckReport = withContext(io) {
        val base = baseUrl.trim().trimEnd('/')
        val target = "$base?ac=list&pg=1"
        val start = System.currentTimeMillis()
        try {
            probeClient.newCall(Request.Builder().url(target).build()).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    return@withContext SourceCheckReport(
                        target = base,
                        reachable = false,
                        latencyMs = latency,
                        message = "采集接口不可达：HTTP ${response.code}",
                    )
                }
                val body = response.body?.string().orEmpty()
                val total = Regex("\"total\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (!body.contains("\"list\"") || total <= 0) {
                    return@withContext SourceCheckReport(
                        target = base,
                        reachable = true,
                        latencyMs = latency,
                        message = "接口可达，但未识别到 MacCMS 数据列表，请确认是采集接口",
                    )
                }
                // 抽样详情，验证播放地址可用性
                val samples = runCatching {
                    val detail = probeClient
                        .newCall(Request.Builder().url("$base?ac=detail&pg=1").build())
                        .execute()
                        .use { it.body?.string().orEmpty() }
                    Regex("\"vod_play_url\"\\s*:\\s*\"([^\"]+)\"").findAll(detail)
                        .take(SAMPLE_SIZE)
                        .map { it.groupValues[1] }
                        .mapNotNull { raw ->
                            val url = raw.split("#").firstOrNull()
                                ?.substringAfterLast('$')
                                ?.replace("\\/", "/")
                                ?.trim()
                                ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                            ChannelEntity(sourceId = "probe", id = url, name = "样片", url = url)
                        }
                        .toList()
                }.getOrDefault(emptyList())
                val (broken, healthy) = probeStreams(samples)
                SourceCheckReport(
                    target = base,
                    reachable = true,
                    latencyMs = latency,
                    channelCount = total,
                    streamHealthy = healthy > 0,
                    brokenChannels = broken,
                    message = "可用：聚合 $total 部影片" +
                        (if (samples.isNotEmpty()) "，抽样 ${healthy} 个播放地址正常" +
                            (if (broken > 0) "，$broken 个失效" else "") else ""),
                    checkedAt = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            SourceCheckReport(
                target = base,
                reachable = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "采集接口不可达：${e.message ?: "网络错误"}",
            )
        }
    }

    /* ------------------------------ 抽样探测 ------------------------------ */

    /** 抽样探测 3~5 个频道播放地址健康度 */
    private fun probeStreams(samples: List<ChannelEntity>): Pair<Int, Int> {
        if (samples.isEmpty()) return 0 to 0
        var broken = 0
        var healthy = 0
        for (channel in samples) {
            if (probeUrl(channel.url, channel.headers)) healthy++ else broken++
        }
        return broken to healthy
    }

    /** 播放地址探测：发起首包请求并读取少量数据 */
    private fun probeUrl(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return try {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            probeClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val buffer = ByteArray(2048)
                val read = body.byteStream().use { it.read(buffer) }
                read > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    /** EPG 地址探测（HEAD 请求，2xx 视为存在） */
    private fun probeEpgUrl(epgUrl: String?): Boolean {
        if (epgUrl.isNullOrBlank()) return false
        return try {
            val request = Request.Builder().url(epgUrl).head().build()
            probeClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** 抽样探测频道数（需求：抽样探测 3~5 个频道） */
        const val SAMPLE_SIZE = 4
    }
}
