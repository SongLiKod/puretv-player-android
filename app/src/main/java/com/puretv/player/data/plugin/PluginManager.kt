package com.puretv.player.data.plugin

import android.content.Context
import com.puretv.player.data.local.dao.PluginDao
import com.puretv.player.data.local.entity.PluginEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.di.IoDispatcher
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量插件管理器（需求 5.7.1 / 5.7.2）。
 *
 * - 官方插件目录来自 assets/plugins/catalog.json（随 APK 内置，无需联网）；
 * - 仅支持官方审核上架（official=true）的插件，非官方条目一律拒绝安装；
 * - 摘要校验框架：目录内每个条目自带 sha256（插件描述串 id|type|version|name|description
 *   的 SHA-256），安装前逐条复算比对；目录整体另有 checksum 二次汇总校验。
 *   当前示例插件不附带二进制载荷，故校验对象即插件描述本身，
 *   待插件载荷上架后，同一框架可直接扩展为「对插件包二进制做 SHA-256 校验」。
 * - 安装/卸载/启用/禁用状态持久化到 Room（PluginDao）。
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginDao: PluginDao,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 插件市场条目（目录元数据 + 本地安装状态） */
    data class PluginItem(
        val id: String,
        val name: String,
        val description: String,
        val type: String,
        val version: String,
        val official: Boolean,
        val checksumValid: Boolean,
        val installed: Boolean,
        val enabled: Boolean,
    )

    /** 目录加载结果 */
    data class CatalogStatus(
        val ok: Boolean,
        val message: String,
        val entries: List<PluginEntryDto> = emptyList(),
    )

    /** 观察插件列表（目录元数据 + 安装状态），数据库变化实时驱动 UI */
    fun observeCatalog(): Flow<List<PluginItem>> = pluginDao.observeAll()
        .map { rows -> rows.map(::toItem) }
        .flowOn(io)

    fun observeType(type: String): Flow<List<PluginItem>> =
        pluginDao.observeAll().map { rows -> rows.filter { it.type == type }.map(::toItem) }
            .flowOn(io)

    suspend fun get(id: String): PluginEntity? = withContext(io) { pluginDao.get(id) }

    /**
     * 读取内置目录并做完整性校验（目录整体 checksum + 每条 sha256）。
     * 校验失败时返回 ok=false，界面展示错误态（拒绝加载不可信目录）。
     */
    suspend fun loadCatalog(): CatalogStatus = withContext(io) {
        val raw = runCatching {
            context.assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { return@withContext CatalogStatus(false, "插件目录读取失败：${it.message}") }

        val dto = runCatching {
            moshi.adapter(PluginCatalogDto::class.java).fromJson(raw)
        }.getOrElse { return@withContext CatalogStatus(false, "插件目录解析失败：${it.message}") }
            ?: return@withContext CatalogStatus(false, "插件目录为空")

        val entries = dto.plugins
        if (entries.isEmpty()) return@withContext CatalogStatus(false, "插件目录没有条目")

        val catalogOk = catalogChecksum(entries).equals(dto.checksum.trim(), ignoreCase = true)
        val invalid = entries.count { !verifyEntry(it) }
        if (!catalogOk || invalid > 0) {
            return@withContext CatalogStatus(
                ok = false,
                message = "插件目录校验未通过（目录摘要${if (catalogOk) "正常" else "不匹配"}，" +
                    "$invalid 条插件摘要异常），已拒绝加载",
                entries = entries,
            )
        }
        CatalogStatus(true, "插件目录校验通过，共 ${entries.size} 个官方插件", entries)
    }

    /** 同步目录元数据到本地数据库（保留已安装/启用状态），返回目录加载结果 */
    suspend fun syncCatalog(): CatalogStatus = withContext(io) {
        val status = loadCatalog()
        if (status.ok) {
            val now = System.currentTimeMillis()
            val rows = status.entries.map { entry ->
                val existing = pluginDao.get(entry.id)
                PluginEntity(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    type = entry.type,
                    version = entry.version,
                    official = entry.official,
                    checksum = entry.sha256,
                    installed = existing?.installed ?: false,
                    enabled = existing?.enabled ?: false,
                    installedAt = existing?.installedAt ?: 0L,
                )
            }
            pluginDao.upsertAll(rows)
        }
        status
    }

    /**
     * 安装插件：官方标记校验 + 描述串 SHA-256 摘要校验 → 写入安装状态。
     * @throws PluginException 非官方插件 / 摘要校验未通过 / 目录中不存在
     */
    suspend fun install(id: String, onProgress: (ImportProgress) -> Unit = {}): PluginEntity =
        withContext(io) {
            onProgress(ImportProgress(ImportProgress.Stage.PREPARING, message = "正在准备安装插件"))
            val row = pluginDao.get(id)
                ?: throw PluginException("插件不存在：$id")
            if (!row.official) throw PluginException("仅支持官方审核上架的插件")
            onProgress(ImportProgress(ImportProgress.Stage.PARSING, message = "正在校验插件摘要"))
            if (!verifyRow(row)) throw PluginException("插件摘要校验未通过，已拒绝安装")
            onProgress(ImportProgress(ImportProgress.Stage.SAVING, message = "正在写入插件状态"))
            pluginDao.setInstalled(id, installed = true, enabled = true, at = System.currentTimeMillis())
            val installed = pluginDao.get(id) ?: row
            onProgress(ImportProgress(ImportProgress.Stage.DONE, message = "插件安装完成"))
            installed
        }

    /** 卸载插件（保留目录元数据，仅清除安装/启用状态） */
    suspend fun uninstall(id: String) = withContext(io) {
        pluginDao.setInstalled(id, installed = false, enabled = false, at = 0L)
    }

    /** 启用/禁用已安装插件 */
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(io) {
        val row = pluginDao.get(id) ?: return@withContext
        if (!row.installed) throw PluginException("插件尚未安装")
        pluginDao.setEnabled(id, enabled)
    }

    /** 复核已安装插件的摘要（安装后可再次校验，防止目录被篡改） */
    suspend fun verifyInstalled(id: String): Boolean = withContext(io) {
        val row = pluginDao.get(id) ?: return@withContext false
        row.installed && verifyRow(row)
    }

    private fun toItem(row: PluginEntity) = PluginItem(
        id = row.id,
        name = row.name,
        description = row.description,
        type = row.type,
        version = row.version,
        official = row.official,
        checksumValid = row.checksum != null &&
            row.checksum.equals(entryChecksum(row.id, row.type, row.version, row.name, row.description), ignoreCase = true),
        installed = row.installed,
        enabled = row.enabled,
    )

    private fun verifyEntry(entry: PluginEntryDto): Boolean =
        entry.official && entry.sha256.equals(
            entryChecksum(entry.id, entry.type, entry.version, entry.name, entry.description),
            ignoreCase = true,
        )

    private fun verifyRow(row: PluginEntity): Boolean =
        row.checksum?.equals(
            entryChecksum(row.id, row.type, row.version, row.name, row.description),
            ignoreCase = true,
        ) == true

    private fun catalogChecksum(entries: List<PluginEntryDto>): String = sha256Hex(
        entries.joinToString(separator = "") {
            entryChecksum(it.id, it.type, it.version, it.name, it.description) + "\n"
        }
    )

    private fun entryChecksum(
        id: String,
        type: String,
        version: String,
        name: String,
        description: String,
    ): String = sha256Hex("$id|$type|$version|$name|$description")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { builder.append(String.format("%02x", it)) }
        return builder.toString()
    }

    companion object {
        const val CATALOG_ASSET = "plugins/catalog.json"
    }
}
