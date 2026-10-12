package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.FocusStyle
import com.puretv.player.core.theme.LayoutDensity
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.ThemeMode
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvLinearProgress
import com.puretv.player.data.prefs.AppSettings

/**
 * 外观分区（需求 5.2.3：自定义皮肤、字体大小、焦点样式、界面布局）。
 * 极简模式仅开放主题与字体大小，界面自定义（焦点样式/布局密度/皮肤）为专业模式权限（5.6.3）。
 */
@Composable
fun AppearanceSection(
    settings: AppSettings?,
    isPro: Boolean,
    viewModel: SettingsViewModel,
    onOpenPlugins: () -> Unit,
    onOpenTutorial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    val current = settings ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        SettingGroup(title = "主题", subtitle = "出厂默认跟随系统，系统切换深浅色时无闪屏实时换肤") {
            OptionSelectorRow(
                title = "主题模式",
                subtitle = "跟随系统 / 浅色 / 深色",
                options = listOf(
                    ThemeMode.THEME_SYSTEM.name to "跟随系统（默认）",
                    ThemeMode.THEME_LIGHT.name to "浅色模式",
                    ThemeMode.THEME_DARK.name to "深色模式",
                ),
                selectedKey = current.themeMode.name,
                onSelect = { key -> viewModel.setThemeMode(ThemeMode.valueOf(key)) },
            )
        }

        SettingGroup(title = "字体大小", subtitle = "TV 端最小字号 14sp，远距离清晰可视") {
            OptionSelectorRow(
                title = "字体缩放",
                options = listOf(
                    "0.9" to "小（90%）",
                    "1.0" to "标准（100%）",
                    "1.15" to "较大（115%）",
                    "1.3" to "大（130%）",
                    "1.5" to "特大（150%）",
                ),
                selectedKey = formatScale(current.fontScale),
                onSelect = { key -> viewModel.setFontScale(key.toFloat()) },
            )
        }

        if (isPro) {
            SettingGroup(title = "界面自定义", subtitle = "焦点样式与布局密度，仅专业模式开放（5.6.3）") {
                OptionSelectorRow(
                    title = "焦点样式",
                    subtitle = "可点击控件统一焦点边框与动效",
                    options = listOf(
                        FocusStyle.SCALE_AND_BORDER.name to "缩放 + 边框（默认）",
                        FocusStyle.BORDER.name to "仅边框高亮",
                        FocusStyle.SCALE.name to "仅缩放",
                    ),
                    selectedKey = current.focusStyle.name,
                    onSelect = { key -> viewModel.setFocusStyle(FocusStyle.valueOf(key)) },
                )
                OptionSelectorRow(
                    title = "界面布局密度",
                    subtitle = "列表与卡片的留白间距",
                    options = listOf(
                        LayoutDensity.COMPACT.name to "紧凑",
                        LayoutDensity.STANDARD.name to "标准（默认）",
                        LayoutDensity.RELAXED.name to "宽松",
                    ),
                    selectedKey = current.layoutDensity.name,
                    onSelect = { key -> viewModel.setLayoutDensity(LayoutDensity.valueOf(key)) },
                )
            }

            SkinSection(current = current, onApply = viewModel::applySkin)

            SettingGroup(title = "拓展能力") {
                SettingRow(
                    title = "插件市场",
                    subtitle = "字幕增强 · 资源聚合 · 界面美化（官方审核上架）",
                    value = "进入",
                    onClick = onOpenPlugins,
                )
                SettingRow(
                    title = "使用教程与常见问题",
                    subtitle = "遥控器操作速查、常见故障自助修复",
                    value = "进入",
                    onClick = onOpenTutorial,
                )
            }
        } else {
            Text(
                text = "提示：焦点样式、布局密度、自定义皮肤、插件市场等进阶能力，" +
                    "可在右上角切换为「专业模式」后开放（5.6.3）。",
                style = MaterialTheme.typography.labelMedium.scaled(),
                color = colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.card)
                    .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

private fun formatScale(scale: Float): String = when {
    scale <= 0.92f -> "0.9"
    scale <= 1.07f -> "1.0"
    scale <= 1.22f -> "1.15"
    scale <= 1.4f -> "1.3"
    else -> "1.5"
}

/** 自定义皮肤：仅主色/高亮色两项取值，限定品牌蓝体系（配色禁忌 7.3：全局仅蓝白灰黑） */
@Composable
private fun SkinSection(current: AppSettings, onApply: (SkinPreset) -> Unit) {
    val colors = PureTvTheme.colors
    val selected = SkinPreset.from(current)
    SettingGroup(title = "自定义皮肤", subtitle = "仅开放主色与高亮色，且限定品牌蓝体系，杜绝杂色混用") {
        SkinPreset.ALL.forEach { preset ->
            SkinPresetRow(
                preset = preset,
                active = preset.id == selected.id,
                onClick = { onApply(preset) },
            )
        }
        SkinPreview()
    }
}

@Composable
private fun SkinPresetRow(preset: SkinPreset, active: Boolean, onClick: () -> Unit) {
    val colors = PureTvTheme.colors
    TvCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(color = Color(preset.accent or 0xFF000000))
            Spacer(modifier = Modifier.width(8.dp))
            Swatch(color = Color(preset.highlight or 0xFF000000))
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyLarge.scaled(),
                    color = colors.textPrimary,
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (active) {
                Text(
                    text = "使用中",
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.success,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color)
            .border(1.dp, PureTvTheme.colors.divider, RoundedCornerShape(7.dp)),
    )
}

/** 实时预览行：按当前主题真实取色渲染主按钮 / 次按钮 / 进度条效果 */
@Composable
private fun SkinPreview() {
    val colors = PureTvTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background)
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
            .padding(18.dp),
    ) {
        Text(
            text = "实时预览",
            style = MaterialTheme.typography.labelMedium.scaled(),
            color = colors.textTertiary,
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewButton(text = "主按钮", container = colors.primary, content = Color.White)
            Spacer(modifier = Modifier.width(12.dp))
            PreviewButton(
                text = "次按钮",
                container = colors.card,
                content = colors.textPrimary,
                bordered = true,
            )
        }
        TvLinearProgress(
            percent = 64,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )
    }
}

/** 预览专用静态按钮（不可聚焦，仅展示配色效果，避免出现无响应的死控件） */
@Composable
private fun PreviewButton(
    text: String,
    container: Color,
    content: Color,
    bordered: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .border(
                width = 1.dp,
                color = if (bordered) PureTvTheme.colors.divider else container,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
