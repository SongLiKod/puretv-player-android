package com.puretv.player.data.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前选中的资源站（跨页面共享状态）：
 * - 首页「资源站」切换浏览该站全部影片；
 * - 影视页「本站搜索」限定在该站范围内检索。
 * 未选中时由各消费方兜底为第一个可用点播源。
 */
@Singleton
class SelectedSourceStore @Inject constructor() {

    private val _selectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = _selectedSourceId

    fun select(sourceId: String?) {
        _selectedSourceId.value = sourceId
    }
}
