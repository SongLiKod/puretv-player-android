package com.puretv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.ChannelSettingDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.data.local.entity.ReminderEntity
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.SourceType
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.repository.EpgRepository
import com.puretv.player.data.repository.FavoritesRepository
import com.puretv.player.data.repository.SourceRepository
import com.puretv.player.di.IoDispatcher
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlayerRequests
import com.puretv.player.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 分组栏特殊项 */
object LiveGroups {
    const val ALL = "全部"
    const val FAVORITES = "收藏"
}

/**
 * 直播专区 VM（需求 5.3 / 5.2）：
 * 源选择与导入、频道分组与收藏置顶、EPG、播放、预约、四分屏数据源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val channelDao: ChannelDao,
    private val channelSettingDao: ChannelSettingDao,
    private val epgRepository: EpgRepository,
    private val favoritesRepository: FavoritesRepository,
    private val reminderScheduler: ReminderScheduler,
    settingsRepository: SettingsRepository,
    private val playbackEngine: PlaybackEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** 直播源（仅 M3U / Xtream；CMS 聚合点播源不进入直播页） */
    val sources: StateFlow<List<SourceEntity>> = sourceRepository.sources
        .map { list -> list.filter { it.type != SourceType.CMS } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = selectedId

    /** 当前选中源（未选择时取第一个启用源） */
    val selectedSource: StateFlow<SourceEntity?> = combine(sources, selectedId) { list, id ->
        list.firstOrNull { it.id == id }
            ?: list.firstOrNull { it.enabled }
            ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 是否完全没有源（引导去导入） */
    val hasSource: StateFlow<Boolean> = sources.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 当前源频道列表（Room Flow 秒开） */
    val channels: StateFlow<List<ChannelEntity>> =
        combine(channelDao.observeAll(), selectedSource) { all, source ->
            if (source == null) emptyList() else all.filter { it.sourceId == source.id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 分组（含 全部/收藏 两个特殊项） */
    val groups: StateFlow<List<String>> = channels.map { list ->
        listOf(LiveGroups.ALL, LiveGroups.FAVORITES) +
            list.map { it.customGroup ?: it.groupName }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(LiveGroups.ALL))

    private val selectedGroup = MutableStateFlow(LiveGroups.ALL)
    val currentGroup: StateFlow<String> = selectedGroup

    /** 已收藏频道 key（"sourceId|id"）集合与排序值 */
    val favoriteKeys: StateFlow<Set<String>> =
        favoritesRepository.byType(TargetType.CHANNEL).map { list ->
            list.map { it.targetId }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val favorites: StateFlow<Map<String, Int>> =
        favoritesRepository.byType(TargetType.CHANNEL).map { list ->
            list.associate { it.targetId to it.favoriteRank }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 当前分组可见频道（收藏分组按 favoriteRank 置顶排序） */
    val visibleChannels: StateFlow<List<ChannelEntity>> =
        combine(channels, selectedGroup, favorites) { list, group, favMap ->
            when (group) {
                LiveGroups.ALL -> list
                LiveGroups.FAVORITES -> list.filter { "${it.sourceId}|${it.id}" in favMap }
                    .sortedBy { favMap["${it.sourceId}|${it.id}"] ?: Int.MAX_VALUE }
                else -> list.filter { (it.customGroup ?: it.groupName) == group }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 解析/EPG 同步进度（null 表示无进行中任务） */
    val progress = MutableStateFlow<ImportProgress?>(null)

    /** 错误信息（失败提示 + 重试） */
    val error = MutableStateFlow<String?>(null)

    private val activeJob = MutableStateFlow<kotlinx.coroutines.Job?>(null)

    /** 取消进行中的导入/刷新/EPG 同步 */
    fun cancelTask() {
        activeJob.value?.cancel()
        activeJob.value = null
        progress.value = null
    }

    fun selectSource(id: String) {
        if (selectedId.value == id) return
        selectedId.value = id
        selectedGroup.value = LiveGroups.ALL
        maybeSyncEpg(id)
    }

    fun selectGroup(group: String) {
        selectedGroup.value = group
    }

    /** 首次进入：选中默认源后自动同步一次 EPG（手动同步走 syncEpgNow） */
    private val epgSyncedSourceIds = mutableSetOf<String>()

    fun maybeSyncEpg(sourceId: String) {
        if (!epgSyncedSourceIds.add(sourceId)) return
        val source = sources.value.firstOrNull { it.id == sourceId } ?: return
        if (source.epgUrl.isNullOrBlank()) return
        syncEpgNow(source)
    }

    /** 手动同步 EPG 节目单 */
    fun syncEpgNow(source: SourceEntity) {
        launchTask { epgRepository.syncFrom(source) { progress.value = it } }
    }

    /** 刷新（重新拉取）当前源 */
    fun refreshSource(sourceId: String) {
        launchTask {
            runCatching { sourceRepository.refresh(sourceId) { progress.value = it } }
                .onFailure { error.value = it.message ?: "刷新失败" }
        }
    }

    /** 删除源 */
    fun removeSource(sourceId: String) {
        launchTask {
            sourceRepository.removeSource(sourceId)
            if (selectedId.value == sourceId) selectedId.value = null
        }
    }

    /** 导入 M3U 源（网络地址） */
    fun importM3u(name: String, url: String, epgUrl: String?) {
        launchTask {
            runCatching {
                sourceRepository.importM3u(name, url, epgUrl?.ifBlank { null }) { progress.value = it }
            }.onSuccess { source ->
                selectedId.value = source.id
                if (!source.epgUrl.isNullOrBlank()) epgRepository.syncFrom(source) { progress.value = it }
            }.onFailure { error.value = it.message ?: "导入失败" }
        }
    }

    /** 导入 Xtream 源 */
    fun importXtream(name: String, baseUrl: String, username: String, password: String) {
        launchTask {
            runCatching {
                sourceRepository.importXtream(name, baseUrl, username, password) { progress.value = it }
            }.onSuccess { source ->
                selectedId.value = source.id
                if (!source.epgUrl.isNullOrBlank()) epgRepository.syncFrom(source) { progress.value = it }
            }.onFailure { error.value = it.message ?: "导入失败" }
        }
    }

    private fun launchTask(block: suspend () -> Unit) {
        activeJob.value?.cancel()
        error.value = null
        activeJob.value = viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "操作失败"
            } finally {
                progress.value = null
                activeJob.value = null
            }
        }
    }

    fun dismissError() {
        error.value = null
    }

    /* ------------------------------- 播放 ------------------------------- */

    /** 清晰度记忆（需求 5.3.3）：设置开启时读取频道记住的清晰度 */
    suspend fun rememberedQuality(channel: ChannelEntity): String? {
        return channelSettingDao.get("${channel.sourceId}|${channel.id}")?.quality
    }

    /** OK 播放频道：换台直接 engine.play 新请求（换台提速） */
    fun playChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            val remembered = rememberedQuality(channel)
            playbackEngine.play(PlayerRequests.live(channel, remembered))
        }
    }

    /** 播放已构造好的请求（四分屏/回看共用） */
    fun playRequest(request: com.puretv.player.player.PlaybackRequest) {
        playbackEngine.play(request)
    }

    /* ------------------------------- EPG ------------------------------- */

    /** 当前/下个节目 */
    suspend fun nowNext(epgId: String): Pair<EpgProgramEntity?, EpgProgramEntity?> =
        epgRepository.nowNext(epgId)

    /** 今日节目单 */
    fun todayPrograms(epgId: String): Flow<List<EpgProgramEntity>> {
        val start = TimeUtils.startOfToday()
        return epgRepository.observePrograms(epgId, start, start + 24 * 3600_000L)
    }

    /** 构造回看地址（null = 该频道不支持回看） */
    suspend fun buildCatchupUrl(channel: ChannelEntity, program: EpgProgramEntity): String? =
        epgRepository.buildCatchupUrl(channel, program)

    /** 回看播放：不支持回看时回调提示 */
    fun playCatchup(channel: ChannelEntity, program: EpgProgramEntity, onUnsupported: (String) -> Unit) {
        viewModelScope.launch {
            val url = epgRepository.buildCatchupUrl(channel, program)
            if (url == null) {
                onUnsupported("该频道不支持回看")
            } else {
                playbackEngine.play(PlayerRequests.catchup(channel, program.title, url))
            }
        }
    }

    /* ------------------------------- 收藏 ------------------------------- */

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            favoritesRepository.toggle(
                type = TargetType.CHANNEL,
                targetId = "${channel.sourceId}|${channel.id}",
                title = channel.name,
                sourceId = channel.sourceId,
                imageUrl = channel.logo,
                url = channel.url,
            )
        }
    }

    /* ------------------------------- 预约 ------------------------------- */

    suspend fun reminderOf(programId: String): ReminderEntity? =
        reminderScheduler.getByProgramId(programId)

    /** 预约播放：节目开始时通知提醒 */
    fun scheduleReminder(channel: ChannelEntity, program: EpgProgramEntity) {
        viewModelScope.launch {
            reminderScheduler.schedule(
                ReminderEntity(
                    programId = program.id,
                    epgId = program.epgId,
                    channelSourceId = channel.sourceId,
                    channelId = channel.id,
                    channelName = channel.name,
                    title = program.title,
                    startAt = program.startAt,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun cancelReminder(programId: String) {
        viewModelScope.launch { reminderScheduler.cancelByProgramId(programId) }
    }

    /** 搜索频道（名称/分组模糊匹配） */
    fun search(query: String): Flow<List<ChannelEntity>> =
        if (query.isBlank()) flowOf(emptyList()) else channelDao.search(query.trim())
}
