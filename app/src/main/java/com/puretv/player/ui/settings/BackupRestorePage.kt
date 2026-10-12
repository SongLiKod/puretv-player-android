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

/**
 * 备份与恢复页（需求 5.7.4：源备份、一键恢复）。
 * 全量备份 = 直播源配置 + 设置（JSON，Moshi 序列化），经 SAF 保存/读取。
 */
@Composable
fun BackupRestorePage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.backupTo(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.restoreFrom(uri)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TvButton(text = "返回源管理", onClick = onBack, variant = TvButtonVariant.SECONDARY)

        SettingGroup(title = "全量备份", subtitle = "把源配置与全部设置打包为 JSON 文件，换机可一键恢复") {
            NoteBox(
                text = "备份内容：直播源列表（含 Xtream 账号密码）+ 设置中心全部参数。" +
                    "备份文件请自行妥善保管，建议保存到 U 盘或网盘目录。",
            )
            SettingRow(
                title = "备份到文件",
                subtitle = "选择保存位置（可选网盘目录），文件名 puretv_backup_时间戳.json",
                value = "开始备份",
                onClick = { backupLauncher.launch("puretv_backup_${timestampSuffix()}.json") },
            )
        }

        SettingGroup(title = "一键恢复", subtitle = "从备份文件重建源配置并写回设置（5.7.4）") {
            NoteBox(
                text = "恢复会覆盖同 ID 的源配置，并整体写回设置（含主题、双模式、播放参数与 API Key）。" +
                    "频道列表不会包含在备份中，恢复后在源管理里逐个「刷新」即可重建。",
            )
            SettingRow(
                title = "从备份文件恢复",
                subtitle = "选择此前导出的 puretv_backup_*.json 文件",
                value = "选择文件",
                onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
            )
        }

        SettingGroup(title = "提示") {
            Text(
                text = "若需要同步收藏与播放记录，请使用「云同步」（回到源管理分区进入）。",
                style = MaterialTheme.typography.labelMedium.scaled(),
                color = colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
