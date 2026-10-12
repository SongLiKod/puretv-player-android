package com.puretv.player.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

/**
 * TV端文字规范：
 * 1. 所有文字禁止加粗过度——一级文字常规加粗（Medium），二三级文字常规字重（Normal）
 * 2. TV端最小文字尺寸不小于14sp，远距离可视
 * 3. 系统抗锯齿渲染默认开启（Compose 文本渲染自带）
 */
val PureTvTypography = Typography(
    // 页面大标题
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 32.sp),
    // 标题：影片名、频道名、菜单主选项（一级文字）
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    // 正文：简介、节目单、参数说明（二级文字）
    bodyLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    // 标签
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    labelMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    // 占位/次要（三级文字），全局最小 14sp
    labelSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
)

/**
 * 按设置中心「字体大小」缩放文字（保证缩放后仍不小于14sp下限）
 */
@Composable
fun TextStyle.scaled(): TextStyle {
    val scale = LocalTvUiConfig.current.fontScale
    return copy(
        fontSize = (fontSize.value * scale).coerceAtLeast(14f).sp,
        lineHeight = (lineHeight.value * scale).sp,
    )
}

/**
 * 按设置中心「字体大小」缩放字号（sp 值专用；保证缩放后仍不小于14sp下限）
 */
@Composable
fun TextUnit.scaled(): TextUnit {
    val scale = LocalTvUiConfig.current.fontScale
    val raw = if (type == TextUnitType.Sp) value else 16f
    return (raw * scale).coerceAtLeast(14f).sp
}

/**
 * 整数字号缩放便捷重载：20.scaled()（同样保证不小于14sp下限）
 */
@Composable
fun Int.scaled(): TextUnit {
    val scale = LocalTvUiConfig.current.fontScale
    return (this * scale).coerceAtLeast(14f).sp
}
