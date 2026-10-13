package com.puretv.player.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.ui.TvLinearProgress
import com.puretv.player.core.ui.focusOnLaunch
import com.puretv.player.core.util.DeviceUtils
import com.puretv.player.core.util.StringUtils
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.dao.ChannelDao
import com.puretv.player.data.local.dao.ChannelSettingDao
import com.puretv.player.data.local.dao.EpgDao
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.repository.FavoritesRepository
import com.puretv.player.player.AspectRatioMode
import com.puretv.player.player.FloatingPlayerController
import com.puretv.player.player.SystemFloatingWindowManager
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlaybackEngineImpl
import com.puretv.player.player.PlaybackMode
import com.puretv.player.player.PlaybackEpisode
import com.puretv.player.player.PlaybackRequest
import com.puretv.player.player.PlaybackState
import com.puretv.player.player.PlayerProvider
import com.puretv.player.player.PlayerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/* ================================ 页面 ViewModel ================================ */

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    val engine: PlaybackEngine,
    private val floatingPlayer: FloatingPlayerController,
    private val floatWindowManager: SystemFloatingWindowManager,
    private val channelDao: ChannelDao,
    private val channelSettingDao: ChannelSettingDao,
    private val epgDao: EpgDao,
    private val settingsRepository: SettingsRepository,
    favoritesRepository: FavoritesRepository,
) : ViewModel() {

    /** 弹窗播放状态（小窗悬浮接管画面时，播放页退出不释放内核） */
    val isFloating: StateFlow<Boolean> = floatingPlayer.isFloating

    /** 进入弹窗播放：置标志 + 同步添加系统悬浮窗（随后调用方退出播放页） */
    fun enterFloatMode() {
        floatingPlayer.enter()
        floatWindowManager.show()
    }

    /**
     * 点「弹窗」但尚未授予悬浮窗权限：标记待决并跳系统设置
     * 「显示在其他应用上层」；用户授权返回后（播放页 ON_RESUME）继续弹窗流程。
     */
    fun requestOverlayPermission(activity: android.app.Activity?) {
        if (activity == null) return
        floatingPlayer.markPermissionPending()
        activity.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${activity.packageName}"),
            ),
        )
    }

    fun consumePermissionPending(): Boolean = floatingPlayer.consumePermissionPending()

    /** 退出弹窗播放（重新进入播放页全屏接管） */
    fun exitFloatMode() {
        floatingPlayer.exit()
    }

    fun isFloatingActive(): Boolean = floatingPlayer.isFloating.value

    val channels: StateFlow<List<ChannelEntity>> = channelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = favoritesRepository.favoriteKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 连播倒计时秒数（设置 → 播放；0=关闭） */
    val nextEpisodeCountdownSec: StateFlow<Int> = settingsRepository.settings
        .map { it.nextEpisodeCountdownSec }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5)

    /** 全屏底部迷你进度条开关（设置 → 播放界面） */
    val miniProgressEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.miniProgressEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 跳过片头片尾配置（秒，0=关闭；插件「跳过片头片尾」安装后可配） */
    val skipConfig: StateFlow<SkipConfig> = settingsRepository.settings
        .map { SkipConfig(it.skipIntroSec, it.skipOutroSec) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkipConfig(0, 0))

    private val _currentProgram = MutableStateFlow<EpgProgramEntity?>(null)
    val currentProgram: StateFlow<EpgProgramEntity?> = _currentProgram.asStateFlow()

    /** 换台 = 再次 play：读取该频道清晰度记忆后投递给内核（需求 5.3.3 清晰度记忆 / 5.2.2 上下换台） */
    fun switchChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            val rememberQuality = settingsRepository.settings
                .first().rememberChannelQuality
            val key = "${channel.sourceId}|${channel.id}"
            val quality = if (rememberQuality) channelSettingDao.get(key)?.quality else null
            engine.play(PlayerRequests.live(channel, quality))
            refreshCurrentProgram()
        }
    }

    /** 当前直播节目信息（EPG） */
    fun refreshCurrentProgram() {
        viewModelScope.launch {
            val request = engine.currentRequest.value
            if (request == null || request.historyType != "channel") {
                _currentProgram.value = null
                return@launch
            }
            val targetId = request.historyTargetId
            if (targetId == null || !targetId.contains('|')) {
                _currentProgram.value = null
                return@launch
            }
            val channel = channelDao.get(targetId.substringBefore('|'), targetId.substringAfter('|'))
            val epgId = channel?.epgId
            _currentProgram.value = if (epgId.isNullOrBlank()) null
            else epgDao.currentProgram(epgId, System.currentTimeMillis())
        }
    }
}

/* ================================ 播放页 ================================ */

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 3_000L
private const val DRAWER_AUTO_CLOSE_MS = 2_000L

/** 跳过片头片尾配置（秒，0=关闭；插件「跳过片头片尾」） */
data class SkipConfig(val introSec: Int, val outroSec: Int)

private enum class PlaybackMenu { SPEED, SUBTITLE, AUDIO, ASPECT, BACKUP }

private data class OptionItem(val label: String, val selected: Boolean = false)

/**
 * 播放页（需求 5.2 UI 交互 / 5.5.4 倍速字幕音轨比例 / 5.3.3 直播换台 / 5.6 易用性）：
 * 全屏 PlayerView + 控制层（3 秒无操作自动隐藏，OK/菜单键切换）、直播频道抽屉、缓冲与错误浮层。
 * 播放内容由 [PlaybackEngine] 统一驱动；退出/后台时停止并释放播放器（需求 5.1/6.1 防泄漏）。
 */
