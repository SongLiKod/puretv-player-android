package com.puretv.player.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.ConfirmDialog
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.ErrorState
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvLinearProgress
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.TvSwitch
import com.puretv.player.data.plugin.PluginManager
import com.puretv.player.data.plugin.PluginType
import kotlinx.coroutines.delay

/**
 * 插件市场（需求 5.7.1：轻量化插件市场，可按需安装字幕/资源/美化插件；
 * 5.7.2：插件官方审核上架，杜绝闪退、崩溃、恶意插件）。
 */
@Composable
fun PluginsScreen(
    nav: NavHostController,
    viewModel: PluginsViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(PluginType.SUBTITLE) }
    var uninstalling by remember { mutableStateOf<PendingUninstall?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4_000)
            viewModel.clearNotice()
        }
    }

    TvScaffold(
        title = "插件市场",
        subtitle = "官方审核上架 · 按需安装 · 稳定可控",
        onBack = { nav.popBackStack() },
        actions = {
            TvButton(
                text = "重新校验目录",
                onClick = viewModel::refresh,
                variant = TvButtonVariant.SECONDARY,
            )
        },
    ) {
        val portraitPad = if (com.puretv.player.core.ui.isPortrait()) 16.dp else 40.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = portraitPad, vertical = 24.dp),
        ) {
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.scaled(),
                    color = colors.success,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }

            // 三类 Tab：字幕增强 / 资源聚合 / 界面美化（5.7.1）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PluginType.ALL.forEach { type ->
                    TabButton(
                        text = PluginType.label(type),
                        selected = type == selectedType,
                        onClick = { selectedType = type },
                    )
                }
            }

            when {
                status != null && status?.ok == false -> ErrorState(
                    message = status?.message ?: "插件目录校验未通过",
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(top = 24.dp),
                )

                catalog.isEmpty() -> EmptyState(
                    text = "正在加载官方插件目录…",
                    modifier = Modifier.padding(top = 24.dp),
                )

                else -> {
                    val items = catalog.filter { it.type == selectedType }
                    if (items.isEmpty()) {
                        EmptyState(
                            text = "该分类暂无插件",
                            subtext = "官方插件目录更新后会自动出现在这里",
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items.forEach { item ->
                                PluginCard(
                                    item = item,
                                    installUi = installing?.takeIf { it.id == item.id },
                                    onInstall = { viewModel.install(item.id) },
                                    onUninstall = {
                                        uninstalling = PendingUninstall(item.id, item.name)
                                    },
                                    onEnabledChange = { enabled ->
                                        viewModel.setEnabled(item.id, enabled)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uninstalling?.let { target ->
        ConfirmDialog(
            title = "卸载插件",
            message = "确认卸载「${target.name}」？卸载后相关增强能力将立即失效，可随时重新安装。",
            confirmText = "卸载",
            danger = true,
            onConfirm = {
                viewModel.uninstall(target.id, target.name)
                uninstalling = null
            },
            onDismiss = { uninstalling = null },
        )
    }
}

private data class PendingUninstall(val id: String, val name: String)

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    TvCard(
        onClick = onClick,
        modifier = Modifier.width(170.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.scaled(),
            color = if (selected) colors.highlight else colors.textSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** 插件卡片：名称 / 版本 / 官方徽标 / 描述 / 安装状态与操作 */
@Composable
private fun PluginCard(
    item: PluginManager.PluginItem,
    installUi: PluginInstallUi?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colors = PureTvTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.scaled(),
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            OfficialBadge()
            Text(
                text = " v${item.version}",
                style = MaterialTheme.typography.labelMedium.scaled(),
                color = colors.textTertiary,
            )
        }
        Text(
            text = item.description,
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = if (item.checksumValid) "摘要校验通过（sha256）" else "摘要校验异常，请重新校验目录",
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = if (item.checksumValid) colors.success else colors.error,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (installUi != null) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = installUi.message,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textSecondary,
                )
                TvLinearProgress(
                    percent = installUi.percent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.installed) {
                    Text(
                        text = if (item.enabled) "已安装 · 已启用" else "已安装 · 已停用",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = if (item.enabled) colors.success else colors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "启用",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = colors.textSecondary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    TvCard(
                        onClick = { onEnabledChange(!item.enabled) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            // 开关自身可点（与源管理一致：无回调时开关会吞掉点击导致点按无效）
                            TvSwitch(
                                checked = item.enabled,
                                onCheckedChange = { onEnabledChange(it) },
                            )
                        }
                    }
                    TvButton(
                        text = "卸载",
                        onClick = onUninstall,
                        variant = TvButtonVariant.GHOST,
                        containerColor = colors.error.copy(alpha = 0.14f),
                        modifier = Modifier.padding(start = 14.dp),
                    )
                } else {
                    Text(
                        text = "未安装",
                        style = MaterialTheme.typography.labelMedium.scaled(),
                        color = colors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    TvButton(text = "安装", onClick = onInstall)
                }
            }
        }
    }
}

@Composable
private fun OfficialBadge() {
    val colors = PureTvTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.success.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "官方",
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.success,
            fontWeight = FontWeight.Medium,
        )
    }
}
