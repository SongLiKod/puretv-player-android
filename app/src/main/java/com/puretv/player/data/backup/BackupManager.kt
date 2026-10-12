package com.puretv.player.data.backup

import android.content.Context
import android.net.Uri
import com.puretv.player.BuildConfig
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.di.IoDispatcher
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 源导入导出 / 备份恢复管理器（需求 5.7.4：支持自定义源导入、导出、备份、一键恢复）。
 *
 * - 导出源：把已保存源下的全部频道导出为标准 M3U 文本（#EXTM3U/#EXTINF），
 *   经 SAF `ACTION_CREATE_DOCUMENT` 保存，可再次作为 M3U 源导入；
 * - 全量备份：源配置 + 设置 打包为 [BackupBundleDto] JSON；
 * - 恢复：读取备份 JSON，重建 SourceEntity 并写回设置（合并覆盖同 ID 源）。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val settingsRepository: SettingsRepository,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 全量备份（sources + settings）导出到用户选定目录 */
    suspend fun backupTo(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): BackupSummary =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在收集源与设置"))
            val sources = sourceDao.getAll()
            val settings = settingsRepository.settings.first()
            val bundle = BackupBundleDto(
                schemaVersion = BackupBundleDto.SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                sources = sources,
                settings = settings,
            )
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = sources.size,
                    total = sources.size,
                    message = "正在写入备份文件",
                )
            )
            writeText(uri, moshi.adapter(BackupBundleDto::class.java).toJson(bundle))
            onProgress(ImportProgress(ImportProgress.Stage.DONE, message = "备份完成"))
            BackupSummary(
                sources = sources.size,
                settingsApplied = true,
                message = "已备份 ${sources.size} 个源与全部设置",
            )
        }

    /** 从备份文件恢复：重建源配置并写回设置 */
    suspend fun restoreFrom(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): BackupSummary =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.DOWNLOADING, message = "正在读取备份文件"))
            val raw = readText(uri) ?: throw IllegalStateException("无法读取所选备份文件")
            onProgress(ImportProgress(ImportProgress.Stage.PARSING, message = "正在解析备份文件"))
            val bundle = moshi.adapter(BackupBundleDto::class.java).fromJson(raw)
                ?: throw IllegalStateException("备份文件格式不正确")
            if (bundle.schemaVersion > BackupBundleDto.SCHEMA_VERSION) {
                throw IllegalStateException("备份文件版本过高，请升级应用后再恢复")
            }
            onProgress(
                ImportProgress(
                    ImportProgress.Stage.SAVING,
                    processed = bundle.sources.size,
                    total = bundle.sources.size,
                    message = "正在恢复源与设置",
                )
            )
            if (bundle.sources.isNotEmpty()) {
                sourceDao.upsertAll(bundle.sources.map { it.copy(lastSyncAt = 0L) })
            }
            bundle.settings?.let { settingsRepository.applySnapshot(it) }
            onProgress(ImportProgress(ImportProgress.Stage.DONE, message = "恢复完成"))
            BackupSummary(
                sources = bundle.sources.size,
                settingsApplied = bundle.settings != null,
                message = "已恢复 ${bundle.sources.size} 个源，可在源管理中逐个刷新频道",
            )
        }

    /** 导出源频道为 M3U 文本（默认导出全部已启用源） */
    suspend fun exportM3U(
        uri: Uri,
        sourceIds: List<String>? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): BackupSummary = withContext(io) {
        onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在读取源列表"))
        val allSources = sourceDao.getAll()
            .filter { sourceIds == null || it.id in sourceIds }
            .filter { sourceIds != null || it.enabled }
        if (allSources.isEmpty()) throw IllegalStateException("没有可导出的源")

        val sourceChannels = allSources.map { source ->
            source to channelDao.observeBySource(source.id).first()
        }
        val total = sourceChannels.sumOf { it.second.size }
        onProgress(
            ImportProgress(
                ImportProgress.Stage.PARSING,
                processed = 0,
                total = total,
                message = "正在整理频道列表",
            )
        )

        val builder = StringBuilder("#EXTM3U\n")
        var processed = 0
        sourceChannels.forEach { (source, channels) ->
            channels.forEach { channel ->
                val group = escapeAttr("${source.name} · ${channel.groupName}")
                val logo = channel.logo.orEmpty()
                val tvgId = channel.epgId.orEmpty()
                builder.append("#EXTINF:-1")
                if (tvgId.isNotBlank()) builder.append(" tvg-id=\"").append(escapeAttr(tvgId)).append('"')
                if (logo.isNotBlank()) builder.append(" tvg-logo=\"").append(escapeAttr(logo)).append('"')
                builder.append(" group-title=\"").append(group).append('"')
                builder.append(',').append(channel.name).append('\n')
                builder.append(channel.url).append('\n')
                processed++
                if (processed % 200 == 0) {
                    onProgress(
                        ImportProgress(
                            ImportProgress.Stage.SAVING,
                            processed = processed,
                            total = total,
                            message = "正在导出频道",
                        )
                    )
                }
            }
        }
        writeText(uri, builder.toString())
        onProgress(ImportProgress(ImportProgress.Stage.DONE, processed, total, "导出完成"))
        BackupSummary(sources = allSources.size, message = "已导出 ${allSources.size} 个源共 $processed 个频道")
    }

    private fun escapeAttr(value: String): String = value.replace("\"", "'")

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
