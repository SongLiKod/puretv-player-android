package com.puretv.player.data.sync

import android.content.Context
import android.net.Uri
import com.puretv.player.BuildConfig
import com.puretv.player.data.local.dao.FavoriteDao
import com.puretv.player.data.local.dao.HistoryDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.di.IoDispatcher
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端同步管理器（需求 5.7.3：支持配置、收藏、播放记录云端同步，多设备互通）。
 *
 * 实现方式（文档未指定云端后端协议，采用「可选云盘目录」的同步链路）：
 * 1. 立即同步：把 sources + settings + favorites + history 打包为 [SyncBundleDto] JSON，
 *    经 SAF `ACTION_CREATE_DOCUMENT` 写入用户选定目录（可选网盘同步目录）；
 * 2. 从云端恢复：经 SAF `ACTION_OPEN_DOCUMENT` 读取该 JSON，重建源、写回设置、
 *    覆盖收藏与播放记录；
 * 3. 上次同步时间持久化在设置中心（lastSyncAt），同步状态页实时展示。
 *
 * 两台设备指向同一目录（如网盘同步目录）即可实现配置互通。
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val settingsRepository: SettingsRepository,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 上次同步时间（毫秒），0 表示尚未同步 */
    val lastSyncAt: Flow<Long> = settingsRepository.settings
        .map { it.lastSyncAt }
        .flowOn(io)

    /** 收藏数量（同步状态页展示） */
    val favoriteCount: Flow<Int> = favoriteDao.observeAll().map { it.size }.flowOn(io)

    /** 播放记录数量（同步状态页展示） */
    val historyCount: Flow<Int> = historyDao.observeAll().map { it.size }.flowOn(io)

    /** 源数量（同步状态页展示） */
    val sourceCount: Flow<Int> = sourceDao.observeAll().map { it.size }.flowOn(io)

    /** 打包并导出同步数据到用户选定目录（SAF 文档 Uri） */
    suspend fun exportTo(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): SyncSummary =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在收集同步数据"))
            val sources = sourceDao.getAll()
            val favorites = favoriteDao.observeAll().first()
            val history = historyDao.observeAll().first()
            val settings = settingsRepository.settings.first()
            val bundle = SyncBundleDto(
                schemaVersion = SyncBundleDto.SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                sources = sources,
                settings = settings,
                favorites = favorites,
                history = history,
            )
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = sources.size + favorites.size + history.size,
                    message = "正在写入同步数据包",
                )
            )
            val json = moshi.adapter(SyncBundleDto::class.java).toJson(bundle)
            writeText(uri, json)
            val now = System.currentTimeMillis()
            settingsRepository.setLastSyncAt(now)
            onProgress(ImportProgress(ImportProgress.Stage.DONE, message = "同步完成"))
            SyncSummary(
                sources = sources.size,
                favorites = favorites.size,
                history = history.size,
                settingsApplied = true,
                at = now,
            )
        }

    /** 从云端目录恢复：读取同步数据包并覆盖写入本机 */
    suspend fun importFrom(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): SyncSummary =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.DOWNLOADING, message = "正在读取同步数据包"))
            val raw = readText(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            onProgress(ImportProgress(ImportProgress.Stage.PARSING, message = "正在解析同步数据包"))
            val bundle = moshi.adapter(SyncBundleDto::class.java).fromJson(raw)
                ?: throw IllegalStateException("同步数据包格式不正确")
            if (bundle.schemaVersion > SyncBundleDto.SCHEMA_VERSION) {
                throw IllegalStateException("同步数据包版本过高，请升级应用后再恢复")
            }
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = bundle.sources.size + bundle.favorites.size + bundle.history.size,
                    message = "正在恢复源、收藏与播放记录",
                )
            )
            if (bundle.sources.isNotEmpty()) sourceDao.upsertAll(bundle.sources)
            bundle.settings?.let { settingsRepository.applySnapshot(it) }
            bundle.favorites.forEach { favoriteDao.upsert(normalizeFavorite(it)) }
            bundle.history.forEach { historyDao.upsert(it) }
            val now = System.currentTimeMillis()
            settingsRepository.setLastSyncAt(now)
            onProgress(ImportProgress(ImportProgress.Stage.DONE, message = "恢复完成"))
            SyncSummary(
                sources = bundle.sources.size,
                favorites = bundle.favorites.size,
                history = bundle.history.size,
                settingsApplied = bundle.settings != null,
                at = now,
            )
        }

    /** 兼容旧数据：收藏创建时间为空时补当前时间 */
    private fun normalizeFavorite(item: FavoriteEntity): FavoriteEntity =
        if (item.createdAt > 0L) item else item.copy(createdAt = System.currentTimeMillis())

    private fun writeText(uri: Uri, text: String) {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入所选位置")
        stream.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun readText(uri: Uri): String? {
        val resolver = context.contentResolver
        return runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }
}
