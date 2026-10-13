package com.puretv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.BufferProfile
import java.util.Locale

/**
 * 播放分区（需求 5.5 智能缓冲 / 5.4.4 清晰度智能切换 / 5.3.3 清晰度记忆）。
 * 极简模式只展示缓冲档位与两项核心开关；缓冲自定义参数、解码与倍速为专业模式权限（5.6.3）。
 */
@Composable
fun PlaybackSection(
    settings: AppSettings?,
    isPro: Boolean,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val current = settings ?: return
    val cacheSize by viewModel.cacheSizeLabel.collectAsStateWithLifecycle()
    val brokenMarkCount by viewModel.brokenMarkCount.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingGroup(title = "智能缓冲", subtitle = "弱网预加载、强网提速，卡顿自动纠错（5.5.3）") {
            OptionSelectorRow(
                title = "缓冲档位",
                subtitle = "小缓冲换台更快，大缓冲弱网更稳",
                options = bufferOptions(isPro),
                selectedKey = current.bufferProfile.name,
                onSelect = { key -> viewModel.setBufferProfile(BufferProfile.valueOf(key)) },
            )
            if (isPro && current.bufferProfile == BufferProfile.CUSTOM) {
                BufferParamsEditor(settings = current, onSave = viewModel::setBufferParams)
            }
        }

        SettingGroup(title = "清晰度与换源") {
            SwitchRow(
                title = "清晰度智能切换",
                subtitle = "根据网速自动在 1080P / 4K 之间切换（5.4.4）",
                checked = current.autoQualitySwitch,
                onCheckedChange = viewModel::setAutoQualitySwitch,
            )
            SwitchRow(
                title = "记住直播清晰度",
                subtitle = "下次进入该频道自动使用上次选择的清晰度（5.3.3）",
                checked = current.rememberChannelQuality,
                onCheckedChange = viewModel::setRememberChannelQuality,
            )
            if (isPro) {
                SwitchRow(
                    title = "卡顿自动换备用源",
                    subtitle = "连续卡顿自动切换备用地址，减少断流（5.5.3）",
                    checked = current.autoSwitchBackupSource,
                    onCheckedChange = viewModel::setAutoSwitchBackup,
                )
            }
        }

        SettingGroup(title = "播放界面", subtitle = "全屏播放时的常驻显示元素") {
            SwitchRow(
                title = "底部迷你进度条",
                subtitle = "全屏播放时屏幕底部常驻细进度条（直播不显示）",
                checked = current.miniProgressEnabled,
                onCheckedChange = viewModel::setMiniProgressEnabled,
            )
        }

        SettingGroup(title = "跨集预缓冲", subtitle = "提前缓冲后续剧集（最长 2 小时），选集/分集列表显示缓冲进度") {
            OptionSelectorRow(
                title = "预缓冲时长",
                subtitle = "按当前集时长折算集数，如 30 分钟 ≈ 本集 + 后续共 3 集（10 分钟/集）",
                options = preloadOptions(),
                selectedKey = current.preloadMinutes.toString(),
                onSelect = { key -> viewModel.setPreloadMinutes(key.toInt()) },
            )
            OptionSelectorRow(
                title = "连播倒计时",
                subtitle = "本集结束前提示「N 秒后播放下一集」，到点自动连播（0=关闭）",
                options = countdownOptions(),
                selectedKey = current.nextEpisodeCountdownSec.toString(),
                onSelect = { key -> viewModel.setNextEpisodeCountdown(key.toInt()) },
            )
        }

        // 跳过片头片尾：由插件「跳过片头片尾」（播放增强类）提供，卸载插件即隐藏并还原为关闭
        val skipPlugin by viewModel.playbackEnhanceInstalled.collectAsStateWithLifecycle()
        if (skipPlugin) {
            SettingGroup(
                title = "跳过片头片尾",
                subtitle = "点播每集自动执行；片头过短的内容不跳，0=关闭（5.6 易用性）",
            ) {
                OptionSelectorRow(
                    title = "跳过片头",
                    subtitle = "本集开始自动跳过片头曲（如 60 秒），回看片头不再打断",
                    options = skipIntroOptions(),
                    selectedKey = current.skipIntroSec.toString(),
                    onSelect = { key -> viewModel.setSkipIntro(key.toInt()) },
                )
                OptionSelectorRow(
                    title = "跳过片尾",
                    subtitle = "剩余时长进入片尾：有下一集直接连播，电影/最后一集跳到结尾",
                    options = skipOutroOptions(),
                    selectedKey = current.skipOutroSec.toString(),
                    onSelect = { key -> viewModel.setSkipOutro(key.toInt()) },
                )
            }
        }

        SettingGroup(title = "存储与缓存", subtitle = "缓存超限自动淘汰最久未用内容；换集自动回收上一集") {
            OptionSelectorRow(
                title = "缓存上限",
                subtitle = "占用超过上限时自动淘汰最旧内容（修改后重启应用生效）",
                options = cacheLimitOptions(),
                selectedKey = current.cacheMaxMb.toString(),
                onSelect = { key -> viewModel.setCacheMaxMb(key.toInt()) },
            )
            SettingRow(
                title = "缓存占用",
                subtitle = "点按清除全部播放缓存（正在播放的内容会重新拉取）",
                value = cacheSize,
                valueColor = PureTvTheme.colors.textSecondary,
                onClick = { viewModel.clearMediaCache() },
            )
            SettingRow(
                title = "失效资源标记",
                subtitle = "源端确定不可播放的影片会自动从列表隐藏（重新播放成功自动恢复显示），点按清除全部标记",
                value = "$brokenMarkCount 部",
                valueColor = PureTvTheme.colors.textSecondary,
                onClick = { viewModel.clearBrokenMarks() },
            )
        }

        if (isPro) {
            SettingGroup(title = "解码与倍速", subtitle = "4K / HEVC 硬件加速与默认播放倍速（5.5.2）") {
                SwitchRow(
                    title = "硬件加速解码",
                    subtitle = "关闭后使用软解兜底，兼容特殊编码流",
                    checked = current.hardwareDecode,
                    onCheckedChange = viewModel::setHardwareDecode,
                )
                OptionSelectorRow(
                    title = "默认播放倍速",
                    subtitle = "进入播放器时的初始倍速",
                    options = listOf(
                        "0.5" to "0.5x",
                        "0.75" to "0.75x",
                        "1.0" to "1.0x（正常）",
                        "1.25" to "1.25x",
                        "1.5" to "1.5x",
                        "2.0" to "2.0x",
                        "2.5" to "2.5x",
                        "3.0" to "3.0x",
                    ),
                    selectedKey = speedKey(current.defaultSpeed),
                    onSelect = { key -> viewModel.setDefaultSpeed(key.toFloat()) },
                )
            }
        }
    }
}

