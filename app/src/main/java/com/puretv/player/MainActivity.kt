package com.puretv.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puretv.player.core.navigation.AppNavHost
import com.puretv.player.core.navigation.PhoneShell
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.TvUiConfig
import com.puretv.player.core.util.DeviceUtils
import com.puretv.player.data.prefs.AppSettings
import com.puretv.player.player.FloatingPlayerController
import com.puretv.player.player.SystemFloatingWindowManager
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.compose.rememberNavController
import org.json.JSONObject
import javax.inject.Inject

/**
 * 单 Activity 承载全部页面（TV / 手机双形态）。
 * 启动即渲染（无闪屏、无加载延迟，需求 5.1.4：启动速度≤1秒）。
 * 方向策略：TV 锁定横屏；手机/平板跟随传感器，支持竖屏手机界面。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var floatingPlayerController: FloatingPlayerController

    // 悬浮窗管理器：应用启动即注入实例化（Hilt 单例首次注入才创建），
    // 其内部监听负责显示/收起/重试；不注入则整个悬浮层是死代码（用户反馈：只有声音没窗口）
    @Inject
    lateinit var floatingWindowManager: SystemFloatingWindowManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity 被系统回收后经还原事件拉起：onNewIntent 不回调，初始 intent 里的 action 在此消费
        if (intent?.action == FloatingPlayerController.ACTION_EXPAND) {
            floatingPlayerController.markExpandPending()
        }
        DeviceUtils.applyDeviceOrientation(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val current = settings
            if (current == null) {
                // 首帧前保持窗口底色（深/浅随系统），避免闪屏
                Modifier.fillMaxSize().background(Color.Transparent)
            } else {
                PureTvApp(current, floatingPlayerController)
            }
        }
    }

    // 弹窗悬浮窗「还原」：singleTask 复用本实例，转发为跳转播放页事件
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent?.action == FloatingPlayerController.ACTION_EXPAND) {
            floatingPlayerController.requestExpandToPlayer()
        }
    }
}

@Composable
private fun PureTvApp(settings: AppSettings, floatingController: FloatingPlayerController) {
    val (accent, highlight) = parseSkinColors(settings.customSkinJson)
    PureTvTheme(
        themeMode = settings.themeMode,
        uiConfig = TvUiConfig(
            fontScale = settings.fontScale,
            focusStyle = settings.focusStyle,
            layoutDensity = settings.layoutDensity,
            skinId = settings.skinId,
        ),
        skinAccent = accent,
        skinHighlight = highlight,
    ) {
        val navController = rememberNavController()
        val context = LocalContext.current
        val isTv = remember(context) { DeviceUtils.isTvDevice(context) }
        val startDestination = if (settings.onboardingDone) Routes.HOME else Routes.GUIDE
        Box(modifier = Modifier.fillMaxSize()) {
            if (isTv) {
                // TV 形态：遥控器 D-pad 焦点导航（保持既有横屏体验）
                AppNavHost(
                    navController = navController,
                    startDestination = startDestination,
                )
            } else {
                // 手机形态（手机优先）：底部浮动标签栏 + 标准手机返回栈
                PhoneShell(
                    navController = navController,
                    startDestination = startDestination,
                )
            }
        }

        // 弹窗悬浮窗「还原」：拉起应用后跳转播放页全屏接管（系统悬浮层自行收起）。
        // expand() 与 onNewIntent 会连续投递两次事件（同帧双重 navigate 会压出瞬时播放页，
        // 其 onDispose 按「非弹窗」兜底 engine.stop() 清掉播放任务 → 还原后黑屏/无信息/播放键失灵），
        // 故 launchSingleTop 去重：已在播放页时不再压栈
        LaunchedEffect(navController) {
            if (floatingController.consumeExpandPending()) {
                navController.navigate(Routes.PLAYBACK) { launchSingleTop = true }
            }
            floatingController.expandToPlayer.collect {
                navController.navigate(Routes.PLAYBACK) { launchSingleTop = true }
            }
        }
    }
}

/** 自定义皮肤色值解析（仅允许主色/高亮色，保证全局仅蓝白灰黑体系） */
private fun parseSkinColors(json: String?): Pair<Color?, Color?> {
    if (json.isNullOrBlank()) return null to null
    return try {
        val obj = JSONObject(json)
        val accent = obj.optString("accent", "").takeIf { it.isNotBlank() }?.let(::parseHexColor)
        val highlight = obj.optString("highlight", "").takeIf { it.isNotBlank() }?.let(::parseHexColor)
        accent to highlight
    } catch (e: Exception) {
        null to null
    }
}

private fun parseHexColor(value: String): Color? = try {
    val v = value.trim().removePrefix("#")
    when (v.length) {
        6 -> Color(0xFF000000 or v.toLong(16))
        8 -> Color(v.toLong(16))
        else -> null
    }
} catch (e: Exception) {
    null
}
