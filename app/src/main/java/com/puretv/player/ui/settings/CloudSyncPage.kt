package com.puretv.player.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.util.TimeUtils
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.sync.SyncSummary

/**
 * 云同步页（需求 5.7.3：支持配置、收藏、播放记录云端同步，多设备互通）。
 *
 * 实现方式：把 sources + settings + favorites + history 打包为 JSON 数据包，
 * 导出到用户选定目录（可选网盘同步目录），其他设备从同一目录导入即可互通；
 * 同步状态页展示上次同步时间、进度与结果反馈（文档未规定云端后端协议，
 * 故采用可选云盘目录的同步链路，无需注册账号、不做设备绑定）。
 */
@Composable
fun CloudSyncPage(
    settings: AppSettings?,
    lastSyncAt: Long,
    syncResult: SyncSummary?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors

    val syncLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.syncTo(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.restoreSyncFrom(uri)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TvButton(text = "返回源管理", onClick = onBack, variant = TvButtonVariant.SECONDARY)

        SettingGroup(title = "同步状态") {
            SettingRow(
                title = "上次同步时间",
                value = if (lastSyncAt > 0L) TimeUtils.formatDateTime(lastSyncAt) else "尚未同步",
                valueColor = if (lastSyncAt > 0L) colors.textSecondary else colors.warning,
            )
            SettingRow(
                title = "本机数据",
                subtitle = "源 · 设置 · 收藏 · 播放记录",
                value = settings?.let {
                    "字体 ${formatPercent(it.fontScale)} · 模式 ${if (it.uiMode.name == "PRO") "专业" else "极简"}"
                } ?: "",
                valueColor = colors.textTertiary,
            )
            syncResult?.let { result ->
                Text(
                    text = "最近一次结果：${result.describe}（${TimeUtils.formatDateTime(result.at)}）",
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.success,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        SettingGroup(title = "立即同步", subtitle = "打包本机配置、收藏与播放记录并导出（5.7.3）") {
            NoteBox(
                text = "把数据导出到你选定的目录——推荐选择网盘同步目录（如坚果云、iCloud 同步盘等），" +
                    "其他设备登录同一网盘后从该目录恢复，即可实现多设备互通；" +
                    "全程无需注册账号，也不做设备绑定（5.1.3）。",
            )
            SettingRow(
                title = "同步到目录",
                subtitle = "导出 puretv_sync_时间戳.json 到所选位置",
                value = "立即同步",
                onClick = { syncLauncher.launch("puretv_sync_${timestampSuffix()}.json") },
            )
        }

        SettingGroup(title = "从云端恢复", subtitle = "读取同步数据包并覆盖写入本机") {
            NoteBox(
                text = "恢复内容：直播源配置、设置中心全部参数、我的收藏、播放记录（含断点续播位置）。" +
                    "同 ID 的源 / 收藏 / 记录会被覆盖，其余保留。",
            )
            SettingRow(
                title = "选择同步数据包",
                subtitle = "选择此前同步导出的 puretv_sync_*.json 文件",
                value = "开始恢复",
                onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
            )
        }
    }
}

private fun formatPercent(scale: Float): String = "${(scale * 100).toInt()}%"
