package com.puretv.player.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.local.entity.WatchHistoryEntity
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.data.vod.VodRepository
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlayerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记录 Tab */
object HistoryTabs {
    const val ALL = "all"
    const val CHANNEL = TargetType.CHANNEL
    const val VOD = TargetType.VOD
    const val EPISODE = TargetType.EPISODE
    const val LOCAL = TargetType.LOCAL
    val ALL_TABS = listOf(ALL to "全部", CHANNEL to "直播", VOD to "影视", LOCAL to "本地")
}

/**
 * 播放记录 VM（需求 5.2.4、5.4.3：播放记录模块、断点续播、未看完集数标记）
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val vodRepository: VodRepository,
    private val playbackEngine: PlaybackEngine,
) : ViewModel() {

    private val currentTab = MutableStateFlow(HistoryTabs.ALL)
    val currentTabFlow: StateFlow<String> = currentTab

    /** 播放启动事件（UI 收到后跳转播放页） */
    private val _openPlayback = MutableStateFlow(false)
    val openPlayback: StateFlow<Boolean> = _openPlayback
    fun consumeOpenPlayback() { _openPlayback.value = false }

    /** 记录列表（updatedAt 倒序由 DAO 保证；同一部剧仅保留最新一条记录） */
    val items: StateFlow<List<WatchHistoryEntity>> = currentTab
        .flatMapLatest { tab ->
            if (tab == HistoryTabs.ALL) historyRepository.history
            else historyRepository.byType(tab)
        }
        .map { list ->
            // 影视/剧集按 (sourceId, 剧id) 聚合，首条即最新一条（DAO 已按 updatedAt 倒序）；
            // 直播/本地记录逐条保留
            list.distinctBy { entry ->
                when (entry.targetType) {
                    TargetType.VOD, TargetType.EPISODE ->
                        "${entry.sourceId ?: ""}#${entry.targetId.substringBefore('|')}"
                    else -> entry.key
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun switchTab(tab: String) {
        currentTab.value = tab
    }

    /** 删除记录：影视/剧集按整部剧删除（与同剧聚合展示行为一致），直播/本地删单条 */
    fun remove(item: WatchHistoryEntity) {
        viewModelScope.launch {
            if (item.targetType == TargetType.VOD || item.targetType == TargetType.EPISODE) {
                val seriesId = item.targetId.substringBefore('|')
                historyRepository.history.first()
                    .filter {
                        (it.targetType == TargetType.VOD || it.targetType == TargetType.EPISODE) &&
                            (it.sourceId ?: "") == (item.sourceId ?: "") &&
                            it.targetId.substringBefore('|') == seriesId
                    }
                    .forEach { historyRepository.remove(it.key) }
            } else {
                historyRepository.remove(item.key)
            }
        }
    }

    fun clear() {
        viewModelScope.launch { historyRepository.clear() }
    }

    /** OK 续播：按记录类型还原请求，从上次进度起播（断点续播，需求 5.4.3） */
    fun resume(item: WatchHistoryEntity) {
        val url = item.url ?: return
        when (item.targetType) {
            TargetType.CHANNEL -> {
                val channel = ChannelEntity(
                    sourceId = item.sourceId ?: "history",
                    id = item.targetId,
                    name = item.title,
                    logo = item.imageUrl,
                    url = url,
                )
                playbackEngine.play(PlayerRequests.live(channel))
            }
            TargetType.LOCAL -> {
                playbackEngine.play(
                    PlayerRequests.local(
                        mediaId = item.targetId,
                        uri = url,
                        displayName = item.title,
                        // 未看完才续播，已看完从头播放
                        startPositionMs = if (item.isFinished) 0L else item.positionMs,
                    )
                )
            }
            else -> {
                // 真实影片 id（剧集记录 targetId = itemId|第N集）；读库构建播放页「选集」
                val seriesId = item.targetId.substringBefore('|')
                viewModelScope.launch {
                    val episodes = runCatching {
                        vodRepository.episodesOnce(item.sourceId, seriesId, hintUrl = url)
                    }.getOrDefault(emptyList())
                    val vodItem = VodItemEntity(
                        sourceId = item.sourceId ?: "history",
                        id = seriesId,
                        title = item.title,
                        posterUrl = item.imageUrl,
                    )
                    playbackEngine.play(
                        PlayerRequests.vod(
                            item = vodItem,
                            playUrl = url,
                            startPositionMs = if (item.isFinished) 0L else item.positionMs,
                            episodeLabel = item.episodeLabel,
                            subtitle = item.subtitle,
                            episodes = PlayerRequests.episodeOptions(seriesId, episodes) { label ->
                                historyRepository.get(TargetType.EPISODE, "$seriesId|$label")
                                    ?.takeIf { !it.isFinished }
                                    ?.positionMs ?: 0L
                            },
                        )
                    )
                    _openPlayback.value = true
                }
                return
            }
        }
        _openPlayback.value = true
    }
}
