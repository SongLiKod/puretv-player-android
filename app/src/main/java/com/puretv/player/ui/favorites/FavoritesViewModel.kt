package com.puretv.player.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.FavoriteEntity
import com.puretv.player.data.local.entity.VodItemEntity
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.repository.FavoritesRepository
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.data.vod.VodRepository
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlayerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 我的收藏 Tab（需求 5.2.4：我的收藏模块） */
object FavoriteTabs {
    const val CHANNEL = TargetType.CHANNEL
    const val VOD = TargetType.VOD
    const val LOCAL = TargetType.LOCAL
    val ALL = listOf(CHANNEL to "直播频道", VOD to "影视", LOCAL to "本地视频")
}

/**
 * 我的收藏 VM（需求 5.2.4、5.3.3：收藏置顶排序、取消收藏、直接播放）
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val historyRepository: HistoryRepository,
    private val vodRepository: VodRepository,
    private val playbackEngine: PlaybackEngine,
) : ViewModel() {

    private val currentType = MutableStateFlow(FavoriteTabs.CHANNEL)
    val currentTab: StateFlow<String> = currentType

    /** 播放启动事件（UI 收到后跳转播放页） */
    private val _openPlayback = MutableStateFlow(false)
    val openPlayback: StateFlow<Boolean> = _openPlayback
    fun consumeOpenPlayback() { _openPlayback.value = false }

    /** 当前 Tab 的收藏列表（数据库 Flow 驱动，删除/置顶即时生效） */
    val items: StateFlow<List<FavoriteEntity>> = currentType
        .flatMapLatest { type -> favoritesRepository.byType(type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun switchTab(type: String) {
        currentType.value = type
    }

    /** 置顶排序上移/下移（收藏置顶，需求 5.3.3） */
    fun move(item: FavoriteEntity, delta: Int) {
        viewModelScope.launch {
            val list = items.value
            val index = list.indexOfFirst {
                it.targetType == item.targetType && it.targetId == item.targetId
            }
            val target = index + delta
            if (index < 0 || target !in list.indices) return@launch
            // 顺序重排：当前列表顺序写回 rank（越小越靠前）
            val reordered = list.toMutableList().apply {
                removeAt(index).also { add(target, it) }
            }
            reordered.forEachIndexed { i, fav ->
                favoritesRepository.move(fav.targetType, fav.targetId, i)
            }
        }
    }

    fun remove(item: FavoriteEntity) {
        viewModelScope.launch { favoritesRepository.remove(item.targetType, item.targetId) }
    }

    fun clearAll() {
        viewModelScope.launch { favoritesRepository.clearAll() }
    }

    /** OK 播放：按收藏类型还原对应请求（直播/点播/本地，本地与点播带断点续播位置） */
    fun play(item: FavoriteEntity) {
        viewModelScope.launch {
            val url = item.url ?: return@launch
            when (item.targetType) {
                TargetType.CHANNEL -> {
                    val channel = ChannelEntity(
                        sourceId = item.sourceId ?: "favorites",
                        id = item.targetId,
                        name = item.title,
                        logo = item.imageUrl,
                        url = url,
                    )
                    playbackEngine.play(PlayerRequests.live(channel))
                }
                TargetType.LOCAL -> {
                    val history = historyRepository.get(TargetType.LOCAL, item.targetId)
                    playbackEngine.play(
                        PlayerRequests.local(
                            mediaId = item.targetId,
                            uri = url,
                            displayName = item.title,
                            startPositionMs = history?.positionMs ?: 0L,
                        )
                    )
                }
                else -> {
                    // 真实影片 id（剧集记录可能带 |第N集 后缀）；读库构建播放页「选集」
                    val seriesId = item.targetId.substringBefore('|')
                    val episodes = runCatching {
                        vodRepository.episodesOnce(item.sourceId, seriesId, hintUrl = url)
                    }.getOrDefault(emptyList())
                    val itemEntity = VodItemEntity(
                        sourceId = item.sourceId ?: "favorites",
                        id = seriesId,
                        title = item.title,
                        posterUrl = item.imageUrl,
                    )
                    val history = historyRepository.get(TargetType.VOD, item.targetId)
                    playbackEngine.play(
                        PlayerRequests.vod(
                            item = itemEntity,
                            playUrl = url,
                            startPositionMs = history?.positionMs ?: 0L,
                            episodes = PlayerRequests.episodeOptions(seriesId, episodes) { label ->
                                historyRepository.get(TargetType.EPISODE, "$seriesId|$label")
                                    ?.takeIf { !it.isFinished }
                                    ?.positionMs ?: 0L
                            },
                        )
                    )
                }
            }
            _openPlayback.value = true
        }
    }
}
