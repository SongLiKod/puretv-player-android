package com.puretv.player.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme

/** 底部导航标签项（手机形态顶层页面） */
data class TabItem(val route: String, val label: String, val icon: ImageVector)

/** 手机形态顶层标签页：与主流手机 App 一致的底部导航结构 */
val PhoneTopLevelRoutes = setOf(
    Routes.HOME,
    Routes.LIVE,
    Routes.VOD,
    Routes.LOCAL,
    Routes.SETTINGS,
)

private val PhoneTabs = listOf(
    TabItem(Routes.HOME, "首页", Icons.Default.Home),
    TabItem(Routes.LIVE, "直播", Icons.Default.LiveTv),
    TabItem(Routes.VOD, "影视", Icons.Default.Movie),
    TabItem(Routes.LOCAL, "本地", Icons.Default.Storage),
    TabItem(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

/**
 * 底部浮动导航栏占用高度（内容区为其留出空间）。
 * 手机形态顶层页面由 [com.puretv.player.core.navigation.PhoneShell] 提供非 0 值；
 * TV 形态 / 二级页面为默认 0，不影响既有布局。
 */
val LocalTabBarPadding = compositionLocalOf { 0.dp }

/**
 * iOS 风格浮动胶囊底部导航条（手机优先设计，用户指定：导航栏在下面、像苹果浮动导航条）。
 * - 悬浮于内容之上（内容滚动时从导航条下方穿过），带外阴影与细描边营造浮起感；
 * - 选中项图标带胶囊底板高亮 + 主题色着色，未选中灰阶显示；
 * - 半透明底色 + 大圆角胶囊，贴近 iOS 悬浮 Tab Bar 视觉。
 * 仅手机形态显示；TV 形态沿用遥控器焦点导航（见 MainActivity 分支）。
 */
@Composable
fun FloatingTabBar(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    Row(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(30.dp),
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(RoundedCornerShape(30.dp))
            .background(colors.dialog.copy(alpha = 0.96f))
            .border(1.dp, colors.divider.copy(alpha = 0.8f), RoundedCornerShape(30.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneTabs.forEach { tab ->
            TabCell(
                item = tab,
                selected = tab.route == selectedRoute,
                onClick = { onSelect(tab.route) },
            )
        }
    }
}

@Composable
private fun TabCell(
    item: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = PureTvTheme.colors
    val tint by animateColorAsState(
        targetValue = if (selected) colors.highlight else colors.textTertiary,
        animationSpec = tween(durationMillis = 180),
        label = "tabTint",
    )
    val pillAlpha by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "tabPill",
    )
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(pillAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = item.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
