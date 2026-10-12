package com.puretv.player.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.puretv.player.core.ui.LocalTabBarPadding
import com.puretv.player.core.ui.PhoneTopLevelRoutes
import com.puretv.player.ui.favorites.FavoritesScreen
import com.puretv.player.ui.guide.GuideScreen
import com.puretv.player.ui.history.HistoryScreen
import com.puretv.player.ui.live.LiveScreen
import com.puretv.player.ui.local.LocalScreen
import com.puretv.player.ui.player.PlaybackScreen
import com.puretv.player.ui.plugins.PluginsScreen
import com.puretv.player.ui.settings.SettingsScreen
import com.puretv.player.ui.settings.SourceCheckScreen
import com.puretv.player.ui.settings.TutorialScreen
import com.puretv.player.ui.vod.VodDetailScreen
import com.puretv.player.ui.vod.VodScreen
import com.puretv.player.ui.home.HomeScreen

/**
 * 全局导航图（Jetpack Navigation 页面路由管理）
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.GUIDE) { pane(Routes.GUIDE) { GuideScreen(navController) } }
        composable(Routes.HOME) { pane(Routes.HOME) { HomeScreen(navController) } }
        composable(Routes.LIVE) { pane(Routes.LIVE) { LiveScreen(navController) } }
        composable(Routes.VOD) { pane(Routes.VOD) { VodScreen(navController) } }
        composable(
            route = Routes.VOD_DETAIL,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.StringType },
            ),
        ) { entry ->
            pane(Routes.VOD_DETAIL) {
                VodDetailScreen(
                    nav = navController,
                    sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                    itemId = java.net.URLDecoder.decode(
                        entry.arguments?.getString("itemId").orEmpty(),
                        "UTF-8",
                    ),
                )
            }
        }
        composable(Routes.LOCAL) { pane(Routes.LOCAL) { LocalScreen(navController) } }
        composable(Routes.FAVORITES) { pane(Routes.FAVORITES) { FavoritesScreen(navController) } }
        composable(Routes.HISTORY) { pane(Routes.HISTORY) { HistoryScreen(navController) } }
        composable(Routes.SETTINGS) { pane(Routes.SETTINGS) { SettingsScreen(navController, null) } }
        composable(
            route = Routes.SETTINGS_SECTION,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            pane(Routes.SETTINGS_SECTION) { SettingsScreen(navController, entry.arguments?.getString("section")) }
        }
        composable(Routes.PLUGINS) { pane(Routes.PLUGINS) { PluginsScreen(navController) } }
        composable(Routes.TUTORIAL) { pane(Routes.TUTORIAL) { TutorialScreen(navController) } }
        composable(Routes.SOURCE_CHECK) { pane(Routes.SOURCE_CHECK) { SourceCheckScreen(navController) } }
        composable(Routes.PLAYBACK) { pane(Routes.PLAYBACK) { PlaybackScreen(navController) } }
    }
}

/**
 * 页面容器：手机形态顶层页面为底部浮动导航栏留出高度（[LocalTabBarPadding]），
 * 使内容不被导航条遮挡；按各页面自身路由判定，转场期间不会闪跳。
 * TV 形态与手机二级页面该值为 0，直接透传，不影响既有布局。
 */
@Composable
private fun pane(route: String, content: @Composable () -> Unit) {
    val bottomPadding =
        if (route in PhoneTopLevelRoutes) LocalTabBarPadding.current else 0.dp
    if (bottomPadding <= 0.dp) {
        content()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding),
        ) {
            content()
        }
    }
}