private fun bufferOptions(isPro: Boolean): List<Pair<String, String>> {
    val base = listOf(
        BufferProfile.SMALL.name to "小（换台更快）",
        BufferProfile.MEDIUM.name to "中（默认均衡）",
        BufferProfile.LARGE.name to "大（弱网更稳）",
    )
    return if (isPro) base + (BufferProfile.CUSTOM.name to "自定义") else base
}

/** 跨集预缓冲时长档位（分钟；0=关闭，120=2 小时上限） */
private fun preloadOptions(): List<Pair<String, String>> = listOf(
    "0" to "关闭",
    "10" to "10 分钟",
    "30" to "30 分钟",
    "60" to "1 小时",
    "120" to "2 小时（最长）",
)

/** 连播倒计时档位（秒；0=关闭） */
private fun countdownOptions(): List<Pair<String, String>> = listOf(
    "0" to "关闭",
    "3" to "3 秒",
    "5" to "5 秒",
    "10" to "10 秒",
    "15" to "15 秒",
    "30" to "30 秒",
)

/** 跳过片头档位（秒；0=关闭） */
private fun skipIntroOptions(): List<Pair<String, String>> = listOf(
    "0" to "关闭",
    "15" to "15 秒",
    "30" to "30 秒",
    "45" to "45 秒",
    "60" to "60 秒",
    "90" to "90 秒",
)

