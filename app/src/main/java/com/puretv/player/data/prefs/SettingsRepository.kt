package com.puretv.player.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.puretv.player.core.theme.FocusStyle
import com.puretv.player.core.theme.LayoutDensity
import com.puretv.player.core.theme.ThemeMode
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 极简/专业双模式（需求 5.6：默认极简模式，可切换专业模式开放全部参数）
 */
enum class UiMode {
    SIMPLE, // 极简模式（默认）：隐藏复杂设置，只展示核心播放、收藏、记录
    PRO;    // 专业模式：开放插件、解码参数、缓冲参数、界面自定义全部权限

    companion object {
        fun fromName(name: String?): UiMode = entries.firstOrNull { it.name == name } ?: SIMPLE
    }
}

/** 智能缓冲档位（需求 5.5：智能缓冲策略；专业模式可调参数） */
enum class BufferProfile {
    SMALL, MEDIUM, LARGE, CUSTOM;

    companion object {
        fun fromName(name: String?): BufferProfile =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/** 全局设置项快照（由 DataStore 持久化，替代 SharedPreferences） */
@JsonClass(generateAdapter = true)
data class AppSettings(
    // —— 主题（技术文档：三套主题枚举，跟随系统为出厂默认）——
    val themeMode: ThemeMode = ThemeMode.THEME_SYSTEM,
    // —— 双模式（需求 5.6）——
    val uiMode: UiMode = UiMode.SIMPLE,
    // —— UI 自定义（需求 5.2：字体大小/焦点样式/界面布局/皮肤）——
    val fontScale: Float = 1f,
    val focusStyle: FocusStyle = FocusStyle.SCALE_AND_BORDER,
    val layoutDensity: LayoutDensity = LayoutDensity.STANDARD,
    val skinId: String = "default",
    /** 自定义皮肤文件（JSON：accent/highlight 两个色值，仅蓝白灰黑体系内） */
    val customSkinJson: String? = null,
    // —— 播放内核参数（需求 5.5）——
    val bufferProfile: BufferProfile = BufferProfile.MEDIUM,
    /** CUSTOM 档位下的缓冲参数（毫秒） */
    val bufferMaxMs: Int = 50_000,
    val bufferMinMs: Int = 15_000,
    val bufferForPlaybackMs: Int = 2_500,
    val bufferForAfterMs: Int = 5_000,
    /** 清晰度智能切换：自动根据网速切换 1080P/4K（需求 5.4.4） */
    val autoQualitySwitch: Boolean = true,
    /** 记住频道清晰度（需求 5.3.3） */
    val rememberChannelQuality: Boolean = true,
    /** 弱网/卡顿自动切换备用源（需求 5.5.3、3.2.8） */
    val autoSwitchBackupSource: Boolean = true,
    /** 硬件加速解码 4K/HEVC（需求 5.5.2） */
    val hardwareDecode: Boolean = true,
    /** 默认播放倍速记忆 */
    val defaultSpeed: Float = 1.0f,
    /** 跨集预缓冲时长（分钟，0=关闭，最长 120 分钟=2 小时）：按当前集时长折算提前缓冲后续剧集 */
    val preloadMinutes: Int = 0,
    /** 连播倒计时（秒，0=关闭）：本集结束前提示「N 秒后播放下一集」 */
    val nextEpisodeCountdownSec: Int = 5,
    /** 播放媒体缓存磁盘上限（MB，512..8192；默认 4GB，重启应用生效） */
    val cacheMaxMb: Int = 4096,
    /** 全屏播放底部迷你进度条（常驻细条，直播不显示；默认开） */
    val miniProgressEnabled: Boolean = true,
    // —— TMDB 刮削（需求 5.4.2）——
    val tmdbApiKey: String = "",
    val tmdbRegion: String = "CN",
    // —— 高级拓展（需求 5.4.5：Real-Debrid、TorBox）——
    val realDebridApiKey: String = "",
    val torboxApiKey: String = "",
    // —— 云端同步（需求 5.7.3：配置、收藏、播放记录云端同步）——
    val syncBaseUrl: String = "",
    val syncToken: String = "",
    val lastSyncAt: Long = 0L,
    // —— 增值付费边界（需求 5.8.3：高级自定义皮肤、专属云端空间、无水印美化）——
    val premiumCustomSkin: Boolean = false,
    val premiumCloudSpace: Boolean = false,
    val premiumNoWatermark: Boolean = false,
    // —— 新手引导（需求 5.6.1）——
    val onboardingDone: Boolean = false,
    // —— 其它 ——
    val lastStartupAt: Long = 0L,
)

/**
 * 设置仓库：DataStore 轻量持久化（主题选择、用户设置、配置项）
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }

    suspend fun setUiMode(mode: UiMode) = edit { it[KEY_UI_MODE] = mode.name }

    suspend fun setFontScale(scale: Float) =
        edit { it[KEY_FONT_SCALE] = scale.coerceIn(0.85f, 1.6f) }

    suspend fun setFocusStyle(style: FocusStyle) = edit { it[KEY_FOCUS] = style.name }

    suspend fun setLayoutDensity(density: LayoutDensity) =
        edit { it[KEY_LAYOUT] = density.name }

    suspend fun setSkin(skinId: String, customJson: String?) = edit {
        it[KEY_SKIN] = skinId
        if (customJson != null) it[KEY_SKIN_JSON] = customJson else it.remove(KEY_SKIN_JSON)
    }

    suspend fun setBufferProfile(profile: BufferProfile) = edit { it[KEY_BUFFER_PROFILE] = profile.name }

    suspend fun setBufferParams(maxMs: Int, minMs: Int, forPlaybackMs: Int, forAfterMs: Int) = edit {
        it[KEY_BUFFER_MAX] = maxMs
        it[KEY_BUFFER_MIN] = minMs
        it[KEY_BUFFER_PLAYBACK] = forPlaybackMs
        it[KEY_BUFFER_AFTER] = forAfterMs
    }

    suspend fun setAutoQualitySwitch(enabled: Boolean) = edit { it[KEY_AUTO_QUALITY] = enabled }

    suspend fun setRememberChannelQuality(enabled: Boolean) = edit { it[KEY_REMEMBER_QUALITY] = enabled }

    suspend fun setAutoSwitchBackup(enabled: Boolean) = edit { it[KEY_AUTO_BACKUP] = enabled }

    suspend fun setHardwareDecode(enabled: Boolean) = edit { it[KEY_HW_DECODE] = enabled }

    suspend fun setDefaultSpeed(speed: Float) = edit { it[KEY_DEFAULT_SPEED] = speed }

    suspend fun setPreloadMinutes(minutes: Int) = edit {
        it[KEY_PRELOAD_MINUTES] = minutes.coerceIn(0, 120)
    }

    suspend fun setNextEpisodeCountdown(sec: Int) = edit {
        it[KEY_NEXT_EP_COUNTDOWN] = sec.coerceIn(0, 30)
    }

    suspend fun setCacheMaxMb(mb: Int) = edit {
        it[KEY_CACHE_MAX_MB] = mb.coerceIn(512, 8192)
    }

    suspend fun setMiniProgressEnabled(show: Boolean) = edit {
        it[KEY_MINI_PROGRESS] = show
    }

    suspend fun setTmdbApiKey(key: String, region: String) = edit {
        it[KEY_TMDB_KEY] = key
        it[KEY_TMDB_REGION] = region
    }

    suspend fun setDebridKeys(realDebrid: String, torbox: String) = edit {
        it[KEY_RD_KEY] = realDebrid
        it[KEY_TORBOX_KEY] = torbox
    }

    suspend fun setSyncConfig(baseUrl: String, token: String) = edit {
        it[KEY_SYNC_URL] = baseUrl
        it[KEY_SYNC_TOKEN] = token
    }

    suspend fun setLastSyncAt(time: Long) = edit { it[KEY_LAST_SYNC] = time }

    suspend fun setPremium(customSkin: Boolean, cloudSpace: Boolean, noWatermark: Boolean) = edit {
        it[KEY_PREMIUM_SKIN] = customSkin
        it[KEY_PREMIUM_CLOUD] = cloudSpace
        it[KEY_PREMIUM_WATERMARK] = noWatermark
    }

    suspend fun setOnboardingDone(done: Boolean) = edit { it[KEY_ONBOARDING] = done }

    suspend fun setLastStartupAt(time: Long) = edit { it[KEY_STARTUP] = time }

    /** 一键恢复默认设置（需求 5.7.4：一键恢复） */
    suspend fun resetToDefaults() = edit { it.clear() }

    /** 应用整份配置快照（源备份恢复 / 云端同步拉取） */
    suspend fun applySnapshot(s: AppSettings) = edit { prefs ->
        prefs[KEY_THEME] = s.themeMode.name
        prefs[KEY_UI_MODE] = s.uiMode.name
        prefs[KEY_FONT_SCALE] = s.fontScale
        prefs[KEY_FOCUS] = s.focusStyle.name
        prefs[KEY_LAYOUT] = s.layoutDensity.name
        prefs[KEY_SKIN] = s.skinId
        if (s.customSkinJson != null) prefs[KEY_SKIN_JSON] = s.customSkinJson else prefs.remove(KEY_SKIN_JSON)
        prefs[KEY_BUFFER_PROFILE] = s.bufferProfile.name
        prefs[KEY_BUFFER_MAX] = s.bufferMaxMs
        prefs[KEY_BUFFER_MIN] = s.bufferMinMs
        prefs[KEY_BUFFER_PLAYBACK] = s.bufferForPlaybackMs
        prefs[KEY_BUFFER_AFTER] = s.bufferForAfterMs
        prefs[KEY_AUTO_QUALITY] = s.autoQualitySwitch
        prefs[KEY_REMEMBER_QUALITY] = s.rememberChannelQuality
        prefs[KEY_AUTO_BACKUP] = s.autoSwitchBackupSource
        prefs[KEY_HW_DECODE] = s.hardwareDecode
        prefs[KEY_DEFAULT_SPEED] = s.defaultSpeed
        prefs[KEY_PRELOAD_MINUTES] = s.preloadMinutes
        prefs[KEY_NEXT_EP_COUNTDOWN] = s.nextEpisodeCountdownSec
        prefs[KEY_CACHE_MAX_MB] = s.cacheMaxMb
        prefs[KEY_MINI_PROGRESS] = s.miniProgressEnabled
        prefs[KEY_TMDB_KEY] = s.tmdbApiKey
        prefs[KEY_TMDB_REGION] = s.tmdbRegion
        prefs[KEY_RD_KEY] = s.realDebridApiKey
        prefs[KEY_TORBOX_KEY] = s.torboxApiKey
        prefs[KEY_SYNC_URL] = s.syncBaseUrl
        prefs[KEY_SYNC_TOKEN] = s.syncToken
        prefs[KEY_LAST_SYNC] = s.lastSyncAt
        prefs[KEY_PREMIUM_SKIN] = s.premiumCustomSkin
        prefs[KEY_PREMIUM_CLOUD] = s.premiumCloudSpace
        prefs[KEY_PREMIUM_WATERMARK] = s.premiumNoWatermark
        prefs[KEY_ONBOARDING] = s.onboardingDone
        prefs[KEY_STARTUP] = s.lastStartupAt
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        themeMode = ThemeMode.fromName(this[KEY_THEME]),
        uiMode = UiMode.fromName(this[KEY_UI_MODE]),
        fontScale = this[KEY_FONT_SCALE] ?: 1f,
        focusStyle = FocusStyle.fromName(this[KEY_FOCUS]),
        layoutDensity = LayoutDensity.fromName(this[KEY_LAYOUT]),
        skinId = this[KEY_SKIN] ?: "default",
        customSkinJson = this[KEY_SKIN_JSON],
        bufferProfile = BufferProfile.fromName(this[KEY_BUFFER_PROFILE]),
        bufferMaxMs = this[KEY_BUFFER_MAX] ?: 50_000,
        bufferMinMs = this[KEY_BUFFER_MIN] ?: 15_000,
        bufferForPlaybackMs = this[KEY_BUFFER_PLAYBACK] ?: 2_500,
        bufferForAfterMs = this[KEY_BUFFER_AFTER] ?: 5_000,
        autoQualitySwitch = this[KEY_AUTO_QUALITY] ?: true,
        rememberChannelQuality = this[KEY_REMEMBER_QUALITY] ?: true,
        autoSwitchBackupSource = this[KEY_AUTO_BACKUP] ?: true,
        hardwareDecode = this[KEY_HW_DECODE] ?: true,
        defaultSpeed = this[KEY_DEFAULT_SPEED] ?: 1.0f,
        preloadMinutes = this[KEY_PRELOAD_MINUTES] ?: 0,
        nextEpisodeCountdownSec = this[KEY_NEXT_EP_COUNTDOWN] ?: 5,
        cacheMaxMb = this[KEY_CACHE_MAX_MB] ?: 4096,
        miniProgressEnabled = this[KEY_MINI_PROGRESS] ?: true,
        tmdbApiKey = this[KEY_TMDB_KEY] ?: "",
        tmdbRegion = this[KEY_TMDB_REGION] ?: "CN",
        realDebridApiKey = this[KEY_RD_KEY] ?: "",
        torboxApiKey = this[KEY_TORBOX_KEY] ?: "",
        syncBaseUrl = this[KEY_SYNC_URL] ?: "",
        syncToken = this[KEY_SYNC_TOKEN] ?: "",
        lastSyncAt = this[KEY_LAST_SYNC] ?: 0L,
        premiumCustomSkin = this[KEY_PREMIUM_SKIN] ?: false,
        premiumCloudSpace = this[KEY_PREMIUM_CLOUD] ?: false,
        premiumNoWatermark = this[KEY_PREMIUM_WATERMARK] ?: false,
        onboardingDone = this[KEY_ONBOARDING] ?: false,
        lastStartupAt = this[KEY_STARTUP] ?: 0L,
    )

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_UI_MODE = stringPreferencesKey("ui_mode")
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        private val KEY_FOCUS = stringPreferencesKey("focus_style")
        private val KEY_LAYOUT = stringPreferencesKey("layout_density")
        private val KEY_SKIN = stringPreferencesKey("skin_id")
        private val KEY_SKIN_JSON = stringPreferencesKey("skin_json")
        private val KEY_BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        private val KEY_BUFFER_MAX = intPreferencesKey("buffer_max_ms")
        private val KEY_BUFFER_MIN = intPreferencesKey("buffer_min_ms")
        private val KEY_BUFFER_PLAYBACK = intPreferencesKey("buffer_playback_ms")
        private val KEY_BUFFER_AFTER = intPreferencesKey("buffer_after_ms")
        private val KEY_AUTO_QUALITY = booleanPreferencesKey("auto_quality_switch")
        private val KEY_REMEMBER_QUALITY = booleanPreferencesKey("remember_channel_quality")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_switch_backup_source")
        private val KEY_HW_DECODE = booleanPreferencesKey("hardware_decode")
        private val KEY_DEFAULT_SPEED = floatPreferencesKey("default_speed")
        private val KEY_PRELOAD_MINUTES = intPreferencesKey("preload_minutes")
        private val KEY_NEXT_EP_COUNTDOWN = intPreferencesKey("next_ep_countdown_sec")
        private val KEY_CACHE_MAX_MB = intPreferencesKey("cache_max_mb")
        private val KEY_MINI_PROGRESS = booleanPreferencesKey("mini_progress_enabled")
        private val KEY_TMDB_KEY = stringPreferencesKey("tmdb_api_key")
        private val KEY_TMDB_REGION = stringPreferencesKey("tmdb_region")
        private val KEY_RD_KEY = stringPreferencesKey("real_debrid_key")
        private val KEY_TORBOX_KEY = stringPreferencesKey("torbox_key")
        private val KEY_SYNC_URL = stringPreferencesKey("sync_base_url")
        private val KEY_SYNC_TOKEN = stringPreferencesKey("sync_token")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
        private val KEY_PREMIUM_SKIN = booleanPreferencesKey("premium_custom_skin")
        private val KEY_PREMIUM_CLOUD = booleanPreferencesKey("premium_cloud_space")
        private val KEY_PREMIUM_WATERMARK = booleanPreferencesKey("premium_no_watermark")
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_done")
        private val KEY_STARTUP = longPreferencesKey("last_startup_at")
    }
}
