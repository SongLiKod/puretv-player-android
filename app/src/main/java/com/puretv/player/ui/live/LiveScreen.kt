package com.puretv.player.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.navigation.SettingsSections
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.ui.ErrorState
import com.puretv.player.core.ui.LoadingOverlay
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.focusOnLaunch
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.core.util.StringUtils
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import com.puretv.player.player.PlayerRequests

/**
 * 直播专区（需求 5.3 直播IPTV功能：M3U/Xtream、分组、EPG、回看、预约、四分屏）。
 * 布局：页头（源选择/导入/搜索/四分屏）+ 左侧分组栏 + 右侧频道列表。
 */
@Composable
fun LiveScreen(nav: NavHostController) {
    val viewModel: LiveViewModel = hiltViewModel()
    val colors = LocalPureTvColors.current
    val context = LocalContext.current

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val hasSource by viewModel.hasSource.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val visibleChannels by viewModel.visibleChannels.collectAsStateWithLifecycle()
    val currentGroup by viewModel.currentGroup.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showSourceDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var mosaicMode by remember { mutableStateOf(false) }
    var guideChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var catchupError by remember { mutableStateOf<String?>(null) }

    // Android 13+ 首次进入申请通知权限（节目预约提醒）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.selectedSource.value?.let { viewModel.maybeSyncEpg(it.id) }
    }

    // 四分屏模式：独立于主布局（不新增导航路由）
    if (mosaicMode) {
        val mosaicChannels = remember(visibleChannels, currentGroup) { mosaicCells(visibleChannels) }
        MosaicScreen(
            channels = mosaicChannels,
            onPlayChannel = { channel ->
                viewModel.playChannel(channel)
                nav.navigate(Routes.PLAYBACK)
            },
            onExit = { mosaicMode = false },
        )
        return
    }

    val portrait = isPortrait()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        // ---------------- 页头 ----------------
        LiveHeader(
            sourceName = selectedSource?.name ?: "选择源",
            hasSource = hasSource,
            onSourceClick = { showSourceDialog = true },
            onSearchClick = { showSearch = true },
            onMosaicClick = { mosaicMode = true },
        )
        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        if (!hasSource) {
            // 首次无源：引导导入
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        text = "尚未导入直播源",
                        subtext = "导入 M3U / Xtream 直播源后在此观看直播频道",
                    )
                    TvButton(
                        text = "去导入",
                        onClick = { nav.navigate(Routes.settings(SettingsSections.SOURCES)) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            return@Column
        }

        if (portrait) {
            // ---------------- 竖屏：分组横排 + 频道列表 ----------------
            GroupChipsRow(
                groups = groups,
                selected = currentGroup,
                onSelect = { viewModel.selectGroup(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (visibleChannels.isEmpty()) {
                    EmptyState(
                        text = if (currentGroup == LiveGroups.FAVORITES) "暂无收藏频道"
                        else "该分组暂无频道",
                        subtext = "点按频道播放，信息键查看节目单",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    ChannelList(
                        channels = visibleChannels,
                        viewModel = viewModel,
                        favoriteKeys = favoriteKeys,
                        onPlay = { channel ->
                            viewModel.playChannel(channel)
                            nav.navigate(Routes.PLAYBACK)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onOpenGuide = { guideChannel = it },
                    )
                }
            }
        } else {
            // ---------------- 分组栏 + 频道列表 ----------------
            Row(modifier = Modifier.fillMaxSize()) {
                GroupRail(
                    groups = groups,
                    selected = currentGroup,
                    onSelect = { viewModel.selectGroup(it) },
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .padding(vertical = 16.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = colors.divider,
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (visibleChannels.isEmpty()) {
                        EmptyState(
                            text = if (currentGroup == LiveGroups.FAVORITES) "暂无收藏频道"
                            else "该分组暂无频道",
                            subtext = "按 OK 播放，菜单键/信息键查看节目单",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        ChannelList(
                            channels = visibleChannels,
                            viewModel = viewModel,
                            favoriteKeys = favoriteKeys,
                            onPlay = { channel ->
                                viewModel.playChannel(channel)
                                nav.navigate(Routes.PLAYBACK)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onOpenGuide = { guideChannel = it },
                        )
                    }
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------
    if (showSourceDialog) {
        SourceManageDialog(
            viewModel = viewModel,
            sources = sources,
            selectedSourceId = selectedSource?.id,
            onDismiss = { showSourceDialog = false },
        )
    }
    if (showSearch) {
        LiveSearchDialog(
            viewModel = viewModel,
            onPlay = { channel ->
                showSearch = false
                viewModel.playChannel(channel)
                nav.navigate(Routes.PLAYBACK)
            },
            onDismiss = { showSearch = false },
        )
    }
    guideChannel?.let { channel ->
        ProgramGuideDialog(
            channel = channel,
            viewModel = viewModel,
            onPlayCatchup = { program ->
                viewModel.playCatchup(channel, program) { msg -> catchupError = msg }
            },
            onDismiss = { guideChannel = null },
        )
    }
    catchupError?.let { message ->
        TvDialog(onDismiss = { catchupError = null }, title = "提示") {
            Text(
                text = message,
                color = colors.textSecondary,
                fontSize = 17.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            TvButton(text = "知道了", onClick = { catchupError = null })
        }
    }

    // ---------------- 解析/EPG 同步进度 / 失败 ----------------
    if (progress != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
            LoadingOverlay(
                message = stageText(progress!!),
                percent = progress!!.percent,
                onCancel = { viewModel.cancelTask() },
            )
        }
    }
    if (error != null && progress == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ErrorState(
                message = error ?: "",
                onRetry = {
                    viewModel.dismissError()
                    selectedSource?.let { viewModel.refreshSource(it.id) }
                },
            )
        }
    }
}

/** 四分屏取当前分组前 4 个频道 */
private fun mosaicCells(channels: List<ChannelEntity>): List<ChannelEntity> = channels.take(4)

/** 竖屏（手机）分组横排：替代左侧分组栏，横向滚动的分组 chips */
@Composable
private fun GroupChipsRow(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(groups, key = { it }) { group ->
            val isSelected = group == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) colors.primary.copy(alpha = 0.2f) else colors.card)
                    .clickable { onSelect(group) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    text = group,
                    color = if (isSelected) colors.primary else colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 进度阶段文案 */
private fun stageText(progress: com.puretv.player.data.model.ImportProgress): String =
    progress.message ?: when (progress.stage) {
        com.puretv.player.data.model.ImportProgress.Stage.PREPARING -> "正在准备…"
        com.puretv.player.data.model.ImportProgress.Stage.DOWNLOADING -> "正在下载频道列表…"
        com.puretv.player.data.model.ImportProgress.Stage.PARSING -> "正在解析频道…"
        com.puretv.player.data.model.ImportProgress.Stage.SAVING -> "正在写入数据库…"
        com.puretv.player.data.model.ImportProgress.Stage.DONE -> "完成"
        com.puretv.player.data.model.ImportProgress.Stage.FAILED -> "失败"
    }
