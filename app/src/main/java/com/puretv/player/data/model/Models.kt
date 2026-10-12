package com.puretv.player.data.model

import android.net.Uri

/** 源导入/刷新进度（需求 5.3.2：解析过程显示进度条，界面不卡死、可操作） */
data class ImportProgress(
    val stage: Stage = Stage.PREPARING,
    /** 已解析条目数 */
    val processed: Int = 0,
    /** 预估总条目数（未知时为 0） */
    val total: Int = 0,
    val message: String? = null,
) {
    enum class Stage {
        PREPARING,   // 准备中
        DOWNLOADING, // 下载源文件
        PARSING,     // 解析频道
        SAVING,      // 写入数据库
        DONE,        // 完成
        FAILED,      // 失败
    }

    /** 0-100 进度百分比，未知总长时按已处理量给出平滑进度 */
    val percent: Int
        get() = when {
            stage == Stage.DONE -> 100
            total > 0 -> (processed * 100 / total).coerceIn(0, 99)
            else -> 0
        }
}

/** 源检测工具结果（需求 5.6.4：内置源检测工具） */
data class SourceCheckReport(
    val target: String,
    /** 是否可达 */
    val reachable: Boolean = false,
    /** 首包响应耗时（毫秒） */
    val latencyMs: Long = -1L,
    /** 频道数量（无法解析时为 0） */
    val channelCount: Int = 0,
    /** 分组数量 */
    val groupCount: Int = 0,
    /** 是否发现 EPG 地址 */
    val epgFound: Boolean = false,
    /** 是否检测到有效播放地址（抽样探测） */
    val streamHealthy: Boolean = false,
    /** 抽样探测失败的频道数 */
    val brokenChannels: Int = 0,
    val message: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
)

/** 直播/点播源类型 */
object SourceType {
    const val M3U = "m3u"
    const val XTREAM = "xtream"

    /** MacCMS 采集协议（苹果 CMS「api.php/provide/vod」JSON 接口，TVBox 资源站通用后端） */
    const val CMS = "cms"
}

/** 收藏目标类型 */
object TargetType {
    const val CHANNEL = "channel"
    const val VOD = "vod"
    const val EPISODE = "episode"
    const val LOCAL = "local"
    const val SERIES = "series"
}

/** 点播品类（需求 5.4.1：电影、电视剧、综艺、动漫全品类聚合分类） */
object VodKind {
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val VARIETY = "variety"
    const val ANIME = "anime"
}
