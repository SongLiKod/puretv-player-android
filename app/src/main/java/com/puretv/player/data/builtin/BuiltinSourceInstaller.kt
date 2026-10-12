package com.puretv.player.data.builtin

import android.content.Context
import com.puretv.player.di.IoDispatcher
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.tvbox.TvBoxConfigImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置示例源安装器（新手引导「一键导入源 / 一键初始化」· 源管理「重新导入内置源」）。
 *
 * 两部分内置资源：
 * 1. 内置 10 条公开、稳定、高清直播/点播样片（4 路官方直播台 + 6 路高清官方样片），
 *    与 assets/builtin/puretv_demo.m3u 保持一致；
 * 2. 内置 20 条 TVBox 格式聚合影视线路（assets/builtin/puretv_tvbox.json，MacCMS 采集协议，
 *    飞飞/华哥/量子/无尽/红牛/暴风/豆瓣等公开资源站接口，均已实测可达）。
 */
@Singleton
class BuiltinSourceInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val tvBoxConfigImporter: TvBoxConfigImporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeInstalled(): Flow<Boolean> =
        sourceDao.observeAll().map { list -> list.any { it.id == BUILTIN_SOURCE_ID } }.flowOn(io)

    suspend fun getInstalled(): SourceEntity? = withContext(io) { sourceDao.get(BUILTIN_SOURCE_ID) }

    /**
     * 安装（或按最新内置清单刷新）内置示例源。
     * @param onProgress 导入进度回调（PREPARING → SAVING → DONE），供 UI 弹进度条
     */
    suspend fun install(onProgress: (ImportProgress) -> Unit = {}): SourceEntity =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在准备内置源"))
            val now = System.currentTimeMillis()
            val channels = BUILTIN_CHANNELS.map { it.copy(updatedAt = now) }
            val source = SourceEntity(
                id = BUILTIN_SOURCE_ID,
                name = BUILTIN_SOURCE_NAME,
                type = SourceType.M3U,
                url = ASSET_URL,
                enabled = true,
                channelCount = channels.size,
                groupCount = channels.map { it.groupName }.distinct().size,
                lastSyncAt = now,
                createdAt = now,
            )
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = channels.size,
                    total = channels.size,
                    message = "正在写入内置频道",
                )
            )
            sourceDao.upsert(source)
            channelDao.replaceSourceChannels(BUILTIN_SOURCE_ID, channels)
            onProgress(ImportProgress(ImportProgress.Stage.SAVING, channels.size, channels.size, "正在安装内置聚合影视线路"))
            // 内置 TVBox 格式聚合影视线路（20 条 MacCMS 资源站，复用 TVBox 配置解析逻辑）
            val tvBoxSummary = try {
                val json = context.assets.open(TVBOX_ASSET_PATH)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                tvBoxConfigImporter.importFromJson(json, "内置 TVBox 线路", onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                null // 内置聚合线路失败不阻断直播样片安装
            }
            // 从 GitHub 公开 TVBox 配置仓库在线导入（追加需求：内置之外再从 GitHub 拉取
            // 可用 JSON 配置，自动提取其中可直连的 MacCMS 采集站；jar/爬虫站自动跳过）
            val ghImported = importGitHubConfigs(onProgress)
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.DONE,
                    channels.size,
                    channels.size,
                    "内置源导入完成" +
                        (tvBoxSummary?.let { "，另装 ${it.cmsImported} 条聚合影视线路" } ?: "") +
                        (if (ghImported > 0) "，并从 GitHub 追加 $ghImported 条" else ""),
                )
            )
            source
        }

    /**
     * 从 GitHub 上的公开 TVBox 配置仓库在线导入聚合线路（best-effort，逐个镜像尝试）。
     * 解析复用 [TvBoxConfigImporter]：其中 MacCMS 采集站入库、csp_/jar 爬虫站自动跳过；
     * 拿到第一份可用配置即停止，网络不可达时静默降级（不阻断内置源安装）。
     * @return 实际导入的采集线路数
     */
    private suspend fun importGitHubConfigs(onProgress: (ImportProgress) -> Unit): Int {
        var imported = 0
        for (url in GITHUB_CONFIG_URLS) {
            onProgress(
                ImportProgress(ImportProgress.Stage.SAVING, message = "正在从 GitHub 获取 TVBox 配置…"),
            )
            val json = try {
                withTimeoutOrNull(GITHUB_FETCH_TIMEOUT_MS) { fetchText(url) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            } ?: continue
            val summary = try {
                val name = url.substringAfterLast('/').removeSuffix(".json")
                tvBoxConfigImporter.importFromJson(json, "GitHub·$name", onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            } ?: continue
            imported += summary.cmsImported
            if (imported > 0) break
        }
        return imported
    }

    private suspend fun fetchText(url: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return withContext(io) {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    companion object {
        const val BUILTIN_SOURCE_ID = "builtin_demo"
        const val BUILTIN_SOURCE_NAME = "内置示例源"
        private const val ASSET_URL = "asset:///builtin/puretv_demo.m3u"

        /** 内置 TVBox 格式聚合影视线路配置（20 条 MacCMS 采集源） */
        private const val TVBOX_ASSET_PATH = "builtin/puretv_tvbox.json"

        /**
         * GitHub 公开 TVBox 配置仓库地址（直连 + 镜像逐个尝试）。
         * 这些配置为 TVBox 标准 JSON 格式（sites/lives/parses），
         * 其中的 MacCMS 采集站会被提取入库，csp_/jar 爬虫站自动跳过。
         */
        private val GITHUB_CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/0827.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/gaotianliuyun/gao/master/0827.json",
            "https://raw.githubusercontent.com/anaer/Meow/main/meow.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/anaer/Meow/main/meow.json",
            "https://raw.githubusercontent.com/jinenge/tvbox/main/tvbox.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/jinenge/tvbox/main/tvbox.json",
        )

        /** GitHub 配置抓取超时（弱网下不拖慢内置源安装） */
        private const val GITHUB_FETCH_TIMEOUT_MS = 25_000L

        /** 内置 10 条资源（与 assets/builtin/puretv_demo.m3u 一一对应，均已实测可用） */
        private val BUILTIN_CHANNELS = listOf(
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo01", number = 1,
                name = "NASA TV", groupName = "内置直播", quality = "HD",
                url = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo02", number = 2,
                name = "France 24 English", groupName = "内置直播", quality = "HD",
                url = "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo03", number = 3,
                name = "Red Bull TV", groupName = "内置直播", quality = "HD",
                url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo04", number = 4,
                name = "France 24 Francais", groupName = "内置直播", quality = "HD",
                url = "https://static.france24.com/live/F24_FR_LO_HLS/live_web.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo05", number = 5,
                name = "Apple 高清自适应测试流", groupName = "内置点播", quality = "FHD",
                url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo06", number = 6,
                name = "Big Buck Bunny 自适应流", groupName = "内置点播", quality = "FHD",
                url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo07", number = 7,
                name = "Tears of Steel 高清", groupName = "内置点播", quality = "FHD",
                url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo08", number = 8,
                name = "Angel One 开源电影", groupName = "内置点播", quality = "FHD",
                url = "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo09", number = 9,
                name = "Sintel 高清样片", groupName = "内置点播", quality = "FHD",
                url = "https://test-videos.co.uk/vids/sintel/mp4/h264/1080/Sintel_1080_10s_30MB.mp4",
            ),
            ChannelEntity(
                sourceId = BUILTIN_SOURCE_ID, id = "demo10", number = 10,
                name = "Big Buck Bunny 4K 超清", groupName = "内置点播", quality = "4K",
                url = "https://upload.wikimedia.org/wikipedia/commons/c/c0/Big_Buck_Bunny_4K.webm",
            ),
        )
    }
}
