package com.puretv.player.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.theme.FocusStyle
import com.puretv.player.core.theme.LayoutDensity
import com.puretv.player.core.theme.ThemeMode
import com.puretv.player.data.backup.BackupManager
import com.puretv.player.data.backup.BackupSummary
import com.puretv.player.data.builtin.BuiltinSourceInstaller
import com.puretv.player.data.tvbox.TvBoxConfigImporter
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.BufferProfile
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.prefs.UiMode
import java.util.Locale
import com.puretv.player.data.repository.SourceRepository
import com.puretv.player.data.sync.SyncManager
import com.puretv.player.data.sync.SyncSummary
import com.puretv.player.player.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 耗时操作进度（导入/备份/恢复/同步） */
data class ProgressUi(
    val message: String?,
    val percent: Int,
    val cancelable: Boolean = true,
)

/**
 * 设置中心 ViewModel（需求 5.2.3 UI 自定义 / 5.6 双模式 / 5.7 导入导出备份同步）。
 * 所有设置经 SettingsRepository（DataStore）持久化，实时驱动全局生效。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val builtinSourceInstaller: BuiltinSourceInstaller,
    private val tvBoxConfigImporter: TvBoxConfigImporter,
    private val backupManager: BackupManager,
    private val syncManager: SyncManager,
    private val playbackEngine: PlaybackEngine,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sources: StateFlow<List<SourceEntity>> = sourceRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastSyncAt: StateFlow<Long> = syncManager.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _progress = MutableStateFlow<ProgressUi?>(null)
    val progress: StateFlow<ProgressUi?> = _progress.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncSummary?>(null)
    val syncResult: StateFlow<SyncSummary?> = _syncResult.asStateFlow()

    private val _backupResult = MutableStateFlow<BackupSummary?>(null)
    val backupResult: StateFlow<BackupSummary?> = _backupResult.asStateFlow()

    private var progressJob: Job? = null

    /* ----------------------------- 极简 / 专业双模式（5.6） ----------------------------- */

    fun setUiMode(mode: UiMode) = launchSettings { settingsRepository.setUiMode(mode) }

    /* ----------------------------- 外观（5.2.3） ----------------------------- */

    fun setThemeMode(mode: ThemeMode) = launchSettings { settingsRepository.setThemeMode(mode) }

    fun setFontScale(scale: Float) = launchSettings { settingsRepository.setFontScale(scale) }

    fun setFocusStyle(style: FocusStyle) = launchSettings { settingsRepository.setFocusStyle(style) }

    fun setLayoutDensity(density: LayoutDensity) =
        launchSettings { settingsRepository.setLayoutDensity(density) }

    /** 自定义皮肤：仅主色/高亮色两项，且限定在品牌蓝体系内（配色禁忌 7.3） */
    fun applySkin(preset: SkinPreset) = launchSettings {
        val json = if (preset.isDefault) null else preset.toJson()
        settingsRepository.setSkin(preset.id, json)
    }

    /* ----------------------------- 播放（5.5 / 5.4） ----------------------------- */

    fun setBufferProfile(profile: BufferProfile) =
        launchSettings { settingsRepository.setBufferProfile(profile) }

    fun setBufferParams(maxMs: Int, minMs: Int, forPlaybackMs: Int, forAfterMs: Int) =
        launchSettings {
            settingsRepository.setBufferParams(
                maxMs.coerceAtLeast(1_000),
                minMs.coerceIn(500, maxMs),
                forPlaybackMs.coerceAtLeast(200),
                forAfterMs.coerceAtLeast(500),
            )
        }

    fun setAutoQualitySwitch(enabled: Boolean) =
        launchSettings { settingsRepository.setAutoQualitySwitch(enabled) }

    fun setRememberChannelQuality(enabled: Boolean) =
        launchSettings { settingsRepository.setRememberChannelQuality(enabled) }

    fun setAutoSwitchBackup(enabled: Boolean) =
        launchSettings { settingsRepository.setAutoSwitchBackup(enabled) }

    fun setHardwareDecode(enabled: Boolean) =
        launchSettings { settingsRepository.setHardwareDecode(enabled) }

    fun setDefaultSpeed(speed: Float) = launchSettings { settingsRepository.setDefaultSpeed(speed) }

    fun setPreloadMinutes(minutes: Int) = launchSettings { settingsRepository.setPreloadMinutes(minutes) }

    fun setNextEpisodeCountdown(sec: Int) = launchSettings { settingsRepository.setNextEpisodeCountdown(sec) }

    /* ---- 播放缓存（存储与缓存分区） ---- */

    /** 缓存占用展示文案（进入设置页计算一次，清除后刷新） */
    private val _cacheSizeLabel = MutableStateFlow("计算中…")
    val cacheSizeLabel: StateFlow<String> = _cacheSizeLabel.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun setCacheMaxMb(mb: Int) = launchSettings { settingsRepository.setCacheMaxMb(mb) }

    fun setMiniProgressEnabled(show: Boolean) = launchSettings { settingsRepository.setMiniProgressEnabled(show) }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = playbackEngine.cacheSizeBytes()
            _cacheSizeLabel.value = when {
                bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
                bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
                bytes >= 1L shl 10 -> "${bytes / 1024} KB"
                else -> "$bytes B"
            }
        }
    }

    fun clearMediaCache() {
        viewModelScope.launch {
            playbackEngine.clearMediaCache()
            refreshCacheSize()
        }
    }

    /* ----------------------------- 账号与服务 ----------------------------- */

    /** TMDB API Key（DataStore 键 "tmdb_api_key"，与点播模块共用） */
    fun setTmdbApiKey(key: String) = launchSettings {
        settingsRepository.setTmdbApiKey(key.trim(), settings.value?.tmdbRegion ?: "CN")
        notify("TMDB API Key 已保存")
    }

    /* ----------------------------- 源管理（5.3.1 / 5.7.4） ----------------------------- */

    fun importM3u(name: String, url: String, epgUrl: String? = null) {
        runProgress(cancelable = true) { onProgress ->
            sourceRepository.importM3u(name.trim(), url.trim(), epgUrl, onProgress)
            notify("M3U 源导入完成：$name")
        }
    }

    fun importM3uFromFile(name: String, uri: Uri, epgUrl: String? = null) {
        runProgress(cancelable = true) { onProgress ->
            sourceRepository.importM3uFromFile(name, uri, epgUrl, onProgress)
            notify("本地 M3U 导入完成：$name")
        }
    }

    fun importXtream(name: String, baseUrl: String, username: String, password: String) {
        runProgress(cancelable = true) { onProgress ->
            sourceRepository.importXtream(name.trim(), baseUrl.trim(), username.trim(), password, null, onProgress)
            notify("Xtream 源导入完成：$name")
        }
    }

    /** 导入 TVBox 配置（聚合影视线路：MacCMS 采集站 + M3U 直播，如「饭太硬」等 .json 配置） */
    fun importTvBoxConfig(url: String) {
        runProgress(cancelable = true) { onProgress ->
            val summary = tvBoxConfigImporter.importFromUrl(url.trim(), onProgress)
            notify(summary.message)
        }
    }

    fun refreshSource(sourceId: String, name: String) {
        runProgress(cancelable = true) { onProgress ->
            sourceRepository.refresh(sourceId, onProgress)
            notify("已刷新：$name")
        }
    }

    fun removeSource(sourceId: String, name: String) = launchSettings {
        sourceRepository.removeSource(sourceId)
        notify("已删除源：$name")
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) = launchSettings {
        sourceRepository.setSourceEnabled(sourceId, enabled)
    }

    /** 重新导入内置源（新手引导「一键初始化」同款流程：10 条高清样片 + 20 条 TVBox 聚合影视线路） */
    fun reinstallBuiltinSource() {
        runProgress(cancelable = false) { onProgress ->
            builtinSourceInstaller.install(onProgress)
            notify("内置源已导入：10 条高清直播/点播样片 + 20 条 TVBox 聚合影视线路")
        }
    }

    /* ----------------------------- 导出 / 备份 / 恢复 / 同步 ----------------------------- */

    fun exportSourcesM3u(uri: Uri) {
        runProgress(cancelable = false) { onProgress ->
            val summary = backupManager.exportM3U(uri, null, onProgress)
            notify(summary.message)
        }
    }

    fun backupTo(uri: Uri) {
        runProgress(cancelable = false) { onProgress ->
            val summary = backupManager.backupTo(uri, onProgress)
            _backupResult.value = summary
            notify("备份完成：${summary.message}")
        }
    }

    fun restoreFrom(uri: Uri) {
        runProgress(cancelable = false) { onProgress ->
            val summary = backupManager.restoreFrom(uri, onProgress)
            _backupResult.value = summary
            notify("恢复完成：${summary.message}")
        }
    }

    fun syncTo(uri: Uri) {
        runProgress(cancelable = false) { onProgress ->
            val summary = syncManager.exportTo(uri, onProgress)
            _syncResult.value = summary
            notify("同步完成：${summary.describe}")
        }
    }

    fun restoreSyncFrom(uri: Uri) {
        runProgress(cancelable = false) { onProgress ->
            val summary = syncManager.importFrom(uri, onProgress)
            _syncResult.value = summary
            notify("已从云端恢复：${summary.describe}")
        }
    }

    /* ----------------------------- 内部工具 ----------------------------- */

    fun cancelProgress() {
        progressJob?.cancel()
        _progress.value = null
        notify("已取消")
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** 直接给出操作提示（校验失败等即时反馈） */
    fun showMessage(message: String) {
        _notice.value = message
    }

    private fun launchSettings(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** 耗时操作统一在主线程发起、IO 由仓库内部调度，进度实时回传 UI */
    private fun runProgress(cancelable: Boolean, block: suspend ((ImportProgress) -> Unit) -> Unit) {
        if (progressJob?.isActive == true) {
            notify("有任务正在进行，请稍候")
            return
        }
        progressJob = viewModelScope.launch {
            _progress.value = ProgressUi("正在处理…", 0, cancelable)
            try {
                block { p ->
                    _progress.value = ProgressUi(
                        message = p.message ?: stageLabel(p.stage),
                        percent = p.percent,
                        cancelable = cancelable,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "操作失败，请稍后重试")
            } finally {
                _progress.value = null
            }
        }
    }

    private fun notify(message: String) {
        _notice.value = message
    }

    private fun stageLabel(stage: ImportProgress.Stage): String = when (stage) {
        ImportProgress.Stage.PREPARING -> "准备中…"
        ImportProgress.Stage.DOWNLOADING -> "读取数据…"
        ImportProgress.Stage.PARSING -> "解析中…"
        ImportProgress.Stage.SAVING -> "写入中…"
        ImportProgress.Stage.DONE -> "已完成"
        ImportProgress.Stage.FAILED -> "失败"
    }
}
