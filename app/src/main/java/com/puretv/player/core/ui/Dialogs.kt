package com.puretv.player.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.puretv.player.core.theme.LocalPureTvColors

/**
 * TV 弹窗规范：
 * - 弹窗底部背景取主题 dialog 色（深色 #232736 / 浅色 #F3F4F6）
 * - 背景遮罩使用全局固定透明遮罩 #00000080（三套主题共用）
 */

@Composable
fun TvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = false,
    maxWidth: Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPureTvColors.current
    // 高度按屏幕自适应封顶 + 内容可滚动（用户反馈：直播源管理弹窗内容一多就超出屏幕，
    // 底部「导入/完成」按钮够不着且无法滚动，完全没法使用）
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxHeight = minOf(MAX_DIALOG_HEIGHT, (configuration.screenHeightDp * 0.86f).dp)
    val portrait = isPortrait()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        Column(
            modifier = modifier
                .padding(
                    horizontal = if (portrait) 16.dp else 48.dp,
                    vertical = if (portrait) 16.dp else 24.dp,
                )
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.dialog)
                .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
                .padding(if (portrait) 20.dp else 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (title != null) {
                androidx.compose.material3.Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = if (portrait) 20.sp else 24.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
            content()
        }
    }
}

/** 弹窗高度上限（再与屏幕高度 86% 取小，保证小屏手机也有上下留白） */
private val MAX_DIALOG_HEIGHT = 720.dp

/** 确认弹窗：确认/取消双按钮（TV 遥控器 OK 确认、返回取消） */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确认",
    cancelText: String = "取消",
    danger: Boolean = false,
) {
    val colors = LocalPureTvColors.current
    TvDialog(onDismiss = onDismiss, title = title) {
        androidx.compose.material3.Text(
            text = message,
            color = colors.textSecondary,
            fontSize = 17.sp,
            modifier = Modifier.padding(bottom = 28.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(
                text = cancelText,
                onClick = onDismiss,
                variant = TvButtonVariant.SECONDARY,
            )
            TvButton(
                text = confirmText,
                onClick = onConfirm,
                containerColor = if (danger) colors.error else null,
            )
        }
    }
}

/**
 * 输入弹窗（源地址、API Key 等输入）
 */
@Composable
fun InputDialog(
    title: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    label: String = "",
    placeholder: String = "",
    confirmText: String = "确认",
    cancelText: String = "取消",
    password: Boolean = false,
) {
    val colors = LocalPureTvColors.current
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    TvDialog(onDismiss = onDismiss, title = title) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        if (label.isNotEmpty()) {
            androidx.compose.material3.Text(
                text = label,
                color = colors.textTertiary,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 18.sp),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.divider, shape)
                .focusRequester(focusRequester)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = placeholder,
                            color = colors.textHint,
                            fontSize = 18.sp,
                        )
                    }
                    inner()
                }
            },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            TvButton(text = cancelText, onClick = onDismiss, variant = TvButtonVariant.SECONDARY)
            TvButton(text = confirmText, onClick = { onDone(value) })
        }
    }
}
