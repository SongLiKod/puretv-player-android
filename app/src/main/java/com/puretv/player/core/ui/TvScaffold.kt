package com.puretv.player.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.theme.LocalPureTvColors

/**
 * 页面骨架：统一顶部导航（返回 + 标题 + 操作区）+ 分割线 + 内容区。
 * 所有二级页面使用，保证「菜单呼出逻辑统一」（需求 5.2.2）。
 * TV 横屏为宽边距大标题；手机竖屏自动收窄边距、缩小标题。
 */
@Composable
fun TvScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    headerBadge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPureTvColors.current
    val portrait = isPortrait()
    val hPad = if (portrait) 18.dp else 40.dp
    val vPad = if (portrait) 14.dp else 24.dp
    val titleSize = if (portrait) 22.sp else 28.sp
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack && onBack != null) {
                TvButton(
                    text = "返回",
                    onClick = onBack,
                    variant = TvButtonVariant.SECONDARY,
                    padding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = if (portrait) 16.dp else 22.dp,
                        vertical = if (portrait) 8.dp else 12.dp,
                    ),
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = if (showBack && onBack != null) (if (portrait) 12.dp else 24.dp) else 0.dp)
                    .weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = colors.textPrimary,
                        fontSize = titleSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (headerBadge != null) {
                        Box(modifier = Modifier.padding(start = 16.dp)) { headerBadge() }
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.textTertiary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = if (portrait) 2 else 1,
                    )
                }
            }
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(if (portrait) 10.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        HorizontalDivider(color = colors.divider, thickness = 1.dp)
        content()
    }
}
