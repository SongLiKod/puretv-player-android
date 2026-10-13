package com.puretv.player.data.source

import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前选中的资源站（跨页面共享状态）：
 * - 首页「资源站」切换浏览该站全部影片；
 * - 影视页「本站搜索」限定在该站范围内检索。
 * 未选中时由各消费方兜底为第一个可用点播源。
 * 追加需求：选择结果持久化到 DataStore，退出软件重进不回默认。
 */
@Singleton
class SelectedSourceStore @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _selectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = _selectedSourceId

    init {
        scope.launch {
            val saved = settingsRepository.selectedVodSourceId.firstOrNull()
            // 仅在用户尚未做出新选择时写入（避免恢复慢于交互覆盖用户选择）
            if (saved != null) _selectedSourceId.compareAndSet(null, saved)
        }
    }

    fun select(sourceId: String?) {
        _selectedSourceId.value = sourceId
        // 异步持久化（不阻塞 UI）
        scope.launch { settingsRepository.setSelectedVodSourceId(sourceId) }
    }
}
