package com.puretv.player.data.repository

import com.puretv.player.core.util.StringUtils
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.dao.EpgDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.source.XmlTvParser
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPG 节目单仓库（需求 5.3.4：EPG 实时节目单、时间轴预览、回看、预约播放）。
 * XMLTV 流式下载解析 → 分块入库 → 清理过期数据。
 */
@Singleton
class EpgRepository @Inject constructor(
    private val client: OkHttpClient,
    private val epgDao: EpgDao,
    private val sourceDao: SourceDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    data class SyncResult(val programCount: Int, val channelCount: Int)

    /**
     * 从源的 EPG 地址（XMLTV）同步节目单。
     * 下载后分块 upsert、prune 过期（早于昨天 0 点）。
     */
    suspend fun syncFrom(
        source: SourceEntity,
        onProgress: (ImportProgress) -> Unit = {},
    ): SyncResult = withContext(io) {
        val epgUrl = source.epgUrl?.trim()
        if (epgUrl.isNullOrEmpty()) return@withContext SyncResult(0, 0)
        onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在准备节目单"))

        val request = Request.Builder().url(epgUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("节目单下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("节目单为空")
            onProgress(ImportProgress(ImportProgress.Stage.DOWNLOADING, message = "正在下载节目单"))

            val buffer = ArrayList<EpgProgramEntity>(EPG_CHUNK)
            val epgIds = HashSet<String>()
            var total = 0
            onProgress(ImportProgress(ImportProgress.Stage.PARSING, 0, 0, "正在解析节目单"))
            val count = XmlTvParser.parse(
                java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8)
            ) { program ->
                buffer.add(program)
                epgIds.add(program.epgId)
                total++
                if (buffer.size >= EPG_CHUNK) {
                    epgDao.upsertPrograms(buffer)
                    buffer.clear()
                    onProgress(
                        ImportProgress(
                            ImportProgress.Stage.PARSING,
                            processed = total,
                            total = 0,
                            message = "正在解析节目单（$total 条）",
                        )
                    )
                }
            }
            if (buffer.isNotEmpty()) {
                onProgress(ImportProgress(ImportProgress.Stage.SAVING, total, total, "正在写入节目单"))
                epgDao.upsertPrograms(buffer)
                buffer.clear()
            }
            // 清理过期节目（早于昨天 0 点）
            epgDao.prune(TimeUtils.startOfToday() - DAY_MS)
            onProgress(ImportProgress(ImportProgress.Stage.DONE, count, count, "节目单同步完成"))
            SyncResult(count, epgIds.size)
        }
    }

    /** 某频道在 [from, to) 时间窗内的节目单（EPG 时间轴预览） */
    fun observePrograms(epgId: String, from: Long, to: Long): Flow<List<EpgProgramEntity>> =
        epgDao.observePrograms(epgId, from, to)

    /** 当前正在播出 + 下一个节目 */
    suspend fun nowNext(epgId: String): Pair<EpgProgramEntity?, EpgProgramEntity?> =
        withContext(io) {
            val now = System.currentTimeMillis()
            val current = epgDao.currentProgram(epgId, now)
            val next = epgDao.upcoming(epgId, now, 1).firstOrNull()
            current to next
        }

    /** 清理全部过期节目 */
    suspend fun pruneOld() = withContext(io) {
        epgDao.prune(TimeUtils.startOfToday() - DAY_MS)
    }

    /**
     * 构造回看（时移）播放地址（需求 5.3.4：回看）。
     * - catchupType=default/shift 且有模板：StringUtils.applyCatchupTemplate 替换占位
     * - Xtream 源无模板：源地址拼 utc/lutc 秒级时间戳参数
     * - catchupDays<=0 或无任何回看配置：返回 null（该频道不支持回看）
     */
    suspend fun buildCatchupUrl(
        channel: ChannelEntity,
        program: EpgProgramEntity,
    ): String? = withContext(io) {
        val now = System.currentTimeMillis()
        // 只有已播出的节目才支持回看
        if (program.endAt > now) return@withContext null
        val catchupDays = channel.catchupDays
        val template = channel.catchupSource?.trim()
        val type = channel.catchupType?.trim()?.lowercase()

        // 无任何回看配置
        if (type.isNullOrEmpty() && template.isNullOrEmpty()) return@withContext null
        if (catchupDays <= 0) return@withContext null
        // 超出可回溯天数
        if (now - program.startAt > catchupDays * DAY_MS) return@withContext null

        val fromTemplate = when {
            !template.isNullOrEmpty() ->
                StringUtils.applyCatchupTemplate(template, program.startAt, program.endAt)
            type == "xtream" || sourceTypeOf(channel.sourceId) == SourceType.XTREAM -> {
                val separator = if (channel.url.contains("?")) "&" else "?"
                val utc = program.startAt / 1000
                val lutc = now / 1000
                "${channel.url}${separator}utc=$utc&lutc=$lutc"
            }
            else -> null
        }
        fromTemplate?.takeIf { it.isNotBlank() }
    }

    private suspend fun sourceTypeOf(sourceId: String): String? =
        sourceDao.get(sourceId)?.type

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val EPG_CHUNK = 300
    }
}
