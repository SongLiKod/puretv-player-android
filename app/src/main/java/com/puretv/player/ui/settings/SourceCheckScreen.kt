package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.ErrorState
import com.puretv.player.core.ui.InlineLoading
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.core.ui.SectionHeader
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.SourceCheckReport

/**
 * 源检测工具（需求 5.6.4：内置使用教程、常见问题修复、源检测工具）。
 * 检测已保存源或任意地址：可达性、响应延迟、频道/分组数、EPG、抽样播放健康度。
 */
@Composable
fun SourceCheckScreen(
    nav: NavHostController,
    viewModel: SourceCheckViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showUrlInput by remember { mutableStateOf(false) }

    TvScaffold(
        title = "源检测工具",
        subtitle = "检测直播源可用性与播放健康度",
        onBack = { nav.popBackStack() },
        actions = {
            TvTextButton(text = "检测新地址", onClick = { showUrlInput = true })
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (checking) {
                InlineLoading(message = "正在检测，请稍候…")
            }

            error?.let { message ->
                ErrorState(message = message, onRetry = null)
            }

            report?.let { r ->
                SectionHeader(title = "检测结果")
                ReportCard(report = r)
            }

            SectionHeader(title = "检测已保存的源")
            if (sources.isEmpty()) {
                Text(
                    text = "尚未导入直播源，可在设置中心 → 源管理中导入，或点右上角「检测新地址」。",
                    color = colors.textTertiary,
                    fontSize = 16.scaled(),
                )
            } else {
                sources.forEach { source ->
                    SourceRow(
                        source = source,
                        enabled = !checking,
                        onCheck = { viewModel.checkSource(source.id) },
                    )
                }
            }
        }
    }

    if (showUrlInput) {
        InputDialog(
            title = "检测新地址",
            label = "M3U 地址或 Xtream 服务器地址",
            placeholder = "https://example.com/playlist.m3u8",
            confirmText = "开始检测",
            onDone = { url ->
                showUrlInput = false
                if (url.isNotBlank()) viewModel.checkUrl(url.trim())
            },
            onDismiss = { showUrlInput = false },
        )
    }
}

/** 已保存源条目 */
@Composable
private fun SourceRow(source: SourceEntity, enabled: Boolean, onCheck: () -> Unit) {
    val colors = PureTvTheme.colors
    TvCard(onClick = onCheck, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    color = colors.textPrimary,
                    fontSize = 18.scaled(),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (source.type == "xtream") "Xtream · ${source.channelCount} 频道"
                    else "M3U · ${source.channelCount} 频道",
                    color = colors.textTertiary,
                    fontSize = 14.scaled(),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TvTextButton(text = "检测", onClick = onCheck)
        }
    }
}

/** 检测报告卡片：可达性 + 各项指标 */
@Composable
private fun ReportCard(report: SourceCheckReport) {
    val colors = PureTvTheme.colors
    TvCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 可达性指示点（绿/红状态色）
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (report.reachable) colors.success else colors.error),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (report.reachable) "源可达" else "无法连接",
                    color = if (report.reachable) colors.success else colors.error,
                    fontSize = 18.scaled(),
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = report.target,
                color = colors.textTertiary,
                fontSize = 14.scaled(),
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.size(10.dp))
            MetricRow("响应延迟", if (report.latencyMs >= 0) "${report.latencyMs} ms" else "未知")
            MetricRow("频道数量", "${report.channelCount}")
            MetricRow("分组数量", "${report.groupCount}")
            MetricRow("EPG 节目单", if (report.epgFound) "已发现" else "未发现")
            MetricRow(
                "抽样播放",
                when {
                    !report.reachable -> "未检测"
                    report.streamHealthy && report.brokenChannels == 0 -> "全部正常"
                    report.streamHealthy -> "可用（${report.brokenChannels} 个失效）"
                    else -> "未检测到有效播放地址"
                },
                valueColor = when {
                    !report.reachable -> colors.textTertiary
                    report.streamHealthy && report.brokenChannels == 0 -> colors.success
                    report.streamHealthy -> colors.warning
                    else -> colors.error
                },
            )
            if (report.message.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = report.message,
                    color = colors.textSecondary,
                    fontSize = 16.scaled(),
                )
            }
        }
    }
}

/** 指标行：左标签右取值 */
@Composable
private fun MetricRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val colors = PureTvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = colors.textSecondary, fontSize = 16.scaled())
        Text(text = value, color = valueColor ?: colors.textPrimary, fontSize = 16.scaled())
    }
}
