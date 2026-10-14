package com.puretv.player.data.source

import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CompletableDeferred
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

    /** 恢复完成信号（即使读取失败也放行，避免消费方永久挂起） */
    private val restored = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val saved = settingsRepository.selectedVodSourceId.firstOrNull()
                // 仅在用户尚未做出新选择时写入（避免恢复慢于交互覆盖用户选择）
                if (saved != null) _selectedSourceId.compareAndSet(null, saved)
            } catch (t: Throwable) {
                // 读取失败视为无历史选择，兜底逻辑照常走
            } finally {
                restored.complete(Unit)
            }
        }
    }

    /**
     * 等持久化恢复完成后再返回当前选中线路（null = 无历史选择）。
     *
     * 启动期「未选中则默认第一个源」的逻辑**必须先调这里**：此前直接读 [.selectedSourceId.value]，
     * DataStore 恢复尚未落地时默认线路会抢占选中态并被写回持久化，把用户保存的选择覆盖成默认，
     * 导致退出软件重进永远回到默认（用户反馈）。
     */
    suspend fun awaitSelection(): String? {
        restored.await()
        return _selectedSourceId.value
    }

    fun select(sourceId: String?) {
        _selectedSourceId.value = sourceId
        // 异步持久化（不阻塞 UI）
        scope.launch { settingsRepository.setSelectedVodSourceId(sourceId) }
    }
}