/** 跳过片尾档位（秒；0=关闭） */
private fun skipOutroOptions(): List<Pair<String, String>> = listOf(
    "0" to "关闭",
    "10" to "10 秒",
    "20" to "20 秒",
    "30" to "30 秒",
    "60" to "60 秒",
)

/** 播放缓存上限档位（MB；默认 4GB） */
private fun cacheLimitOptions(): List<Pair<String, String>> = listOf(
    "512" to "512 MB",
    "1024" to "1 GB",
    "2048" to "2 GB",
    "4096" to "4 GB（默认）",
    "8192" to "8 GB",
)

private fun speedKey(speed: Float): String = if (speed % 1f == 0f) {
    String.format(Locale.US, "%.1f", speed)
} else {
    String.format(Locale.US, "%.2f", speed)
}

/** 缓冲时长展示：整分钟显示「分钟」，其余显示「秒」（不出现毫秒） */
private fun formatBufferMs(ms: Int): String = when {
    ms >= 60_000 && ms % 60_000 == 0 -> "${ms / 60_000} 分钟"
    ms % 1000 == 0 -> "${ms / 1000} 秒"
    else -> String.format(Locale.US, "%.1f 秒", ms / 1000.0)
}

/** 缓冲时长输入初值：秒（整数不带小数） */
private fun formatBufferInput(ms: Int): String =
    if (ms % 1000 == 0) (ms / 1000).toString()
    else String.format(Locale.US, "%.1f", ms / 1000.0)

/** 自定义缓冲参数编辑（专业模式 + 自定义档位）：存储为毫秒，展示/输入用秒或分钟 */
@Composable
private fun BufferParamsEditor(
    settings: AppSettings,
    onSave: (maxMs: Int, minMs: Int, forPlaybackMs: Int, forAfterMs: Int) -> Unit,
) {
    val colors = PureTvTheme.colors
    var editing by remember { mutableStateOf<BufferField?>(null) }

    BufferField.entries.forEach { field ->
        val value = when (field) {
            BufferField.MAX -> settings.bufferMaxMs
            BufferField.MIN -> settings.bufferMinMs
            BufferField.PLAYBACK -> settings.bufferForPlaybackMs
            BufferField.AFTER -> settings.bufferForAfterMs
        }
        SettingRow(
            title = field.title,
            subtitle = field.subtitle,
            value = formatBufferMs(value),
            valueColor = colors.textSecondary,
            onClick = { editing = field },
        )
    }

    editing?.let { field ->
        val initial = when (field) {
            BufferField.MAX -> settings.bufferMaxMs
            BufferField.MIN -> settings.bufferMinMs
            BufferField.PLAYBACK -> settings.bufferForPlaybackMs
            BufferField.AFTER -> settings.bufferForAfterMs
        }
        InputDialog(
            title = field.title,
            label = field.subtitle,
            initialValue = formatBufferInput(initial),
            placeholder = "秒",
            onDone = { text ->
                val seconds = text.trim().filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                if (seconds != null && seconds > 0) {
                    val ms = (seconds * 1000).toInt().coerceAtLeast(1)
                    onSave(
                        if (field == BufferField.MAX) ms else settings.bufferMaxMs,
                        if (field == BufferField.MIN) ms else settings.bufferMinMs,
                        if (field == BufferField.PLAYBACK) ms else settings.bufferForPlaybackMs,
                        if (field == BufferField.AFTER) ms else settings.bufferForAfterMs,
                    )
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private enum class BufferField(val title: String, val subtitle: String) {
    MAX("缓冲上限", "最大缓冲时长，越大越抗抖动、占用内存越高"),
    MIN("缓冲下限", "低于该缓冲时长触发补载"),
    PLAYBACK("起播缓冲", "起播前预缓冲时长，越小起播越快"),
    AFTER("卡顿补载", "卡顿后重新补载的目标缓冲时长"),
}
