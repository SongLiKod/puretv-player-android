package com.puretv.player.player

import kotlinx.coroutines.flow.StateFlow

/** 播放场景 */
enum class PlaybackMode {
    LIVE,   // 直播 IPTV（低延迟、换台提速）
    VOD,    // 影视点播（断点续播）
    LOCAL,  // 本地影音
}

/** 画面比例（需求 5.5.4：画面比例调节） */
enum class AspectRatioMode {
    FIT,       // 适应（默认）
    FILL,      // 填满裁剪
    ZOOM,      // 放大
    RATIO_16_9,// 16:9
    RATIO_4_3, // 4:3
    RATIO_ORIGINAL, // 原始比例
}

/** 播放任务（所有播放入口统一通过该模型投递给播放内核） */
data class PlaybackRequest(
    /** 播放地址 */
    val url: String,
    /** 标题（频道名/影片名） */
    val title: String,
    /** 副标题（分组名/季集信息） */
    val subtitle: String? = null,
    /** 封面/频道台标 */
    val artworkUrl: String? = null,
    /** 自定义请求头（部分源需要 UA/Referer） */
    val headers: Map<String, String> = emptyMap(),
    val mode: PlaybackMode = PlaybackMode.VOD,
    /** 起播位置（断点续播） */
    val startPositionMs: Long = 0L,
    /** 断点续播记录键 targetId，null 表示不记录 */
    val historyTargetId: String? = null,
    /** 记录类型（channel/vod/episode/local） */
    val historyType: String? = null,
    /** 记录附加标签（如 "第3集"，用于未看完集数标记） */
    val episodeLabel: String? = null,
    /** 所属资源站 id（点播记录用；播放页「选集」读取依赖它定位剧集表） */
    val sourceId: String? = null,
    /** 备用源地址（卡顿/弱网自动切换备用源） */
    val backupUrls: List<String> = emptyList(),
    /** 频道清晰度记忆键（sourceId|channelId） */
    val channelSettingKey: String? = null,
    /** 记住的清晰度标签 */
    val rememberedQuality: String? = null,
    /** 是否直播流（实时流，禁用 seek 录制类行为） */
    val isLive: Boolean = mode == PlaybackMode.LIVE,
    /**
     * 选集列表（剧集类点播；播放页「选集」抽屉内直接换集，免回详情页）。
     * 直播/电影/本地为空。
     */
    val episodes: List<PlaybackEpisode> = emptyList(),
)

/** 播放页选集项（[PlaybackRequest.episodes] 元素） */
data class PlaybackEpisode(
    /** 集数标记（「第N集」，与播放历史 targetId 约定一致） */
    val label: String,
    /** 该集播放地址 */
    val url: String,
    /** 季号（多季剧展示用，单季为 1） */
    val season: Int = 1,
    /** 该集历史记录键（itemId|label），null 时沿用当前请求 */
    val historyTargetId: String? = null,
    /** 展示副标题（第N集 · 剧名） */
    val subtitle: String? = null,
    /** 该集断点续播起播位置 */
    val startPositionMs: Long = 0L,
    /**
     * 该集备用线路地址（同片其他播放线路的同集地址）。
     * 主线路播放失败时内核自动切换，播放页「备用源」按钮也随之可用（用户反馈：备用线路点不了）。
     */
    val backups: List<String> = emptyList(),
)

/** 音轨/字幕轨选项（需求 5.5.4：字幕加载、音轨切换） */
data class TrackOption(
    val index: Int,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

/** 播放器状态（UI 与换台逻辑统一读取） */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 播放自然结束（STATE_ENDED；用于剧集自动连播下一集，新任务/重播时复位） */
    val isEnded: Boolean = false,
    val errorMessage: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 已缓冲时长 */
    val bufferedMs: Long = 0L,
    /** 播放倍速（需求 5.5.4：倍速播放） */
    val speed: Float = 1.0f,
    val muted: Boolean = false,
    /** 画面比例 */
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    /** 音轨列表 */
    val audioTracks: List<TrackOption> = emptyList(),
    val selectedAudioTrack: Int = 0,
    /** 字幕轨列表（含外挂字幕） */
    val subtitleTracks: List<TrackOption> = emptyList(),
    val selectedSubtitleTrack: Int = 0,
    /** 当前使用的备用源下标（0 = 主源） */
    val backupIndex: Int = 0,
    /** 连续卡顿次数（触发自动换源判定） */
    val stallCount: Int = 0,
    /** 实时网速（bytes/s，用于清晰度智能切换与弱网提示） */
    val networkSpeedBps: Long = 0L,
    /** 视频宽高 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 清晰度标签 */
    val qualityLabel: String? = null,
    /** 是否处于弱网提示状态 */
    val weakNetwork: Boolean = false,
)

/**
 * 播放内核对外统一接口（需求 5.5 播放内核性能需求）。
 * 实现基于 ExoPlayer（Media3）基础播放器 + FFmpeg JNI 软解兜底扩展，
 * 由 [com.puretv.player.player.PlaybackEngineImpl] 提供（Hilt 单例）。
 */
interface PlaybackEngine {

    /** 当前播放任务 */
    val currentRequest: StateFlow<PlaybackRequest?>

    /** 播放状态 */
    val state: StateFlow<PlaybackState>

    /** 跨集预缓冲进度：播放地址 → 已缓冲百分比（100 = 已完整写入本地缓存；选集/分集列表徽标用） */
    val preloadProgress: StateFlow<Map<String, Int>>

    /** 开始/切换播放（换台直接再次调用即可） */
    fun play(request: PlaybackRequest)

    fun playPause()

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    /** 倍速播放 */
    fun setSpeed(speed: Float)

    /** 清空播放媒体缓存（设置 → 存储与缓存） */
    suspend fun clearMediaCache()

    /** 播放媒体缓存当前占用字节数（设置展示用） */
    suspend fun cacheSizeBytes(): Long

    fun mute(muted: Boolean)

    /** 画面比例调节 */
    fun setAspectRatio(mode: AspectRatioMode)

    /** 手动切换备用源 */
    fun switchBackup(index: Int)

    fun selectAudioTrack(index: Int)

    fun selectSubtitleTrack(index: Int)

    /** 添加外挂字幕（网络/本地地址，支持 srt/vtt/ass） */
    fun addSubtitleTrack(url: String, label: String)

    /** 停止播放并释放界面占用 */
    fun stop()

    /** 释放内核（退出播放页/应用后台回收时调用） */
    fun release()
}
