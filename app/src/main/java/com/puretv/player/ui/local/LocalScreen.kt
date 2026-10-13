package com.puretv.player.ui.local

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.LocalPaneBottomPadding
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.core.ui.tvFocusHighlight
import com.puretv.player.core.util.TimeUtils

/**
 * 本地影音页（需求 5.2.4 首页模块 / 5.5.5 本地播放优化）：
 * 运行时权限申请（Android 13+ READ_MEDIA_VIDEO，12 及以下 READ_EXTERNAL_STORAGE）、
 * MediaStore 扫描结果网格/列表展示、搜索过滤、缩略图仅可见项加载、断点续播起播。
 */
@Composable
fun LocalScreen(nav: NavHostController) {
    val colors = LocalPureTvColors.current
    val context = LocalContext.current
    val viewModel: LocalViewModel = hiltViewModel()

    val permission by viewModel.permission.collectAsStateWithLifecycle()
    val videos by viewModel.displayVideos.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    var gridMode by rememberSaveable { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val activity = context as? Activity
            val permanently = activity?.shouldShowRequestPermissionRationale(
                viewModel.requiredPermission,
            ) != true
            viewModel.onPermissionDenied(permanently)
        }
    }

    LaunchedEffect(Unit) { viewModel.checkPermission() }

    val portrait = isPortrait()
    val sidePad = if (portrait) 16.dp else 40.dp

    TvScaffold(
        title = "本地影音",
        onBack = { nav.popBackStack() },
        actions = {
            TvTextButton(
                text = if (gridMode) "切换列表" else "切换网格",
                onClick = { gridMode = !gridMode },
                compact = true,
            )
            if (permission == LocalViewModel.PermissionState.GRANTED) {
                TvTextButton(text = "重新扫描", onClick = { viewModel.scan() }, compact = true)
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(
                query = query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePad, vertical = if (portrait) 8.dp else 16.dp),
            )
            when (permission) {
                LocalViewModel.PermissionState.UNKNOWN -> PermissionRationale(
                    text = "本地影音需要读取视频文件的权限，仅用于扫描和播放本机视频，不会上传任何数据。",
                    buttonText = "授权并扫描",
                    onButtonClick = { permissionLauncher.launch(viewModel.requiredPermission) },
                )

                LocalViewModel.PermissionState.DENIED -> PermissionRationale(
                    text = "未获得读取视频权限，无法扫描本地视频。",
                    buttonText = "重新申请授权",
                    onButtonClick = { permissionLauncher.launch(viewModel.requiredPermission) },
                )

                LocalViewModel.PermissionState.DENIED_PERMANENT -> PermissionRationale(
                    text = "权限已被永久拒绝，请前往系统设置手动开启「读取视频/媒体」权限后返回。",
                    buttonText = "前往系统设置",
                    onButtonClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    },
                )

                LocalViewModel.PermissionState.GRANTED -> {
                    if (scanning) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = colors.primary,
                                    strokeWidth = 3.dp,
                                )
                                Text(
                                    text = "正在扫描本地视频…",
                                    color = colors.textSecondary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            }
                        }
                    } else if (videos.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                text = if (query.isBlank()) "未在设备中找到视频文件"
                                else "没有匹配「$query」的本地视频",
                                subtext = if (query.isBlank()) "确认视频文件已放入设备存储后，选择「重新扫描」"
                                else "换个关键词试试",
                            )
                        }
                    } else if (gridMode) {
                        VideoGrid(
                            videos = videos,
                            viewModel = viewModel,
                            onPlay = { nav.navigate(Routes.PLAYBACK) },
                        )
                    } else {
                        VideoList(
                            videos = videos,
                            viewModel = viewModel,
                            onPlay = { nav.navigate(Routes.PLAYBACK) },
                        )
                    }
                }
            }
        }
    }
}

/** 搜索框（TV：聚焦后弹出输入法） */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "🔍", fontSize = 16.sp, color = colors.textTertiary)
        Box(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            if (query.isEmpty()) {
                Text(text = "搜索本地视频…", color = colors.textHint, fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(modifier = Modifier.width(12.dp))
            TvTextButton(text = "清除", onClick = { onQueryChange("") })
        }
    }
}

/** 权限说明 + 操作按钮 */
@Composable
private fun PermissionRationale(
    text: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 80.dp),
        ) {
            Text(
                text = text,
                color = colors.textSecondary,
                fontSize = 17.sp,
                lineHeight = 26.sp,
            )
            TvButton(
                text = buttonText,
                onClick = onButtonClick,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}

/** 网格视图（缩略图 16:9，低配优化：仅可见项通过 produceState 加载缩略图） */
@Composable
private fun VideoGrid(
    videos: List<LocalVideo>,
    viewModel: LocalViewModel,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridFocusRequester = remember { FocusRequester() }
    val portrait = isPortrait()
    val spacing = if (portrait) 12.dp else 20.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (portrait) 150.dp else 250.dp),
        contentPadding = PaddingValues(
            start = if (portrait) 16.dp else 40.dp,
            end = if (portrait) 16.dp else 40.dp,
            bottom = (if (portrait) 24.dp else 40.dp) + LocalPaneBottomPadding.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = modifier
            .fillMaxSize()
            .focusRequester(gridFocusRequester),
    ) {
        items(videos, key = { it.id }) { video ->
            VideoGridItem(
                video = video,
                viewModel = viewModel,
                onClick = {
                    viewModel.play(video, onPlay)
                },
            )
        }
    }
    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty()) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }
}

@Composable
private fun VideoGridItem(
    video: LocalVideo,
    viewModel: LocalViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    TvCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            VideoThumbnail(
                video = video,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Text(
                text = video.displayName,
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = "${TimeUtils.formatDuration(video.durationMs)} · ${video.sizeLabel}",
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 列表视图（名称 + 时长/大小/修改时间） */
@Composable
private fun VideoList(
    videos: List<LocalVideo>,
    viewModel: LocalViewModel,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listFocusRequester = remember { FocusRequester() }
    val portrait = isPortrait()
    LazyColumn(
        contentPadding = PaddingValues(
            start = if (portrait) 16.dp else 40.dp,
            end = if (portrait) 16.dp else 40.dp,
            bottom = (if (portrait) 24.dp else 40.dp) + LocalPaneBottomPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester),
    ) {
        items(videos, key = { it.id }) { video ->
            VideoListItem(
                video = video,
                viewModel = viewModel,
                onClick = { viewModel.play(video, onPlay) },
            )
        }
    }
    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty()) {
            runCatching { listFocusRequester.requestFocus() }
        }
    }
}

@Composable
private fun VideoListItem(
    video: LocalVideo,
    viewModel: LocalViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusHighlight(shape = RoundedCornerShape(12.dp))
            .background(colors.card, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(
            video = video,
            viewModel = viewModel,
            modifier = Modifier
                .width(192.dp)
                .aspectRatio(16f / 9f),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 20.dp)) {
            Text(
                text = video.displayName,
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "时长 ${TimeUtils.formatDuration(video.durationMs)} · ${video.sizeLabel}",
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "修改于 ${TimeUtils.formatDateTime(video.dateModifiedSec * 1000)}",
                color = colors.textHint,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 缩略图：可见项才触发加载（produceState 随组合/销毁创建与取消） */
@Composable
private fun VideoThumbnail(
    video: LocalVideo,
    viewModel: LocalViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    val thumbnail by produceState<Bitmap?>(
        initialValue = null,
        key1 = video.id,
    ) {
        value = viewModel.loadThumbnail(video)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = video.displayName,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "无缩略图", color = colors.textHint, fontSize = 14.sp)
        }
    }
}
