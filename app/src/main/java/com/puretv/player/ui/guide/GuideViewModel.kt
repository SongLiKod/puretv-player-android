package com.puretv.player.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.theme.ThemeMode
import com.puretv.player.data.builtin.BuiltinSourceInstaller
import com.puretv.player.data.local.dao.SourceDao
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 新手引导 ViewModel（需求 5.6.1：引导式配置，一键导入源、一键初始化，零基础可用）。
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val builtinSourceInstaller: BuiltinSourceInstaller,
    sourceDao: SourceDao,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 已有源数量：大于 0 时引导页直接给简短欢迎，不阻塞进首页（5.6.1） */
    val sourceCount: StateFlow<Int> = sourceDao.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _initProgress = MutableStateFlow<ImportProgress?>(null)
    val initProgress: StateFlow<ImportProgress?> = _initProgress.asStateFlow()

    private val _initDone = MutableStateFlow(false)
    val initDone: StateFlow<Boolean> = _initDone.asStateFlow()

    private var initJob: kotlinx.coroutines.Job? = null

    /** 一键初始化：写入内置示例源（10 条官方高清直播/点播资源） */
    fun oneKeyInit() {
        if (initJob?.isActive == true) return
        initJob = viewModelScope.launch {
            _initProgress.value = ImportProgress(
                ImportProgress.Stage.PREPARING,
                message = "正在准备内置示例源",
            )
            try {
                builtinSourceInstaller.install { p ->
                    _initProgress.value = p
                }
                _initDone.value = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                _initProgress.value = null
                throw e
            } catch (e: Exception) {
                _initProgress.value = null
                _error.value = e.message ?: "初始化失败，请稍后重试"
            }
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun cancelInit() {
        initJob?.cancel()
        _initProgress.value = null
    }

    /** 体验主题切换（完成页可选入口） */
    fun previewTheme(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    /** 标记引导完成并返回首页（记录字段 onboardingDone，首页据此决定启动页） */
    fun finish() = viewModelScope.launch {
        settingsRepository.setOnboardingDone(true)
    }
}
