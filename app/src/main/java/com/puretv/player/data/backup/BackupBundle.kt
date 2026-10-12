package com.puretv.player.data.backup

import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.prefs.AppSettings
import com.squareup.moshi.JsonClass

/**
 * 全量备份数据包（需求 5.7.4：源导入、导出、备份、一键恢复）。
 * 备份范围：直播源配置 + 全量设置（不含频道列表，恢复后可一键刷新重建）。
 */
@JsonClass(generateAdapter = true)
data class BackupBundleDto(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    /** 直播源配置（含 M3U / Xtream 账号密码，备份文件请自行妥善保管） */
    val sources: List<SourceEntity> = emptyList(),
    /** 全量设置快照 */
    val settings: AppSettings? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** 备份/恢复/导出结果摘要 */
data class BackupSummary(
    val sources: Int = 0,
    val settingsApplied: Boolean = false,
    val message: String = "",
)
