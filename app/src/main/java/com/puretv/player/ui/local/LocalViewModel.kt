package com.puretv.player.ui.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.model.TargetType
import com.puretv.player.data.repository.HistoryRepository
import com.puretv.player.di.IoDispatcher
import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlayerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 本地视频条目（MediaStore 扫描结果） */
data class LocalVideo(
    val id: String,
    /** content:// URI */
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** DATE_MODIFIED（秒） */
    val dateModifiedSec: Long,
    /** API < 29 兜底缩略图所需的真实路径 */
    val dataPath: String?,
) {
    /** 大小展示：MB / GB */
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_073_741_824L ->
                String.format("%.1f GB", sizeBytes / 1_073_741_824.0)

            sizeBytes >= 1_048_576L -> "${sizeBytes / 1_048_576L} MB"
            sizeBytes >= 1_024L -> "${sizeBytes / 1_024L} KB"
            else -> "$sizeBytes B"
        }
}

/**
 * 本地影音（需求 5.2.4 首页模块 / 5.5.5 本地播放优化）：
 * 运行时权限申请（Android 13+ READ_MEDIA_VIDEO，12 及以下 READ_EXTERNAL_STORAGE）、
 * MediaStore 按修改时间倒序扫描、搜索过滤、断点续播起点读取。
 */
@HiltViewModel
class LocalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyRepository: HistoryRepository,
    private val playbackEngine: PlaybackEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    enum class PermissionState {
        UNKNOWN,   // 未申请
        GRANTED,   // 已授权
        DENIED,    // 拒绝过（可再次申请）
        DENIED_PERMANENT, // 拒绝且不再询问（需去设置）
    }

    private val _permission = MutableStateFlow(PermissionState.UNKNOWN)
    val permission: StateFlow<PermissionState> = _permission

    private val _videos = MutableStateFlow<List<LocalVideo>>(emptyList())
    val videos: StateFlow<List<LocalVideo>> = _videos

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** 列表展示数据（搜索过滤） */
    val displayVideos: StateFlow<List<LocalVideo>> =
        combine(_query, _videos) { keyword, list ->
            if (keyword.isBlank()) list
            else list.filter { it.displayName.contains(keyword, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 运行时读取权限（SDK 版本自适应） */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** 进入页面时检查是否已持有权限，已授权则直接扫描 */
    fun checkPermission() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, requiredPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) onPermissionGranted() else _permission.value = PermissionState.UNKNOWN
    }

    fun onPermissionGranted() {
        _permission.value = PermissionState.GRANTED
        scan()
    }

    fun onPermissionDenied(permanently: Boolean) {
        _permission.value =
            if (permanently) PermissionState.DENIED_PERMANENT else PermissionState.DENIED
    }

    fun setQuery(keyword: String) {
        _query.value = keyword
    }

    /** 扫描 MediaStore 本地视频（按修改时间倒序，兼容低配盒子的轻量查询） */
    fun scan() {
        viewModelScope.launch {
            _scanning.value = true
            val result = withContext(ioDispatcher) { queryVideos() }
            _videos.value = result
            _scanning.value = false
        }
    }

    private fun queryVideos(): List<LocalVideo> {
        val baseProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        val lowSdk = Build.VERSION.SDK_INT < 29
        val projection = if (lowSdk) {
            baseProjection + MediaStore.Video.Media.DATA
        } else {
            baseProjection
        }
        val result = ArrayList<LocalVideo>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val dataIndex = if (lowSdk) cursor.getColumnIndex(MediaStore.Video.Media.DATA) else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    result.add(
                        LocalVideo(
                            id = id.toString(),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                            ).toString(),
                            displayName = cursor.getString(nameIndex) ?: "视频 $id",
                            durationMs = cursor.getLong(durationIndex),
                            sizeBytes = cursor.getLong(sizeIndex),
                            dateModifiedSec = cursor.getLong(modifiedIndex),
                            dataPath = if (dataIndex >= 0) cursor.getString(dataIndex) else null,
                        ),
                    )
                }
            }
        }
        return result
    }

    /**
     * 加载缩略图（低配优化：仅可见项触发，见播放页/列表的 produceState）：
     * Android 10+ 用 contentResolver.loadThumbnail，低版本 ThumbnailUtils 兜底。
     */
    suspend fun loadThumbnail(video: LocalVideo): Bitmap? = withContext(ioDispatcher) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id.toLong(),
                    ),
                    Size(320, 180),
                    null,
                )
            } else {
                val path = video.dataPath
                if (path.isNullOrBlank()) null
                else @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(
                    path,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                )
            }
        }.getOrNull()
    }

    /** 起播：读取本地播放记录作为断点续播起点，投递播放请求（需求 5.2.4 / 5.4.3） */
    fun play(video: LocalVideo, onStarted: () -> Unit) {
        viewModelScope.launch {
            val history = historyRepository.get(TargetType.LOCAL, video.id)
            playbackEngine.play(
                PlayerRequests.local(
                    mediaId = video.id,
                    uri = video.uri,
                    displayName = video.displayName,
                    startPositionMs = history?.positionMs ?: 0L,
                ),
            )
            onStarted()
        }
    }
}
