package com.puretv.player.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.navigation.SettingsSections
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.ThemeMode
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.LoadingOverlay
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard

private const val PAGE_WELCOME = 0
private const val PAGE_INIT = 1
private const val PAGE_DONE = 2

/**
 * 新手引导（需求 5.6.1：引导式配置，一键导入源、一键初始化，零基础可用）。
 *
 * 首次启动进入：欢迎页 →「一键初始化」（内置示例源，带进度）+「一键导入源」（跳设置-源管理）
 * → 完成页（可选主题体验入口）。已有源或已完成引导时，直接给简短欢迎 + 入口按钮，不阻塞进首页。
 */
@Composable
fun GuideScreen(
    nav: NavHostController,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sourceCount by viewModel.sourceCount.collectAsStateWithLifecycle()
    val initProgress by viewModel.initProgress.collectAsStateWithLifecycle()
    val initDone by viewModel.initDone.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var page by remember { mutableIntStateOf(PAGE_WELCOME) }

    fun goHome() {
        viewModel.finish()
        nav.navigate(Routes.HOME) {
            popUpTo(Routes.GUIDE) { inclusive = true }
        }
    }

    val compact = sourceCount > 0 || settings?.onboardingDone == true
    val portrait = com.puretv.player.core.ui.isPortrait()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(
                    horizontal = if (portrait) 24.dp else 96.dp,
                    vertical = if (portrait) 32.dp else 48.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "PureTV 极简影音",
                style = MaterialTheme.typography.headlineLarge.scaled(),
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "直播 · 点播 · 本地影音 一体化播放",
                style = MaterialTheme.typography.bodyLarge.scaled(),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(modifier = Modifier.height(30.dp))

            if (compact) {
                CompactWelcome(
                    sourceCount = sourceCount,
                    onHome = ::goHome,
                    onSettings = { nav.navigate(Routes.settings(SettingsSections.SOURCES)) },
                    onTutorial = { nav.navigate(Routes.TUTORIAL) },
                )
            } else {
                StepDots(current = page)
                Spacer(modifier = Modifier.height(24.dp))
                when (page) {
                    PAGE_WELCOME -> WelcomePage(
                        onStart = { page = PAGE_INIT },
                        onSkip = ::goHome,
                    )

                    PAGE_INIT -> InitPage(
                        progress = initProgress,
                        done = initDone,
                        error = error,
                        onInit = viewModel::oneKeyInit,
                        onCancel = viewModel::cancelInit,
                        onImportManually = { nav.navigate(Routes.settings(SettingsSections.SOURCES)) },
                        onNext = { page = PAGE_DONE },
                    )

                    else -> DonePage(
                        onTheme = viewModel::previewTheme,
                        onHome = ::goHome,
                    )
                }
            }
        }

        initProgress?.let { state ->
            if (state.stage != com.puretv.player.data.model.ImportProgress.Stage.DONE) {
                LoadingOverlay(
                    message = state.message,
                    percent = state.percent,
                    onCancel = { viewModel.cancelInit() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 96.dp),
                )
            }
        }
    }
}

/** 步骤指示点 */
@Composable
private fun StepDots(current: Int) {
    val colors = PureTvTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (index == current) colors.primary else colors.divider),
            )
        }
    }
}

