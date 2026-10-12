package com.puretv.player.ui.live

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.data.local.entity.ChannelEntity

/**
 * 四流马赛克画面（需求 5.3.4：四流马赛克画面）：
 * 2x2 网格展示当前分组前 4 个频道（不足 4 个按实际数量），每格独立 ExoPlayer；
 * 仅焦点格有声（其他 setVolume(0f)），D-pad 移动焦点格（高亮边框），
 * OK 进入播放页，返回释放全部播放器（低配设备离开页面务必释放）。
 */
@Composable
fun MosaicScreen(
    channels: List<ChannelEntity>,
    onPlayChannel: (ChannelEntity) -> Unit,
    onExit: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val context = LocalContext.current
    val cells = remember(channels) { channels.take(MAX_CELLS) }
    var focusIndex by remember { mutableIntStateOf(0) }
    val session = remember(cells) { MosaicSession(context, cells) }
    val cellFocusRequesters = remember { List(MAX_CELLS) { FocusRequester() } }

    // 离开页面务必释放 4 个播放器（低配设备内存）
    DisposableEffect(session) {
        onDispose { session.release() }
    }
    // 仅焦点格有声
    LaunchedEffect(session, focusIndex) {
        session.setFocused(focusIndex.coerceIn(0, (cells.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(session) {
        cellFocusRequesters.getOrNull(0)?.let { runCatching { it.requestFocus() } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val current = focusIndex
                val columns = 2
                when (event.key) {
                    Key.DirectionLeft -> if (current % columns != 0) focusIndex = current - 1
                    Key.DirectionRight -> if (current % columns == 0 && current + 1 < cells.size) focusIndex = current + 1
                    Key.DirectionUp -> if (current - columns >= 0) focusIndex = current - columns
                    Key.DirectionDown -> if (current + columns < cells.size) focusIndex = current + columns
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        cells.getOrNull(focusIndex)?.let(onPlayChannel)
                    }
                    Key.Back, Key.Escape -> {
                        onExit()
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                // 焦点随选中格移动
                cellFocusRequesters.getOrNull(focusIndex)
                    ?.let { runCatching { it.requestFocus() } }
                true
            },
    ) {
        for (rowIndex in 0 until 1) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MosaicCell(
                    channel = cells.getOrNull(rowIndex * 2),
                    focused = focusIndex == rowIndex * 2,
                    focusRequester = cellFocusRequesters[rowIndex * 2],
                    player = session.players.getOrNull(rowIndex * 2),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Box(modifier = Modifier.padding(horizontal = 8.dp))
                MosaicCell(
                    channel = cells.getOrNull(rowIndex * 2 + 1),
                    focused = focusIndex == rowIndex * 2 + 1,
                    focusRequester = cellFocusRequesters[rowIndex * 2 + 1],
                    player = session.players.getOrNull(rowIndex * 2 + 1),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
            MosaicCell(
                channel = cells.getOrNull(2),
                focused = focusIndex == 2,
                focusRequester = cellFocusRequesters[2],
                player = session.players.getOrNull(2),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Box(modifier = Modifier.padding(horizontal = 8.dp))
            MosaicCell(
                channel = cells.getOrNull(3),
                focused = focusIndex == 3,
                focusRequester = cellFocusRequesters[3],
                player = session.players.getOrNull(3),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Text(
            text = "方向键切换画面 · OK 播放 · 返回退出四分屏",
            color = colors.textTertiary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun MosaicCell(
    channel: ChannelEntity?,
    focused: Boolean,
    focusRequester: FocusRequester,
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) colors.focusBorder else colors.divider,
                shape = shape,
            )
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null && channel != null) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            ) {
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = "无可用频道",
                color = colors.textHint,
                fontSize = 16.sp,
            )
        }
    }
}

/**
 * 四分屏播放会话：每个格子独立 ExoPlayer，仅焦点格有声。
 */
private class MosaicSession(context: Context, channels: List<ChannelEntity>) {

    val players: List<ExoPlayer> = channels.map { channel ->
        // 自定义请求头经 DataSource 工厂注入（Media3 无 MediaItem 级 HttpRequestConfig）；
        // DefaultMediaSourceFactory 自动按 URI 类型分发 HLS/DASH/渐进式
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(channel.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.Builder().setUri(channel.url).build())
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
    }

    fun setFocused(index: Int) {
        players.forEachIndexed { i, player ->
            runCatching { player.volume = if (i == index) 1f else 0f }
        }
    }

    fun release() {
        players.forEach { player ->
            runCatching {
                player.stop()
                player.release()
            }
        }
    }
}

private const val MAX_CELLS = 4
