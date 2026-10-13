package com.puretv.player.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.ConfirmDialog
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvSwitch
import com.puretv.player.core.ui.TvDialog
import com.puretv.player.core.util.StringUtils
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.SourceType

/**
 * 源管理分区（需求 5.3.1 M3U/Xtream 导入 · 5.7.4 导入导出备份一键恢复）。
 * 导入过程带进度条与阶段文案，界面不卡死（5.3.2）。
 */
@Composable
fun SourcesSection(
    sources: List<SourceEntity>,
    isPro: Boolean,
    viewModel: SettingsViewModel,
    onOpenBackup: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    var showM3uDialog by remember { mutableStateOf(false) }
    var showXtreamDialog by remember { mutableStateOf(false) }
    var showTvBoxDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SourceEntity?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "本地 M3U 源"
            viewModel.importM3uFromFile(name, uri)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportSourcesM3u(uri)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingGroup(title = "添加源", subtitle = "支持在线搜索、M3U、Xtream、TVBox 配置") {
            SettingRow(
                title = "在线搜索线路",
                subtitle = "关键词搜索全网 MacCMS 采集站，自动探测可用性后多选批量添加",
                value = "搜索",
                onClick = onOpenAddSource,
            )
            SettingRow(
                title = "M3U 网址",
                subtitle = "输入 M3U 播放列表网络地址，解析过程带进度提示",
                value = "添加",
                onClick = { showM3uDialog = true },
            )
            SettingRow(
                title = "本地文件",
                subtitle = "从 U 盘 / 本机存储选择 .m3u 播放列表文件",
                value = "选择文件",
                onClick = { fileLauncher.launch(arrayOf("*/*")) },
            )
            SettingRow(
                title = "Xtream Codes",
                subtitle = "输入服务器地址与账号密码，自动拉取直播与点播",
                value = "添加",
                onClick = { showXtreamDialog = true },
            )
            SettingRow(
                title = "TVBox 聚合配置",
                subtitle = "粘贴 TVBox 配置地址（如「饭太硬」等 .json/.txt），自动按 TVBox 原生格式解析并导入其中的直连影视线路与直播源；" +
                    "csp_/jar 爬虫站点依赖 TVBox 的 Java 爬虫引擎，本应用不加载第三方代码，导入时会自动跳过并提示数量",
                value = "导入",
                onClick = { showTvBoxDialog = true },
            )
        }

        SettingGroup(title = "我的源", subtitle = if (sources.isEmpty()) "尚未添加任何源" else "共 ${sources.size} 个源") {
            if (sources.isEmpty()) {
                Text(
                    text = "还没有直播源。可以先「重新导入内置源」体验 10 条高清样片与 20 条 TVBox 聚合影视线路，" +
                        "或添加自己的 M3U / Xtream / TVBox 源。",
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textHint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                sources.forEach { source ->
                    SourceItemCard(
                        source = source,
                        onEnabledChange = { enabled ->
                            viewModel.setSourceEnabled(source.id, enabled)
                        },
                        onRefresh = { viewModel.refreshSource(source.id, source.name) },
                        onDelete = { deleting = source },
                    )
                }
            }
        }

        SettingGroup(title = "内置示例源", subtitle = "随应用内置 10 条高清样片 + 20 条 TVBox 聚合影视线路，零基础可用（5.6.1）") {
            SettingRow(
                title = "重新导入内置源",
                subtitle = "覆盖写入最新内置频道清单，导入后即可直接播放",
                value = "导入",
                onClick = { viewModel.reinstallBuiltinSource() },
            )
        }

        SettingGroup(title = "导入导出与备份", subtitle = "源导出、全量备份、一键恢复与云端同步（5.7.4 / 5.7.3）") {
            SettingRow(
                title = "导出源为 M3U",
                subtitle = "把已启用源的全部频道导出为标准 M3U 文件，可再导入或迁移",
                value = "导出",
                onClick = {
                    exportLauncher.launch("puretv_sources_${timestampSuffix()}.m3u")
                },
            )
            SettingRow(
                title = "备份与恢复",
                subtitle = "全量备份源配置与设置，换机一键恢复",
                value = "进入",
                onClick = onOpenBackup,
            )
            SettingRow(
                title = "云同步",
                subtitle = "配置、收藏、播放记录打包同步，多设备互通（5.7.3）",
                value = if (isPro) "进入" else "进入（专业模式）",
                onClick = onOpenSync,
            )
        }
    }

    if (showM3uDialog) {
        InputDialog(
            title = "添加 M3U 源",
            label = "M3U 播放列表地址",
            placeholder = "https://example.com/playlist.m3u",
            confirmText = "开始导入",
            onDone = { text ->
                showM3uDialog = false
                if (StringUtils.isHttpUrl(text)) {
                    val name = text.trim().substringAfterLast('/').takeIf { it.isNotBlank() }
                        ?: "M3U 源"
                    viewModel.importM3u(name, text)
                } else {
                    viewModel.showMessage("请输入以 http:// 或 https:// 开头的 M3U 播放列表地址")
                }
            },
            onDismiss = { showM3uDialog = false },
        )
    }

    if (showXtreamDialog) {
        XtreamDialog(
            onConfirm = { name, server, user, password ->
                showXtreamDialog = false
                viewModel.importXtream(name, server, user, password)
            },
            onDismiss = { showXtreamDialog = false },
        )
    }

    if (showTvBoxDialog) {
        InputDialog(
            title = "导入 TVBox 聚合配置",
            label = "TVBox 配置地址（.json / .txt）",
            placeholder = "http://example.com/tvbox.json",
            confirmText = "开始导入",
            onDone = { text ->
                showTvBoxDialog = false
                if (StringUtils.isHttpUrl(text)) {
                    viewModel.importTvBoxConfig(text)
                } else {
                    viewModel.showMessage("请输入以 http:// 或 https:// 开头的 TVBox 配置地址")
                }
            },
            onDismiss = { showTvBoxDialog = false },
        )
    }

    deleting?.let { source ->
        ConfirmDialog(
            title = "删除源",
            message = "确认删除「${source.name}」？该源下的频道列表也会一并移除。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deleting = null
                viewModel.removeSource(source.id, source.name)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** 单个源卡片：名称 / 类型 / 频道数 / 启用开关 / 刷新 / 删除 */
@Composable
private fun SourceItemCard(
    source: SourceEntity,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val isCms = source.type == SourceType.CMS
    val typeLabel = when (source.type) {
        SourceType.XTREAM -> "Xtream"
        SourceType.CMS -> "TVBox 聚合"
        else -> "M3U"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background)
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyLarge.scaled(),
                    color = if (source.enabled) colors.textPrimary else colors.textTertiary,
                )
                Text(
                    text = if (isCms) {
                        // TVBox 线路与 MacCMS 采集是同一体系（TVBox 配置里的 type:1 站点
                        // 本身就是采集接口），不再对用户展示「CMS 匹配」类标签
                        "$typeLabel · ${source.channelCount} 部影片"
                    } else {
                        "$typeLabel · ${source.channelCount} 个频道 · ${source.groupCount} 个分组"
                    },
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TvCard(
                onClick = { onEnabledChange(!source.enabled) },
                modifier = Modifier.padding(start = 12.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (source.enabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = if (source.enabled) colors.success else colors.textTertiary,
                    )
                    // 开关自身可点（用户反馈：此前开关无回调、点击被开关消费成空操作，
                    // 只能点外面的文字才能切换）；文字/卡片区域点击同样生效
                    TvSwitch(
                        checked = source.enabled,
                        onCheckedChange = { onEnabledChange(it) },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvButton(
                text = "刷新",
                onClick = onRefresh,
                variant = TvButtonVariant.GHOST,
                enabled = source.enabled,
            )
            TvButton(
                text = "删除",
                onClick = onDelete,
                variant = TvButtonVariant.GHOST,
                containerColor = colors.error.copy(alpha = 0.14f),
            )
        }
    }
}

/** Xtream 复合输入弹窗：名称 / 服务器地址 / 用户名 / 密码 */
@Composable
private fun XtreamDialog(
    onConfirm: (name: String, server: String, user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canConfirm = server.isNotBlank() && user.isNotBlank()

    TvDialog(onDismiss = onDismiss, title = "添加 Xtream Codes 源") {
        XtreamField("名称", "如：我的 IPTV", name) { name = it }
        XtreamField("服务器地址", "https://example.com:8080", server) { server = it }
        XtreamField("用户名", "账号", user) { user = it }
        XtreamField("密码", "密码", password, password = true) { password = it }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            TvButton(text = "取消", onClick = onDismiss, variant = TvButtonVariant.SECONDARY)
            TvButton(
                text = "开始导入",
                enabled = canConfirm,
                onClick = {
                    val finalName = name.trim().ifBlank {
                        server.trim().substringAfterLast('/').ifBlank { "Xtream 源" }
                    }
                    onConfirm(finalName, server, user, password)
                },
            )
        }
    }
}

/** 弹窗内的单行输入（带标签与占位提示） */
@Composable
private fun XtreamField(
    label: String,
    placeholder: String,
    value: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val colors = PureTvTheme.colors
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.textTertiary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.divider, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = colors.textHint, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
    }
}

/** 备份文件名时间后缀：20261012 */
internal fun timestampSuffix(): String {
    val format = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
    return format.format(java.util.Date())
}
