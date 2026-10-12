package com.puretv.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.SourceCheckReport
import com.puretv.player.data.repository.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 源检测工具 VM（需求 5.6.4：常见问题修复、源检测工具）。
 * 检测任意 M3U/Xtream 地址或已保存源的可达性、频道数、EPG、抽样播放健康度。
 */
@HiltViewModel
class SourceCheckViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    /** 已保存的源（供选择检测） */
    val sources: StateFlow<List<SourceEntity>> = sourceRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _report = MutableStateFlow<SourceCheckReport?>(null)
    val report: StateFlow<SourceCheckReport?> = _report.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 检测已保存源 */
    fun checkSource(sourceId: String) {
        launchCheck { sourceRepository.checkSource(sourceId) }
    }

    /** 检测临时输入的地址（M3U 直链；Xtream 可附用户名/密码） */
    fun checkUrl(url: String, username: String? = null, password: String? = null) {
        launchCheck { sourceRepository.checkUrl(url, username, password) }
    }

    private fun launchCheck(block: suspend () -> SourceCheckReport) {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _error.value = null
            _report.value = null
            try {
                _report.value = block()
            } catch (t: Throwable) {
                _error.value = t.message ?: "检测失败，请检查地址与网络"
            } finally {
                _checking.value = false
            }
        }
    }

    fun dismissReport() {
        _report.value = null
    }
}
