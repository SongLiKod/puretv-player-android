package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.navigation.SettingsSections
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.LoadingOverlay
import com.puretv.player.core.ui.LocalPaneBottomPadding
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.data.prefs.UiMode
import kotlinx.coroutines.delay

/** 设置中心内的二级页面（源管理内的备份恢复 / 云同步） */
private enum class SettingsSubPage { NONE, BACKUP, SYNC }

/**
 * 设置中心（需求 5.2.3 UI 自定义 / 5.6 极简与专业双模式 / 5.7 导入导出备份同步 / 5.8 纯净承诺）。
 *
 * 布局：左侧分区导航（外观/播放/源管理/账号与服务/关于）+ 右侧分区内容，
 * 焦点在分区与设置行之间自然移动，符合 TV 遥控器交互（需求 5.2.2）。
 * @param section 直达分区（见 [SettingsSections]），null 时默认停在「外观」
 */
@Composable
fun SettingsScreen(
    nav: NavHostController,
    section: String?,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val backupResult by viewModel.backupResult.collectAsStateWithLifecycle()
    val syncResult by viewModel.syncResult.collectAsStateWithLifecycle()

    val isPro = settings?.uiMode == UiMode.PRO

    var selected by rememberSaveable(section) {
        mutableStateOf(section?.takeIf { it in SECTION_TITLES } ?: SettingsSections.APPEARANCE)
    }
    var subPage by remember { mutableStateOf(SettingsSubPage.NONE) }

    fun selectSection(key: String) {
        selected = key
        subPage = SettingsSubPage.NONE
    }

    // 操作提示条 4 秒后自动消失
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4_000)
            viewModel.clearNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvScaffold(
            title = "设置中心",
            subtitle = if (isPro) "专业模式 · 已开放全部参数" else "极简模式 · 仅展示核心设置",
            onBack = { nav.popBackStack() },
            actions = {
                TvButton(
                    text = if (isPro) "切回极简模式" else "切换专业模式",
                    onClick = { viewModel.setUiMode(if (isPro) UiMode.SIMPLE else UiMode.PRO) },
                    variant = TvButtonVariant.SECONDARY,
                )
            },
        ) {
            val portrait = isPortrait()
            val sectionBody: @Composable (Modifier) -> Unit = { contentMod ->
                Column(
                    modifier = contentMod
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (portrait) 16.dp else 36.dp, vertical = if (portrait) 16.dp else 24.dp)
                        .padding(bottom = LocalPaneBottomPadding.current),
                ) {
                    notice?.let { NoticeBanner(it) }
                    when (selected) {
                        SettingsSections.APPEARANCE -> AppearanceSection(
                            settings = settings,
                            isPro = isPro,
                            viewModel = viewModel,
                            onOpenPlugins = { nav.navigate(Routes.PLUGINS) },
                            onOpenTutorial = { nav.navigate(Routes.TUTORIAL) },
                        )

                        SettingsSections.PLAYBACK -> PlaybackSection(
                            settings = settings,
                            isPro = isPro,
                            viewModel = viewModel,
                        )

                        SettingsSections.SOURCES -> when (subPage) {
                            SettingsSubPage.BACKUP -> BackupRestorePage(
                                onBack = { subPage = SettingsSubPage.NONE },
                                viewModel = viewModel,
                            )

                            SettingsSubPage.SYNC -> CloudSyncPage(
                                settings = settings,
                                lastSyncAt = lastSyncAt,
                                syncResult = syncResult,
                                onBack = { subPage = SettingsSubPage.NONE },
                                viewModel = viewModel,
                            )

                            SettingsSubPage.NONE -> SourcesSection(
                                sources = sources,
                                isPro = isPro,
                                viewModel = viewModel,
                                onOpenBackup = { subPage = SettingsSubPage.BACKUP },
                                onOpenSync = { subPage = SettingsSubPage.SYNC },
                                onOpenAddSource = { nav.navigate(Routes.ADD_SOURCE) },
                            )
                        }

                        SettingsSections.ACCOUNT -> AccountSection(
                            settings = settings,
                            isPro = isPro,
                            viewModel = viewModel,
                            onOpenSync = {
                                selected = SettingsSections.SOURCES
                                subPage = SettingsSubPage.SYNC
                            },
                        )

                        else -> AboutSection(nav = nav)
                    }
                }
            }

            if (portrait) {
                // 竖屏（手机）：分区导航改为顶部横排 chips
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsSectionChips(
                        selected = selected,
                        isPro = isPro,
                        onSelect = ::selectSection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(color = colors.divider, thickness = 1.dp)
                    sectionBody(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsRail(
                        selected = selected,
                        isPro = isPro,
                        onSelect = ::selectSection,
                        onUiModeChange = { enabled ->
                            viewModel.setUiMode(if (enabled) UiMode.PRO else UiMode.SIMPLE)
                        },
                        modifier = Modifier
                            .width(290.dp)
                            .fillMaxHeight()
                            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp),
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        color = colors.divider,
                    )
                    sectionBody(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        progress?.let { state ->
            LoadingOverlay(
                message = state.message,
                percent = state.percent,
                onCancel = if (state.cancelable) ({ viewModel.cancelProgress() }) else null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp),
            )
        }
    }
}

private val SECTION_TITLES = mapOf(
    SettingsSections.APPEARANCE to "外观",
    SettingsSections.PLAYBACK to "播放",
    SettingsSections.SOURCES to "源管理",
    SettingsSections.ACCOUNT to "账号与服务",
    SettingsSections.ABOUT to "关于",
)

/** 左侧分区导航（TV 焦点：上下选择分区，OK 切换内容） */
@Composable
private fun SettingsRail(
    selected: String,
    isPro: Boolean,
    onSelect: (String) -> Unit,
    onUiModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "设置分区",
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        SECTION_TITLES.filterKeys { it != SettingsSections.ACCOUNT || isPro }
            .forEach { (key, title) ->
                val active = key == selected
                TvCard(
                    onClick = { onSelect(key) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (active) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(18.dp)
                                .background(
                                    if (active) colors.primary else colors.divider,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge.scaled(),
                            color = if (active) colors.highlight else colors.textPrimary,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        Box(modifier = Modifier.weight(1f))
        SwitchRow(
            title = "专业模式",
            subtitle = "开放缓冲自定义、解码、插件、云同步与 API Key",
            checked = isPro,
            onCheckedChange = onUiModeChange,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/** 竖屏（手机）顶部横排分区导航：替代左侧分区栏 */
@Composable
private fun SettingsSectionChips(
    selected: String,
    isPro: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            SECTION_TITLES.filterKeys { it != SettingsSections.ACCOUNT || isPro }.toList(),
            key = { it.first },
        ) { (key, title) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) colors.primary.copy(alpha = 0.2f) else colors.card)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.scaled(),
                    color = if (active) colors.highlight else colors.textSecondary,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 顶部操作结果提示条 */
@Composable
private fun NoticeBanner(message: String) {
    val colors = PureTvTheme.colors
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium.scaled(),
        color = colors.success,
        modifier = Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
