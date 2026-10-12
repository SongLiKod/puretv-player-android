package com.puretv.player.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 三套主题枚举（代码枚举，名称与技术文档一致）
 * THEME_SYSTEM = 跟随系统 / THEME_LIGHT = 浅色模式 / THEME_DARK = 深色模式
 */
enum class ThemeMode {
    THEME_SYSTEM,
    THEME_LIGHT,
    THEME_DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: THEME_SYSTEM
    }
}

/** 焦点样式（设置中心可自定义，对应需求：自定义焦点样式） */
enum class FocusStyle {
    SCALE_AND_BORDER, // 缩放1.02倍 + 边框高亮（默认，双重提示）
    BORDER,           // 仅边框高亮
    SCALE;            // 仅缩放

    companion object {
        fun fromName(name: String?): FocusStyle =
            entries.firstOrNull { it.name == name } ?: SCALE_AND_BORDER
    }
}

/** 界面布局密度（设置中心可自定义，对应需求：自定义界面布局） */
enum class LayoutDensity {
    COMPACT,   // 紧凑
    STANDARD,  // 标准（默认）
    RELAXED;   // 宽松

    companion object {
        fun fromName(name: String?): LayoutDensity =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/**
 * 全局主题色集合：深色/浅色两套完整色值 + 三套主题共用固定色
 */
@Immutable
data class PureTvColors(
    val isDark: Boolean,
    // 全局背景体系
    val background: Color,
    val card: Color,
    val dialog: Color,
    val divider: Color,
    // 主题主辅色
    val primary: Color,
    val highlight: Color,
    // 文字四级层级
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textHint: Color,
    // 焦点专属色
    val focusBorder: Color,
    val focusPressed: Color,
    // 全局固定色（不随主题切换）
    val success: Color = FixedSuccess,
    val warning: Color = FixedWarning,
    val error: Color = FixedError,
    val link: Color = FixedLink,
    val scrim: Color = FixedScrim,
) {
    companion object {
        /** 深色主题全套色值 */
        val Dark = PureTvColors(
            isDark = true,
            background = DarkBackground,
            card = DarkCard,
            dialog = DarkDialog,
            divider = DarkDivider,
            primary = DarkPrimary,
            highlight = DarkHighlight,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            textTertiary = DarkTextTertiary,
            textHint = DarkTextHint,
            focusBorder = DarkFocusBorder,
            focusPressed = DarkFocusPressed,
        )

        /** 浅色主题全套色值 */
        val Light = PureTvColors(
            isDark = false,
            background = LightBackground,
            card = LightCard,
            dialog = LightDialog,
            divider = LightDivider,
            primary = LightPrimary,
            highlight = LightHighlight,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            textTertiary = LightTextTertiary,
            textHint = LightTextHint,
            focusBorder = LightFocusBorder,
            focusPressed = LightFocusPressed,
        )

        /**
         * 自定义皮肤覆盖：仅允许覆盖主色/高亮色，保证全局仅蓝白灰黑体系（配色禁忌）
         */
        fun applySkin(base: PureTvColors, accent: Color?, highlight: Color?): PureTvColors =
            base.copy(
                primary = accent ?: base.primary,
                highlight = highlight ?: base.highlight,
                focusBorder = accent ?: base.focusBorder,
                link = accent ?: base.link,
            )
    }
}

/** 全局UI配置（字体大小、焦点样式、界面布局、皮肤），由设置中心驱动 */
@Immutable
data class TvUiConfig(
    val fontScale: Float = 1f,
    val focusStyle: FocusStyle = FocusStyle.SCALE_AND_BORDER,
    val layoutDensity: LayoutDensity = LayoutDensity.STANDARD,
    val skinId: String = "default",
) {
    /** 布局间距系数 */
    val densityScale: Float
        get() = when (layoutDensity) {
            LayoutDensity.COMPACT -> 0.8f
            LayoutDensity.STANDARD -> 1f
            LayoutDensity.RELAXED -> 1.25f
        }
}

val LocalPureTvColors = staticCompositionLocalOf { PureTvColors.Dark }
val LocalTvUiConfig = staticCompositionLocalOf { TvUiConfig() }

/** 在任意 Composable 中读取当前主题色 */
object PureTvTheme {
    val colors: PureTvColors
        @Composable @ReadOnlyComposable get() = LocalPureTvColors.current

    val config: TvUiConfig
        @Composable @ReadOnlyComposable get() = LocalTvUiConfig.current
}

/**
 * 应用主题：
 * 1. THEME_SYSTEM 读取系统夜间模式状态（跟随系统为默认出厂主题）
 * 2. THEME_LIGHT / THEME_DARK 强制对应整套色值
 * 3. DataStore 主题选项或系统主题变化时自动重组，实时动态换肤、无需重启
 */
@Composable
fun PureTvTheme(
    themeMode: ThemeMode,
    uiConfig: TvUiConfig = TvUiConfig(),
    skinAccent: Color? = null,
    skinHighlight: Color? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.THEME_SYSTEM -> systemDark
        ThemeMode.THEME_LIGHT -> false
        ThemeMode.THEME_DARK -> true
    }
    val colors = if (dark) PureTvColors.Dark else PureTvColors.Light
    val finalColors = PureTvColors.applySkin(colors, skinAccent, skinHighlight)

    val colorScheme = if (dark) {
        darkColorScheme(
            primary = finalColors.primary,
            onPrimary = Color.White,
            background = finalColors.background,
            onBackground = finalColors.textPrimary,
            surface = finalColors.card,
            onSurface = finalColors.textPrimary,
            surfaceVariant = finalColors.card,
            onSurfaceVariant = finalColors.textSecondary,
            outline = finalColors.divider,
            error = finalColors.error,
        )
    } else {
        lightColorScheme(
            primary = finalColors.primary,
            onPrimary = Color.White,
            background = finalColors.background,
            onBackground = finalColors.textPrimary,
            surface = finalColors.card,
            onSurface = finalColors.textPrimary,
            surfaceVariant = finalColors.card,
            onSurfaceVariant = finalColors.textSecondary,
            outline = finalColors.divider,
            error = finalColors.error,
        )
    }

    CompositionLocalProvider(
        LocalPureTvColors provides finalColors,
        LocalTvUiConfig provides uiConfig,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PureTvTypography,
            content = content,
        )
    }
}
