package com.puretv.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import com.puretv.player.data.local.dao.ChannelSettingDao
import com.puretv.player.data.local.entity.ChannelSettingEntity
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.BufferProfile
import com.puretv.player.data.prefs.SettingsRepository
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放内核实现（需求 5.5 播放内核性能需求，基于 Media3 ExoPlayer）：
 *
 * - 5.5.1 格式兼容：MP4/MKV/TS/FLV 走默认 Extractors，HLS/DASH 走已依赖模块，自定义请求头走 OkHttp
 * - 5.5.2 4K/HEVC 硬解：硬解优先的 MediaCodecSelector（见 [PlaybackRenderersFactory]）+ 解码回退
 * - 5.5.3 智能缓冲/弱网：按设置档位配置 [DefaultLoadControl]，弱网降清晰度、卡顿自动切换备用源
 * - 5.5.4 倍速、字幕、音轨、画面比例（比例由播放页 PlayerView 落地）
 * - 5.4.3/5.2.4 断点续播与播放记录：每 5 秒及停止/出错时写入 HistoryRepository，频道清晰度记忆写入 ChannelSettingDao
 * - 5.1/6.1 低配长期运行：播放器懒创建、退出与后台完整释放，全部监听与协程随播放器注销，不持有 Activity
 */
@Singleton
class PlaybackEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val channelSettingDao: ChannelSettingDao,
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlaybackEngine, PlayerProvider {

    private val _currentRequest = MutableStateFlow<PlaybackRequest?>(null)
    override val currentRequest: StateFlow<PlaybackRequest?> = _currentRequest.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 跨集预缓冲进度（播放地址 → 百分比；选集/分集列表徽标实时刷新） */
    private val _preloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val preloadProgress: StateFlow<Map<String, Int>> = _preloadProgress.asStateFlow()

    private var preloadJob: Job? = null

    /** 统一网络数据源：默认携带当前播放任务的自定义请求头（UA/Referer 等） */
    private val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

    /** 共享播放磁盘缓存：上限可在设置调整（默认 4GB），超过自动淘汰最久未用分片 */
    private val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "puretv_media"),
            LeastRecentlyUsedCacheEvictor(cacheMaxBytes()),
            StandaloneDatabaseProvider(context),
        )
    }

    /** 基础数据源（本地文件 + 网络） */
    private val baseDataSourceFactory: DefaultDataSource.Factory by lazy {
        DefaultDataSource.Factory(context, okHttpDataSourceFactory)
    }

    /** 预缓冲缓存数据源工厂（不经键记录：预缓冲键由 preloadDirect 按集登记） */
    private val cacheFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(baseDataSourceFactory)
    }

    /* ---- 换集缓存回收：登记每集经缓存的键，换集时删除上一集，避免长期累积占满存储 ---- */
    private val cacheKeyLock = Any()
    private val playbackCacheKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val preloadCacheKeys = mutableMapOf<String, MutableSet<String>>()

    /** 播放专用缓存工厂：边播边记键（预缓冲走 [cacheFactory]，避免把后续集的键误记到本集） */
    private val playerCacheFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(baseDataSourceFactory)
            .setCacheKeyFactory { dataSpec ->
                val key = CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
                playbackCacheKeys.add(key)
                key
            }
    }

    /** 内核作用域（主线程，ExoPlayer 必须在同一线程创建与调用） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var bandwidthListener: BandwidthMeter.EventListener? = null
    private var bandwidthHandler: Handler? = null
    private var playerListener: Player.Listener? = null
    private var statusJob: Job? = null
    private var playerConfig: PlayerConfig? = null

    private var latestSettings: AppSettings = AppSettings()

    // ---- 当前播放任务的卡顿/备用源状态 ----
    private var backupIndex = 0
    private val attemptedSources = mutableSetOf(0)
    private var stallCount = 0
    private var bufferingSinceMs = 0L
    private var stallCountedInEpisode = false
    private var healthySinceMs = 0L
    private var stableSinceMs = 0L
    private var qualityCapped = false
    private var lastHistoryAt = 0L
    private var networkSpeedBps = 0L
    private var lastSpeedSampleAt = 0L
    private var pendingSubtitleLabel: String? = null
    private var audioSelections: List<Pair<Int, Int>> = emptyList()
    private var subtitleSelections: List<Pair<Int, Int>> = emptyList()
    private var extraSubtitles: List<MediaItem.SubtitleConfiguration> = emptyList()

    init {
        scope.launch {
            settingsRepository.settings.collect { latestSettings = it }
        }
    }

    /* ================================ 对外接口 ================================ */

    override fun play(request: PlaybackRequest) {
        scope.launch { doPlay(request) }
    }

    override fun playPause() {
        scope.launch {
            val p = exoPlayer ?: return@launch
            if (p.isPlaying) p.playWhenReady = false else p.playWhenReady = true
        }
    }

    override fun pause() {
        scope.launch { exoPlayer?.playWhenReady = false }
    }

    override fun resume() {
        scope.launch { exoPlayer?.playWhenReady = true }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch {
            val p = exoPlayer ?: return@launch
            val duration = p.duration
            val target = if (duration > 0) positionMs.coerceIn(0L, duration)
            else positionMs.coerceAtLeast(0L)
            p.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    override fun setSpeed(speed: Float) {
        scope.launch {
            exoPlayer?.setPlaybackParameters(PlaybackParameters(speed))
            _state.value = _state.value.copy(speed = speed)
        }
    }

    override fun mute(muted: Boolean) {
        scope.launch {
            exoPlayer?.setVolume(if (muted) 0f else 1f)
            _state.value = _state.value.copy(muted = muted)
        }
    }

    /** 画面比例仅记录状态，播放页映射到 PlayerView.resizeMode 与视频容器比例 */
    override fun setAspectRatio(mode: AspectRatioMode) {
        _state.value = _state.value.copy(aspectRatio = mode)
    }

    override fun switchBackup(index: Int) {
        scope.launch {
            val req = _currentRequest.value ?: return@launch
            val total = 1 + req.backupUrls.size
            if (index !in 0 until total) return@launch
            attemptedSources.clear()
            switchToSource(index, notice = null)
        }
    }

    override fun selectAudioTrack(index: Int) {
        scope.launch { applyTrackSelection(C.TRACK_TYPE_AUDIO, index, audioSelections) }
    }

    override fun selectSubtitleTrack(index: Int) {
        scope.launch {
            val ts = trackSelector ?: return@launch
            if (index < 0) {
                ts.setParameters(
                    ts.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true),
                )
                _state.value = _state.value.copy(selectedSubtitleTrack = -1)
            } else {
                applyTrackSelection(C.TRACK_TYPE_TEXT, index, subtitleSelections)
            }
        }
    }

    /** 添加外挂字幕：按扩展名判定 mimeType，重建带 SubtitleConfiguration 的 MediaItem 并保持播放位置 */
    override fun addSubtitleTrack(url: String, label: String) {
        scope.launch {
            val p = exoPlayer ?: return@launch
            val req = _currentRequest.value ?: return@launch
            val current = p.currentMediaItem ?: return@launch
            val mime = mimeTypeForSubtitle(url) ?: return@launch
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url.trim()))
                .setMimeType(mime)
                .setLabel(label)
                .build()
            extraSubtitles = extraSubtitles.filterNot { it.label == label } + config
            pendingSubtitleLabel = label
            val position = if (req.mode == PlaybackMode.LIVE) C.TIME_UNSET else p.currentPosition
            p.setMediaItem(
                current.buildUpon().setSubtitleConfigurations(extraSubtitles).build(),
                position,
            )
            trackSelector?.let { ts ->
                ts.setParameters(ts.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false))
            }
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** 停止播放：写记录并释放界面占用，保留单例播放器（下次 play 复用） */
    override fun stop() {
        scope.launch { doStop() }
    }

    /** 释放内核：注销全部监听与协程并释放 ExoPlayer（退出播放页/应用后台回收时调用） */
    override fun release() {
        scope.launch { doRelease() }
    }

    override val player: Player?
        get() = exoPlayer

    /* ================================ 播放流程 ================================ */

    private fun doPlay(request: PlaybackRequest) {
        // 切换播放前保存上一条记录
        val previous = _currentRequest.value
        if (previous != null && previous != request) persistHistory(previous)
        // 换集/换内容：回收上一集缓存（快照键后延时删除，不阻塞切换）
        if (previous != null && previous.url != request.url &&
            previous.mode == PlaybackMode.VOD && request.mode == PlaybackMode.VOD
        ) {
            recycleEpisodeCache(previous.url)
        }

        _currentRequest.value = request
        backupIndex = 0
        attemptedSources.clear()
        attemptedSources.add(0)
        stallCount = 0
        bufferingSinceMs = 0L
        stallCountedInEpisode = false
        healthySinceMs = 0L
        stableSinceMs = 0L
        qualityCapped = false
        lastHistoryAt = System.currentTimeMillis()
        networkSpeedBps = 0L
        lastSpeedSampleAt = 0L
        pendingSubtitleLabel = null
        extraSubtitles = emptyList()
        audioSelections = emptyList()
        subtitleSelections = emptyList()

        val previousState = _state.value
        // 换集/重试保持当前倍速（勿回 1 倍）；首次进入本播放会话取设置默认倍速
        val speed = if (previous != null) {
            previousState.speed.takeIf { it > 0f } ?: latestSettings.defaultSpeed
        } else {
            latestSettings.defaultSpeed
        }
        _state.value = PlaybackState(
            speed = speed,
            muted = previousState.muted,
            aspectRatio = previousState.aspectRatio,
        )

        val p = ensurePlayer()
        // 自定义请求头（部分源需要 UA/Referer）；默认补浏览器 UA——
        // 部分采集站对 ExoPlayer 默认 UA 直接 403 拒绝，导致「播放失败」
        okHttpDataSourceFactory.setDefaultRequestProperties(
            DEFAULT_REQUEST_HEADERS + request.headers,
        )
        // 新任务从零开始限速判定，放开清晰度上限
        qualityCapped = false
        stableSinceMs = 0L
        trackSelector?.let { ts ->
            ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints())
        }
        val startPosition = if (request.mode == PlaybackMode.LIVE) C.TIME_UNSET
        else request.startPositionMs.coerceAtLeast(0L)
        p.setMediaItem(buildMediaItem(request, backupIndex = 0), startPosition)
        p.setPlaybackParameters(PlaybackParameters(speed))
        p.prepare()
        p.playWhenReady = true
        startPositionLoop()
        schedulePreload(request)
    }

    private fun doStop() {
        val request = _currentRequest.value
        persistHistory(request)
        statusJob?.cancel()
        statusJob = null
        preloadJob?.cancel()
        preloadJob = null
        val p = exoPlayer
        if (p != null) {
            p.stop()
            p.clearMediaItems()
        }
        _currentRequest.value = null
        _state.value = PlaybackState()
        backupIndex = 0
        attemptedSources.clear()
        attemptedSources.add(0)
    }

    private fun doRelease() {
        val request = _currentRequest.value
        persistHistory(request)
        preloadJob?.cancel()
        preloadJob = null
        destroyPlayer()
        // 保留 currentRequest 与进度：应用回到前台时播放页可按原进度续播
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
    }

    /** 懒创建播放器；解码/缓冲配置变化时（下次起播）重建 */
    private fun ensurePlayer(): ExoPlayer {
        val config = PlayerConfig(
            hardwareFirst = latestSettings.hardwareDecode,
            buffer = bufferDurations(latestSettings),
        )
        val existing = exoPlayer
        if (existing != null && config == playerConfig) return existing
        if (existing != null) destroyPlayer()
        return buildPlayer(config).also { exoPlayer = it; playerConfig = config }
    }

    private fun buildPlayer(config: PlayerConfig): ExoPlayer {
        val bandwidth = DefaultBandwidthMeter.Builder(context).build()
        val handler = Handler(Looper.getMainLooper())
        val listener = object : BandwidthMeter.EventListener {
            override fun onBandwidthSample(elapsedMs: Int, bytes: Long, bitrateEstimate: Long) {
                if (elapsedMs > 0) {
                    networkSpeedBps = bytes * 1000L / elapsedMs
                    lastSpeedSampleAt = System.currentTimeMillis()
                }
            }
        }
        bandwidth.addEventListener(handler, listener)
        bandwidthMeter = bandwidth
        bandwidthHandler = handler
        bandwidthListener = listener

        val selector = DefaultTrackSelector(context)
        trackSelector = selector

        val dataSourceFactory = playerCacheFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            DefaultExtractorsFactory(), // MP4/MKV/TS/FLV 默认 Extractors；.m3u8/.mpd 由已依赖模块识别
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.buffer.min,
                config.buffer.max,
                config.buffer.playback,
                config.buffer.after,
            )
            .setBackBuffer(REWIND_BUFFER_MS, false)
            .build()

        val renderersFactory = PlaybackRenderersFactory(context, config.hardwareFirst)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidth)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        val listener2 = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
                        stallCountedInEpisode = false
                        _state.value = _state.value.copy(isBuffering = true)
                    }

                    Player.STATE_READY -> {
                        bufferingSinceMs = 0L
                        _state.value = _state.value.copy(isBuffering = false)
                    }

                    Player.STATE_ENDED -> {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            isEnded = true,
                        )
                        persistHistory(_currentRequest.value)
                    }

                    else -> _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = System.currentTimeMillis()
                if (isPlaying) healthySinceMs = now
                val s = _state.value
                _state.value = s.copy(
                    isPlaying = isPlaying,
                    // 恢复播放后清除提示类错误（切换备用源提示/缓冲超时提示）；
                    // 重新起播/重播时复位结束标记（自动连播防重复用）
                    errorMessage = if (isPlaying) null else s.errorMessage,
                    isEnded = if (isPlaying) false else s.isEnded,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                stallCount += 1
                attemptedSources.add(backupIndex)
                val switched = tryAutoSwitch()
                if (switched) {
                    _state.value = _state.value.copy(isBuffering = true, stallCount = stallCount)
                } else {
                    // 全部线路连续失败才置真正的错误（红色，UI 提供重试）
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "播放失败：${friendlyError(error)}",
                        stallCount = stallCount,
                    )
                    persistHistory(_currentRequest.value)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks(tracks)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.value = _state.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    qualityLabel = qualityLabelFor(videoSize.height),
                )
            }
        }
        player.addListener(listener2)
        playerListener = listener2
        return player
    }

    private fun destroyPlayer() {
        statusJob?.cancel()
        statusJob = null
        val p = exoPlayer
        if (p != null) {
            playerListener?.let { p.removeListener(it) }
            p.stop()
            p.clearMediaItems()
            p.release()
        }
        playerListener = null
        exoPlayer = null
        trackSelector = null
        val meter = bandwidthMeter
        if (meter != null) {
            bandwidthListener?.let { meter.removeEventListener(it) }
        }
        bandwidthMeter = null
        bandwidthListener = null
        bandwidthHandler = null
        playerConfig = null
        audioSelections = emptyList()
        subtitleSelections = emptyList()
        pendingSubtitleLabel = null
    }

    private fun buildMediaItem(request: PlaybackRequest, backupIndex: Int): MediaItem {
        val url = if (backupIndex <= 0) request.url
        else request.backupUrls.getOrNull(backupIndex - 1) ?: request.url
        val builder = MediaItem.Builder().setUri(url)
        if (extraSubtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(extraSubtitles)
        }
        return builder.build()
    }

    /* ================================ 状态推送 / 弱网 / 卡顿 ================================ */

    private fun startPositionLoop() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val p = exoPlayer ?: return
        val request = _currentRequest.value ?: return
        val now = System.currentTimeMillis()
        val snapshot = _state.value

        val position = p.currentPosition
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val bufferedAhead = (p.bufferedPosition - position).coerceAtLeast(0L)
        val speedBps = currentSpeedBytes(now)

        // 弱网判定：网速低于约 1.5MB/s 或持续缓冲中（需求 5.5.3 / 5.4.4）
        val lowSpeed = speedBps in 1 until WEAK_SPEED_BYTES_PER_SEC
        val bufferingStall = snapshot.isBuffering && bufferingSinceMs > 0 &&
            now - bufferingSinceMs >= WEAK_BUFFERING_MS
        val weak = lowSpeed || bufferingStall

        // 缓冲超时（>8s 未恢复）计一次卡顿并触发自动换源判定
        if (snapshot.isBuffering && bufferingSinceMs > 0 &&
            now - bufferingSinceMs >= STALL_TIMEOUT_MS && !stallCountedInEpisode
        ) {
            stallCountedInEpisode = true
            stallCount += 1
            handleStall()
        }

        // 清晰度智能切换（需求 5.4.4）：弱网降 1080P，平稳 30s 后放开 4K
        if (weak) stableSinceMs = 0L else if (stableSinceMs == 0L) stableSinceMs = now
        if (latestSettings.autoQualitySwitch) {
            if (weak && !qualityCapped) {
                applyQualityCap()
                qualityCapped = true
            } else if (!weak && qualityCapped && now - stableSinceMs >= QUALITY_RECOVER_MS) {
                liftQualityCap()
            }
        }

        // 连续卡顿在稳定播放 30s 后清零（“连续失败”语义），并重置已尝试源
        if (p.isPlaying && !snapshot.isBuffering) {
            if (healthySinceMs == 0L) healthySinceMs = now
            else if (stallCount > 0 && now - healthySinceMs >= HEALTHY_RESET_MS) {
                stallCount = 0
                attemptedSources.clear()
                attemptedSources.add(backupIndex)
            }
        } else {
            healthySinceMs = 0L
        }

        _state.value = _state.value.copy(
            positionMs = position,
            durationMs = duration,
            bufferedMs = bufferedAhead,
            speed = p.playbackParameters.speed,
            networkSpeedBps = speedBps,
            weakNetwork = weak,
            stallCount = stallCount,
            backupIndex = backupIndex,
        )

        // 播放记录每 5 秒落库（需求 5.4.3 断点续播 / 5.2.4 播放记录）
        if (now - lastHistoryAt >= HISTORY_INTERVAL_MS) {
            lastHistoryAt = now
            persistHistory(request)
        }
    }

    private fun currentSpeedBytes(now: Long): Long {
        if (lastSpeedSampleAt > 0 && now - lastSpeedSampleAt <= SPEED_STALE_MS) return networkSpeedBps
        val estimateBits = bandwidthMeter?.bitrateEstimate ?: 0L
        return if (estimateBits > 0) estimateBits / 8 else networkSpeedBps
    }

    private fun handleStall() {
        attemptedSources.add(backupIndex)
        if (stallCount < STALL_SWITCH_THRESHOLD) return
        if (!tryAutoSwitch()) {
            // 全部线路均卡顿：置真正的错误，由 UI 提供重试
            _state.value = _state.value.copy(
                errorMessage = MSG_STALL_ALL_SOURCES,
                stallCount = stallCount,
            )
        }
    }

    /** 自动切换备用源（需求 5.5.3）；全部源均失败返回 false */
    private fun tryAutoSwitch(): Boolean {
        val request = _currentRequest.value ?: return false
        if (!latestSettings.autoSwitchBackupSource) return false
        if (request.backupUrls.isEmpty()) return false
        val total = 1 + request.backupUrls.size
        if (attemptedSources.size >= total) return false
        val next = (0 until total).firstOrNull { it !in attemptedSources } ?: return false
        switchToSource(next, notice = MSG_BACKUP_SWITCHED)
        return true
    }

    private fun switchToSource(index: Int, notice: String?) {
        val request = _currentRequest.value ?: return
        val p = exoPlayer ?: return
        backupIndex = index
        attemptedSources.add(index)
        bufferingSinceMs = 0L
        stallCountedInEpisode = false
        healthySinceMs = 0L
        val position = if (request.mode == PlaybackMode.LIVE) C.TIME_UNSET
        else p.currentPosition
        p.stop()
        p.setMediaItem(buildMediaItem(request, index), position)
        p.prepare()
        p.playWhenReady = true
        _state.value = _state.value.copy(
            backupIndex = index,
            errorMessage = notice,
            isBuffering = true,
            stallCount = stallCount,
        )
    }

    private fun applyQualityCap() {
        val ts = trackSelector ?: return
        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1920, 1080))
    }

    private fun liftQualityCap() {
        val ts = trackSelector ?: return
        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(3840, 2160))
        qualityCapped = false
    }

    /* ================================ 音轨 / 字幕轨 ================================ */

    private fun refreshTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val audioSel = mutableListOf<Pair<Int, Int>>()
        val text = mutableListOf<TrackOption>()
        val textSel = mutableListOf<Pair<Int, Int>>()
        var selectedAudio = 0
        var selectedText = 0
        var hasSelectedAudio = false
        var hasSelectedText = false

        tracks.groups.forEachIndexed { groupIndex, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val selected = group.isTrackSelected(i)
                        if (selected && !hasSelectedAudio) {
                            hasSelectedAudio = true
                            selectedAudio = audioSel.size
                        }
                        audioSel.add(groupIndex to i)
                        audio.add(
                            TrackOption(
                                index = audioSel.lastIndex,
                                label = trackLabel(format, i, group.length, "音轨"),
                                language = format.language,
                                selected = selected,
                            ),
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val selected = group.isTrackSelected(i)
                        if (selected && !hasSelectedText) {
                            hasSelectedText = true
                            selectedText = textSel.size
                        }
                        textSel.add(groupIndex to i)
                        text.add(
                            TrackOption(
                                index = textSel.lastIndex,
                                label = trackLabel(format, i, group.length, "字幕"),
                                language = format.language,
                                selected = selected,
                            ),
                        )
                    }
                }
            }
        }

        audioSelections = audioSel
        subtitleSelections = textSel

        // 新加入的外挂字幕自动选中
        val pending = pendingSubtitleLabel
        var pendingIndex = -1
        if (pending != null) {
            pendingIndex = text.indexOfFirst { it.label == pending }
            if (pendingIndex >= 0) pendingSubtitleLabel = null
        }
        val subtitleSelected = when {
            pendingIndex >= 0 -> pendingIndex
            hasSelectedText -> selectedText
            else -> -1
        }
        _state.value = _state.value.copy(
            audioTracks = audio,
            selectedAudioTrack = if (hasSelectedAudio) selectedAudio else 0,
            subtitleTracks = text,
            selectedSubtitleTrack = subtitleSelected,
        )
        if (pendingIndex >= 0) {
            applyTrackSelection(C.TRACK_TYPE_TEXT, pendingIndex, textSel)
        }
    }

    private fun applyTrackSelection(trackType: Int, index: Int, selections: List<Pair<Int, Int>>) {
        if (index !in selections.indices) return
        val p = exoPlayer ?: return
        val ts = trackSelector ?: return
        val (groupIndex, trackIndex) = selections[index]
        val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
        ts.setParameters(
            ts.buildUponParameters()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex)),
        )
        if (trackType == C.TRACK_TYPE_AUDIO) {
            _state.value = _state.value.copy(selectedAudioTrack = index)
        } else {
            _state.value = _state.value.copy(selectedSubtitleTrack = index)
        }
    }

    private fun trackLabel(format: Format, index: Int, groupLength: Int, prefix: String): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language?.takeIf { it.isNotBlank() }?.let { return it }
        return if (groupLength > 1) "$prefix ${index + 1}" else prefix
    }

    /* ================================ 播放记录 / 清晰度记忆 ================================ */

    /**
     * 写播放记录（需求 5.4.3 断点续播、5.2.4 播放记录）：
     * - 直播只记“看过”（positionMs=0, durationMs=0），不写进度
     * - 非直播在无有效进度时不覆盖已有记录
     * - channelSettingKey 存在时同步写入频道清晰度记忆（需求 5.3.3）
     */
    /** 当前设置的播放缓存上限字节（512MB..8GB，默认 4GB；缓存创建时读取，修改后重启应用生效） */
    private fun cacheMaxBytes(): Long =
        latestSettings.cacheMaxMb.coerceIn(512, 8192).toLong() * 1024L * 1024L

    /** 回收上一集缓存：快照其播放/预写全部键，等旧源释放后删除文件 */
    private fun recycleEpisodeCache(episodeUrl: String) {
        val keys = synchronized(cacheKeyLock) {
            val all = mutableSetOf<String>()
            all.addAll(playbackCacheKeys)
            playbackCacheKeys.clear()
            preloadCacheKeys.remove(episodeUrl)?.let { all.addAll(it) }
            all
        }
        if (keys.isEmpty()) return
        scope.launch(ioDispatcher) {
            delay(1_500) // 等旧 MediaSource 释放文件句柄后再删
            keys.forEach { key -> runCatching { mediaCache.removeResource(key) } }
        }
    }

    override suspend fun clearMediaCache() {
        withContext(ioDispatcher) {
            synchronized(cacheKeyLock) {
                playbackCacheKeys.clear()
                preloadCacheKeys.clear()
            }
            runCatching { mediaCache.keys.toList() }
                .getOrDefault(emptyList())
                .forEach { key -> runCatching { mediaCache.removeResource(key) } }
        }
    }

    override suspend fun cacheSizeBytes(): Long = withContext(ioDispatcher) {
        runCatching {
            mediaCache.keys.sumOf { key ->
                mediaCache.getCachedSpans(key).sumOf { span -> span.length }
            }
        }.getOrDefault(0L)
    }

    private fun persistHistory(request: PlaybackRequest?) {
        val req = request ?: return
        val type = req.historyType ?: return
        val targetId = req.historyTargetId ?: return
        val live = req.mode == PlaybackMode.LIVE
        val position: Long
        val duration: Long
        if (live) {
            position = 0L
            duration = 0L
        } else {
            val p = exoPlayer
            position = p?.currentPosition ?: 0L
            duration = p?.duration?.takeIf { it > 0 } ?: 0L
            if (position <= 0L) return
        }
        // 点播带所属资源站（选集定位用）；历史遗留记录回退从直播键推导
        val sourceId = req.sourceId
            ?: if (type == TargetType.CHANNEL) targetId.substringBefore('|') else null
        val quality = _state.value.qualityLabel ?: req.rememberedQuality
        val settingKey = req.channelSettingKey
        val rememberQuality = latestSettings.rememberChannelQuality
        scope.launch(ioDispatcher) {
            historyRepository.record(
                targetType = type,
                targetId = targetId,
                title = req.title,
                sourceId = sourceId,
                subtitle = req.subtitle,
                imageUrl = req.artworkUrl,
                url = req.url,
                positionMs = position,
                durationMs = duration,
                episodeLabel = req.episodeLabel,
            )
            if (settingKey != null && rememberQuality) {
                channelSettingDao.upsert(
                    ChannelSettingEntity(
                        key = settingKey,
                        quality = quality,
                        lastPlayedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /* ================================ 工具 ================================ */

    private fun qualityLabelFor(height: Int): String? = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080P"
        height >= 700 -> "720P"
        height > 0 -> "SD"
        else -> null
    }

    private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        -> "网络连接失败或超时"

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        -> "播放地址无效或被拒绝"

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        -> "解码器初始化失败"

        else -> error.message ?: "未知错误"
    }

    private fun mimeTypeForSubtitle(url: String): String? {
        val ext = url.trim().substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .lowercase()
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt", "sub", "txt", "" -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    }

    private data class BufferDurations(
        val min: Int,
        val max: Int,
        val playback: Int,
        val after: Int,
    )

    private data class PlayerConfig(
        val hardwareFirst: Boolean,
        val buffer: BufferDurations,
    )

    /* ================================ 跨集预缓冲（设置 → 预缓冲时长，最长 2 小时） ================================ */

    /**
     * 跨集预缓冲调度：以当前集时长为单位把预算折算成集数（含当前集，当前集由播放器边播边写入
     * 同一份缓存），依次把后续剧集整体预写入磁盘缓存；选集/分集列表经 [preloadProgress] 显示
     * 「缓冲中 x% / 已缓冲」徽标。HLS 解析主播放列表与分片逐段写入，直连文件整体写入。
     */
    private fun schedulePreload(request: PlaybackRequest) {
        preloadJob?.cancel()
        preloadJob = null
        val budgetMinutes = latestSettings.preloadMinutes
        if (request.mode != PlaybackMode.VOD || budgetMinutes <= 0) {
            _preloadProgress.value = emptyMap()
            return
        }
        if (request.episodes.size <= 1) return
        // 换集时仅保留同剧集进度：已写入的缓存命中后立即显示「已缓冲」
        val urlSet = request.episodes.mapTo(mutableSetOf()) { it.url }
        _preloadProgress.value = _preloadProgress.value.filterKeys { it in urlSet }
        preloadJob = scope.launch(ioDispatcher) {
            val durationMs = awaitEpisodeDuration()
            if (durationMs <= 0L) return@launch
            val slots = (budgetMinutes * 60_000L + durationMs - 1L) / durationMs
            val ahead = (slots - 1L).coerceAtLeast(0L).toInt()
            if (ahead <= 0) return@launch
            val index = request.episodes.indexOfFirst { it.url == request.url }
            val upcoming = if (index >= 0) {
                request.episodes.subList(index + 1, request.episodes.size)
            } else {
                request.episodes.filter { it.url != request.url }
            }
            upcoming.take(ahead).forEach { episode ->
                if (!isActive) return@launch
                preloadEpisode(episode.url, request.headers)
            }
        }
    }

    /** 等待当前集时长解析（预缓冲集数按时长折算；30 秒未解析出则放弃本轮） */
    private suspend fun awaitEpisodeDuration(): Long {
        repeat(60) {
            val duration = _state.value.durationMs
            if (duration > 0L) return duration
            delay(500)
        }
        return 0L
    }

    /** 预缓冲单集：失败（网络/解析异常）时移除徽标；取消异常向上抛 */
    private suspend fun preloadEpisode(url: String, headers: Map<String, String>) {
        updatePreloadProgress(url, 0)
        try {
            if (url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)) {
                preloadHls(url, headers)
            } else {
                preloadDirect(url) { pct -> updatePreloadProgress(url, pct) }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _preloadProgress.value = _preloadProgress.value - url
        }
    }

    /** HLS 预缓冲：主播放列表取首条变体，媒体播放列表逐分片写缓存，按分片数汇报总进度 */
    private suspend fun preloadHls(url: String, headers: Map<String, String>) {
        var playlistUrl = url
        var text = fetchPlaylistText(url, headers)
            ?: throw java.io.IOException("预缓冲拉取播放列表失败")
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw java.io.IOException("预缓冲解析变体失败")
            playlistUrl = resolveUrl(url, variant)
            text = fetchPlaylistText(playlistUrl, headers)
                ?: throw java.io.IOException("预缓冲拉取变体播放列表失败")
        }
        // 内容不是标准播放列表（个别站点直出媒体文件）：按直连整体写入
        if (!text.contains("#EXTINF")) {
            preloadDirect(url) { pct -> updatePreloadProgress(url, pct) }
            return
        }
        val segments = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUrl(playlistUrl, it) }
            .toList()
        if (segments.isEmpty()) throw java.io.IOException("预缓冲分片列表为空")
        // 预写播放列表本身：切集时主/子列表解析直接命中缓存，消除网络往返停顿（尽力而为）
        try {
            preloadDirect(playlistUrl, episodeUrl = url) {}
            if (url != playlistUrl) preloadDirect(url, episodeUrl = url) {}
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
        segments.forEachIndexed { i, segment ->
            currentCoroutineContext().ensureActive()
            preloadDirect(segment, episodeUrl = url) { pct ->
                updatePreloadProgress(url, (i * 100 + pct) / segments.size)
            }
        }
        updatePreloadProgress(url, 100)
    }

    /**
     * 直连/分片预缓冲：经共享缓存数据源从头读到流末尾（已完整缓存时立即结束），
     * 读取循环内检查取消；瞬时失败断点重试一次，仍失败抛给调用方移除徽标。
     */
    private suspend fun preloadDirect(
        url: String,
        episodeUrl: String? = null,
        onProgress: (Int) -> Unit,
    ) {
        // 登记本集写入的缓存键（换集回收用；含失败前已写入的部分）
        synchronized(cacheKeyLock) {
            preloadCacheKeys.getOrPut(episodeUrl ?: url) { mutableSetOf() }.add(url)
        }
        var attempt = 0
        while (true) {
            val dataSource = cacheFactory.createDataSource()
            try {
                var position = 0L
                var total = -1L
                var lastReported = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val remaining = dataSource.open(
                        DataSpec.Builder().setUri(url).setPosition(position).build(),
                    )
                    if (total < 0 && remaining >= 0) total = position + remaining
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = dataSource.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        if (read <= 0) continue
                        position += read
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        }
                    }
                    break
                }
                onProgress(100)
                return
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                attempt += 1
                if (attempt > 1) throw t
            } finally {
                runCatching { dataSource.close() }
            }
        }
    }

    /** 拉取播放列表文本（复用当前任务 UA/Referer 请求头；失败返回 null 由调用方判定） */
    private suspend fun fetchPlaylistText(url: String, headers: Map<String, String>): String? {
        val req = runCatching {
            val builder = Request.Builder().url(url)
            (DEFAULT_REQUEST_HEADERS + headers).forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        }.getOrNull() ?: return null
        return runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** 播放列表相对地址 → 绝对地址 */
    private fun resolveUrl(base: String, ref: String): String =
        runCatching { java.net.URL(java.net.URL(base), ref).toString() }.getOrDefault(ref)

    /** 更新单集预缓冲进度（同值不重复发布，避免多余重组） */
    private fun updatePreloadProgress(key: String, percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (_preloadProgress.value[key] == value) return
        _preloadProgress.value = _preloadProgress.value + (key to value)
    }

    companion object {
        /** 自动切换备用源后的提示（UI 以 warning 色展示） */
        const val MSG_BACKUP_SWITCHED = "网络波动，已切换备用源"

        /** 全部线路均卡顿后的真正错误（UI 以 error 色展示并提供重试） */
        const val MSG_STALL_ALL_SOURCES = "网络波动持续，已尝试全部线路，请重试"

        private const val STATUS_INTERVAL_MS = 500L
        private const val HISTORY_INTERVAL_MS = 5_000L
        private const val SEEK_STEP_MS = 10_000L

        /** 默认请求头：浏览器 UA（规避部分资源站对播放器 UA 的 403 拦截），单请求头可按需覆盖 */
        private val DEFAULT_REQUEST_HEADERS = mapOf(
            "User-Agent" to ("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
        )
        private const val REWIND_BUFFER_MS = 2_000
        private const val WEAK_SPEED_BYTES_PER_SEC = 1_500_000L // ≈1.5MB/s
        private const val WEAK_BUFFERING_MS = 2_000L
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val STALL_SWITCH_THRESHOLD = 2
        private const val QUALITY_RECOVER_MS = 30_000L
        private const val HEALTHY_RESET_MS = 30_000L
        private const val SPEED_STALE_MS = 4_000L

        /** 智能缓冲档位 → DefaultLoadControl 参数（需求 5.5.3） */
        private fun bufferDurations(settings: AppSettings): BufferDurations =
            when (settings.bufferProfile) {
                BufferProfile.SMALL -> BufferDurations(
                    min = 5_000, max = 25_000, playback = 1_500, after = 3_000,
                )

                BufferProfile.LARGE -> BufferDurations(
                    min = 30_000, max = 90_000, playback = 5_000, after = 10_000,
                )

                BufferProfile.CUSTOM -> {
                    val min = settings.bufferMinMs.coerceIn(1_000, 600_000)
                    val max = settings.bufferMaxMs.coerceIn(min, 1_200_000)
                    BufferDurations(
                        min = min,
                        max = max,
                        playback = settings.bufferForPlaybackMs.coerceIn(250, min),
                        after = settings.bufferForAfterMs.coerceIn(250, min),
                    )
                }

                // MEDIUM 为默认档
                BufferProfile.MEDIUM -> BufferDurations(
                    min = 15_000, max = 50_000, playback = 2_500, after = 5_000,
                )
            }
    }
}
