package com.puretv.player.player

import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.VodEpisodeEntity
import com.puretv.player.data.local.entity.VodItemEntity

/**
 * 各播放入口到 [PlaybackRequest] 的统一封装（直播/点播/本地共用，保证请求参数一致）。
 * 注意：本文件为共享契约，勿在功能模块中修改。
 */
object PlayerRequests {

    /** 直播频道播放请求：携带自定义请求头、备用源、清晰度记忆 */
    fun live(channel: ChannelEntity, rememberedQuality: String? = null): PlaybackRequest =
        PlaybackRequest(
            url = channel.url,
            title = channel.name,
            subtitle = channel.groupName,
            artworkUrl = channel.logo,
            headers = channel.headers,
            mode = PlaybackMode.LIVE,
            historyTargetId = "${channel.sourceId}|${channel.id}",
            historyType = "channel",
            backupUrls = channel.backupUrls,
            channelSettingKey = "${channel.sourceId}|${channel.id}",
            rememberedQuality = rememberedQuality ?: channel.quality,
            isLive = true,
        )

    /** 影视点播播放请求：携带断点续播位置与集数标记（episodes 供播放页「选集」抽屉换集） */
    fun vod(
        item: VodItemEntity,
        playUrl: String,
        startPositionMs: Long = 0L,
        episodeLabel: String? = null,
        subtitle: String? = null,
        headers: Map<String, String> = emptyMap(),
        episodes: List<PlaybackEpisode> = emptyList(),
    ): PlaybackRequest = PlaybackRequest(
        url = playUrl,
        title = item.title,
        subtitle = subtitle ?: episodeLabel ?: item.category,
        artworkUrl = item.posterUrl,
        headers = headers,
        mode = PlaybackMode.VOD,
        startPositionMs = startPositionMs,
        historyTargetId = episodeLabel?.let { "${item.id}|$it" } ?: item.id,
        historyType = if (episodeLabel != null) "episode" else "vod",
        episodeLabel = episodeLabel,
        sourceId = item.sourceId,
        episodes = episodes,
    )

    /**
     * 分集实体 → 播放页「选集」列表（详情页/历史/收藏各播放入口共用）。
     * @param itemId 影片 id（历史键 = itemId|第N集）
     * @param resumePosition 按集标记取断点续播位置（默认从头）
     */
    suspend fun episodeOptions(
        itemId: String,
        episodes: List<VodEpisodeEntity>,
        resumePosition: suspend (label: String) -> Long = { 0L },
    ): List<PlaybackEpisode> {
        val all = episodes.filter { it.playUrl.isNotBlank() }
        if (all.isEmpty()) return emptyList()
        val multiSeason = all.map { it.season }.distinct().size > 1
        return all.map { ep ->
            val label = "第${ep.episode}集"
            PlaybackEpisode(
                label = label,
                url = ep.playUrl,
                season = if (multiSeason) ep.season else 1,
                historyTargetId = "$itemId|$label",
                subtitle = listOfNotNull(label, ep.title).joinToString(" · "),
                startPositionMs = resumePosition(label),
            )
        }
    }

    /** 本地视频播放请求 */
    fun local(mediaId: String, uri: String, displayName: String, startPositionMs: Long = 0L): PlaybackRequest =
        PlaybackRequest(
            url = uri,
            title = displayName,
            mode = PlaybackMode.LOCAL,
            startPositionMs = startPositionMs,
            historyTargetId = mediaId,
            historyType = "local",
        )

    /** 回看（时移）播放请求：可拖动进度 */
    fun catchup(
        channel: ChannelEntity,
        programTitle: String,
        catchupUrl: String,
        startPositionMs: Long = 0L,
    ): PlaybackRequest = PlaybackRequest(
        url = catchupUrl,
        title = "回看 · ${channel.name}",
        subtitle = programTitle,
        artworkUrl = channel.logo,
        headers = channel.headers,
        mode = PlaybackMode.VOD,
        startPositionMs = startPositionMs,
        historyTargetId = "${channel.sourceId}|${channel.id}|catchup",
        historyType = "channel",
        episodeLabel = programTitle,
        isLive = false,
    )
}