@Composable
fun PlaybackScreen(nav: NavHostController) {
    val viewModel: PlaybackViewModel = hiltViewModel()
    val engine = viewModel.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val request by engine.currentRequest.collectAsStateWithLifecycle()
    val preloadProgress by engine.preloadProgress.collectAsStateWithLifecycle()
    val nextEpisodeCountdown by viewModel.nextEpisodeCountdownSec.collectAsStateWithLifecycle()
    val miniProgressEnabled by viewModel.miniProgressEnabled.collectAsStateWithLifecycle()
    val skipConfig by viewModel.skipConfig.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val currentProgram by viewModel.currentProgram.collectAsStateWithLifecycle()
    val colors = PureTvTheme.colors
    val context = LocalContext.current
    val activity = context.findActivity()
    val provider = engine as? PlayerProvider
    // 手机优先：手机用触摸控制层（Tap 呼出/收起），TV 保留 D-pad 焦点控制层
    val isTv = remember(context) { DeviceUtils.isTvDevice(context) }
    // 手机全屏态：默认竖屏播放（视频在上、控制在下），点「全屏」才转横屏沉浸播放
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // 需求 5.1/6.1：播放页保持屏幕常亮；退出时写记录并停止。
    // 手机端默认竖屏播放，进入/退出全屏切换横竖屏；退出播放恢复原方向。TV 本身恒为横屏不受影响。
    // 弹窗播放中退出不停止（小窗接管画面继续播放，追加需求）。
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val previousOrientation = activity?.requestedOrientation
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
            if (!viewModel.isFloatingActive()) engine.stop()
        }
    }

    // 重新进入播放页 = 全屏接管：收起弹窗小窗
    LaunchedEffect(Unit) { viewModel.exitFloatMode() }

    if (activity != null && !isTv) {
        LaunchedEffect(fullscreen) {
            if (fullscreen) {
                DeviceUtils.lockLandscape(activity)
            } else {
                DeviceUtils.lockPortrait(activity)
            }
        }
    }

    // 后台时释放播放器，回前台按原进度续播（低配盒子防内存泄漏，需求 5.1/6.1）
    // 弹窗播放中不释放：导航 2.7+ 每个页面独立 lifecycle，退出播放页会派发 ON_STOP，
    // 若此处无条件 release，小窗将拿到已释放的播放器（用户反馈：一点弹窗就暂停、播放键全失灵）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stopped = false
        var resumePosition = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!viewModel.isFloatingActive()) {
                        stopped = true
                        resumePosition = engine.state.value.positionMs
                        engine.release()
                    }
                }

                Lifecycle.Event.ON_RESUME -> {
                    // 悬浮窗权限申请返回：已授权则继续弹窗流程（点弹窗时被权限拦截的用户路径）
                    if (viewModel.consumePermissionPending() &&
                        android.provider.Settings.canDrawOverlays(context)
                    ) {
                        viewModel.enterFloatMode()
                        nav.popBackStack()
                    }
                }

                Lifecycle.Event.ON_START -> {
                    if (stopped) {
                        stopped = false
                        val current = engine.currentRequest.value
                        if (current != null) {
                            val position = resumePosition.takeIf { it > 0 } ?: current.startPositionMs
                            engine.play(current.copy(startPositionMs = position))
                        }
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 底层播放器实例轮询（内核懒创建/后台释放后需重新绑定 PlayerView）
    var player by remember { mutableStateOf<Player?>(null) }
    LaunchedEffect(provider) {
        while (true) {
            val current = provider?.player
            if (current !== player) player = current
            delay(200)
        }
    }

    /* ---- 控制层状态 ---- */
    var showControls by remember { mutableStateOf(true) }
    var controlTick by remember { mutableStateOf(0) }
    var openMenu by remember { mutableStateOf<PlaybackMenu?>(null) }
    var showAddSubtitle by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var episodeOpen by remember { mutableStateOf(false) }
    var toolbarFocused by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }

    fun poke() {
        controlTick += 1
        showControls = true
    }

    // 换集瞬时抑制加载圈：已预缓冲的切集应无感直切；超过窗口仍在缓冲则照常显示
    var seamlessSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(seamlessSwitch, state.isBuffering) {
        if (!seamlessSwitch) return@LaunchedEffect
        delay(if (state.isBuffering) 1200L else 250L)
        seamlessSwitch = false
    }

    /** 上一集/下一集（与选集抽屉同一条换集路径：保留断点、重置备用源） */
    fun stepEpisode(delta: Int) {
        val current = request ?: return
        val index = current.episodes.indexOfFirst { it.url == current.url }
        if (index < 0) return
        val target = current.episodes.getOrNull(index + delta) ?: return
        seamlessSwitch = true
        engine.play(
            current.copy(
                url = target.url,
                subtitle = target.subtitle ?: target.label,
                episodeLabel = target.label,
                historyTargetId = target.historyTargetId ?: current.historyTargetId,
                startPositionMs = target.startPositionMs,
                backupUrls = target.backups,
            ),
        )
        poke()
    }

    /* ---- 跳过片头片尾（插件「跳过片头片尾」；每集各触发一次，回看不打断） ---- */
    val introSec = skipConfig.introSec
    val outroSec = skipConfig.outroSec
    var introSkippedFor by remember { mutableStateOf<String?>(null) }
    var outroHandledFor by remember { mutableStateOf<String?>(null) }

    // 片头：本集开始且进度落在片头窗口内 → 自动跳到片头结束（时长过短不跳防误伤正片）
    LaunchedEffect(request?.url, state.positionMs, state.durationMs, introSec) {
        val current = request ?: return@LaunchedEffect
        if (introSec <= 0 || current.mode != PlaybackMode.VOD) return@LaunchedEffect
        if (introSkippedFor == current.url) return@LaunchedEffect
        val introMs = introSec * 1000L
        if (state.durationMs <= introMs * 2) return@LaunchedEffect
        if (state.positionMs < introMs) {
            introSkippedFor = current.url
            engine.seekTo(introMs)
        }
    }

    // 片尾：剩余时长进入片尾窗口 → 有下一集直接连播；电影/最后一集跳到结尾自然收束
    LaunchedEffect(request?.url, state.positionMs, state.durationMs, outroSec) {
        val current = request ?: return@LaunchedEffect
        if (outroSec <= 0 || current.mode != PlaybackMode.VOD) return@LaunchedEffect
        if (outroHandledFor == current.url) return@LaunchedEffect
        val outroMs = outroSec * 1000L
        val duration = state.durationMs
        if (duration <= outroMs * 2) return@LaunchedEffect
        if (duration - state.positionMs > outroMs) return@LaunchedEffect
        val index = current.episodes.indexOfFirst { it.url == current.url }
        if (index < 0) return@LaunchedEffect
        outroHandledFor = current.url
        if (index + 1 < current.episodes.size) {
            stepEpisode(1)
        } else {
            engine.seekTo(duration)
        }
    }

    /** 连播倒计时：本集剩余 ≤ 设置秒数时显示「N 秒后播放下一集」（电影/直播/最后一集不提示） */
    val countdownSecondsLeft: Int? = request?.let { req ->
        val duration = state.durationMs
        val remainingMs = duration - state.positionMs
        if (
            nextEpisodeCountdown > 0 &&
            req.mode == PlaybackMode.VOD &&
            req.episodes.size > 1 &&
            duration > 0 &&
            remainingMs in 1..nextEpisodeCountdown * 1000L
        ) {
            val index = req.episodes.indexOfFirst { it.url == req.url }
            if (index in 0 until req.episodes.lastIndex) {
                ((remainingMs + 999) / 1000).toInt()
            } else {
                null
            }
        } else {
            null
        }
    }

    fun toggleControls() {
        val next = !showControls
        controlTick += 1
        showControls = next
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun retry() {
        val current = request ?: return
        val position = if (current.mode == PlaybackMode.LIVE) 0L else state.positionMs
        engine.play(current.copy(startPositionMs = position))
        poke()
    }

    fun exit() {
        engine.stop()
        nav.popBackStack()
    }

    // 弹窗播放统一入口：已授权 → 直接弹出系统悬浮窗并退出播放页；
    // 未授权 → 跳系统设置「显示在其他应用上层」，授权返回后（ON_RESUME）自动续弹
    val enterFloatOrRequestPermission: () -> Unit = {
        if (android.provider.Settings.canDrawOverlays(context)) {
            viewModel.enterFloatMode()
            nav.popBackStack()
        } else {
            viewModel.requestOverlayPermission(activity)
            android.widget.Toast
                .makeText(context, "请允许「显示在其他应用上层」，返回后自动继续弹窗", android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

    // 3 秒无操作自动隐藏（弹窗/抽屉打开时不隐藏；暂停/未起播时常驻，便于随时操作）
    LaunchedEffect(showControls, controlTick, openMenu, drawerOpen, episodeOpen, showAddSubtitle, state.isPlaying) {
        if (showControls && openMenu == null && !drawerOpen && !episodeOpen && !showAddSubtitle && state.isPlaying) {
            delay(CONTROLS_HIDE_MS)
            showControls = false
        }
    }

    // 隐藏控制层后焦点回到播放区，保证左右键 seek / OK 呼出仍可用
    LaunchedEffect(showControls, drawerOpen, episodeOpen) {
        if (!drawerOpen && !episodeOpen && (!showControls || openMenu == null)) {
            delay(50)
            runCatching { rootFocus.requestFocus() }
        }
    }

    // 新的播放任务（换台/续播）时刷新 EPG 当前节目
    LaunchedEffect(request) {
        viewModel.refreshCurrentProgram()
    }

    // 播放完成自动连播下一集（剧集；电影/直播/最后一集不动作）
    var autoAdvancedFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isEnded) {
        if (!state.isEnded) autoAdvancedFrom = null
    }
    LaunchedEffect(state.isEnded, request) {
        val current = request ?: return@LaunchedEffect
        if (!state.isEnded) return@LaunchedEffect
        if (current.mode == PlaybackMode.LIVE) return@LaunchedEffect
        if (current.episodes.size <= 1) return@LaunchedEffect
        // 结束信号与进度互证（切集瞬间 isEnded 可能短暂为 true 的竞态窗口）
        if (state.durationMs <= 0L || state.positionMs < state.durationMs - 500L) {
            return@LaunchedEffect
        }
        if (autoAdvancedFrom == current.url) return@LaunchedEffect
        val index = current.episodes.indexOfFirst { it.url == current.url }
        if (index < 0) return@LaunchedEffect
        val next = current.episodes.getOrNull(index + 1) ?: return@LaunchedEffect
        autoAdvancedFrom = current.url
        seamlessSwitch = true
        engine.play(
            current.copy(
                url = next.url,
                subtitle = next.subtitle ?: next.label,
                episodeLabel = next.label,
                historyTargetId = next.historyTargetId ?: current.historyTargetId,
                startPositionMs = next.startPositionMs,
                backupUrls = next.backups,
            ),
        )
        autoAdvancedFrom = next.url
    }

    // 真正的错误（红色）出现时自动弹出控制层；恢复后焦点回播放区
    val errorMessage = state.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            if (errorMessage != PlaybackEngineImpl.MSG_BACKUP_SWITCHED) {
                controlTick += 1
                showControls = true
            }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // 短暂提示自动消失
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4_000)
            notice = null
        }
    }

    BackHandler(enabled = openMenu == null && !showAddSubtitle) {
        when {
            drawerOpen -> closeDrawer()
            episodeOpen -> episodeOpen = false
            // 手机全屏播放中：返回先退回竖屏（再按一次才退出播放）
            !isTv && fullscreen -> fullscreen = false
            else -> exit()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isLive = request?.mode == PlaybackMode.LIVE
                val overlayOpen = openMenu != null || showAddSubtitle || episodeOpen
                when (event.key) {
                    Key.Menu -> {
                        if (drawerOpen || overlayOpen) false
                        else {
                            toggleControls()
                            true
                        }
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!toolbarFocused && !drawerOpen && !overlayOpen) {
                            toggleControls()
                            true
                        } else false
                    }

                    Key.DirectionLeft, Key.DirectionRight -> {
                        val seekable = !isLive && !toolbarFocused && !drawerOpen && !overlayOpen
                        if (seekable) {
                            val delta = if (event.key == Key.DirectionRight) SEEK_STEP_MS else -SEEK_STEP_MS
                            engine.seekTo(state.positionMs + delta)
                            poke()
                            true
                        } else false
                    }

                    Key.DirectionUp, Key.DirectionDown -> {
                        if (isLive && !drawerOpen && !overlayOpen) {
                            poke()
                            drawerOpen = true
                            true
                        } else false
                    }

                    else -> false
                }
            }
            .focusOnLaunch(rootFocus)
            .focusable()
            .onFocusChanged { if (it.isFocused) toolbarFocused = false },
        contentAlignment = Alignment.Center,
    ) {
        if (!isTv && !fullscreen) {
            // 手机竖屏播放（手机优先）：视频 16:9 置顶、控制区常驻下方，点「全屏」才转横屏
            PhonePortraitPlayer(
                player = player,
                state = state,
                request = request,
                preload = preloadProgress,
                onPlayPause = {
                    engine.playPause()
                    poke()
                },
                onSeek = { position ->
                    engine.seekTo(position)
                    poke()
                },
                onInteract = { poke() },
                onMenu = { menu ->
                    poke()
                    openMenu = menu
                },
                onOpenChannels = {
                    poke()
                    drawerOpen = true
                },
                onOpenEpisodes = {
                    poke()
                    episodeOpen = true
                },
                onPrevEpisode = { stepEpisode(-1) },
                onNextEpisode = { stepEpisode(1) },
                onFullscreen = {
                    poke()
                    fullscreen = true
                },
                onFloat = enterFloatOrRequestPermission,
                onStop = { exit() },
            )
        } else {
            VideoSurface(
                player = player,
                aspectRatio = state.aspectRatio,
                modifier = Modifier.fillMaxSize(),
            )

            // 手机横屏全屏：点按画面切换控制层显隐（TV 端由 OK/菜单键切换，见下方按键处理）
            if (!isTv) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { toggleControls() },
                )
            }

            if (showControls) {
                if (isTv) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopInfoBar(state = state, request = request)
                        Spacer(modifier = Modifier.weight(1f))
                        MiddleInfo(state = state, request = request, currentProgram = currentProgram)
                        PlaybackToolbar(
                            state = state,
                            request = request,
                            preload = preloadProgress,
                            onPlayPause = {
                                engine.playPause()
                                poke()
                            },
                            onMenu = { menu ->
                                poke()
                                openMenu = menu
                            },
                            onOpenEpisodes = {
                                poke()
                                episodeOpen = true
                            },
                            onPrevEpisode = { stepEpisode(-1) },
                            onNextEpisode = { stepEpisode(1) },
                            onFloat = enterFloatOrRequestPermission,
                            onStop = { exit() },
                            onToolbarFocus = { focused ->
                                toolbarFocused = focused
                                if (focused) poke()
                            },
                        )
                    }
                } else {
                    // 手机横屏全屏：触摸控制层（顶部返回+标题 / 中央大播放暂停 / 底部拖动进度条+功能行）
                    PhonePlayerControls(
                        state = state,
                        request = request,
                        preload = preloadProgress,
                        onBack = { fullscreen = false },
                        onPlayPause = {
                            engine.playPause()
                            poke()
                        },
                        onSeek = { position ->
                            engine.seekTo(position)
                            poke()
                        },
                        onInteract = { poke() },
                        onMenu = { menu ->
                            poke()
                            openMenu = menu
                        },
                        onOpenChannels = {
                            poke()
                            drawerOpen = true
                        },
                        onOpenEpisodes = {
                            poke()
                            episodeOpen = true
                        },
                        onPrevEpisode = { stepEpisode(-1) },
                        onNextEpisode = { stepEpisode(1) },
                        onFloat = enterFloatOrRequestPermission,
                        onStop = { exit() },
                    )
                }
            }
        }

        if (state.isBuffering && errorMessage == null && !seamlessSwitch) {
            BufferingOverlay()
        }

        errorMessage?.let { message ->
            ErrorOrNotice(
                message = message,
                request = request,
                backupIndex = state.backupIndex,
                onRetry = { retry() },
                onSwitchBackup = {
                    val total = 1 + (request?.backupUrls?.size ?: 0)
                    if (total > 1) {
                        engine.switchBackup((state.backupIndex + 1) % total)
                        poke()
                    }
                },
            )
        }

        if (drawerOpen) {
            ChannelDrawer(
                channels = channels,
                favoriteKeys = favoriteKeys,
                currentKey = request?.historyTargetId ?: "",
                onSwitch = { channel ->
                    viewModel.switchChannel(channel)
                },
                onClose = { closeDrawer() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 选集抽屉（追加需求：播放界面直接看全剧集、点按换集，免回详情页）
        val playingRequest = request
        if (episodeOpen && playingRequest != null && playingRequest.episodes.size > 1) {
            EpisodeDrawer(
                episodes = playingRequest.episodes,
                currentUrl = playingRequest.url,
                preload = preloadProgress,
                onSwitch = { episode ->
                    seamlessSwitch = true
                    engine.play(
                        playingRequest.copy(
                            url = episode.url,
                            subtitle = episode.subtitle ?: episode.label,
                            episodeLabel = episode.label,
                            historyTargetId = episode.historyTargetId ?: playingRequest.historyTargetId,
                            startPositionMs = episode.startPositionMs,
                            backupUrls = episode.backups,
                        ),
                    )
                    episodeOpen = false
                    poke()
                },
                onClose = { episodeOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 连播倒计时（设置 → 播放：连播倒计时；0=关闭）：提示下一集 + 点按快捷连播
        countdownSecondsLeft?.let { seconds ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(top = 52.dp, end = 14.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$seconds 秒后播放下一集",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "播放下一集",
                        color = colors.highlight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { stepEpisode(1) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 全屏底部迷你进度条（设置 → 播放界面可关；直播/无时长不显示；控制层显示时也不干扰）
        if (miniProgressEnabled && (isTv || fullscreen) && state.durationMs > 0) {
            val miniFraction = (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(miniFraction)
                        .height(3.dp)
                        .background(colors.highlight),
                )
            }
        }

        if (notice != null) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.dialog)
                        .border(1.dp, colors.divider, RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(text = notice ?: "", color = colors.error, fontSize = 16.sp)
                }
            }
        }

        openMenu?.let { menu ->
            MenuDialog(
                menu = menu,
                state = state,
                request = request,
                onSelectSpeed = { speed ->
                    engine.setSpeed(speed)
                    openMenu = null
                    poke()
                },
                onSelectSubtitle = { index ->
                    engine.selectSubtitleTrack(index)
                    openMenu = null
                    poke()
                },
                onAddSubtitle = {
                    openMenu = null
                    showAddSubtitle = true
                },
                onSelectAudio = { index ->
                    engine.selectAudioTrack(index)
                    openMenu = null
                    poke()
                },
                onSelectAspect = { mode ->
                    engine.setAspectRatio(mode)
                    openMenu = null
                    poke()
                },
                onSelectBackup = { index ->
                    engine.switchBackup(index)
                    openMenu = null
                    poke()
                },
                onDismiss = {
                    openMenu = null
                    poke()
                },
            )
        }

        if (showAddSubtitle) {
            InputDialog(
                title = "添加网络字幕",
                label = "字幕地址",
                placeholder = "https://example.com/subtitle.srt",
                confirmText = "加载",
                onDismiss = { showAddSubtitle = false },
                onDone = { value ->
                    val url = value.trim()
                    if (StringUtils.isHttpUrl(url)) {
                        engine.addSubtitleTrack(url, "网络字幕")
                    } else {
                        notice = "字幕地址无效，请输入 http(s) 链接"
                    }
                    showAddSubtitle = false
                    poke()
                },
            )
        }
    }
}

/* ================================ 视频画面（画面比例落地） ================================ */

/**
 * 画面比例（需求 5.5.4）实现方式：
 * - FIT/FILL/ZOOM 直接映射 [PlayerView] 的 RESIZE_MODE_FIT/FILL/ZOOM
 * - 强制 16:9 / 4:3：视频容器按目标比例居中，PlayerView 使用 RESIZE_MODE_FILL 拉伸铺满，
 *   由 SurfaceView 布局缩放实现（不依赖 View 变换矩阵，API21+ 可靠生效）
 * - 原始比例：全屏 + RESIZE_MODE_FIT（按片源原始比例留黑边）
 */
@Composable
private fun VideoSurface(
    player: Player?,
    aspectRatio: AspectRatioMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerView = remember(context) {
        PlayerView(context).apply {
            setUseController(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(true)
        }
    }
    DisposableEffect(playerView) {
        onDispose { playerView.setPlayer(null) }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val forcedRatio = when (aspectRatio) {
            AspectRatioMode.RATIO_16_9 -> 16f / 9f
            AspectRatioMode.RATIO_4_3 -> 4f / 3f
            else -> null
        }
        val resizeMode = when (aspectRatio) {
            AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioMode.RATIO_16_9, AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioMode.RATIO_ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val containerModifier = if (forcedRatio != null) {
            val screenW = maxWidth.value
            val screenH = maxHeight.value
            val boxW: Float
            val boxH: Float
            if (screenW / screenH > forcedRatio) {
                boxH = screenH
                boxW = screenH * forcedRatio
            } else {
                boxW = screenW
                boxH = screenW / forcedRatio
            }
            Modifier.size(Dp(boxW), Dp(boxH))
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { playerView },
                update = { view ->
                    view.setPlayer(player)
                    view.setResizeMode(resizeMode)
                },
                onRelease = { it.setPlayer(null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/* ================================ 控制层 ================================ */

/** 顶部：标题/副标题 + 清晰度徽标 + 网速 + 弱网提示 + 备用源角标 */
@Composable
private fun TopInfoBar(state: PlaybackState, request: PlaybackRequest?) {
    val colors = PureTvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.scrim)
            .padding(horizontal = 36.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request?.title ?: "PureTV 播放",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (!request?.subtitle.isNullOrBlank()) {
                Text(
                    text = request?.subtitle ?: "",
                    color = colors.textTertiary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        state.qualityLabel?.let { label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.primary)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(text = label, color = Color.White, fontSize = 14.sp)
            }
        }
        if (state.networkSpeedBps > 0) {
            Text(
                text = formatSpeed(state.networkSpeedBps),
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (state.backupIndex > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.card)
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(text = "备用源${state.backupIndex}", color = colors.textSecondary, fontSize = 14.sp)
            }
        }
        if (state.weakNetwork) {
            Text(
                text = "网络波动 · 智能缓冲中",
                color = colors.warning,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** 中部：点播/本地为进度与 seek 提示；直播为当前节目与换台提示 */
@Composable
private fun MiddleInfo(
    state: PlaybackState,
    request: PlaybackRequest?,
    currentProgram: EpgProgramEntity?,
) {
    val colors = PureTvTheme.colors
    val live = request?.mode == PlaybackMode.LIVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.scrim)
            .padding(horizontal = 36.dp, vertical = 14.dp),
    ) {
        if (live) {
            currentProgram?.let { program ->
                Text(
                    text = "当前节目：${program.title}（${TimeUtils.formatClock(program.startAt)} - ${TimeUtils.formatClock(program.endAt)}）",
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = "上下键换台 · OK 显示操作",
                color = colors.textTertiary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = if (currentProgram != null) 6.dp else 0.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TimeUtils.formatDuration(state.positionMs),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                )
                val percent = if (state.durationMs > 0) {
                    ((state.positionMs * 100) / state.durationMs).toInt().coerceIn(0, 100)
                } else 0
                TvLinearProgress(
                    percent = percent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                    progressColor = if (state.weakNetwork) colors.warning else colors.primary,
                )
                Text(
                    text = if (state.durationMs > 0) TimeUtils.formatDuration(state.durationMs) else "--:--",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                )
            }
            Text(
                text = "左右键快退/快进 10 秒",
                color = colors.textTertiary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 底部工具栏：播放/暂停、倍速、字幕、音轨、画面比例、备用源、停止（焦点依次可达） */
@Composable
private fun PlaybackToolbar(
    state: PlaybackState,
    request: PlaybackRequest?,
    preload: Map<String, Int>,
    onPlayPause: () -> Unit,
    onMenu: (PlaybackMenu) -> Unit,
    onOpenEpisodes: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onFloat: () -> Unit,
    onStop: () -> Unit,
    onToolbarFocus: (Boolean) -> Unit,
) {
    val colors = PureTvTheme.colors
    val speedLabel = when (state.speed) {
        1.0f -> "倍速"
        else -> "倍速 ${trimSpeed(state.speed)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.scrim)
            .padding(horizontal = 36.dp, vertical = 16.dp)
            .onFocusChanged { onToolbarFocus(it.hasFocus) },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvButton(
            text = if (state.isPlaying) "暂停" else "播放",
            onClick = onPlayPause,
            variant = TvButtonVariant.SECONDARY,
            padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        )
        TvButton(
            text = speedLabel,
            onClick = { onMenu(PlaybackMenu.SPEED) },
            variant = TvButtonVariant.SECONDARY,
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        TvButton(
            text = "字幕",
            onClick = { onMenu(PlaybackMenu.SUBTITLE) },
            variant = TvButtonVariant.SECONDARY,
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        TvButton(
            text = "音轨",
            onClick = { onMenu(PlaybackMenu.AUDIO) },
            variant = TvButtonVariant.SECONDARY,
            enabled = state.audioTracks.isNotEmpty(),
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        TvButton(
            text = "比例",
            onClick = { onMenu(PlaybackMenu.ASPECT) },
            variant = TvButtonVariant.SECONDARY,
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        val episodes = request?.episodes ?: emptyList()
        if (episodes.size > 1) {
            val episodeIndex = episodes.indexOfFirst { it.url == request?.url }
            TvButton(
                text = "上一集",
                onClick = onPrevEpisode,
                variant = TvButtonVariant.SECONDARY,
                enabled = episodeIndex > 0,
                padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            )
            TvButton(
                text = "下一集",
                onClick = onNextEpisode,
                variant = TvButtonVariant.SECONDARY,
                enabled = episodeIndex >= 0 && episodeIndex < episodes.lastIndex,
                padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            )
            TvButton(
                text = run {
                    val bufferedCount = episodes.count { ep -> (preload[ep.url] ?: 0) >= 100 }
                    if (bufferedCount > 0) {
                        "选集 ${episodes.size}（已缓冲 $bufferedCount）"
                    } else {
                        "选集 ${episodes.size}"
                    }
                },
                onClick = onOpenEpisodes,
                variant = TvButtonVariant.SECONDARY,
                padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        TvButton(
            text = "备用源",
            onClick = { onMenu(PlaybackMenu.BACKUP) },
            variant = TvButtonVariant.SECONDARY,
            enabled = !request?.backupUrls.isNullOrEmpty(),
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        TvButton(
            text = "弹窗",
            onClick = onFloat,
            variant = TvButtonVariant.SECONDARY,
            padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        TvButton(
            text = "停止",
            onClick = onStop,
            variant = TvButtonVariant.PRIMARY,
            containerColor = colors.error,
            padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

/* ================================ 手机竖屏播放布局（手机优先） ================================ */

/**
 * 手机竖屏播放页（参照主流手机视频 App，追加需求：点播放不再直接横屏）：
 * - 视频 16:9 置顶，点按画面播放/暂停，暂停时中央大播放按钮提示；
 * - 画面下方常驻控制区：标题/清晰度 → 分割线 → 进度条 → 圆形主操作行 → 功能胶囊行（自动换行），
 *   在剩余空间内垂直居中，上下留白均匀；
 * - 「选集」入口：全剧集抽屉内点按换集（多季显示 S·第N集，当前集高亮，免回详情页）；
 * - 点「全屏」转横屏沉浸播放（覆盖式控制层 [PhonePlayerControls]），系统返回键先退回竖屏。
 * 缓冲/错误/频道/选集抽屉浮层由外层统一渲染，两种形态均生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhonePortraitPlayer(
    player: Player?,
    state: PlaybackState,
    request: PlaybackRequest?,
    preload: Map<String, Int>,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit,
    onMenu: (PlaybackMenu) -> Unit,
    onOpenChannels: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onFullscreen: () -> Unit,
    onFloat: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val isLive = request?.mode == PlaybackMode.LIVE
    val seekable = !isLive && state.durationMs > 0
    var draggingPosition by remember { mutableStateOf<Long?>(null) }
    val speedLabel = if (state.speed == 1.0f) "倍速" else "倍速 ${trimSpeed(state.speed)}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        // 视频区（16:9）：点按播放/暂停
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            VideoSurface(
                player = player,
                aspectRatio = state.aspectRatio,
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.isPlaying && !state.isBuffering && state.errorMessage == null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }

        // 控制区：紧贴画面下方排布，剩余空白留在底部（内容超高时可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request?.title ?: "PureTV 播放",
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (!request?.subtitle.isNullOrBlank()) {
                        Text(
                            text = request?.subtitle ?: "",
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                state.qualityLabel?.let { label ->
                    Text(
                        text = label,
                        color = colors.highlight,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // 分割线：标题区与播放控制区分层，视觉更清晰
            HorizontalDivider(
                color = colors.divider,
                thickness = 1.dp,
                modifier = Modifier.padding(top = 12.dp),
            )

            // 进度条（可拖动）；直播显示徽标行
            if (seekable) {
                val position = (draggingPosition ?: state.positionMs).coerceIn(0L, state.durationMs)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        TimeUtils.formatDuration(position),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    SeekBar(
                        positionMs = position,
                        durationMs = state.durationMs,
                        bufferedMs = state.bufferedMs,
                        onScrubPreview = { target ->
                            onInteract()
                            draggingPosition = target
                        },
                        onScrubCommit = { target ->
                            onSeek(target.coerceIn(0L, state.durationMs))
                            draggingPosition = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        trackColor = colors.divider,
                        bufferedColor = colors.highlight.copy(alpha = 0.35f),
                        activeColor = colors.highlight,
                        thumbColor = colors.highlight,
                    )
                    Text(
                        TimeUtils.formatDuration(state.durationMs),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        text = if (isLive) "● 直播" else "--:--",
                        color = if (isLive) colors.error else colors.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (!state.qualityLabel.isNullOrBlank()) {
                        Text(
                            text = state.qualityLabel ?: "",
                            color = colors.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 主操作行：上一集 / 快退 / 播放 / 快进 / 下一集（剧集导航置外侧；全屏移入功能胶囊行）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val episodes = request?.episodes ?: emptyList()
                val episodeIndex = episodes.indexOfFirst { it.url == request?.url }
                if (episodes.size > 1) {
                    PortraitCircleAction(
                        Icons.Filled.SkipPrevious,
                        "上一集",
                        enabled = episodeIndex > 0,
                    ) { onPrevEpisode() }
                }
                if (seekable) {
                    PortraitCircleAction(Icons.Filled.Replay10, "快退 10 秒") {
                        onInteract()
                        onSeek((state.positionMs - 10_000L).coerceAtLeast(0L))
                    }
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(colors.primary)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPlayPause,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                if (seekable) {
                    PortraitCircleAction(Icons.Filled.Forward30, "快进 30 秒") {
                        onInteract()
                        val target = state.positionMs + 30_000L
                        onSeek(
                            if (state.durationMs > 0) target.coerceAtMost(state.durationMs) else target,
                        )
                    }
                }
                if (episodes.size > 1) {
                    PortraitCircleAction(
                        Icons.Filled.SkipNext,
                        "下一集",
                        enabled = episodeIndex >= 0 && episodeIndex < episodes.lastIndex,
                    ) { onNextEpisode() }
                }
            }

            // 功能胶囊行：全屏/选集/频道/倍速/字幕/音轨/比例/备用源/退出（自动换行铺满，不再横滑）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PortraitPill(icon = Icons.Filled.Fullscreen, label = "全屏") { onFullscreen() }
                // 弹窗播放（追加需求）：视频缩小为浮动小窗悬浮各界面之上继续播放
                PortraitPill(icon = Icons.Filled.PictureInPicture, label = "弹窗") { onFloat() }
                val episodeCount = request?.episodes?.size ?: 0
                if (episodeCount > 1) {
                    val bufferedCount = request?.episodes?.count { ep -> (preload[ep.url] ?: 0) >= 100 } ?: 0
                    PortraitPill(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = if (bufferedCount > 0) {
                            "选集 $episodeCount（已缓冲 $bufferedCount）"
                        } else {
                            "选集 $episodeCount"
                        },
                    ) { onOpenEpisodes() }
                }
                if (isLive) {
                    PortraitPill(icon = Icons.AutoMirrored.Filled.List, label = "频道", onClick = onOpenChannels)
                }
                PortraitPill(icon = Icons.Filled.Speed, label = speedLabel) { onMenu(PlaybackMenu.SPEED) }
                PortraitPill(
                    icon = Icons.Filled.ClosedCaption,
                    label = "字幕",
                    active = state.selectedSubtitleTrack >= 0,
                ) { onMenu(PlaybackMenu.SUBTITLE) }
                PortraitPill(
                    icon = Icons.Filled.Audiotrack,
                    label = "音轨",
                    enabled = state.audioTracks.isNotEmpty(),
                ) { onMenu(PlaybackMenu.AUDIO) }
                PortraitPill(icon = Icons.Filled.AspectRatio, label = "比例") { onMenu(PlaybackMenu.ASPECT) }
                if (!request?.backupUrls.isNullOrEmpty()) {
                    PortraitPill(icon = Icons.Filled.SwapHoriz, label = "备用源") { onMenu(PlaybackMenu.BACKUP) }
                }
                PortraitPill(icon = Icons.Filled.Stop, label = "退出", onClick = onStop)
            }
        }
    }
}

/**
 * 自绘进度条（替换 Material Slider：位置指示为圆形点，杜绝「竖线」观感）：
 * 圆角轨道 + 已播主题色段 + 圆形指示点；点按即跳、拖动连续取点，松手提交。
 * 触控高度 36dp（拇指友好），视觉轨道 4dp 垂直居中。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0L,
    onScrubPreview: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    bufferedColor: Color = Color.White.copy(alpha = 0.45f),
    activeColor: Color = Color.White,
    thumbColor: Color = Color.White,
) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (durationMs > 0) {
        (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    var lastPosition by remember { mutableStateOf(positionMs) }

    fun positionOf(x: Float, width: Int): Long =
        if (durationMs <= 0L || width <= 0) 0L
        else ((x / width) * durationMs).toLong().coerceIn(0L, durationMs)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            // 点按：直接跳到该处
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val target = positionOf(offset.x, size.width)
                    lastPosition = target
                    onScrubPreview(target)
                    onScrubCommit(target)
                }
            }
            // 拖动：过程预览、松手提交
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        lastPosition = positionOf(offset.x, size.width)
                        onScrubPreview(lastPosition)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        lastPosition = positionOf(change.position.x, size.width)
                        onScrubPreview(lastPosition)
                    },
                    onDragEnd = { onScrubCommit(lastPosition) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor),
        )
        // 已缓冲段（播放点之后，颜色介于轨道与已播段之间，与未缓冲明确区分）
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedFraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(bufferedColor),
        )
        // 已播段
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(activeColor),
        )
        // 圆形位置指示点
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(13.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(thumbColor)
                    .border(2.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

/** 竖屏主行圆形描边按钮（快退/快进/全屏，48dp 触控目标） */
@Composable
private fun PortraitCircleAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.divider, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.textPrimary else colors.textHint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 竖屏功能胶囊按钮（图标 + 文字横排，触控友好）：
 * [active] 高亮主题色（如字幕已开启），[enabled=false] 置灰。
 */
@Composable
private fun PortraitPill(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val tint = when {
        !enabled -> colors.textHint
        active -> colors.highlight
        else -> colors.textSecondary
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (active) colors.primary.copy(alpha = 0.14f) else colors.card)
            .border(
                width = 1.dp,
                color = if (active) colors.highlight else colors.divider,
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/* ================================ 手机端触摸控制层（手机优先设计） ================================ */

/**
 * 手机播放控制层（参照主流手机视频 App 的触控布局，手机优先）：
 * - 顶部渐变遮罩：返回 + 标题/副标题 + 清晰度/弱网徽标
 * - 中央：快退10秒 / 大号播放暂停圆钮 / 快进30秒（点播）；直播仅播放暂停
 * - 底部渐变遮罩：可拖动进度条 + 时长 + 功能行（频道·倍速·字幕·音轨·比例·备用源·退出）
 * 点按画面空白处收起控制层；暂停时常驻不自动隐藏。
 * TV 端仍使用 [PlaybackToolbar] 的 D-pad 焦点控制层，两者按设备形态互斥。
 */
@Composable
private fun PhonePlayerControls(
    state: PlaybackState,
    request: PlaybackRequest?,
    preload: Map<String, Int>,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit,
    onMenu: (PlaybackMenu) -> Unit,
    onOpenChannels: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onFloat: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val isLive = request?.mode == PlaybackMode.LIVE
    val seekable = !isLive && state.durationMs > 0
    var draggingPosition by remember { mutableStateOf<Long?>(null) }
    val speedLabel = if (state.speed == 1.0f) "倍速" else "倍速 ${trimSpeed(state.speed)}"

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：返回 + 标题（渐变遮罩保证视频上文字可读）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.68f), Color.Transparent),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Text(
                    text = request?.title ?: "PureTV 播放",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (!request?.subtitle.isNullOrBlank()) {
                    Text(
                        text = request?.subtitle ?: "",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            state.qualityLabel?.let { label ->
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (state.weakNetwork) {
                Text(
                    text = "弱网",
                    color = colors.warning,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 中央：播放控制主按钮（触控目标 68dp）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val episodes = request?.episodes ?: emptyList()
            val episodeIndex = episodes.indexOfFirst { it.url == request?.url }
            if (episodes.size > 1) {
                PhoneCircleButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "上一集",
                    enabled = episodeIndex > 0,
                    onClick = onPrevEpisode,
                )
                Spacer(modifier = Modifier.width(24.dp))
            }
            if (seekable) {
                PhoneCircleButton(
                    icon = Icons.Filled.Replay10,
                    contentDescription = "快退 10 秒",
                    onClick = {
                        onInteract()
                        onSeek((state.positionMs - 10_000L).coerceAtLeast(0L))
                    },
                )
                Spacer(modifier = Modifier.width(52.dp))
            }
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(34.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (seekable) {
                Spacer(modifier = Modifier.width(52.dp))
                PhoneCircleButton(
                    icon = Icons.Filled.Forward30,
                    contentDescription = "快进 30 秒",
                    onClick = {
                        onInteract()
                        val target = state.positionMs + 30_000L
                        onSeek(
                            if (state.durationMs > 0) target.coerceAtMost(state.durationMs) else target,
                        )
                    },
                )
            }
            if (episodes.size > 1) {
                Spacer(modifier = Modifier.width(24.dp))
                PhoneCircleButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "下一集",
                    enabled = episodeIndex >= 0 && episodeIndex < episodes.lastIndex,
                    onClick = onNextEpisode,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 底部：进度条 + 功能行（渐变遮罩）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (seekable) {
                val position = (draggingPosition ?: state.positionMs).coerceIn(0L, state.durationMs)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TimeUtils.formatDuration(position), color = Color.White, fontSize = 12.sp)
                    SeekBar(
                        positionMs = position,
                        durationMs = state.durationMs,
                        bufferedMs = state.bufferedMs,
                        onScrubPreview = { target ->
                            onInteract()
                            draggingPosition = target
                        },
                        onScrubCommit = { target ->
                            onSeek(target.coerceIn(0L, state.durationMs))
                            draggingPosition = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        trackColor = Color.White.copy(alpha = 0.28f),
                        bufferedColor = Color.White.copy(alpha = 0.5f),
                        activeColor = colors.highlight,
                        thumbColor = Color.White,
                    )
                    Text(
                        TimeUtils.formatDuration(state.durationMs),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isLive) "● 直播" else "--:--",
                        color = if (isLive) colors.error else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (!state.qualityLabel.isNullOrBlank()) {
                        Text(
                            text = state.qualityLabel ?: "",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if ((request?.episodes?.size ?: 0) > 1) {
                    val epCount = request?.episodes?.size ?: 0
                    val bufferedCount = request?.episodes?.count { ep -> (preload[ep.url] ?: 0) >= 100 } ?: 0
                    PhoneControlAction(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = if (bufferedCount > 0) "选集 $epCount·缓 $bufferedCount" else "选集 $epCount",
                        onClick = onOpenEpisodes,
                    )
                }
                if (isLive) {
                    PhoneControlAction(
                        icon = Icons.AutoMirrored.Filled.List,
                        label = "频道",
                        onClick = onOpenChannels,
                    )
                }
                PhoneControlAction(
                    icon = Icons.Filled.Speed,
                    label = speedLabel,
                    onClick = { onMenu(PlaybackMenu.SPEED) },
                )
                PhoneControlAction(
                    icon = Icons.Filled.ClosedCaption,
                    label = "字幕",
                    onClick = { onMenu(PlaybackMenu.SUBTITLE) },
                )
                PhoneControlAction(
                    icon = Icons.Filled.Audiotrack,
                    label = "音轨",
                    enabled = state.audioTracks.isNotEmpty(),
                    onClick = { onMenu(PlaybackMenu.AUDIO) },
                )
                PhoneControlAction(
                    icon = Icons.Filled.AspectRatio,
                    label = "比例",
                    onClick = { onMenu(PlaybackMenu.ASPECT) },
                )
                if (!request?.backupUrls.isNullOrEmpty()) {
                    PhoneControlAction(
                        icon = Icons.Filled.SwapHoriz,
                        label = "备用源",
                        onClick = { onMenu(PlaybackMenu.BACKUP) },
                    )
                }
                // 弹窗播放（追加需求）：视频缩小为浮动小窗悬浮各界面之上继续播放
                PhoneControlAction(
                    icon = Icons.Filled.PictureInPicture,
                    label = "弹窗",
                    onClick = onFloat,
                )
                PhoneControlAction(icon = Icons.Filled.Stop, label = "退出", onClick = onStop)
                // 横屏全屏层专用：退回竖屏（手机端 onBack 即「退出全屏」）
                PhoneControlAction(
                    icon = Icons.Filled.FullscreenExit,
                    label = "退出全屏",
                    onClick = onBack,
                )
            }
        }
    }
}

/** 手机控制层圆形图标钮（快退/快进，触控目标 52dp） */
@Composable
private fun PhoneCircleButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** 手机控制层功能项（图标圆底 + 文字，如「字幕」「音轨」） */
@Composable
private fun PhoneControlAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 缓冲中：中央提示（warning 色） */
@Composable
private fun BufferingOverlay() {
    val colors = PureTvTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(colors.scrim)
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = colors.warning,
                strokeWidth = 3.dp,
            )
            Text(
                text = "缓冲中…",
                color = colors.warning,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** 错误浮层（红色 + 重试/切换备用源）或切换备用源提示（warning 色） */
@Composable
private fun ErrorOrNotice(
    message: String,
    request: PlaybackRequest?,
    backupIndex: Int,
    onRetry: () -> Unit,
    onSwitchBackup: () -> Unit,
) {
    val colors = PureTvTheme.colors
    if (message == PlaybackEngineImpl.MSG_BACKUP_SWITCHED) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.dialog)
                    .border(1.dp, colors.warning, RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(text = message, color = colors.warning, fontSize = 16.sp)
            }
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.dialog)
                .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
                .padding(horizontal = 40.dp, vertical = 30.dp),
        ) {
            Text(
                text = message,
                color = colors.error,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(460.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                TvButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.focusOnLaunch(),
                )
                val hasBackup = !request?.backupUrls.isNullOrEmpty()
                TvButton(
                    text = "切换备用源",
                    onClick = onSwitchBackup,
                    variant = TvButtonVariant.SECONDARY,
                    enabled = hasBackup,
                )
            }
        }
    }
}

/* ================================ 选集抽屉（剧集换集） ================================ */

/**
 * 选集抽屉（追加需求：播放界面看不到播放列表）：
 * - 底部上滑面板，全剧集按网格平铺（多季显示 S·第N集），当前集高亮；
 * - 触屏点按直接换集（保留断点续播位置），TV 端焦点可定位当前集用 OK 换集；
 * - 点击遮罩/关闭按钮/系统返回收起。
 */
@Composable
private fun EpisodeDrawer(
    episodes: List<PlaybackEpisode>,
    currentUrl: String,
    preload: Map<String, Int> = emptyMap(),
    onSwitch: (PlaybackEpisode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    val multiSeason = episodes.map { it.season }.distinct().size > 1
    val focusIndex = episodes.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 } ?: 0
    val currentFocus = remember { FocusRequester() }

    // 打开后焦点定位到当前集（TV 端 D-pad 可直接 OK 换集/返回收起）
    LaunchedEffect(episodes.size, currentUrl) {
        if (episodes.isEmpty()) return@LaunchedEffect
        delay(80)
        runCatching { currentFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.card)
                // 消费面板区域点击，避免误触遮罩关闭
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "选集",
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = " · 共 ${episodes.size} 集",
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (preload.count { it.value >= 100 } > 0) {
                    Text(
                        text = " · 已缓冲 ${preload.count { it.value >= 100 }} 集",
                        color = colors.success,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭选集",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 92.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = episodes.size,
                    key = { index -> "s${episodes[index].season}-${episodes[index].label}-$index" },
                ) { index ->
                    val episode = episodes[index]
                    val isCurrent = episode.url == currentUrl
                    val shape = RoundedCornerShape(11.dp)
                    Box(
                        modifier = Modifier
                            .then(
                                if (index == focusIndex) {
                                    Modifier.focusRequester(currentFocus)
                                } else {
                                    Modifier
                                },
                            )
                            .focusable()
                            .clip(shape)
                            .background(
                                if (isCurrent) colors.primary.copy(alpha = 0.16f)
                                else colors.background,
                            )
                            .border(
                                width = 1.dp,
                                color = if (isCurrent) colors.highlight else colors.divider,
                                shape = shape,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSwitch(episode) },
                            )
                            .padding(vertical = 11.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (multiSeason) {
                                    "S${episode.season}·${episode.label}"
                                } else {
                                    episode.label
                                },
                                color = if (isCurrent) colors.highlight else colors.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                            )
                            // 跨集预缓冲徽标：缓冲中显示百分比，完整写入显示「已缓冲」
                            preload[episode.url]?.let { pct ->
                                Text(
                                    text = if (pct >= 100) "已缓冲" else "缓冲 $pct%",
                                    color = if (pct >= 100) colors.success else colors.warning,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================ 频道抽屉（直播换台） ================================ */

/**
 * 左侧频道抽屉（需求 5.3.3、5.2.2 上下换台）：台标/名称/清晰度/星标，当前频道高亮，
 * OK 切换后 2 秒自动隐藏（抽屉内任意按键取消倒计时）。
 */
@Composable
private fun ChannelDrawer(
    channels: List<ChannelEntity>,
    favoriteKeys: Set<String>,
    currentKey: String,
    onSwitch: (ChannelEntity) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    var autoCloseAt by remember { mutableLongStateOf(0L) }
    val currentFocus = remember { FocusRequester() }
    // 焦点锚点：优先当前频道，列表中不存在时锚到第一项
    val focusHolderIndex = channels.indexOfFirst { "${it.sourceId}|${it.id}" == currentKey }
        .takeIf { it >= 0 } ?: 0

    // OK 切换后 2 秒自动隐藏
    LaunchedEffect(autoCloseAt) {
        val at = autoCloseAt
        if (at > 0) {
            val wait = at - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (autoCloseAt == at) onClose()
        }
    }

    // 打开后焦点定位到当前频道
    LaunchedEffect(currentKey, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        delay(80)
        runCatching { currentFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .background(colors.scrim)
            // 抽屉内任意按键取消自动隐藏倒计时
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) autoCloseAt = 0L
                false
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(400.dp)
                .background(colors.background)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "频道列表",
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "${channels.size} 个", color = colors.textTertiary, fontSize = 14.sp)
            }
            if (channels.isEmpty()) {
                EmptyState(
                    text = "暂无频道",
                    subtext = "请先在直播专区导入直播源",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> "${c.sourceId}|${c.id}" }) { index, channel ->
                        val key = "${channel.sourceId}|${channel.id}"
                        val isCurrent = key == currentKey
                        val isFavorite = "channel|$key" in favoriteKeys ||
                            "channel|${channel.id}" in favoriteKeys
                        TvCard(
                            onClick = {
                                onSwitch(channel)
                                // OK 切换 2 秒后自动隐藏
                                autoCloseAt = System.currentTimeMillis() + DRAWER_AUTO_CLOSE_MS
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .then(
                                    if (index == focusHolderIndex) Modifier.focusRequester(currentFocus)
                                    else Modifier,
                                ),
                            shape = RoundedCornerShape(10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = channel.logo,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.card),
                                    contentScale = ContentScale.Crop,
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp),
                                ) {
                                    Text(
                                        text = channel.name,
                                        color = colors.textPrimary,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            channel.groupName,
                                            channel.quality,
                                        ).joinToString(" · "),
                                        color = colors.textTertiary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                    )
                                }
                                if (isCurrent) {
                                    Text(
                                        text = "播放中",
                                        color = colors.success,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(end = 10.dp),
                                    )
                                }
                                if (isFavorite) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "已收藏",
                                        tint = colors.warning,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================ 功能菜单 ================================ */

@Composable
private fun MenuDialog(
    menu: PlaybackMenu,
    state: PlaybackState,
    request: PlaybackRequest?,
    onSelectSpeed: (Float) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onAddSubtitle: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectAspect: (AspectRatioMode) -> Unit,
    onSelectBackup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
    val aspects = listOf(
        AspectRatioMode.FIT to "适应",
        AspectRatioMode.FILL to "填满",
        AspectRatioMode.ZOOM to "放大",
        AspectRatioMode.RATIO_16_9 to "16:9",
        AspectRatioMode.RATIO_4_3 to "4:3",
        AspectRatioMode.RATIO_ORIGINAL to "原始比例",
    )

    when (menu) {
        PlaybackMenu.SPEED -> OptionListDialog(
            title = "播放倍速",
            options = speeds.map {
                OptionItem("${trimSpeed(it)}x", abs(state.speed - it) < 0.01f)
            },
            onSelect = { onSelectSpeed(speeds[it]) },
            onDismiss = onDismiss,
        )

        PlaybackMenu.SUBTITLE -> {
            val tracks = state.subtitleTracks
            val options = ArrayList<OptionItem>(tracks.size + 2)
            options += OptionItem("关闭字幕", state.selectedSubtitleTrack < 0)
            tracks.forEach { options += OptionItem(it.label, it.index == state.selectedSubtitleTrack) }
            options += OptionItem("添加网络字幕…")
            OptionListDialog(
                title = "字幕",
                options = options,
                onSelect = { index ->
                    when {
                        index == 0 -> onSelectSubtitle(-1)
                        index <= tracks.size -> onSelectSubtitle(tracks[index - 1].index)
                        else -> onAddSubtitle()
                    }
                },
                onDismiss = onDismiss,
            )
        }

        PlaybackMenu.AUDIO -> {
            val tracks = state.audioTracks
            OptionListDialog(
                title = "音轨",
                options = tracks.map {
                    OptionItem(it.label, it.index == state.selectedAudioTrack)
                },
                onSelect = { onSelectAudio(tracks[it].index) },
                onDismiss = onDismiss,
            )
        }

        PlaybackMenu.ASPECT -> OptionListDialog(
            title = "画面比例",
            options = aspects.map { OptionItem(it.second, state.aspectRatio == it.first) },
            onSelect = { onSelectAspect(aspects[it].first) },
            onDismiss = onDismiss,
        )

        PlaybackMenu.BACKUP -> {
            val backups = request?.backupUrls.orEmpty()
            val options = buildList {
                add(OptionItem("本源", state.backupIndex == 0))
                backups.forEachIndexed { index, _ ->
                    add(OptionItem("备用${index + 1}", state.backupIndex == index + 1))
                }
            }
            OptionListDialog(
                title = "备用源",
                options = options,
                onSelect = { onSelectBackup(it) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun OptionListDialog(
    title: String,
    options: List<OptionItem>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PureTvTheme.colors
    TvDialog(onDismiss = onDismiss, title = title, maxWidth = 520.dp) {
        if (options.isEmpty()) {
            EmptyState(text = "暂无可选项", modifier = Modifier.fillMaxWidth())
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    TvButton(
                        text = if (option.selected) "✓  ${option.label}" else option.label,
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (option.selected) TvButtonVariant.PRIMARY else TvButtonVariant.SECONDARY,
                        padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/* ================================ 工具 ================================ */

private fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond >= 1_048_576L) {
        String.format("%.1f MB/s", bytesPerSecond / 1_048_576.0)
    } else {
        "${bytesPerSecond / 1024L} KB/s"
    }

private fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}" else "$speed"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
