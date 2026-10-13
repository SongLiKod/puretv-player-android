package com.puretv.player.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.FocusStyle
import com.puretv.player.core.theme.LocalPureTvColors
import com.puretv.player.core.theme.LocalTvUiConfig

/**
 * TV 焦点通用规范（技术文档 7.2）：
 * 1. 所有可点击控件统一焦点边框、统一动效
 * 2. 焦点移动带有匀速缓动动画（180ms 匀速缓动），无闪烁、无瞬移
 * 3. 卡片焦点支持轻微缩放（1.02倍）+ 边框高亮双重提示
 */

/** 焦点卡片：默认缩放 1.02 + 边框高亮（样式跟随设置中心「焦点样式」） */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.02f,
    borderWidth: Dp = 2.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalPureTvColors.current
    val config = LocalTvUiConfig.current
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val highlightBorder = enabled && config.focusStyle != FocusStyle.SCALE
    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed -> 0.98f
            focused && config.focusStyle != FocusStyle.BORDER -> focusedScale
            else -> 1f
        },
        animationSpec = tween(durationMillis = 180),
        label = "cardScale",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.divider
            pressed && focused -> colors.focusPressed
            focused && highlightBorder -> colors.focusBorder
            else -> colors.divider
        },
        animationSpec = tween(durationMillis = 140),
        label = "cardBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (pressed && focused) colors.focusPressed.copy(alpha = 0.16f) else colors.card,
        animationSpec = tween(durationMillis = 120),
        label = "cardBg",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .clip(shape)
            .background(bgColor)
            .border(
                width = if (focused && highlightBorder) borderWidth else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** 通用焦点高亮修饰：用于非卡片控件（列表项、输入行等），保持焦点动效统一 */
@Composable
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(8.dp),
    borderWidth: Dp = 2.dp,
    scaleOnFocus: Boolean = true,
    focusedScale: Float = 1.02f,
    onFocusChanged: (Boolean) -> Unit = {},
): Modifier {
    val colors = LocalPureTvColors.current
    val config = LocalTvUiConfig.current
    var focused by remember { mutableStateOf(false) }
    val highlightBorder = config.focusStyle != FocusStyle.SCALE
    val scale by animateFloatAsState(
        targetValue = if (focused && scaleOnFocus && config.focusStyle != FocusStyle.BORDER) focusedScale else 1f,
        animationSpec = tween(180),
        label = "focusScale",
    )
    val border by animateColorAsState(
        targetValue = if (focused && highlightBorder) colors.focusBorder else colors.divider,
        animationSpec = tween(140),
        label = "focusBorder",
    )
    return this
        .scale(scale)
        .clip(shape)
        .border(
            width = if (focused && highlightBorder) borderWidth else 1.dp,
            color = border,
            shape = shape,
        )
        .onFocusChanged {
            focused = it.isFocused
            onFocusChanged(it.isFocused)
        }
}

/** 启动后自动获取焦点（TV 页面首个可聚焦元素使用） */
@Composable
fun Modifier.focusOnLaunch(requester: FocusRequester = remember { FocusRequester() }): Modifier {
    LaunchedEffect(Unit) {
        runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester)
}

/** 按钮风格 */
enum class TvButtonVariant { PRIMARY, SECONDARY, GHOST }

/**
 * TV 按钮：统一焦点边框、统一动效；按压瞬间「焦点按压加深」色反馈
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TvButtonVariant = TvButtonVariant.PRIMARY,
    enabled: Boolean = true,
    /** 自定义底色（如危险操作红色），null 为按 variant 取默认色 */
    containerColor: Color? = null,
    padding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = LocalPureTvColors.current
    val config = LocalTvUiConfig.current
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val highlightBorder = config.focusStyle != FocusStyle.SCALE
    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed -> 0.97f
            focused && config.focusStyle != FocusStyle.BORDER -> 1.02f
            else -> 1f
        },
        tween(160),
        label = "btnScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.divider
            pressed && focused -> colors.focusPressed
            else -> containerColor ?: when (variant) {
                TvButtonVariant.PRIMARY -> colors.primary
                TvButtonVariant.SECONDARY -> colors.card
                TvButtonVariant.GHOST -> colors.background
            }
        },
        tween(120),
        label = "btnBg",
    )
    val contentColor = when {
        !enabled -> colors.textHint
        variant == TvButtonVariant.PRIMARY -> Color.White
        else -> colors.textPrimary
    }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused && highlightBorder) 2.dp else 1.dp,
                color = if (focused && highlightBorder) colors.focusBorder
                else if (variant == TvButtonVariant.PRIMARY) colors.primary
                else colors.divider,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .padding(padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** TV 开关控件（统一焦点色，无 Material 明亮配色；遥控器 OK 切换） */
@Composable
fun TvSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val colors = LocalPureTvColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .clip(CircleShape)
            .then(
                if (focused && enabled) Modifier.border(2.dp, colors.focusBorder, CircleShape)
                else Modifier
            )
            .clickable(
                enabled = enabled,
                onClick = { onCheckedChange?.let { it(!checked) } },
            )
            .size(width = 54.dp, height = 30.dp)
            .background(
                when {
                    !enabled -> colors.divider
                    checked -> colors.primary
                    else -> colors.divider
                }
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else colors.textHint),
        )
    }
}

/** 次级文字按钮（带统一焦点高亮；compact = 小尺寸，页头动作区用） */
@Composable
fun TvTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    compact: Boolean = false,
) {
    val colors = LocalPureTvColors.current
    val config = LocalTvUiConfig.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val highlightBorder = config.focusStyle != FocusStyle.SCALE
    val border by animateColorAsState(
        targetValue = if (focused && highlightBorder) colors.focusBorder else colors.divider,
        tween(140),
        label = "textBtnBorder",
    )
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        color = when {
            !enabled -> colors.textHint
            pressed && focused -> colors.focusPressed
            else -> color ?: colors.textSecondary
        },
        fontSize = if (compact) 13.sp else 16.sp,
        modifier = modifier
            .clip(shape)
            .background(if (focused && highlightBorder) colors.card else Color.Transparent)
            .border(
                width = if (focused && highlightBorder) 2.dp else 1.dp,
                color = border,
                shape = shape,
            )
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 8.dp),
    )
}

/** 区块标题（首页/设置分区）：主色竖条 + 标题 + 可选副标题/操作 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalPureTvColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(width = 4.dp, height = 24.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp))
            )
            Column {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.textTertiary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
        if (actionText != null && onAction != null) {
            TvTextButton(text = actionText, onClick = onAction)
        }
    }
}
