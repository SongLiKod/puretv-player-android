package com.puretv.player.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.local.entity.ChannelEntity
import com.puretv.player.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.flow.first

/**
 * 节目详情弹窗（需求 5.3.4：EPG 实时节目单、时间轴预览、回看、预约播放）：
 * 今日节目单 + 每条「预约」（已预约显示取消）+ 已播出节目「回看」。
 */
@Composable
fun ProgramGuideDialog(
    channel: ChannelEntity,
    viewModel: LiveViewModel,
    onPlayCatchup: (EpgProgramEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val programs by produceState<List<EpgProgramEntity>>(
        initialValue = emptyList(),
        key1 = channel.epgId,
    ) {
        value = if (channel.epgId.isNullOrBlank()) {
            emptyList()
        } else {
            viewModel.todayPrograms(channel.epgId).first()
        }
    }
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()

    TvDialog(onDismiss = onDismiss, title = "${channel.name} · 今日节目单", maxWidth = 860.dp) {
        if (programs.isEmpty()) {
            EmptyState(
                text = if (channel.epgId.isNullOrBlank()) "该频道未关联 EPG 节目单"
                else "暂无节目单数据，可在源管理中同步 EPG",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(programs, key = { it.id }) { program ->
                    ProgramRow(
                        channel = channel,
                        program = program,
                        now = now,
                        viewModel = viewModel,
                        onPlayCatchup = onPlayCatchup,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(
    channel: ChannelEntity,
    program: EpgProgramEntity,
    now: Long,
    viewModel: LiveViewModel,
    onPlayCatchup: (EpgProgramEntity) -> Unit,
) {
    val colors = LocalPureTvColors.current
    val isPast = program.endAt <= now
    val isCurrent = program.startAt <= now && program.endAt > now
    val reminder by produceState<com.puretv.player.data.local.entity.ReminderEntity?>(
        initialValue = null,
        key1 = program.id,
    ) {
        value = viewModel.reminderOf(program.id)
    }
    val scheduled = reminder != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${TimeUtils.formatClock(program.startAt)} - ${TimeUtils.formatClock(program.endAt)}",
                color = colors.textTertiary,
                fontSize = 15.sp,
                modifier = Modifier.width(120.dp),
            )
            Text(
                text = program.title,
                color = when {
                    isCurrent -> colors.highlight
                    isPast -> colors.textSecondary
                    else -> colors.textPrimary
                },
                fontSize = 16.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Text(
                    text = "正在播出",
                    color = colors.success,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else if (isPast) {
                TvTextButton(
                    text = "回看",
                    color = colors.link,
                    onClick = { onPlayCatchup(program) },
                )
            } else {
                TvTextButton(
                    text = if (scheduled) "取消预约" else "预约",
                    color = if (scheduled) colors.warning else colors.link,
                    onClick = {
                        if (scheduled) viewModel.cancelReminder(program.id)
                        else viewModel.scheduleReminder(channel, program)
                    },
                )
            }
        }
        if (scheduled) {
            Text(
                text = "已预约 · ${TimeUtils.untilStart(program.startAt)}",
                color = colors.textTertiary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 132.dp, top = 2.dp),
            )
        } else if (!program.description.isNullOrBlank()) {
            Text(
                text = program.description,
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 132.dp, top = 2.dp),
            )
        }
    }
}
