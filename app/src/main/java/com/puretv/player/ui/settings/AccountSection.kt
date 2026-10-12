package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.data.prefs.AppSettings

/**
 * 账号与服务分区：TMDB API Key（DataStore 键 "tmdb_api_key"，与点播模块共用）、云同步入口。
 * 极简模式不展示本分区（5.6.2/5.6.3：高级能力隐藏，专业模式开放）。
 * Real-Debrid / TorBox 等拓展服务设置由点播模块「拓展服务」页提供，此处仅预留入口说明。
 */
@Composable
fun AccountSection(
    settings: AppSettings?,
    isPro: Boolean,
    viewModel: SettingsViewModel,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    val current = settings ?: return
    var showTmdbDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingGroup(title = "TMDB 影视信息", subtitle = "自动刮削海报、简介、评分与演员表（5.4.2）") {
            InfoLine(
                text = "TMDB API Key 用于点播模块获取影视元数据（海报、简介、评分、演员、分集），" +
                    "不会上传到任何第三方服务器；留空时仅不显示刮削信息，不影响播放。",
            )
            InfoLine(
                text = "获取方式：访问 themoviedb.org 注册账号 → 「设置」→「API」→ 申请 Developer API Key，" +
                    "把得到的 API Key 填入下方即可。",
            )
            SettingRow(
                title = "TMDB API Key",
                subtitle = "仅保存在本机 DataStore",
                value = if (current.tmdbApiKey.isBlank()) "未配置" else "已配置",
                valueColor = if (current.tmdbApiKey.isBlank()) colors.warning else colors.success,
                onClick = { showTmdbDialog = true },
            )
            SettingRow(
                title = "数据区域",
                subtitle = "影响刮削结果的语言与地区",
                value = current.tmdbRegion,
                valueColor = colors.textSecondary,
                onClick = { showTmdbDialog = true },
            )
        }

        SettingGroup(title = "云同步", subtitle = "配置、收藏、播放记录云端同步，多设备互通（5.7.3）") {
            SettingRow(
                title = "云同步",
                subtitle = "把数据打包同步到你选定的目录（可选网盘同步目录），换机一键恢复",
                value = "进入",
                onClick = onOpenSync,
            )
        }

        SettingGroup(title = "拓展服务", subtitle = "Real-Debrid / TorBox 高级资源服务（5.4.5）") {
            InfoLine(
                text = "配置 Token 后，影视详情页可使用 Debrid 加速解析高清稳定资源。",
            )
            com.puretv.player.ui.vod.DebridSettingsContent()
        }
    }

    if (showTmdbDialog) {
        InputDialog(
            title = "TMDB API Key",
            label = "粘贴在 themoviedb.org 申请的 API Key",
            placeholder = "32 位左右的字符串",
            initialValue = current.tmdbApiKey,
            password = true,
            confirmText = "保存",
            onDone = { text ->
                showTmdbDialog = false
                viewModel.setTmdbApiKey(text)
            },
            onDismiss = { showTmdbDialog = false },
        )
    }
}

/** 卡片内的说明文字 */
@Composable
internal fun InfoLine(text: String, modifier: Modifier = Modifier) {
    val colors = PureTvTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.scaled(),
        color = colors.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 卡片内的提示框（弱化背景） */
@Composable
internal fun NoteBox(text: String, modifier: Modifier = Modifier) {
    val colors = PureTvTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.scaled(),
        color = colors.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background)
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
