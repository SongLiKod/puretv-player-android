package com.puretv.player.data.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.SourceCheckReport
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.repository.EpgRepository
import com.puretv.player.data.repository.SourceRepository
import com.puretv.player.core.util.StringUtils
import com.puretv.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直播源仓库实现（需求 5.3.1 / 5.3.2 / 5.7.4）：
 * - M3U（网络 / SAF 本地文件）/ Xtream 主流协议导入
 * - 大容量频道列表流式解析 + 分块入库，全程 IO 协程 + 进度回调，界面不卡死、可取消
 * - 刷新 / 移除 / 启用 / 统计
 */
@Singleton
class SourceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val xtreamClient: XtreamClient,
    private val sourceChecker: SourceChecker,
    private val epgRepository: EpgRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SourceRepository {

    override val sources: Flow<List<SourceEntity>> = sourceDao.observeAll()

    override suspend fun getSource(id: String): SourceEntity? = withContext(io) {
        sourceDao.get(id)
    }

    /* ================================ 导入 M3U ================================ */

    override suspend fun importM3u(
        name: String,
        url: String,
        epgUrl: String?,
        onProgress: (ImportProgress) -> Unit,
    ): SourceEntity = withContext(io) {
        onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在准备导入"))
        val normalizedUrl = url.trim()
        val request = Request.Builder().url(normalizedUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("源文件为空")
            val contentLength = body.contentLength()
            // 已知字节总长时按字节上报下载进度（DOWNLOADING 阶段）
            val countingStream = if (contentLength > 0) {
                onProgress(
                    ImportProgress(
                        ImportProgress.Stage.DOWNLOADING,
                        processed = 0,
                        total = contentLength.toInt(),
                        message = "正在下载频道列表",
                    )
                )
                CountingInputStream(body.byteStream(), contentLength) { read ->
                    onProgress(
                        ImportProgress(
                            ImportProgress.Stage.DOWNLOADING,
                            processed = read,
                            total = contentLength.toInt(),
                            message = "正在下载频道列表",
                        )
                    )
                }
            } else {
                body.byteStream()
            }
            val sourceId = StringUtils.stableId(normalizedUrl)
            val existing = sourceDao.get(sourceId)
            val reader = InputStreamReader(countingStream, Charsets.UTF_8).buffered()
            saveParsedChannels(
                sourceId = sourceId,
                name = name.trim().ifEmpty { "M3U 源" },
                type = SourceType.M3U,
                url = normalizedUrl,
                username = null,
                password = null,
                requestedEpgUrl = epgUrl,
                existing = existing,
                estimatedTotal = if (contentLength > 0) (contentLength / AVG_BYTES_PER_CHANNEL).toInt() else 0,
                onProgress = onProgress,
                parse = { onChannel -> M3uParser.parse(reader, sourceId, onChannel) },
            )
        }
    }

    /* ============================== 导入本地 M3U ============================== */

    override suspend fun importM3uFromFile(
        name: String,
        uri: Uri,
        epgUrl: String?,
        onProgress: (ImportProgress) -> Unit,
    ): SourceEntity = withContext(io) {
        onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在读取本地文件"))
        val size = queryFileSize(uri)
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选文件")
        val sourceId = StringUtils.stableId(uri.toString())
        val existing = sourceDao.get(sourceId)
        val reader = InputStreamReader(input, Charsets.UTF_8).buffered()
        saveParsedChannels(
            sourceId = sourceId,
            name = name.trim().ifEmpty { displayNameOf(uri) ?: "本地 M3U 源" },
            type = SourceType.M3U,
            url = uri.toString(),
            username = null,
            password = null,
            requestedEpgUrl = epgUrl,
            existing = existing,
            estimatedTotal = if (size > 0) (size / AVG_BYTES_PER_CHANNEL).toInt() else 0,
            onProgress = onProgress,
            parse = { onChannel -> M3uParser.parse(reader, sourceId, onChannel) },
        )
    }

    /* ================================ 导入 Xtream ================================ */

    override suspend fun importXtream(
        name: String,
        baseUrl: String,
        username: String,
        password: String,
        epgUrl: String?,
        onProgress: (ImportProgress) -> Unit,
    ): SourceEntity = withContext(io) {
        onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在校验账号"))
        val normalizedBase = xtreamClient.normalizeBaseUrl(baseUrl)
        // player_api.php 校验（username/password），失败抛出异常
        xtreamClient.auth(normalizedBase, username, password)

        onProgress(ImportProgress(ImportProgress.Stage.DOWNLOADING, message = "正在拉取分类与频道列表"))
        val categories = xtreamClient.liveCategories(normalizedBase, username, password)
        val categoryNameById = categories
            .mapNotNull { cat -> cat.categoryId?.let { it to (cat.categoryName ?: "未分组") } }
            .toMap()
        val streams = xtreamClient.liveStreams(normalizedBase, username, password)
        onProgress(
            ImportProgress(
                ImportProgress.Stage.PARSING,
                processed = 0,
                total = streams.size,
                message = "正在解析频道",
            )
        )

        val now = System.currentTimeMillis()
        val channels = ArrayList<ChannelEntity>(streams.size)
        streams.forEachIndexed { index, stream ->
            val streamId = stream.streamIdText() ?: return@forEachIndexed
            val channelName = stream.name?.trim().orEmpty().ifEmpty { "频道 $streamId" }
            channels.add(
                ChannelEntity(
                    sourceId = StringUtils.stableId(normalizedBase),
                    id = streamId,
                    name = channelName,
                    logo = stream.streamIcon?.trim()?.takeIf { it.isNotEmpty() },
                    url = xtreamClient.streamUrl(normalizedBase, username, password, streamId, stream.directSource),
                    groupName = stream.categoryIdText()?.let { categoryNameById[it] } ?: "未分组",
                    epgId = stream.epgChannelId?.trim()?.takeIf { it.isNotEmpty() },
                    number = stream.num?.trim()?.toIntOrNull() ?: (index + 1),
                    quality = QualityDetector.detect(channelName),
                    // Xtream 时移回看：拼 utc/lutc 参数（见 EpgRepository.buildCatchupUrl）
                    catchupType = "xtream",
                    catchupDays = CATCHUP_DAYS_DEFAULT,
                    updatedAt = now,
                )
            )
            if ((index + 1) % REPORT_EVERY == 0) {
                onProgress(
                    ImportProgress(
                        ImportProgress.Stage.PARSING,
                        processed = index + 1,
                        total = streams.size,
                        message = "正在解析频道",
                    )
                )
            }
        }

        val sourceId = StringUtils.stableId(normalizedBase)
        val existing = sourceDao.get(sourceId)
        val source = writeChannelsInChunks(
            sourceId = sourceId,
            name = name.trim().ifEmpty { "Xtream 源" },
            type = SourceType.XTREAM,
            url = normalizedBase,
            username = username,
            password = password,
            epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${normalizedBase}/xmltv.php",
            channels = channels,
            existing = existing,
            onProgress = onProgress,
        )
        onProgress(
            ImportProgress(ImportProgress.Stage.DONE, channels.size, channels.size, "导入完成")
        )
        source
    }

    /* ================================ 刷新 / 删除 ================================ */

    override suspend fun refresh(sourceId: String, onProgress: (ImportProgress) -> Unit) {
        val source = withContext(io) { sourceDao.get(sourceId) }
            ?: throw IOException("源不存在")
        when (source.type) {
            SourceType.XTREAM -> {
                importXtream(
                    name = source.name,
                    baseUrl = source.url,
                    username = source.username.orEmpty(),
                    password = source.password.orEmpty(),
                    epgUrl = source.epgUrl,
                    onProgress = onProgress,
                )
            }
            SourceType.CMS -> {
                // 聚合点播源无频道清单；内容同步在影视页「同步」触发（VodRepository.refresh）
                onProgress(
                    ImportProgress(
                        ImportProgress.Stage.DONE,
                        message = "聚合影视源无直播频道，影视内容请在影视页同步",
                    )
                )
                return
            }
            else -> {
                if (source.url.startsWith("content://")) {
                    importM3uFromFile(source.name, Uri.parse(source.url), source.epgUrl, onProgress)
                } else {
                    importM3u(source.name, source.url, source.epgUrl, onProgress)
                }
            }
        }
        // 刷新完成后同步 EPG（实时节目单）
        syncEpgQuietly(sourceId, onProgress)
    }

    /** EPG 同步失败不阻断频道刷新（EPG 属于增强信息） */
    private suspend fun syncEpgQuietly(sourceId: String, onProgress: (ImportProgress) -> Unit) {
        val source = withContext(io) { sourceDao.get(sourceId) } ?: return
        if (source.epgUrl.isNullOrBlank()) return
        runCatching { epgRepository.syncFrom(source, onProgress) }
    }

    override suspend fun removeSource(sourceId: String) = withContext(io) {
        sourceDao.delete(sourceId)
        channelDao.deleteBySource(sourceId)
    }

    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) =
        withContext(io) { sourceDao.setEnabled(sourceId, enabled) }

    /* ================================ 源检测 ================================ */

    override suspend fun checkSource(sourceId: String): SourceCheckReport {
        val source = withContext(io) { sourceDao.get(sourceId) }
            ?: return SourceCheckReport(target = sourceId, reachable = false, message = "源不存在")
        return sourceChecker.check(source)
    }

    override suspend fun checkUrl(
        url: String,
        username: String?,
        password: String?,
    ): SourceCheckReport = sourceChecker.checkUrl(url, username, password)

    /* ============================== 内部：解析落库 ============================== */

    /**
     * M3U 流式解析 → 分块写库 → 统计。
     * 先 deleteBySource 再分块 upsertAll，每块回调 SAVING 进度。
     */
    private suspend fun saveParsedChannels(
        sourceId: String,
        name: String,
        type: String,
        url: String,
        username: String?,
        password: String?,
        requestedEpgUrl: String?,
        existing: SourceEntity?,
        estimatedTotal: Int,
        onProgress: (ImportProgress) -> Unit,
        parse: ((ChannelEntity) -> Unit) -> M3uParser.ParseResult,
    ): SourceEntity {
        val buffer = ArrayList<ChannelEntity>(1024)
        var lastParseReportAt = 0L
        val parseResult = parse { channel ->
            buffer.add(channel)
            // PARSING 阶段每 500 条（且不超过每 200ms 一次）回调进度
            if (buffer.size % REPORT_EVERY == 0) {
                val now = System.currentTimeMillis()
                if (now - lastParseReportAt >= 200) {
                    lastParseReportAt = now
                    onProgress(
                        ImportProgress(
                            ImportProgress.Stage.PARSING,
                            processed = buffer.size,
                            total = estimatedTotal,
                            message = "正在解析频道",
                        )
                    )
                }
            }
        }
        val source = writeChannelsInChunks(
            sourceId = sourceId,
            name = name,
            type = type,
            url = url,
            username = username,
            password = password,
            epgUrl = requestedEpgUrl?.trim()?.takeIf { it.isNotEmpty() } ?: parseResult.epgUrl,
            channels = buffer,
            existing = existing,
            onProgress = onProgress,
        )
        onProgress(
            ImportProgress(ImportProgress.Stage.DONE, buffer.size, buffer.size, "导入完成")
        )
        return source
    }

    /** 先 deleteBySource，再按块（每 500 条）写库并回调进度，最后更新源统计 */
    private suspend fun writeChannelsInChunks(
        sourceId: String,
        name: String,
        type: String,
        url: String,
        username: String?,
        password: String?,
        epgUrl: String?,
        channels: List<ChannelEntity>,
        existing: SourceEntity?,
        onProgress: (ImportProgress) -> Unit,
    ): SourceEntity {
        onProgress(
            ImportProgress(
                ImportProgress.Stage.SAVING,
                processed = 0,
                total = channels.size,
                message = "正在写入数据库",
            )
        )
        channelDao.deleteBySource(sourceId)
        var saved = 0
        channels.chunked(SAVE_CHUNK).forEach { chunk ->
            channelDao.upsertAll(chunk)
            saved += chunk.size
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = saved,
                    total = channels.size,
                    message = "正在写入数据库",
                )
            )
        }
        val now = System.currentTimeMillis()
        val groupCount = channels.map { it.groupName }.distinct().size
        val source = SourceEntity(
            id = sourceId,
            name = name,
            type = type,
            url = url,
            username = username,
            password = password,
            epgUrl = epgUrl,
            enabled = existing?.enabled ?: true,
            channelCount = channels.size,
            groupCount = groupCount,
            lastSyncAt = now,
            createdAt = existing?.createdAt ?: now,
        )
        sourceDao.upsert(source)
        return source
    }

    /* ============================== 内部：工具方法 ============================== */

    private fun queryFileSize(uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
            }
        }.getOrNull() ?: -1L
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        /** 解析进度上报步长：每 500 条 */
        const val SAVE_CHUNK = 500
        private const val REPORT_EVERY = 500

        /** M3U 平均每频道字节数（用于估算解析总数，仅影响进度条观感） */
        private const val AVG_BYTES_PER_CHANNEL = 160L

        /** Xtream 默认回看可回溯天数 */
        private const val CATCHUP_DAYS_DEFAULT = 7
    }
}

/**
 * 统计已读字节的输入流（DOWNLOADING 阶段进度回调，节流至每 256KB 一次）
 */
private class CountingInputStream(
    private val delegate: InputStream,
    contentLength: Long,
    private val onRead: (readBytes: Int) -> Unit,
) : InputStream() {
    private var totalRead = 0
    private var lastReport = 0
    private var lastReportAt = 0L
    private val contentLengthBytes = contentLength

    override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) count(read)
        return read
    }

    override fun close() = delegate.close()

    private fun count(read: Int) {
        totalRead += read
        val now = System.currentTimeMillis()
        if (totalRead - lastReport >= REPORT_BYTES || now - lastReportAt >= 500) {
            lastReport = totalRead
            lastReportAt = now
            onRead(totalRead.coerceAtMost(if (contentLengthBytes > 0) contentLengthBytes.toInt() else Int.MAX_VALUE))
        }
    }

    companion object {
        private const val REPORT_BYTES = 256 * 1024
    }
}
