package com.puretv.player.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.LocalPureTvColors

/**
 * 加载/空态/错误 视图规范（需求 5.3.2 解析进度提示、5.6 易用性）
 */

/** 线性进度条（主题色统一） */
@Composable
fun TvLinearProgress(
    percent: Int,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    trackColor: Color? = null,
    progressColor: Color? = null,
) {
    val colors = LocalPureTvColors.current
    val fraction by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100)) / 100f,
        animationSpec = tween(240),
        label = "progress",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor ?: colors.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(progressColor ?: colors.primary),
        )
    }
}

/** 加载遮罩（弹窗式）：显示进度条 + 百分比 + 阶段说明，可选取消 */
@Composable
fun LoadingOverlay(
    message: String?,
    modifier: Modifier = Modifier,
    percent: Int? = null,
    onCancel: (() -> Unit)? = null,
) {
    val colors = LocalPureTvColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.dialog)
            .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
            .padding(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = colors.primary,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = message ?: "加载中…",
                    color = colors.textSecondary,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            if (percent != null) {
                TvLinearProgress(
                    percent = percent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                )
                Text(
                    text = "$percent%",
                    color = colors.textTertiary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (onCancel != null) {
                TvTextButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/** 空列表提示（占位提示文字色） */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    subtext: String? = null,
) {
    val colors = LocalPureTvColors.current
    Column(
        modifier = modifier.padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = colors.textHint,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        if (subtext != null) {
            Text(
                text = subtext,
                color = colors.textHint,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 错误提示（错误红色 #EF4444）+ 重试 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalPureTvColors.current
    Column(
        modifier = modifier.padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = colors.error,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TvButton(
                text = "重试",
                onClick = onRetry,
                variant = TvButtonVariant.SECONDARY,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

/** 行内小加载 */
@Composable
fun InlineLoading(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPureTvColors.current
    Row(
        modifier = modifier.padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = colors.primary,
            strokeWidth = 2.5.dp,
        )
        Text(
            text = message,
            color = colors.textTertiary,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
