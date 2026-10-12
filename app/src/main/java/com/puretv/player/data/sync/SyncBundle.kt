package com.puretv.player.data.sync

import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.prefs.AppSettings
import com.squareup.moshi.JsonClass

/**
 * 云端同步数据包（需求 5.7.3：配置、收藏、播放记录云端同步，多设备互通）。
 *
 * 文档未规定具体云端后端协议，故同步链路实现为「整包 JSON 打包 / 解包」：
 * 用户把数据包导出到自己选定的目录（本地目录或网盘同步目录），
 * 其他设备从同一目录导入即可完成互通。
 */
@JsonClass(generateAdapter = true)
data class SyncBundleDto(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    /** 直播源配置 */
    val sources: List<SourceEntity> = emptyList(),
    /** 全量设置（主题/双模式/播放参数/TMDB Key 等） */
    val settings: AppSettings? = null,
    /** 我的收藏 */
    val favorites: List<FavoriteEntity> = emptyList(),
    /** 播放记录（断点续播位置） */
    val history: List<WatchHistoryEntity> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** 同步结果摘要（界面反馈用） */
data class SyncSummary(
    val sources: Int = 0,
    val favorites: Int = 0,
    val history: Int = 0,
    val settingsApplied: Boolean = false,
    val at: Long = 0L,
) {
    val describe: String
        get() = "源 $sources 个 · 收藏 $favorites 项 · 记录 $history 条" +
            if (settingsApplied) " · 已同步设置" else ""
}
