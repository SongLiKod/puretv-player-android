package com.puretv.player.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.puretv.player.core.ui.FloatingTabBar
import com.puretv.player.core.ui.LocalTabBarPadding
import com.puretv.player.core.ui.PhoneTopLevelRoutes

/** 浮动导航条内容留白（条高约 70dp + 底部悬浮 14dp，再留 4dp 呼吸位） */
private val TabBarSpace = 88.dp

/**
 * 手机形态外壳（手机优先设计）：标准手机 App 结构 = 页面内容 + 底部浮动标签栏。
 * - 仅在顶层页面（首页/直播/影视/本地/设置）显示导航栏，详情、播放、引导等二级页面全屏沉浸；
 * - 通过 [LocalTabBarPadding] 为顶层内容区留出导航栏高度，避免末行内容被遮挡；
 * - TV 形态不使用此外壳（遥控器 D-pad 导航，见 MainActivity 分支）。
 */
@Composable
fun PhoneShell(
    navController: NavHostController,
    startDestination: String,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute in PhoneTopLevelRoutes

    Box(modifier = Modifier.fillMaxSize()) {
        // 留白值恒定提供，由 AppNavHost 的 pane 按各页面路由自行判定是否应用，
        // 避免导航转场期间内容高度闪跳
        CompositionLocalProvider(LocalTabBarPadding provides TabBarSpace) {
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showBar) {
            FloatingTabBar(
                selectedRoute = currentRoute,
                onSelect = { target ->
                    if (target != currentRoute) {
                        navController.navigate(target) {
                            // 标准底部导航返回栈：始终以首页为根，切换标签互不叠加
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp),
            )
        }
    }
}
