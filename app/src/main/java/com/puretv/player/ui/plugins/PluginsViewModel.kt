package com.puretv.player.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.plugin.PluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单个插件的安装进度 */
data class PluginInstallUi(
    val id: String,
    val message: String,
    val percent: Int,
)

/**
 * 插件市场 ViewModel（需求 5.7.1 轻量插件市场 / 5.7.2 官方审核上架）。
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginManager: PluginManager,
) : ViewModel() {

    /** 目录 + 本地安装状态（数据库变化实时驱动 UI） */
    val catalog: StateFlow<List<PluginManager.PluginItem>> = pluginManager.observeCatalog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow<PluginManager.CatalogStatus?>(null)
    val status: StateFlow<PluginManager.CatalogStatus?> = _status.asStateFlow()

    private val _installing = MutableStateFlow<PluginInstallUi?>(null)
    val installing: StateFlow<PluginInstallUi?> = _installing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        refresh()
    }

    /** 重新加载并校验官方目录（checksum + 每条 sha256） */
    fun refresh() {
        viewModelScope.launch {
            _status.value = pluginManager.syncCatalog()
        }
    }

    fun install(id: String) {
        if (_installing.value != null) return
        viewModelScope.launch {
            try {
                val entity = pluginManager.install(id) { p ->
                    _installing.value = PluginInstallUi(
                        id = id,
                        message = p.message ?: "正在安装…",
                        percent = p.percent,
                    )
                }
                _notice.value = "已安装并启用：${entity.name}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "安装失败"
            } finally {
                _installing.value = null
            }
        }
    }

    fun uninstall(id: String, name: String) {
        viewModelScope.launch {
            try {
                pluginManager.uninstall(id)
                _notice.value = "已卸载：$name"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "卸载失败"
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                pluginManager.setEnabled(id, enabled)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "操作失败"
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