/** 简短欢迎（已有源/已引导过） */
@Composable
private fun CompactWelcome(
    sourceCount: Int,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onTutorial: () -> Unit,
) {
    val colors = PureTvTheme.colors
    Text(
        text = if (sourceCount > 0) "已为你准备好 $sourceCount 个直播源，随时可以开播。"
        else "欢迎回来，可在设置中心随时管理源与播放参数。",
        style = MaterialTheme.typography.bodyLarge.scaled(),
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    Row(
        modifier = Modifier.padding(top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvButton(text = "进入首页", onClick = onHome)
        TvButton(text = "源管理", onClick = onSettings, variant = TvButtonVariant.SECONDARY)
        TvButton(text = "使用教程", onClick = onTutorial, variant = TvButtonVariant.SECONDARY)
    }
}

/** 第 0 页：欢迎 */
@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    val colors = PureTvTheme.colors
    FeatureCard(
        title = "三步开播，零基础可用",
        lines = listOf(
            "· 一键初始化：内置 10 条高清直播/点播样片 + 20 条 TVBox 聚合影视线路，导入即可观看；",
            "· 支持 M3U / Xtream / TVBox 配置源导入，解析带进度、界面不卡死（5.3.1/5.3.2）；",
            "· 默认极简模式只保留核心功能，专业模式开放全部参数（5.6）。",
        ),
    )
    Row(
        modifier = Modifier.padding(top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvButton(text = "开始设置", onClick = onStart)
        TvButton(text = "直接进入首页", onClick = onSkip, variant = TvButtonVariant.SECONDARY)
    }
}

/** 第 1 页：一键初始化 / 一键导入源 */
@Composable
private fun InitPage(
    progress: com.puretv.player.data.model.ImportProgress?,
    done: Boolean,
    error: String?,
    onInit: () -> Unit,
    onCancel: () -> Unit,
    onImportManually: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = PureTvTheme.colors
    Text(
        text = "选择一种方式添加直播源，也可以稍后在设置中心随时添加。",
        style = MaterialTheme.typography.bodyLarge.scaled(),
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    Row(
        modifier = Modifier.padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        GuideOptionCard(
            title = "一键初始化",
            subtitle = "导入内置示例源：10 条高清样片 + 20 条 TVBox 聚合影视线路，全部实测可用",
            buttonText = if (done) "已初始化" else if (progress != null) "初始化中…" else "一键初始化",
            enabled = progress == null,
            onClick = onInit,
            modifier = Modifier.weight(1f),
        )
        GuideOptionCard(
            title = "一键导入源",
            subtitle = "前往设置-源管理，导入自己的 M3U 网址 / 本地文件 / Xtream 源",
            buttonText = "去导入",
            enabled = true,
            onClick = onImportManually,
            modifier = Modifier.weight(1f),
        )
    }
    error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.error,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    Row(
        modifier = Modifier.padding(top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvButton(text = "下一步", onClick = onNext)
        TvButton(text = "稍后再说", onClick = onNext, variant = TvButtonVariant.GHOST)
    }
}

/** 第 2 页：完成 + 主题体验入口 */
@Composable
private fun DonePage(onTheme: (ThemeMode) -> Unit, onHome: () -> Unit) {
    val colors = PureTvTheme.colors
    FeatureCard(
        title = "一切就绪！",
        lines = listOf(
            "· 直播：首页 → 直播专区，上/下换台、左右切分组；",
            "· 点播：海报墙选片，未看完自动记住集数与进度；",
            "· 收藏与记录：菜单键可置顶排序、续播、清理；",
            "· 教程与常见问题随时可在设置中心打开。",
        ),
    )
    Text(
        text = "可选：立即体验一下主题（也可在设置中心随时更改）",
        style = MaterialTheme.typography.labelMedium.scaled(),
        color = colors.textTertiary,
        modifier = Modifier.padding(top = 22.dp),
    )
    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvButton(text = "跟随系统", onClick = { onTheme(ThemeMode.THEME_SYSTEM) }, variant = TvButtonVariant.SECONDARY)
        TvButton(text = "浅色", onClick = { onTheme(ThemeMode.THEME_LIGHT) }, variant = TvButtonVariant.SECONDARY)
        TvButton(text = "深色", onClick = { onTheme(ThemeMode.THEME_DARK) }, variant = TvButtonVariant.SECONDARY)
    }
    TvButton(text = "进入首页", onClick = onHome, modifier = Modifier.padding(top = 28.dp))
}

@Composable
private fun FeatureCard(title: String, lines: List<String>) {
    val colors = PureTvTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .padding(horizontal = 32.dp, vertical = 26.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.scaled(),
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(14.dp))
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.scaled(),
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 引导页选项卡片（带说明与主操作按钮） */
@Composable
private fun GuideOptionCard(
    title: String,
    subtitle: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    TvCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.scaled(),
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium.scaled(),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            TvButton(text = buttonText, onClick = onClick, enabled = enabled)
        }
    }
}
