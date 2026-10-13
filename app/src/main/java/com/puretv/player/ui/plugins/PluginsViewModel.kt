package com.puretv.player.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.plugin.PluginManager
import com.puretv.player.data.plugin.PluginType
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.ui.settings.SkinPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

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
    private val settingsRepository: SettingsRepository,
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
                val skinApplied = applySkinIfMatch(id)
                _notice.value = if (skinApplied) {
                    "已安装并应用皮肤：${entity.name}"
                } else {
                    "已安装并启用：${entity.name}"
                }
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
                // 皮肤插件卸载：若正使用该皮肤，自动恢复默认主题（设置列表同步移除该选项）
                val current = settingsRepository.settings.first()
                if (current.skinId == id) settingsRepository.setSkin("default", null)
                // 字号由皮肤托管（长辈模式）时，卸载还原标准字号
                val presetScale = SkinPreset.ALL.firstOrNull { it.id == id }?.fontScale
                if (presetScale != null && abs(current.fontScale - presetScale) < 0.01f) {
                    settingsRepository.setFontScale(1.0f)
                }
                // 播放增强插件卸载：跳过片头片尾配置随插件还原关闭
                if (id == "playback_skip") {
                    settingsRepository.setSkipIntroSec(0)
                    settingsRepository.setSkipOutroSec(0)
                }
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
                if (enabled) applySkinIfMatch(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "操作失败"
            }
        }
    }

    /**
     * 皮肤插件安装/启用即真实换肤：条目 id 与 [SkinPreset] 预设 id 对齐，
     * 命中则写入设置（主色/高亮色全局生效），返回是否应用。
     * 带字号的皮肤（长辈模式）同步联动字体缩放。
     */
    private suspend fun applySkinIfMatch(id: String): Boolean {
        val item = runCatching { pluginManager.observeCatalog().first() }
            .getOrNull()?.firstOrNull { it.id == id } ?: return false
        if (item.type != PluginType.SKIN) return false
        val preset = SkinPreset.ALL.firstOrNull { it.id == id } ?: return false
        settingsRepository.setSkin(preset.id, preset.toJson())
        preset.fontScale?.let { settingsRepository.setFontScale(it) }
        return true
    }

    fun clearNotice() {
        _notice.value = null
    }
}
